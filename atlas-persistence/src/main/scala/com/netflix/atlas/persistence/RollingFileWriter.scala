/*
 * Copyright 2014-2020 Netflix, Inc.
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
package com.netflix.atlas.persistence

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

import com.netflix.atlas.core.model.Datapoint
import com.netflix.spectator.api.Registry
import com.typesafe.scalalogging.StrictLogging
import org.apache.avro.file.DataFileWriter
import org.apache.avro.specific.SpecificDatumWriter

import scala.util.Random

class RollingFileWriter(
  val filePathPrefix: String,
  val rollingConf: RollingConfig,
  val startTime: Long,
  val endTime: Long,
  val registry: Registry
) extends StrictLogging {

  // These "curr*" fields track status of the current file writer
  private var currFile: String = _
  private var currWriter: DataFileWriter[AvroDatapoint] = _
  private var currCreatedAtMs: Long = 0
  private var currNumRecords: Long = 0
  private var currStartTimeSeen: Long = _
  private var currEndTimeSeen: Long = _

  private var nextFileSeqId: Long = 0

  private val avroWriteErrors = registry.counter("persistence.avroWriteErrors")

  def initialize(): Unit = newWriter()

  def shouldAccept(dp: Datapoint): Boolean = {
    dp.timestamp >= startTime && dp.timestamp < endTime
  }

  def write(dp: Datapoint): Unit = {
    if (shouldRollOver) {
      rollOver
      newWriter
    }
    // Ignore the special datapoint
    if ((RollingFileWriter.RolloverCheckDatapoint ne dp) && shouldAccept(dp)) {
      writeImpl(dp)
    }
  }

  def close(): Unit = rollOver

  private def newWriter(): Unit = {
    val newFile = getNextTmpFilePath
    logger.debug(s"New avro file: $newFile")
    val dataFileWriter = new DataFileWriter[AvroDatapoint](
      new SpecificDatumWriter[AvroDatapoint](classOf[AvroDatapoint])
    )
    // Possible to use API that takes OutputStream to track file size if needed
    dataFileWriter.create(AvroDatapoint.getClassSchema, new File(newFile))

    // Update tracking fields
    currFile = newFile
    currWriter = dataFileWriter
    currCreatedAtMs = System.currentTimeMillis()
    currNumRecords = 0
    currStartTimeSeen = endTime - 1
    currEndTimeSeen = startTime
  }

  private def writeImpl(dp: Datapoint): Unit = {
    try {
      currWriter.append(toAvro(dp))
      currStartTimeSeen = Math.min(currStartTimeSeen, dp.timestamp)
      currEndTimeSeen = Math.max(currEndTimeSeen, dp.timestamp)
      currNumRecords += 1
    } catch {
      case e: Exception => {
        avroWriteErrors.increment()
        logger.debug(s"error writing to avro file, file=$currFile datapoint=$dp", e)
      }
    }
  }

  private def shouldRollOver: Boolean = {
    currNumRecords >= rollingConf.maxRecords ||
    (System.currentTimeMillis() - currCreatedAtMs) >= rollingConf.maxDurationMs
  }

  private def rollOver: Unit = {
    currWriter.close()

    if (currNumRecords == 0) {
      // Simply delete the file if no records written
      logger.debug(s"deleting file with 0 record: ${currFile}")
      FileUtil.delete(new File(currFile))
      // Note: nextFileSeqId is not increased here so that actual file seq are always consecutive
    } else {
      logger.debug(s"rolling over file $currFile")
      renameCurrFile()
      nextFileSeqId += 1
    }
  }

  // Removing tmp suffix, and add time range as suffix
  def renameCurrFile(): Unit = {
    try {
      val filePath = new File(currFile).getCanonicalPath
      val rangeSuffix = s".${secOfHour(currStartTimeSeen)}-${secOfHour(currEndTimeSeen)}"
      val newPath = filePath.substring(0, filePath.length - RollingFileWriter.TmpFileSuffix.length) + rangeSuffix
      Files.move(
        Paths.get(filePath),
        Paths.get(newPath),
        // Atomic to avoid incorrect file list view during process of rename
        StandardCopyOption.ATOMIC_MOVE
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to mark file as complete by removing tmp suffix: $currFile", e)
    }
  }

  private def secOfHour(timestamp: Long): String = {
    "%04d".format((timestamp / 1000) % 3600)
  }

  // Example file name: 2020051003.i-localhost.0001.XkvU3A.tmp
  // The random string suffix is to avoid file name conflict when server restarts
  private def getNextTmpFilePath: String = {
    val seqStr = "%04d".format(nextFileSeqId)
    s"$filePathPrefix.$getInstanceId.$seqStr.$getRandomStr${RollingFileWriter.TmpFileSuffix}"
  }

  private def getInstanceId: String = {
    sys.env.getOrElse("NETFLIX_INSTANCE_ID", "i-localhost")
  }

  private def getRandomStr: String = {
    Random.alphanumeric.take(6).mkString
  }

  private def toAvro(dp: Datapoint): AvroDatapoint = {
    import scala.jdk.CollectionConverters._
    AvroDatapoint.newBuilder
      .setTags(dp.tags.asJava)
      .setTimestamp(dp.timestamp)
      .setValue(dp.value)
      .build
  }
}

object RollingFileWriter {
  val TmpFileSuffix = ".tmp"
  // A special Datapoint used solely for triggering rollover check
  val RolloverCheckDatapoint = Datapoint(Map.empty, 0, 0)
}