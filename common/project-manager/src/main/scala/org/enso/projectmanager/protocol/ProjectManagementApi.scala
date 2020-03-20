package org.enso.projectmanager.protocol

import java.util.UUID

import org.enso.jsonrpc.{HasParams, HasResult, Method, Unused}

/**
  * The project management JSON RPC API provided by the project manager.
  * See [[https://github.com/luna/enso/blob/master/doc/design/engine/engine-services.md]]
  * for message specifications.
  */
object ProjectManagementApi {

  case object ProjectCreate extends Method("project/create") {

    case class Params(name: String)

    case class Result(projectId: UUID)

    implicit val hasParams = new HasParams[this.type] {
      type Params = ProjectCreate.Params
    }
    implicit val hasResult = new HasResult[this.type] {
      type Result = ProjectCreate.Result
    }
  }

  case object ProjectDelete extends Method("project/delete") {

    case class Params(projectId: UUID)

    implicit val hasParams = new HasParams[this.type] {
      type Params = ProjectDelete.Params
    }
    implicit val hasResult = new HasResult[this.type] {
      type Result = Unused.type
    }
  }

}
