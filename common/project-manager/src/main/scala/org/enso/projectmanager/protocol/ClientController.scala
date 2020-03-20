package org.enso.projectmanager.protocol

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash}
import org.enso.jsonrpc.{JsonRpcServer, MessageHandler, Method, Request}
import org.enso.projectmanager.infrastructure.execution.Exec
import org.enso.projectmanager.protocol.ProjectManagementApi.ProjectCreate
import org.enso.projectmanager.requesthandler.ProjectCreateHandler
import org.enso.projectmanager.service.ProjectServiceApi
import zio._

import scala.concurrent.duration.FiniteDuration

/**
  * An actor handling communications between a single client and the project
  * manager.
  *
  * @param clientId the internal client id.
  */
class ClientController(
  clientId: UUID,
  projectService: ProjectServiceApi,
  exec: Exec[ZIO[ZEnv, *, *]],
  timeout: FiniteDuration
) extends Actor
    with Stash
    with ActorLogging {

  private val requestHandlers: Map[Method, Props] =
    Map(
      ProjectCreate -> ProjectCreateHandler.props(projectService, exec, timeout)
    )

  override def unhandled(message: Any): Unit =
    log.warning("Received unknown message: {}", message)

  override def receive: Receive = {
    case JsonRpcServer.WebConnect(webActor) =>
      unstashAll()
      context.become(connected(webActor))

    case _ => stash()
  }

  def connected(webActor: ActorRef): Receive = {
    case MessageHandler.Disconnected =>
      context.stop(self)

    case r @ Request(method, _, _) if (requestHandlers.contains(method)) =>
      val handler = context.actorOf(requestHandlers(method))
      handler.forward(r)
  }
}

object ClientController {

  /**
    * Creates a configuration object used to create a [[ClientController]].
    *
    * @param clientId the internal client id.
    * @return a configuration object
    */
  def props(
    clientId: UUID,
    projectService: ProjectServiceApi,
    exec: Exec[ZIO[ZEnv, *, *]],
    timeout: FiniteDuration
  ): Props =
    Props(new ClientController(clientId, projectService, exec, timeout))

}