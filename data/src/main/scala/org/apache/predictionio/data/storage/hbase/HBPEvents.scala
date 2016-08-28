/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.predictionio.data.storage.hbase

import org.apache.predictionio.data.storage.Event
import org.apache.predictionio.data.storage.PEvents
import org.apache.predictionio.data.storage.StorageClientConfig
import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.{HBaseConfiguration, TableName}
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.IdentityTableMapper
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil.initTableMapperJob
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.hbase.mapreduce.TableInputFormat
import org.apache.hadoop.hbase.mapreduce.TableOutputFormat
import org.apache.hadoop.io.Writable
import org.apache.hadoop.mapreduce.OutputFormat
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.joda.time.DateTime

class HBPEvents(client: HBClient, config: StorageClientConfig, namespace: String) extends PEvents {

  def checkTableExists(appId: Int, channelId: Option[Int]): Unit = {
    val tableName = TableName.valueOf(HBEventsUtil.tableName(namespace, appId, channelId))
    if (!client.admin.tableExists(tableName)) {
      if (channelId.nonEmpty) {
        logger.error(s"The appId $appId with channelId $channelId does not exist." +
          s" Please use valid appId and channelId.")
        throw new Exception(s"HBase table not found for appId $appId" +
          s" with channelId $channelId.")
      } else {
        logger.error(s"The appId $appId does not exist. Please use valid appId.")
        throw new Exception(s"HBase table not found for appId $appId.")
      }
    }
  }

  override
  def find(
    appId: Int,
    channelId: Option[Int] = None,
    startTime: Option[DateTime] = None,
    untilTime: Option[DateTime] = None,
    entityType: Option[String] = None,
    entityId: Option[String] = None,
    eventNames: Option[Seq[String]] = None,
    targetEntityType: Option[Option[String]] = None,
    targetEntityId: Option[Option[String]] = None
    )(sc: SparkContext): RDD[Event] = {

    checkTableExists(appId, channelId)
    
    val scan = HBEventsUtil.createScan(
        startTime = startTime,
        untilTime = untilTime,
        entityType = entityType,
        entityId = entityId,
        eventNames = eventNames,
        targetEntityType = targetEntityType,
        targetEntityId = targetEntityId,
        reversed = None)
    scan.setCaching(500) // TODO
    scan.setCacheBlocks(false) // TODO

    val table = HBEventsUtil.tableName(namespace, appId, channelId)
    val rdd = sc.newAPIHadoopRDD(makeConf(table, scan), classOf[TableInputFormat],
      classOf[ImmutableBytesWritable],
      classOf[Result]).map {
        case (key, row) => HBEventsUtil.resultToEvent(row, appId)
      }

    rdd
  }

  private def makeConf(table: String, scan: Scan) = {
    val conf = HBaseConfiguration.create()
    conf.set(TableInputFormat.INPUT_TABLE, table)
    val job = Job.getInstance(conf)
    initTableMapperJob(table, scan, classOf[IdentityTableMapper], null, null, job)
    job.getConfiguration
  }

  override
  def write(
    events: RDD[Event], appId: Int, channelId: Option[Int])(sc: SparkContext): Unit = {

    checkTableExists(appId, channelId)

    val conf = HBaseConfiguration.create()
    conf.set(TableOutputFormat.OUTPUT_TABLE,
      HBEventsUtil.tableName(namespace, appId, channelId))
    conf.setClass("mapreduce.outputformat.class",
      classOf[TableOutputFormat[Object]],
      classOf[OutputFormat[Object, Writable]])

    events.map { event =>
      val (put, rowKey) = HBEventsUtil.eventToPut(event, appId)
      (new ImmutableBytesWritable(rowKey.toBytes), put)
    }.saveAsNewAPIHadoopDataset(conf)

  }

  def delete(
    eventIds: RDD[String], appId: Int, channelId: Option[Int])(sc: SparkContext): Unit = {

    checkTableExists(appId, channelId)

    val tableName = HBEventsUtil.tableName(namespace, appId, channelId)

    eventIds.foreachPartition{ iter =>
      val conf = HBaseConfiguration.create()
      conf.set(TableOutputFormat.OUTPUT_TABLE,
        tableName)
      val connection = ConnectionFactory.createConnection(conf)
      val table = connection.getTable(TableName.valueOf(tableName))
      iter.foreach { id =>
        val rowKey = HBEventsUtil.RowKey(id)
        val delete = new Delete(rowKey.b)
        table.delete(delete)
      }
      table.close()
      connection.close()
    }
  }
}
