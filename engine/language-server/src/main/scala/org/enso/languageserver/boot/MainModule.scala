package org.enso.languageserver.boot

import java.io.File
import java.net.URI

import akka.actor.ActorSystem
import akka.stream.SystemMaterializer
import org.enso.jsonrpc.JsonRpcServer
import org.enso.languageserver.capability.CapabilityRouter
import org.enso.languageserver.data._
import org.enso.languageserver.effect.ZioExec
import org.enso.languageserver.filemanager.{
  FileManager,
  FileSystem,
  ReceivesTreeUpdatesHandler
}
import org.enso.languageserver.http.server.BinaryWebSocketServer
import org.enso.languageserver.io._
import org.enso.languageserver.protocol.binary.{
  BinaryConnectionControllerFactory,
  InboundMessageDecoder
}
import org.enso.languageserver.protocol.json.{
  JsonConnectionControllerFactory,
  JsonRpc
}
import org.enso.languageserver.runtime._
import org.enso.languageserver.session.SessionRouter
import org.enso.languageserver.text.BufferRegistry
import org.enso.languageserver.util.binary.BinaryEncoder
import org.enso.polyglot.{LanguageInfo, RuntimeOptions, RuntimeServerInfo}
import org.enso.searcher.sql.SqlSuggestionsRepo
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.MessageEndpoint

import scala.concurrent.duration._

/**
  * A main module containing all components of the server.
  *
  * @param serverConfig configuration for the language server
  */
class MainModule(serverConfig: LanguageServerConfig) {

  lazy val languageServerConfig = Config(
    Map(serverConfig.contentRootUuid -> new File(serverConfig.contentRootPath)),
    FileManagerConfig(timeout = 3.seconds),
    PathWatcherConfig(),
    ExecutionContextConfig()
  )

  val zioExec = ZioExec(zio.Runtime.default)

  lazy val fileSystem: FileSystem = new FileSystem

  implicit val versionCalculator: ContentBasedVersioning =
    Sha3_224VersionCalculator

  implicit val system =
    ActorSystem(
      serverConfig.name,
      None,
      None,
      Some(serverConfig.computeExecutionContext)
    )

  implicit val materializer = SystemMaterializer.get(system)

  val suggestionsRepo = {
    val repo = SqlSuggestionsRepo()(system.dispatcher)
    repo.init
    repo
  }

  lazy val sessionRouter =
    system.actorOf(SessionRouter.props(), "session-router")

  lazy val runtimeConnector =
    system.actorOf(RuntimeConnector.props, "runtime-connector")

  lazy val fileManager = system.actorOf(
    FileManager.pool(languageServerConfig, fileSystem, zioExec),
    "file-manager"
  )

  lazy val bufferRegistry =
    system.actorOf(
      BufferRegistry.props(fileManager, runtimeConnector),
      "buffer-registry"
    )

  lazy val receivesTreeUpdatesHandler =
    system.actorOf(
      ReceivesTreeUpdatesHandler
        .props(languageServerConfig, fileSystem, zioExec),
      "file-event-registry"
    )

  lazy val suggestionsDatabaseEventsListener =
    system.actorOf(
      SuggestionsDatabaseEventsListener.props(sessionRouter, suggestionsRepo)
    )

  lazy val suggestionsHandler =
    system.actorOf(SuggestionsHandler.props(suggestionsRepo))

  lazy val capabilityRouter =
    system.actorOf(
      CapabilityRouter.props(
        bufferRegistry,
        receivesTreeUpdatesHandler,
        suggestionsDatabaseEventsListener
      ),
      "capability-router"
    )

  lazy val contextRegistry =
    system.actorOf(
      ContextRegistry
        .props(languageServerConfig, runtimeConnector, sessionRouter),
      "context-registry"
    )

  val stdOut    = new ObservableOutputStream
  val stdErr    = new ObservableOutputStream
  val stdInSink = new ObservableOutputStream
  val stdIn     = new ObservablePipedInputStream(stdInSink)

  val context = Context
    .newBuilder(LanguageInfo.ID)
    .allowAllAccess(true)
    .allowExperimentalOptions(true)
    .option(RuntimeServerInfo.ENABLE_OPTION, "true")
    .option(RuntimeOptions.PACKAGES_PATH, serverConfig.contentRootPath)
    .option(
      RuntimeServerInfo.JOB_PARALLELISM_OPTION,
      Runtime.getRuntime.availableProcessors().toString
    )
    .out(stdOut)
    .err(stdErr)
    .in(stdIn)
    .serverTransport((uri: URI, peerEndpoint: MessageEndpoint) => {
      if (uri.toString == RuntimeServerInfo.URI) {
        val connection = new RuntimeConnector.Endpoint(
          runtimeConnector,
          peerEndpoint
        )
        runtimeConnector ! RuntimeConnector.Initialize(peerEndpoint)
        connection
      } else null
    })
    .build()
  context.initialize(LanguageInfo.ID)

  val runtimeKiller =
    system.actorOf(
      RuntimeKiller.props(runtimeConnector, context),
      "runtime-context"
    )

  val stdOutController =
    system.actorOf(
      OutputRedirectionController
        .props(stdOut, OutputKind.StandardOutput, sessionRouter),
      "std-out-controller"
    )

  val stdErrController =
    system.actorOf(
      OutputRedirectionController
        .props(stdErr, OutputKind.StandardError, sessionRouter),
      "std-err-controller"
    )

  val stdInController =
    system.actorOf(
      InputRedirectionController.props(stdIn, stdInSink, sessionRouter),
      "std-in-controller"
    )

  lazy val jsonRpcControllerFactory = new JsonConnectionControllerFactory(
    bufferRegistry,
    capabilityRouter,
    fileManager,
    contextRegistry,
    suggestionsHandler,
    stdOutController,
    stdErrController,
    stdInController,
    runtimeConnector
  )

  lazy val jsonRpcServer =
    new JsonRpcServer(
      JsonRpc.protocol,
      jsonRpcControllerFactory,
      JsonRpcServer
        .Config(outgoingBufferSize = 10000, lazyMessageTimeout = 10.seconds)
    )

  lazy val binaryServer =
    new BinaryWebSocketServer(
      InboundMessageDecoder,
      BinaryEncoder.empty,
      new BinaryConnectionControllerFactory(fileManager)
    )

}
