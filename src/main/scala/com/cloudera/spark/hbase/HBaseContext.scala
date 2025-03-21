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

package com.cloudera.spark.hbase

import java.io._
import java.util.ArrayList
import java.util.concurrent.Executors

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client.{Connection, ConnectionFactory, Delete, Get, HConnection, HConnectionManager, Increment, Mutation, Put, Result, Scan}
import org.apache.hadoop.hbase.client.coprocessor.Batch
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.{IdentityTableMapper, TableInputFormat, TableMapReduceUtil}
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.security.UserGroupInformation
import org.apache.hadoop.security.UserGroupInformation.AuthenticationMethod
import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.dstream.DStream

import scala.reflect.ClassTag


/**
 * HBaseContext is a façade of simple and complex HBase operations
 * like bulk put, get, increment, delete, and scan
 *
 * HBase Context will take the responsibilities to happen to
 * complexity of disseminating the configuration information
 * to the working and managing the life cycle of HConnections.
 *
 * serializable Configuration object
 *
 */
class HBaseContext(@transient sc: SparkContext,
                   @transient config: Configuration,
                    val tmpHdfsConfgFile: String = null) extends Serializable with Logging {


  @transient var credentials = SparkHadoopUtil.get.getCurrentUserCredentials()
  @transient var tmpHdfsConfiguration:Configuration = config
  @transient var appliedCredentials = false;
  @transient val job = new Job(config)
  TableMapReduceUtil.initCredentials(job)
  val broadcastedConf = sc.broadcast(new SerializableWritable(config))
  val credentialsConf = sc.broadcast(new SerializableWritable(job.getCredentials()))

  if (tmpHdfsConfgFile != null && config != null) {
    val fs = FileSystem.newInstance(config)
    val tmpPath = new Path(tmpHdfsConfgFile)
    if (!fs.exists(tmpPath)) {
      val outputStream = fs.create(tmpPath)
      config.write(outputStream)
      outputStream.close();
    } else {
      logWarning("tmpHdfsConfigDir " + tmpHdfsConfgFile + " exist!!")
    }
  }


  /**
   * A simple enrichment of the traditional Spark RDD foreachPartition.
   * This function differs from the original in that it offers the
   * developer access to a already connected HConnection object
   *
   * Note: Do not close the HConnection object.  All HConnection
   * management is handled outside this method
   *
   * @param rdd  Original RDD with data to iterate over
   * @param f    Function to be given a iterator to iterate through
   *             the RDD values and a HConnection object to interact
   *             with HBase
   */
  def foreachPartition[T](rdd: RDD[T],
                          f: (Iterator[T], HConnection) => Unit) = {
    rdd.foreachPartition(
      it => hbaseForeachPartition(broadcastedConf, it, f))
  }

  /**
   * A simple enrichment of the traditional Spark Streaming dStream foreach
   * This function differs from the original in that it offers the
   * developer access to a already connected HConnection object
   *
   * Note: Do not close the HConnection object.  All HConnection
   * management is handled outside this method
   *
   * @param dstream  Original DStream with data to iterate over
   * @param f        Function to be given a iterator to iterate through
   *                 the DStream values and a HConnection object to
   *                 interact with HBase
   */
  def foreachRDD[T](dstream: DStream[T],
                    f: (Iterator[T], HConnection) => Unit) = {
    dstream.foreach((rdd, time) => {
      foreachPartition(rdd, f)
    })
  }

  /**
   * A simple enrichment of the traditional Spark RDD mapPartition.
   * This function differs from the original in that it offers the
   * developer access to a already connected HConnection object
   *
   * Note: Do not close the HConnection object.  All HConnection
   * management is handled outside this method
   *
   * Note: Make sure to partition correctly to avoid memory issue when
   *       getting data from HBase
   *
   * @param rdd  Original RDD with data to iterate over
   * @param mp   Function to be given a iterator to iterate through
   *             the RDD values and a HConnection object to interact
   *             with HBase
   * @return     Returns a new RDD generated by the user definition
   *             function just like normal mapPartition
   */
  def mapPartition[T, R: ClassTag](rdd: RDD[T],
                                   mp: (Iterator[T], HConnection) => Iterator[R]): RDD[R] = {

    rdd.mapPartitions[R](it => hbaseMapPartition[T, R](broadcastedConf,
      it,
      mp), true)
  }

  /**
   * A simple enrichment of the traditional Spark Streaming DStream
   * mapPartition.
   *
   * This function differs from the original in that it offers the
   * developer access to a already connected HConnection object
   *
   * Note: Do not close the HConnection object.  All HConnection
   * management is handled outside this method
   *
   * Note: Make sure to partition correctly to avoid memory issue when
   *       getting data from HBase
   *
   * @param dstream  Original DStream with data to iterate over
   * @param mp       Function to be given a iterator to iterate through
   *                 the DStream values and a HConnection object to
   *                 interact with HBase
   * @return         Returns a new DStream generated by the user
   *                 definition function just like normal mapPartition
   */
  def streamMap[T, U: ClassTag](dstream: DStream[T],
                                mp: (Iterator[T], HConnection) => Iterator[U]): DStream[U] = {

    dstream.mapPartitions(it => hbaseMapPartition[T, U](
      broadcastedConf,
      it,
      mp), true)
  }



  /**
   * A simple abstraction over the HBaseContext.foreachPartition method.
   *
   * It allow addition support for a user to take RDD
   * and generate puts and send them to HBase.
   * The complexity of managing the HConnection is
   * removed from the developer
   *
   * @param rdd       Original RDD with data to iterate over
   * @param tableName The name of the table to put into
   * @param f         Function to convert a value in the RDD to a HBase Put
   * @param autoFlush If autoFlush should be turned on
   */
  def bulkPut[T](rdd: RDD[T], tableName: String, f: (T) => Put, autoFlush: Boolean) {

    rdd.foreachPartition(
      it => hbaseForeachPartition[T](
        broadcastedConf,
        it,
        (iterator, hConnection) => {
          val htable = hConnection.getTable(tableName)
          htable.setAutoFlush(autoFlush, true)
          iterator.foreach(T => htable.put(f(T)))
          htable.flushCommits()
          htable.close()
        }))
  }

  def applyCreds[T] (configBroadcast: Broadcast[SerializableWritable[Configuration]]){


    credentials = SparkHadoopUtil.get.getCurrentUserCredentials()

    logInfo("appliedCredentials:" + appliedCredentials + ",credentials:" + credentials);

    if (appliedCredentials == false && credentials != null) {
      appliedCredentials = true
     // logCredInformation(credentials)

      @transient val ugi = UserGroupInformation.getCurrentUser();
      ugi.addCredentials(credentials)
      // specify that this is a proxy user
      ugi.setAuthenticationMethod(AuthenticationMethod.PROXY)

      ugi.addCredentials(credentialsConf.value.value)
    }
  }

  /*def logCredInformation[T] (credentials2:Credentials) {
    logInfo("credentials:" + credentials2);
    for (a <- 0 until credentials2.getAllSecretKeys.size()) {
      logInfo("getAllSecretKeys:" + a + ":" + credentials2.getAllSecretKeys.get(a));
    }
    val it = credentials2.getAllTokens.iterator();
    while (it.hasNext) {
      logInfo("getAllTokens:" + it.next());
    }
  }*/

  /**
   * A simple abstraction over the HBaseContext.streamMapPartition method.
   *
   * It allow addition support for a user to take a DStream and
   * generate puts and send them to HBase.
   *
   * The complexity of managing the HConnection is
   * removed from the developer
   *
   * @param dstream    Original DStream with data to iterate over
   * @param tableName  The name of the table to put into
   * @param f          Function to convert a value in
   *                   the DStream to a HBase Put
   * @param autoFlush        If autoFlush should be turned on
   */
  def streamBulkPut[T](dstream: DStream[T],
                       tableName: String,
                       f: (T) => Put,
                       autoFlush: Boolean) = {
    dstream.foreach((rdd, time) => {
      bulkPut(rdd, tableName, f, autoFlush)
    })
  }

  /**
   * A simple abstraction over the HBaseContext.foreachPartition method.
   *
   * It allow addition support for a user to take RDD
   * and generate checkAndPuts and send them to HBase.
   * The complexity of managing the HConnection is
   * removed from the developer
   *
   * @param rdd       Original RDD with data to iterate over
   * @param tableName The name of the table to put into
   * @param f         Function to convert a value in the RDD to
   *                  a HBase checkAndPut
   * @param autoFlush If autoFlush should be turned on
   */
  def bulkCheckAndPut[T](rdd: RDD[T], tableName: String, f: (T) => (Array[Byte], Array[Byte], Array[Byte], Array[Byte], Put), autoFlush: Boolean) {
    rdd.foreachPartition(
      it => hbaseForeachPartition[T](
        broadcastedConf,
        it,
        (iterator, hConnection) => {


          val htable = hConnection.getTable(tableName)
          htable.setAutoFlush(autoFlush, true)

          iterator.foreach(T => {
            val checkPut = f(T)
            htable.checkAndPut(checkPut._1, checkPut._2, checkPut._3, checkPut._4, checkPut._5)
          })
          htable.flushCommits()
          htable.close()
        }))
  }

  /**
   * A simple abstraction over the HBaseContext.streamMapPartition method.
   *
   * It allow addition support for a user to take a DStream and
   * generate checkAndPuts and send them to HBase.
   *
   * The complexity of managing the HConnection is
   * removed from the developer
   *
   * @param dstream    Original DStream with data to iterate over
   * @param tableName  The name of the table to checkAndPut into
   * @param f          function to convert a value in the RDD to
   *                   a HBase checkAndPut
   * @param autoFlush        If autoFlush should be turned on
   */
  def streamBulkCheckAndPut[T](dstream: DStream[T], tableName: String, f: (T) => (Array[Byte], Array[Byte], Array[Byte], Array[Byte], Put), autoFlush: Boolean) {
    dstream.foreach((rdd, time) => {
      bulkCheckAndPut(rdd, tableName, f, autoFlush)
    })
  }

  /**
   * A simple abstraction over the HBaseContext.foreachPartition method.
   *
   * It allow addition support for a user to take a RDD and
   * generate increments and send them to HBase.
   *
   * The complexity of managing the HConnection is
   * removed from the developer
   *
   * @param rdd       Original RDD with data to iterate over
   * @param tableName The name of the table to increment to
   * @param f         function to convert a value in the RDD to a
   *                  HBase Increments
   * @param batchSize       The number of increments to batch before sending to HBase
   */
  def bulkIncrement[T](rdd: RDD[T], tableName: String, f: (T) => Increment, batchSize: Integer) {
    bulkMutation(rdd, tableName, f, batchSize)
  }

  /**
   * A simple abstraction over the HBaseContext.foreachPartition method.
   *
   * It allow addition support for a user to take a RDD and
   * generate increments and send them to HBase and process result using callbacks
   *
   * The complexity of managing the HConnection is
   * removed from the developer
   *
   * @param rdd       Original RDD with data to iterate over
   * @param tableName The name of the table to increment to
   * @param f         function to convert a value in the RDD to a
   *                  HBase Increments
   * @param batchSize       The number of increments to batch before sending to HBase
   */
  def bulkIncrementWithCallback[T](rdd: RDD[T], tableName: String, f: (T) => Increment, batchSize: Integer, g: Result => Unit) {
    bulkMutationWithCallback(rdd, tableName, f, batchSize, g)
  }

  /**
   * A simple abstraction over the HBaseContext.foreachPartition method.
   *
   * It allow addition support for a user to take a RDD and generate delete
   * and send them to HBase.  The complexity of managing the HConnection is
   * removed from the developer
   *
   * @param rdd       Original RDD with data to iterate over
   * @param tableName The name of the table to delete from
   * @param f         Function to convert a value in the RDD to a
   *                  HBase Deletes
   * @param batchSize       The number of delete to batch before sending to HBase
   */
  def bulkDelete[T](rdd: RDD[T], tableName: String, f: (T) => Delete, batchSize: Integer) {
    bulkMutation(rdd, tableName, f, batchSize)
  }

  /**
   * A simple abstraction over the HBaseContext.foreachPartition method.
   *
   * It allow addition support for a user to take a RDD and generate
   * checkAndDelete and send them to HBase.  The complexity of managing the
   * HConnection is removed from the developer
   *
   * @param rdd       Original RDD with data to iterate over
   * @param tableName The name of the table to delete from
   * @param f         Function to convert a value in the RDD to a
   *                  HBase Deletes
   */
  def bulkCheckDelete[T](rdd: RDD[T],
                         tableName: String,
                         f: (T) => (Array[Byte], Array[Byte], Array[Byte], Array[Byte], Delete)) {
    rdd.foreachPartition(
      it => hbaseForeachPartition[T](
        broadcastedConf,
        it,
        (iterator, hConnection) => {
          val htable = hConnection.getTable(tableName)

          iterator.foreach(T => {
            val checkDelete = f(T)
            htable.checkAndDelete(checkDelete._1, checkDelete._2, checkDelete._3, checkDelete._4, checkDelete._5)
          })
          htable.flushCommits()
          htable.close()
        }))
  }

  /**
   * A simple abstraction over the HBaseContext.streamBulkMutation method.
   *
   * It allow addition support for a user to take a DStream and
   * generate Increments and send them to HBase.
   *
   * The complexity of managing the HConnection is
   * removed from the developer
   *
   * @param dstream   Original DStream with data to iterate over
   * @param tableName The name of the table to increments into
   * @param f         Function to convert a value in the DStream to a
   *                  HBase Increments
   * @param batchSize       The number of increments to batch before sending to HBase
   */
  def streamBulkIncrement[T](dstream: DStream[T],
                             tableName: String,
                             f: (T) => Increment,
                             batchSize: Int) = {
    streamBulkMutation(dstream, tableName, f, batchSize)
  }

  /**
   * A simple abstraction over the HBaseContext.streamBulkMutation method.
   *
   * It allow addition support for a user to take a DStream and
   * generate Delete and send them to HBase.
   *
   * The complexity of managing the HConnection is
   * removed from the developer
   *
   * @param dstream    Original DStream with data to iterate over
   * @param tableName  The name of the table to delete from
   * @param f          function to convert a value in the DStream to a
   *                   HBase Delete
   * @param batchSize        The number of deletes to batch before sending to HBase
   */
  def streamBulkDelete[T](dstream: DStream[T],
                          tableName: String,
                          f: (T) => Delete,
                          batchSize: Integer) = {
    streamBulkMutation(dstream, tableName, f, batchSize)
  }

  /**
   * A simple abstraction over the bulkCheckDelete method.
   *
   * It allow addition support for a user to take a DStream and
   * generate CheckAndDelete and send them to HBase.
   *
   * The complexity of managing the HConnection is
   * removed from the developer
   *
   * @param dstream    Original DStream with data to iterate over
   * @param tableName  The name of the table to delete from
   * @param f          function to convert a value in the DStream to a
   *                   HBase Delete
   */
  def streamBulkCheckAndDelete[T](dstream: DStream[T],
                                  tableName: String,
                                  f: (T) => (Array[Byte], Array[Byte], Array[Byte], Array[Byte], Delete)) {
    dstream.foreach((rdd, time) => {
      bulkCheckDelete(rdd, tableName, f)
    })
  }

  /**
   *  Under lining function to support all bulk mutations
   *
   *  May be opened up if requested
   */
  private def bulkMutation[T](rdd: RDD[T], tableName: String, f: (T) => Mutation, batchSize: Integer) {
    rdd.foreachPartition(
      it => hbaseForeachPartition[T](
        broadcastedConf,
        it,
        (iterator, hConnection) => {
          val htable = hConnection.getTable(tableName)
          val mutationList = new ArrayList[Mutation]
          iterator.foreach(T => {
            mutationList.add(f(T))
            if (mutationList.size >= batchSize) {
              htable.batch(mutationList)
              mutationList.clear()
            }
          })
          if (mutationList.size() > 0) {
            htable.batch(mutationList)
            mutationList.clear()
          }
          htable.close()
        }))
  }

  /**
   *  Under lining function to support all bulk mutations using callback
   *
   *  May be opened up if requested
   */
  private def bulkMutationWithCallback[T](rdd: RDD[T], tableName: String, f: (T) => Mutation, batchSize: Integer, g: Result => Unit) {
    //df.foreachPartition(p => log.error(s"partition:${p.length}"))
    rdd.foreachPartition(
      it => hbaseForeachRowPartition[T](
        broadcastedConf,
        it,
        (iterator, connection) => {
          val table = connection.getTable(TableName.valueOf(tableName))
          val mutationList = new ArrayList[Mutation]
          iterator.foreach(T => {
            mutationList.add(f(T))
            if (mutationList.size >= batchSize) {

              val results = Array.ofDim[Object](mutationList.size())

              table.batchCallback(mutationList, results, new Batch.Callback[Result]() {
                override def update(region: Array[Byte], row: Array[Byte], result: Result): Unit = {
                  //TODO realize the alert mechanism
                  g(result)
                }
              })

              mutationList.clear()
            }
          })
          if (mutationList.size() > 0) {
            val results = Array.ofDim[Object](mutationList.size())

            table.batchCallback(mutationList, results, new Batch.Callback[Result]() {
              override def update(region: Array[Byte], row: Array[Byte], result: Result): Unit = {
                g(result)
              }
            })
            mutationList.clear()
          }
          table.close()
        }))
  }

  /**
   *  Under lining function to support all bulk streaming mutations
   *
   *  May be opened up if requested
   */
  private def streamBulkMutation[T](dstream: DStream[T],
                                    tableName: String,
                                    f: (T) => Mutation,
                                    batchSize: Integer) = {
    dstream.foreach((rdd, time) => {
      bulkMutation(rdd, tableName, f, batchSize)
    })
  }

  /**
   * A simple abstraction over the HBaseContext.mapPartition method.
   *
   * It allow addition support for a user to take a RDD and generates a
   * new RDD based on Gets and the results they bring back from HBase
   *
   * @param rdd     Original RDD with data to iterate over
   * @param tableName        The name of the table to get from
   * @param makeGet    function to convert a value in the RDD to a
   *                   HBase Get
   * @param convertResult This will convert the HBase Result object to
   *                   what ever the user wants to put in the resulting
   *                   RDD
   * return            new RDD that is created by the Get to HBase
   */
  def bulkGet[T, U](tableName: String,
                    batchSize: Integer,
                    rdd: RDD[T],
                    makeGet: (T) => Get,
                    convertResult: (Result) => U): RDD[U] = {

    val getMapPartition = new GetMapPartition(tableName,
      batchSize,
      makeGet,
      convertResult)

    rdd.mapPartitions[U](it =>
      hbaseMapPartition[T, U](
        broadcastedConf,
        it,
        getMapPartition.run), true)(fakeClassTag[U])
  }

  /**
   * A simple abstraction over the HBaseContext.streamMap method.
   *
   * It allow addition support for a user to take a DStream and
   * generates a new DStream based on Gets and the results
   * they bring back from HBase
   *
   * @param dstream   Original DStream with data to iterate over
   * @param tableName The name of the table to get from
   * @param makeGet   function to convert a value in the DStream to a
   *                  HBase Get
   * @param convertResult This will convert the HBase Result object to
   *                      what ever the user wants to put in the resulting
   *                      DStream
   * return            new DStream that is created by the Get to HBase
   */
  def streamBulkGet[T, U: ClassTag](tableName: String,
                                    batchSize: Integer,
                                    dstream: DStream[T],
                                    makeGet: (T) => Get,
                                    convertResult: (Result) => U): DStream[U] = {

    val getMapPartition = new GetMapPartition(tableName,
      batchSize,
      makeGet,
      convertResult)

    dstream.mapPartitions[U](it => hbaseMapPartition[T, U](
      broadcastedConf,
      it,
      getMapPartition.run), true)
  }

  /**
   * This function will use the native HBase TableInputFormat with the
   * given scan object to generate a new RDD
   *
   *  @param tableName the name of the table to scan
   *  @param scan      the HBase scan object to use to read data from HBase
   *  @param f         function to convert a Result object from HBase into
   *                   what the user wants in the final generated RDD
   *  @return          new RDD with results from scan
   */
  def hbaseRDD[U: ClassTag](tableName: String, scan: Scan, f: ((ImmutableBytesWritable, Result)) => U): RDD[U] = {

    var job: Job = new Job(getConf(broadcastedConf))

    TableMapReduceUtil.initCredentials(job)
    TableMapReduceUtil.initTableMapperJob(tableName, scan, classOf[IdentityTableMapper], null, null, job)

    sc.newAPIHadoopRDD(job.getConfiguration(),
      classOf[TableInputFormat],
      classOf[ImmutableBytesWritable],
      classOf[Result]).map(f)
  }


  /**
   * A overloaded version of HBaseContext hbaseRDD that predefines the
   * type of the outputing RDD
   *
   *  @param tableName the name of the table to scan
   *  @param scans      the HBase scan object to use to read data from HBase
   *  @return New RDD with results from scan
   *
   */
  def hbaseRDD(tableName: String, scans: Scan):
  RDD[(Array[Byte], java.util.List[(Array[Byte], Array[Byte], Array[Byte])])] = {

    hbaseRDD[(Array[Byte], java.util.List[(Array[Byte], Array[Byte], Array[Byte])])](
      tableName,
      scans,
      (r: (ImmutableBytesWritable, Result)) => {
        val it = r._2.list().iterator()
        val list = new ArrayList[(Array[Byte], Array[Byte], Array[Byte])]()

        while (it.hasNext()) {
          val kv = it.next()
          list.add((kv.getFamily(), kv.getQualifier(), kv.getValue()))
        }

        (r._1.copyBytes(), list)
      })
  }

  def hbaseScanRDD(tableName: String, scan: Scan):
  RDD[(Array[Byte], java.util.List[(Array[Byte], Array[Byte], Array[Byte])])] = {

    new HBaseScanRDD(sc, tableName, scan,
      broadcastedConf)
  }


  /**
   *  Under lining wrapper all foreach functions in HBaseContext
   *
   */
  private def hbaseForeachPartition[T](
                                        configBroadcast: Broadcast[SerializableWritable[Configuration]],
                                        it: Iterator[T],
                                        f: (Iterator[T], HConnection) => Unit) = {

    val config = getConf(configBroadcast)


    applyCreds(configBroadcast)
    // specify that this is a proxy user
    val hConnection = HConnectionManager.createConnection(config)
    f(it, hConnection)
    hConnection.close()

  }

  /**
   *  Under lining wrapper all foreach functions in HBaseContext
   *
   */
  private def hbaseForeachRowPartition[T](
                                           configBroadcast: Broadcast[SerializableWritable[Configuration]],
                                           it: Iterator[T],
                                           f: (Iterator[T], Connection) => Unit) = {

    val config = getConf(configBroadcast)

    val executor = Executors.newFixedThreadPool(10);

    val connection = ConnectionFactory.createConnection(config, executor);
    //val connection = ConnectionFactory.createConnection(config);

    f(it, connection)

    connection.close()

  }



  private def getConf(configBroadcast: Broadcast[SerializableWritable[Configuration]]): Configuration = {

    if (tmpHdfsConfiguration != null) {
      tmpHdfsConfiguration
    } else if (tmpHdfsConfgFile != null) {

      val fs = FileSystem.newInstance(SparkHadoopUtil.get.conf)



      val inputStream = fs.open(new Path(tmpHdfsConfgFile))
      tmpHdfsConfiguration = new Configuration(false)
      tmpHdfsConfiguration.readFields(inputStream)
      inputStream.close()

      tmpHdfsConfiguration
    }

    if (tmpHdfsConfiguration == null) {
      try {
        tmpHdfsConfiguration = configBroadcast.value.value
        tmpHdfsConfiguration
      } catch {
        case ex: Exception =>{
          println("Unable to getConfig from broadcast")
        }
      }
    }


    tmpHdfsConfiguration
  }

  /**
   *  Under lining wrapper all mapPartition functions in HBaseContext
   *
   */
  private def hbaseMapPartition[K, U](
                                       configBroadcast: Broadcast[SerializableWritable[Configuration]],
                                       it: Iterator[K],
                                       mp: (Iterator[K], HConnection) => Iterator[U]): Iterator[U] = {

    val config = getConf(configBroadcast)
    applyCreds(configBroadcast)
    val hConnection = HConnectionManager.createConnection(config)

    val res = mp(it, hConnection)
    hConnection.close()
    res

  }


  /**
   *  Under lining wrapper all get mapPartition functions in HBaseContext
   */
  private class GetMapPartition[T, U](tableName: String,
                                      batchSize: Integer,
                                      makeGet: (T) => Get,
                                      convertResult: (Result) => U) extends Serializable {

    def run(iterator: Iterator[T], hConnection: HConnection): Iterator[U] = {
      val htable = hConnection.getTable(tableName)

      val gets = new ArrayList[Get]()
      var res = List[U]()

      while (iterator.hasNext) {
        gets.add(makeGet(iterator.next))

        if (gets.size() == batchSize) {
          var results = htable.get(gets)
          res = res ++ results.map(convertResult)
          gets.clear()
        }
      }
      if (gets.size() > 0) {
        val results = htable.get(gets)
        res = res ++ results.map(convertResult)
        gets.clear()
      }
      htable.close()
      res.iterator
    }
  }



/**
   * Produces a ClassTag[T], which is actually just a casted ClassTag[AnyRef].
   *
   * This method is used to keep ClassTags out of the external Java API, as the Java compiler
   * cannot produce them automatically. While this ClassTag-faking does please the compiler,
   * it can cause problems at runtime if the Scala API relies on ClassTags for correctness.
   *
   * Often, though, a ClassTag[AnyRef] will not lead to incorrect behavior, just worse performance
   * or security issues. For instance, an Array[AnyRef] can hold any type T, but may lose primitive
   * specialization.
   */
  private[spark]
  def fakeClassTag[T]: ClassTag[T] = ClassTag.AnyRef.asInstanceOf[ClassTag[T]]
}