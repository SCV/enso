package org.enso.projectmanager.requesthandler

import java.util.UUID

import akka.actor._
import akka.pattern.pipe
import org.enso.jsonrpc.Errors.ServiceError
import org.enso.jsonrpc._
import org.enso.projectmanager.infrastructure.execution.Exec
import org.enso.projectmanager.protocol.ProjectManagementApi.ProjectCreate
import org.enso.projectmanager.service.ProjectServiceApi
import zio._

import scala.concurrent.duration.FiniteDuration

class ProjectCreateHandler(
  service: ProjectServiceApi[ZIO[ZEnv, *, *]],
  exec: Exec[ZIO[ZEnv, *, *]],
  requestTimeout: FiniteDuration
) extends Actor
    with ActorLogging {
  override def receive: Receive = requestStage

  import context.dispatcher

  private def requestStage: Receive = {
    case Request(ProjectCreate, id, params: ProjectCreate.Params) =>
      exec.exec(service.createUserProject(params.name)).pipeTo(self)
      val cancellable =
        context.system.scheduler
          .scheduleOnce(requestTimeout, self, RequestTimeout)
      context.become(responseStage(id, sender(), cancellable))
  }

  private def responseStage(
    id: Id,
    replyTo: ActorRef,
    cancellable: Cancellable
  ): Receive = {
    case Status.Failure(ex) =>
      log.error(s"Failure during $ProjectCreate operation:", ex)
      replyTo ! ResponseError(Some(id), ServiceError)
      cancellable.cancel()
      context.stop(self)

    case RequestTimeout =>
      log.error(s"Request $ProjectCreate with $id timed out")
      replyTo ! ResponseError(Some(id), ServiceError)
      context.stop(self)

    case Left(failure) =>
      log.error(s"Request $id failed due to $failure")
      replyTo ! ResponseError(Some(id), ServiceError) //todo
      cancellable.cancel()
      context.stop(self)

    case Right(projectId: UUID) =>
      replyTo ! ResponseResult(
        ProjectCreate,
        id,
        ProjectCreate.Result(projectId)
      )
      cancellable.cancel()
      context.stop(self)
  }

}

object ProjectCreateHandler {

  def props(
    service: ProjectServiceApi[ZIO[ZEnv, *, *]],
    exec: Exec[ZIO[ZEnv, *, *]],
    requestTimeout: FiniteDuration
  ): Props =
    Props(new ProjectCreateHandler(service, exec, requestTimeout))

}
