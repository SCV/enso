package org.enso.projectmanager.infrastructure.repository

import java.io.File
import java.util.UUID

import org.enso.pkg.{Package, PackageManager}
import org.enso.projectmanager.boot.configuration.StorageConfig
import org.enso.projectmanager.control.core.CovariantFlatMap
import org.enso.projectmanager.control.core.syntax._
import org.enso.projectmanager.control.effect.syntax._
import org.enso.projectmanager.control.effect.{ErrorChannel, Sync}
import org.enso.projectmanager.infrastructure.file.{FileStorage, FileSystem}
import org.enso.projectmanager.infrastructure.repository.ProjectRepositoryFailure.{
  InconsistentStorage,
  ProjectNotFoundInIndex,
  StorageFailure
}
import org.enso.projectmanager.model.Project

/**
  * File based implementation of the project repository.
  *
  * @param storageConfig a storage config
  * @param fileSystem a file system abstraction
  * @param indexStorage an index storage
  */
class ProjectFileRepository[F[+_, +_]: Sync: ErrorChannel: CovariantFlatMap](
  storageConfig: StorageConfig,
  fileSystem: FileSystem[F],
  indexStorage: FileStorage[ProjectIndex, F]
) extends ProjectRepository[F] {

  /** @inheritdoc * */
  override def exists(
    name: String
  ): F[ProjectRepositoryFailure, Boolean] =
    indexStorage
      .load()
      .map(_.exists(name))
      .mapError(_.fold(convertFileStorageFailure))

  /** @inheritdoc * */
  override def find(
    predicate: Project => Boolean
  ): F[ProjectRepositoryFailure, List[Project]] =
    indexStorage
      .load()
      .map(_.find(predicate))
      .mapError(_.fold(convertFileStorageFailure))

  /** @inheritdoc * */
  override def getAll(): F[ProjectRepositoryFailure, List[Project]] =
    indexStorage
      .load()
      .map(_.projects.values.toList)
      .mapError(_.fold(convertFileStorageFailure))

  /** @inheritdoc * */
  override def findById(
    projectId: UUID
  ): F[ProjectRepositoryFailure, Option[Project]] =
    indexStorage
      .load()
      .map(_.findById(projectId))
      .mapError(_.fold(convertFileStorageFailure))

  /** @inheritdoc * */
  override def create(
    project: Project
  ): F[ProjectRepositoryFailure, Unit] =
    // format: off
    for {
      projectPath     <- findTargetPath(project)
      projectWithPath  = project.copy(path = Some(projectPath.toString))
      _               <- createProjectStructure(project, projectPath)
      _               <- update(projectWithPath)
    } yield ()
    // format: on

  /** @inheritdoc * */
  override def update(project: Project): F[ProjectRepositoryFailure, Unit] =
    indexStorage
      .modify { index =>
        val updated = index.upsert(project)
        (updated, ())
      }
      .mapError(_.fold(convertFileStorageFailure))

  private def createProjectStructure(
    project: Project,
    projectPath: File
  ): F[StorageFailure, Package[File]] =
    Sync[F]
      .blockingOp { PackageManager.Default.create(projectPath, project.name) }
      .mapError(th => StorageFailure(th.toString))

  /** @inheritdoc * */
  override def rename(
    projectId: UUID,
    name: String
  ): F[ProjectRepositoryFailure, Unit] = {
    updateProjectName(projectId, name) *>
    updatePackageName(projectId, name)
  }

  private def updatePackageName(
    projectId: UUID,
    name: String
  ): F[ProjectRepositoryFailure, Unit] =
    for {
      project <- getProject(projectId)
      _       <- changePacketName(new File(project.path.get), name)
    } yield ()

  private def getProject(
    projectId: UUID
  ): F[ProjectRepositoryFailure, Project] =
    findById(projectId)
      .flatMap {
        case None          => ErrorChannel[F].fail(ProjectNotFoundInIndex)
        case Some(project) => CovariantFlatMap[F].pure(project)
      }

  /** @inheritdoc * */
  def getPackageName(projectId: UUID): F[ProjectRepositoryFailure, String] = {
    for {
      project        <- getProject(projectId)
      projectPackage <- getPackage(new File(project.path.get))
    } yield projectPackage.config.name
  }

  private def changePacketName(
    projectPath: File,
    name: String
  ): F[ProjectRepositoryFailure, Unit] =
    getPackage(projectPath)
      .flatMap { projectPackage =>
        val newName = PackageManager.Default.normalizeName(name)
        Sync[F]
          .blockingOp { projectPackage.rename(newName) }
          .map(_ => ())
          .mapError(th => StorageFailure(th.toString))
      }

  private def getPackage(
    projectPath: File
  ): F[ProjectRepositoryFailure, Package[File]] =
    Sync[F]
      .blockingOp { PackageManager.Default.fromDirectory(projectPath) }
      .mapError(th => StorageFailure(th.toString))
      .flatMap {
        case None =>
          ErrorChannel[F].fail(
            InconsistentStorage(s"Cannot find package.yaml at $projectPath")
          )

        case Some(projectPackage) => CovariantFlatMap[F].pure(projectPackage)
      }

  private def updateProjectName(
    projectId: UUID,
    name: String
  ): F[ProjectRepositoryFailure, Unit] =
    indexStorage
      .modify { index =>
        val updated = index.update(projectId)(_.copy(name = name))
        (updated, ())
      }
      .mapError(_.fold(convertFileStorageFailure))

  /** @inheritdoc * */
  override def delete(
    projectId: UUID
  ): F[ProjectRepositoryFailure, Unit] =
    indexStorage
      .modify { index =>
        val maybeProject = index.findById(projectId)
        index.remove(projectId) -> maybeProject
      }
      .mapError(_.fold(convertFileStorageFailure))
      .flatMap {
        case None =>
          ErrorChannel[F].fail(ProjectNotFoundInIndex)

        case Some(project) if project.path.isEmpty =>
          ErrorChannel[F].fail(
            InconsistentStorage(
              "Index cannot contain a user project without path"
            )
          )

        case Some(project) =>
          removeProjectStructure(project.path.get)
      }

  private def removeProjectStructure(
    projectPath: String
  ): F[ProjectRepositoryFailure, Unit] =
    fileSystem
      .removeDir(new File(projectPath))
      .mapError[ProjectRepositoryFailure](failure =>
        StorageFailure(failure.toString)
      )

  /** @inheritdoc * */
  override def moveProjectToTargetDir(
    projectId: UUID
  ): F[ProjectRepositoryFailure, File] = {
    def move(project: Project) =
      for {
        targetPath <- findTargetPath(project)
        _          <- moveProjectDir(project, targetPath)
        _          <- updateProjectDir(projectId, targetPath)
      } yield targetPath

    for {
      project <- getProject(projectId)
      primaryPath = getPrimaryPath(project)
      finalPath <-
        if (isLocationOk(project.path.get, primaryPath.toString)) {
          CovariantFlatMap[F].pure(primaryPath)
        } else {
          move(project)
        }
    } yield finalPath
  }

  private def isLocationOk(
    currentPath: String,
    primaryPath: String
  ): Boolean = {
    if (currentPath.startsWith(primaryPath)) {
      val suffixPattern = "_\\d+"
      val suffix        = currentPath.substring(primaryPath.length, currentPath.length)
      suffix.matches(suffixPattern)
    } else {
      false
    }
  }

  private def updateProjectDir(projectId: UUID, targetPath: File) = {
    indexStorage
      .modify { index =>
        val updated = index.update(projectId)(
          _.copy(path = Some(targetPath.toString))
        )
        (updated, ())
      }
      .mapError(_.fold(convertFileStorageFailure))
  }

  private def moveProjectDir(project: Project, targetPath: File) = {
    fileSystem
      .move(new File(project.path.get), targetPath)
      .mapError[ProjectRepositoryFailure](failure =>
        StorageFailure(failure.toString)
      )
  }

  private def getPrimaryPath(
    project: Project
  ): File =
    new File(storageConfig.userProjectsPath, project.name)

  private def findTargetPath(
    project: Project
  ): F[ProjectRepositoryFailure, File] =
    CovariantFlatMap[F]
      .tailRecM[ProjectRepositoryFailure, Int, File](0) { number =>
        val path =
          new File(
            storageConfig.userProjectsPath,
            project.name + genSuffix(number)
          )
        fileSystem
          .exists(path)
          .mapError[ProjectRepositoryFailure](failure =>
            StorageFailure(failure.toString)
          )
          .flatMap { fileExists =>
            if (fileExists) {
              CovariantFlatMap[F].pure(Left(number + 1))
            } else {
              CovariantFlatMap[F].pure(Right(path))
            }
          }
      }

  private def genSuffix(number: Int) =
    if (number == 0) ""
    else s"_$number"

}
