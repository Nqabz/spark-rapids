/*
 * Copyright (c) 2019-2020, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.rapids.spark

import java.util
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import ai.rapids.cudf._
import ai.rapids.spark.RapidsPluginImplicits._
import org.apache.commons.lang3.mutable.MutableLong

import org.apache.spark.{SparkConf, SparkContext, TaskContext}
import org.apache.spark.api.plugin.{DriverPlugin, ExecutorPlugin, PluginContext, SparkPlugin}
import org.apache.spark.internal.Logging
import org.apache.spark.serializer.{JavaSerializer, KryoSerializer}
import org.apache.spark.sql.{GpuShuffleEnv, SparkSessionExtensions}
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution._
import org.apache.spark.sql.internal.StaticSQLConf
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.TaskCompletionListener

trait GpuPartitioning extends Partitioning {

  def sliceBatch(vectors: Array[RapidsHostColumnVector], start: Int, end: Int): ColumnarBatch = {
    var ret: ColumnarBatch = null
    val count = end - start
    if (count > 0) {
      ret = new ColumnarBatch(vectors.map(vec => new SlicedGpuColumnVector(vec, start, end)))
      ret.setNumRows(count)
    }
    ret
  }

  def sliceInternalOnGpu(batch: ColumnarBatch, partitionIndexes: Array[Int],
      partitionColumns: Array[GpuColumnVector]): Array[ColumnarBatch] = {
    // The first index will always be 0, so we need to skip it.
    val batches = if (batch.numRows > 0) {
      val parts = partitionIndexes.slice(1, partitionIndexes.length)
      val splits = new ArrayBuffer[ColumnarBatch](numPartitions)
      val table = new Table(partitionColumns.map(_.getBase).toArray: _*)
      val contiguousTables: Array[ContiguousTable] = try {
        table.contiguousSplit(parts: _*)
      } finally {
        table.close()
      }
      var succeeded = false
      try {
        contiguousTables.foreach { ct => splits.append(GpuColumnVectorFromBuffer.from(ct)) }
        succeeded = true
      } finally {
        contiguousTables.foreach(_.close())
        if (!succeeded) {
          splits.foreach(_.close())
        }
      }
      splits.toArray
    } else {
      Array[ColumnarBatch]()
    }

    GpuSemaphore.releaseIfNecessary(TaskContext.get())
    batches
  }

  def sliceInternalOnCpu(batch: ColumnarBatch, partitionIndexes: Array[Int],
      partitionColumns: Array[GpuColumnVector]): Array[ColumnarBatch] = {
    // We need to make sure that we have a null count calculated ahead of time.
    // This should be a temp work around.
    partitionColumns.foreach(_.getBase.getNullCount)

    val hostPartColumns = partitionColumns.map(_.copyToHost())
    try {
      // Leaving the GPU for a while
      GpuSemaphore.releaseIfNecessary(TaskContext.get())

      val ret = new Array[ColumnarBatch](numPartitions)
      var start = 0
      for (i <- 1 until Math.min(numPartitions, partitionIndexes.length)) {
        val idx = partitionIndexes(i)
        ret(i - 1) = sliceBatch(hostPartColumns, start, idx)
        start = idx
      }
      ret(numPartitions - 1) = sliceBatch(hostPartColumns, start, batch.numRows())
      ret
    } finally {
      hostPartColumns.safeClose()
    }
  }

  def sliceInternalGpuOrCpu(batch: ColumnarBatch, partitionIndexes: Array[Int],
      partitionColumns: Array[GpuColumnVector]): Array[ColumnarBatch] = {
    val rapidsShuffleEnabled = GpuShuffleEnv.isRapidsShuffleEnabled
    val nvtxRangeKey = if (rapidsShuffleEnabled) {
      "sliceInternalOnGpu"
    } else {
      "sliceInternalOnCpu"
    }
    // If we are not using the Rapids shuffle we fall back to CPU splits way to avoid the hit
    // for large number of small splits.
    val sliceRange = new NvtxRange(nvtxRangeKey, NvtxColor.CYAN)
    try {
      if (rapidsShuffleEnabled) {
        sliceInternalOnGpu(batch, partitionIndexes, partitionColumns)
      } else {
        sliceInternalOnCpu(batch, partitionIndexes, partitionColumns)
      }
    } finally {
      sliceRange.close()
    }
  }
}

case class ColumnarOverrideRules() extends ColumnarRule with Logging {
  val overrides = GpuOverrides()
  val overrideTransitions = new GpuTransitionOverrides()

  override def preColumnarTransitions : Rule[SparkPlan] = overrides

  override def postColumnarTransitions: Rule[SparkPlan] = overrideTransitions
}

/**
  * Extension point to enable GPU SQL processing.
  */
class SQLExecPlugin extends Function1[SparkSessionExtensions, Unit] with Logging {
  override def apply(extensions: SparkSessionExtensions): Unit = {
    logWarning("Installing extensions to enable rapids GPU SQL support." +
      s" To disable GPU support set `${RapidsConf.SQL_ENABLED}` to false")
    extensions.injectColumnar(_ => ColumnarOverrideRules())
  }
}

trait GpuSpillable {
  /**
   * Spill GPU memory if possible
   * @param target the amount of memory that we want to try and spill.
   */
  def spill(target: Long): Unit
}

object GpuResourceManager extends MemoryListener with Logging {
  private val totalActuallyUsed: AtomicLong = new AtomicLong(0)
  private val totalUsedAndReserved: AtomicLong = new AtomicLong(0)
  private val prediction = new ThreadLocal[MutableLong]()
  private val predictionName = new ThreadLocal[String]()
  private var spillCutoff: Long = -1
  private var stopAndSpillCutoff: Long = -1
  private var controller: Controller = _
  private val spillers = new AtomicReference[Set[GpuSpillable]](Set())

  class BufferTracking private[GpuResourceManager](
      val size: Long,
      val note: String) extends AutoCloseable with Logging {
    {
      val tots = totalActuallyUsed.addAndGet(size)
      totalUsedAndReserved.addAndGet(size)
      logDebug(s"BUFFER: $size TOTAL: $tots T: $totalUsedAndReserved ($note)")
      GpuResourceManager.spillIfNeeded()
    }

    override def close(): Unit = {
      val tots = totalActuallyUsed.addAndGet(- size)
      totalUsedAndReserved.addAndGet(- size)
      logDebug(s"CLOSE BUFFER: $size TOTAL: $tots T: $totalUsedAndReserved ($note)")
    }
  }

  def deviceMemoryUsed(cb: ColumnarBatch): Long =
    GpuColumnVector.extractBases(cb).map(_.getDeviceMemorySize).sum

  def register(spiller: GpuSpillable): Unit =
    spillers.getAndUpdate(s => s + spiller)

  def deregister(spiller: GpuSpillable): Unit =
    spillers.getAndUpdate(s => s - spiller)

  private class Controller extends Thread {
    setDaemon(true)
    private var done = false
    private val signal = new Array[Byte](0)

    def setDone(): Unit = {
      done = true
      interrupt()
    }

    def waitForSpillToComplete(): Unit = {
      val range = new NvtxRange("WAIT FOR SPILL", NvtxColor.RED)
      try {
        logDebug("WAIT FOR SPILL")
        synchronized {
          signal.synchronized {
            // Wake up the spilling thread in case it is sleeping
            signal.notify()
          }
          wait(2000)
        }
        logDebug("DONE WAITING FOR SPILL")
      } finally {
        range.close()
      }
    }

    override def run(): Unit = {
      while (!done) {
        try {
          val used = totalUsedAndReserved.get()
          if (used > spillCutoff) {
            val needed = used - spillCutoff
            logInfo(s"SPILLING GPU MEMORY ${used / 1024 / 1024} MB USED " +
              s"${needed / 1024 / 1024} MB MORE NEEDED")
            val canSpill = spillers.get()
            canSpill.foreach(s => {
              val need = totalUsedAndReserved.get() - spillCutoff
              if (need > 0) {
                s.spill(need)
              }
            })
            val newUsed = totalUsedAndReserved.get()
            if (newUsed > stopAndSpillCutoff) {
              val needed = newUsed - spillCutoff
              logWarning(s"SPILL DID NOT FREE ENOUGH MEMORY ${newUsed / 1024 / 1024} MB USED " +
                s"${needed / 1024 / 1024} MB MORE NEEDED")
            }
          }
          synchronized {
            notifyAll()
          }
          try {
            signal.synchronized {
              signal.wait(100)
            }
          } catch {
            case _: InterruptedException => //Ignored
          }
        } catch {
          case e: Throwable => logError("Error during Spill", e)
        }
      }
    }
  }

  private[spark] def setCutoffs(spillAsync: Long, stopAndSpill: Long): Unit = {
    spillCutoff = spillAsync
    stopAndSpillCutoff = stopAndSpill
  }

  private[spark] def spillIfNeeded(): Unit = {
    val t = totalUsedAndReserved.get()
    if (t > spillCutoff) {
      synchronized {
        if (controller == null) {
          controller = new Controller()
          controller.start()
        }
      }
    }

    if (t > stopAndSpillCutoff) {
      controller.waitForSpillToComplete()
    }
  }

  private def getPrediction(): MutableLong = {
    var ret = prediction.get()
    if (ret eq null) {
      ret = new MutableLong(0)
      prediction.set(ret)
      val tc = TaskContext.get()
      if (tc != null) {
        logDebug(s"START FOR TASK ${tc.taskAttemptId()}")
        tc.addTaskCompletionListener(new TaskCompletionListener {
          override def onTaskCompletion(context: TaskContext): Unit = {
            logDebug(s"END FOR TASK ${context.taskAttemptId()}")
            prediction.remove()
          }
        })
      }
    }
    ret
  }

  def rawBuffer(amount: Long, note: String): BufferTracking = {
    new BufferTracking(amount, note)
  }

  override def prediction(amount: Long, note: String): Unit = {
    val prediction = getPrediction()
    predictionName.set(note)
    val previous = prediction.getValue
    prediction.setValue(amount)
    assert(previous == 0)
    totalUsedAndReserved.getAndAdd(amount)
    logDebug(s"PREDICTION: $amount USED: $totalActuallyUsed T: $totalUsedAndReserved ($note)")
    spillIfNeeded()
  }

  override def allocation(amount: Long, id: Long): Unit = {
    val prediction = getPrediction()
    val pred = predictionName.get()
    val newTotal = totalActuallyUsed.addAndGet(amount)
    val left = prediction.getValue()
    val newPrediction = if (left > amount) {
      prediction.addAndGet(-amount)
    } else {
      prediction.addAndGet(-left)
      totalUsedAndReserved.addAndGet(amount - left)
      0
    }
    logDebug(s"ALLOCATION: $id: $amount USED: $newTotal T: $totalUsedAndReserved PREDICTION LEFT: $newPrediction ($pred)")
    spillIfNeeded()
  }

  override def endPrediction(note: String): Unit = {
    val prediction = getPrediction()
    predictionName.remove()
    val wasLeft = prediction.getValue()
    prediction.setValue(0)
    totalUsedAndReserved.addAndGet(-wasLeft)
    logDebug(s"END PREDICTION: $wasLeft T: $totalUsedAndReserved ($note)")
  }

  override def deallocation(amount: Long, id: Long): Unit = {
    val newTotal = totalActuallyUsed.addAndGet(-amount)
    totalUsedAndReserved.addAndGet(-amount)
    logDebug(s"DEALLOCATION: $id: $amount USED: $newTotal T: $totalUsedAndReserved")
  }
}

object RapidsPluginUtils extends Logging {
  private val SQL_PLUGIN_NAME = classOf[SQLExecPlugin].getName
  private val OLD_SQL_PLUGIN_NAME = "ai.rapids.spark.Plugin"
  private val SQL_PLUGIN_CONF_KEY = StaticSQLConf.SPARK_SESSION_EXTENSIONS.key
  private val SERIALIZER_CONF_KEY = "spark.serializer"
  private val JAVA_SERIALIZER_NAME = classOf[JavaSerializer].getName
  private val KRYO_SERIALIZER_NAME = classOf[KryoSerializer].getName
  private val KRYO_REGISRATOR_KEY = "spark.kryo.registrator"
  private val KRYO_REGISRATOR_NAME = classOf[GpuKryoRegistrator].getName

  def fixupConfigs(conf: SparkConf): Unit = {
    // First add in the SQL executor plugin because that is what we need at a minimum
    if (conf.contains(SQL_PLUGIN_CONF_KEY)) {
      val previousValue = conf.get(SQL_PLUGIN_CONF_KEY).split(",")
        .map(_.trim).map(_ match {
        case OLD_SQL_PLUGIN_NAME =>
          logWarning(s"The spark sql extension $OLD_SQL_PLUGIN_NAME is deprecated and " +
            s"you only need to set the conf spark.plugins to ${classOf[SQLPlugin].getName}")
          SQL_PLUGIN_NAME
        case other => other
      })
      if (!previousValue.contains(SQL_PLUGIN_NAME)) {
        conf.set(SQL_PLUGIN_CONF_KEY, previousValue + "," + SQL_PLUGIN_NAME)
      } else {
        conf.set(SQL_PLUGIN_CONF_KEY, previousValue.mkString(","))
      }
    } else {
      conf.set(SQL_PLUGIN_CONF_KEY, SQL_PLUGIN_NAME)
    }

    val serializer = conf.get(SERIALIZER_CONF_KEY, JAVA_SERIALIZER_NAME)
    if (KRYO_SERIALIZER_NAME.equals(serializer)) {
      if (conf.contains(KRYO_REGISRATOR_KEY)) {
        if (!KRYO_REGISRATOR_NAME.equals(conf.get(KRYO_REGISRATOR_KEY)) ) {
          logWarning("Rapids SQL Plugin when used with Kryo needs to register some " +
            s"serializers using $KRYO_REGISRATOR_NAME. Please call it from your registrator " +
            " to let the plugin work properly.")
        } // else it is set and we are good to go
      }  else {
        // We cannot set the kryo key here, it is not early enough to be picked up everywhere
        throw new UnsupportedOperationException("The Rapids SQL Plugin when used with Kryo needs " +
          s"to register some serializers. Please set the spark config $KRYO_REGISRATOR_KEY to " +
          s"$KRYO_REGISRATOR_NAME or some operations may not work properly.")
      }
    } else if (!JAVA_SERIALIZER_NAME.equals(serializer)) {
      throw new UnsupportedOperationException(s"$serializer is not a supported serializer for the " +
        s"Rapids SQL Plugin. Please disable the rapids plugin or use a supported serializer " +
        s"serializer ($JAVA_SERIALIZER_NAME, $KRYO_SERIALIZER_NAME).")
    }
  }
}

/**
 * The Spark driver plugin provided by the RAPIDS Spark plugin.
 */
class RapidsDriverPlugin extends DriverPlugin with Logging {
  override def init(sc: SparkContext, pluginContext: PluginContext): util.Map[String, String] = {
    val sparkConf = pluginContext.conf
    RapidsPluginUtils.fixupConfigs(sparkConf)
    new RapidsConf(sparkConf).rapidsConfMap
  }
}

/**
 * The Spark executor plugin provided by the RAPIDS Spark plugin.
 */
class RapidsExecutorPlugin extends ExecutorPlugin with Logging {
  override def init(
      pluginContext: PluginContext,
      extraConf: util.Map[String, String]): Unit = {
    val conf = new RapidsConf(extraConf.asScala.toMap)

    // we rely on the Rapids Plugin being run with 1 GPU per executor so we can initialize
    // on executor startup.
    if (!GpuDeviceManager.rmmTaskInitEnabled) {
      logInfo("Initializing memory from Executor Plugin")
      GpuDeviceManager.initializeGpuAndMemory(pluginContext.resources().asScala.toMap)
    }

    GpuSemaphore.initialize(conf.concurrentGpuTasks)
  }

  override def shutdown(): Unit = {
    GpuSemaphore.shutdown()
  }
}

/**
 * The RAPIDS plugin for Spark.
 * To enable this plugin, set the config "spark.plugins" to ai.rapids.spark.SQLPlugin
 */
class SQLPlugin extends SparkPlugin with Logging {
  override def driverPlugin(): DriverPlugin = new RapidsDriverPlugin
  override def executorPlugin(): ExecutorPlugin = new RapidsExecutorPlugin
}

/**
 * Old version of SQLPlugin kept for backwards compatibility
 * @deprecated please use SQLPlugin instead
 */
@scala.deprecated
class RapidsSparkPlugin extends SQLPlugin {
  override def driverPlugin(): DriverPlugin = {
    logWarning(s"The plugin class ${this.getClass.getName} is deprecated please use " +
      s"${classOf[SQLPlugin].getName} instead.")
    super.driverPlugin()
  }
}
