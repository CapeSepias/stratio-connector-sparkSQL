/*
 * Licensed to STRATIO (C) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.  The STRATIO (C) licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.stratio.connector.sparksql

import akka.actor.{Kill, ActorRef, ActorRefFactory, ActorSystem}
import com.stratio.connector.sparksql.connection.ConnectionHandler
import com.stratio.connector.sparksql.engine.query.{QueryManager, QueryEngine}
import com.stratio.crossdata.common.connector._
import com.stratio.crossdata.common.data.ClusterName
import com.stratio.crossdata.common.exceptions.{InitializationException, UnsupportedException}
import com.stratio.crossdata.common.security.ICredentials
import com.stratio.crossdata.connectors.ConnectorApp
import com.typesafe.config.Config
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.hive.HiveContext

import com.stratio.connector.commons.timer
import com.stratio.connector.sparksql.engine.SparkSQLMetadataListener

class SparkSQLConnector(
  system: ActorRefFactory) extends IConnector
with Loggable
with Metrics {

  import timer._
  import SparkSQLConnector._

  val connectionHandler = new ConnectionHandler

  lazy val sparkContext: SparkContext =
    timeFor("Creating SparkContext") {
      initContext(connectorConfig.get)
    }

  lazy val sqlContext: SparkSQLContext =
    timeFor(s"Creating $sqlContextType from SparkContext") {
      sqlContextBuilder(sqlContextType, sparkContext)
    }

  lazy val queryManager: ActorRef =
    timeFor("Creating QueryManager") {
      system.actorOf(
        QueryManager(
          queryExecutors,
          sqlContext,
          connectionHandler,
          (workflow, connectionHandler, sqlContext) =>
            QueryEngine.executeQuery(
              workflow,
              sqlContext,
              connectionHandler,
              provider)))
    }

  //  Engines

  lazy val queryEngine: QueryEngine =
    timeFor("Setting query engine instance...") {
      new QueryEngine(sqlContext, queryManager, connectionHandler, provider)
    }

  //  Config parameters

  val queryExecutors: Int =
    connectorConfig.get.getInt(QueryExecutorsAmount)

  val provider: Provider =
    providers(connectorConfig.get.getString(ConnectorProvider)).getOrElse(
      throw new InitializationException("Given provider is not valid"))

  val sqlContextType: String =
    connectorConfig.get.getString(SQLContextType)

  //  IConnector implemented methods

  override def getConnectorName: String =
    connectorConfigFile.get.child
      .find(_.label == ConnectorName)
      .map(_.text)
      .getOrElse(
        throw new NoSuchElementException(
          s"Property $ConnectorName was not set"))

  override def getDatastoreName: Array[String] =
    connectorConfigFile.get.child
      .find(_.label == DataStoreName)
      .map(_.child.map(_.text).toArray)
      .getOrElse(
        throw new NoSuchElementException(
          s"Property $DataStoreName was not set"))

  override def init(configuration: IConfiguration): Unit =
    timeFor(s"Initializing SparkSQL connector") {
      timeFor("Subscribing to metadata updates...") {
        connectorApp.subscribeToMetadataUpdate(
          SparkSQLMetadataListener(
            sqlContext,
            sparkSQLConnector.provider,
            connectionHandler))
      }
    }

  override def connect(
    credentials: ICredentials,
    config: ConnectorClusterConfig): Unit =
    timeFor("Connecting to SparkSQL connector") {
      connectionHandler.createConnection(config, Option(credentials))
    }

  override def getQueryEngine: IQueryEngine =
    queryEngine

  override def isConnected(name: ClusterName): Boolean =
    connectionHandler.isConnected(name.getName)

  override def close(name: ClusterName): Unit =
    timeFor(s"Closing connection to $name cluster") {
      connectionHandler.closeConnection(name.getName)
    }

  override def shutdown(): Unit =
    timeFor("Shutting down connector...") {
      logger.debug("Disposing QueryManager")
      queryManager ! Kill
      logger.debug("Disposing SparkContext")
      sparkContext.stop()
      logger.debug("Connector was shut down.")
    }

  //  Helpers

  /**
   * Build a brand new Spark context given some config parameters.
   * @param config Configuration object.
   * @return A new Spark context
   */
  def initContext(config: Config): SparkContext = {
    import scala.collection.JavaConversions._
    new SparkContext(new SparkConf()
      .setAppName(SparkSQLConnector.SparkSQLConnectorJobConstant)
      .setSparkHome(config.getString(SparkHome))
      .setMaster(config.getString(SparkMaster))
      .setJars(config.getConfig(Spark).getStringList(SparkJars))
      .setAll(List(
      SparkDriverMemory,
      SparkExecutorMemory,
      SparkTaskCPUs).map(k => k -> config.getString(k))))
  }

  /**
   * Build a new SQLContext depending on given type.
   *
   * @param contextType 'HiveContext' and 'SQLContext' (by default) are,
   *                    by now, the only supported types.
   * @param sc SparkContext used to build new SQLContext.
   * @return A brand new SQLContext.
   */
  def sqlContextBuilder(
    contextType: String,
    sc: SparkContext): SparkSQLContext =
    contextType match {
      case HIVEContext => new HiveContext(sc) with Catalog
      case _ => new SQLContext(sc) with Catalog
    }

  //  Unsupported methods

  override def getStorageEngine: IStorageEngine =
    throw new UnsupportedException(SparkSQLConnector.MethodNotSupported)

  override def getMetadataEngine: IMetadataEngine =
    throw new UnsupportedException(SparkSQLConnector.MethodNotSupported)

}

object SparkSQLConnector extends App
with Constants
with Configuration
with Loggable
with Metrics {

  import timer._

  val system =
    timeFor(s"Initializing '$ActorSystemName' actor system...") {
      ActorSystem(ActorSystemName)
    }

  val connectorApp =
    timeFor("Creating Connector App. ...") {
      new ConnectorApp
    }

  val sparkSQLConnector =
    timeFor(s"Building SparkSQLConnector...") {
      new SparkSQLConnector(system)
    }

  timeFor("Starting up connector...") {
    connectorApp.startup(sparkSQLConnector)
  }

  system.registerOnTermination {
    timeFor("Termination detected. Shutting down actor system...") {
      sparkSQLConnector.shutdown()
    }
  }

}