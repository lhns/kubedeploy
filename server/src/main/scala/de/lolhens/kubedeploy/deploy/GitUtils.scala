package de.lolhens.kubedeploy.deploy

import cats.effect.IO
import cats.syntax.traverse._
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.{ConcurrentRefUpdateException, JGitInternalException}
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.internal.storage.dfs.{DfsRepositoryDescription, InMemoryRepository}
import org.eclipse.jgit.lib._
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.PushConfig.PushDefault
import org.eclipse.jgit.transport.{CredentialsProvider, RefSpec, TagOpt}
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter

import java.text.MessageFormat
import scala.jdk.CollectionConverters._
import scala.util.chaining._

object GitUtils {
  def fetchRepository(
                       remote: String,
                       credentialsProvider: Option[CredentialsProvider]
                     ): IO[Repository] = IO.blocking {
    val repository = new InMemoryRepository(new DfsRepositoryDescription())
    val result = new Git(repository).fetch
      .setRemote(remote)
      .setCredentialsProvider(credentialsProvider.orNull)
      .setRefSpecs(new RefSpec(s"+${Constants.R_HEADS}*:${Constants.R_HEADS}*"))
      .setTagOpt(TagOpt.FETCH_TAGS)
      .call

    result.getAdvertisedRefs.asScala.find(_.getName == Constants.HEAD).foreach { headRef =>
      val refUpdate = repository.getRefDatabase.newUpdate(Constants.HEAD, true)
      refUpdate.link(headRef.getTarget.getName)
    }

    repository
  }

  def updateFile(
                  remote: String,
                  credentialsProvider: Option[CredentialsProvider],
                  branch: Option[String],
                  path: String,
                  update: Option[Array[Byte]] => IO[Option[Array[Byte]]],
                  message: String,
                  person: PersonIdent,
                  force: Boolean = false,
                ): IO[Unit] = {
    for {
      repository <- fetchRepository(remote, credentialsProvider)
      branchRef = branch.map(Constants.R_HEADS + _).getOrElse(repository.getFullBranch)
      fileOption <- repository.readFile(branchRef, path)
      newFileOption <- update(fileOption.map(_._1)).map(newBytesOption =>
        (fileOption.map(_._1), newBytesOption, fileOption.map(_._2)) match {
          case (Some(bytes), Some(newBytes), _) if bytes sameElements newBytes => None
          case (_, Some(newBytes), fileModeOption) => Some((newBytes, fileModeOption.getOrElse(FileMode.REGULAR_FILE)))
          case _ => None
        }
      )
      _ <- newFileOption.map {
        case (newBytes, fileMode) =>
          repository.commitFile(branchRef, path, newBytes, fileMode, message, person, force) *>
            repository.push(remote, credentialsProvider)
      }.sequence
    } yield ()
  }

  def branchRef(branchName: String): String = Constants.R_HEADS + branchName

  implicit class RepositoryOps(val repository: Repository) extends AnyVal {
    def readFile(ref: String, path: String): IO[Option[(Array[Byte], FileMode)]] = IO {
      val treeWalk = new TreeWalk(repository).tap { treeWalk =>
        treeWalk.addTree(new RevWalk(repository).parseCommit(repository.resolve(ref)).getTree)
        treeWalk.setRecursive(true)
        treeWalk.setFilter(PathFilter.create(path))
      }
      Option.when(treeWalk.next)(
        (treeWalk.getObjectId(0), treeWalk.getFileMode(0))
      ).map {
        case (objectId, fileMode) =>
          val bytes = repository.open(objectId).getBytes
          (bytes, fileMode)
      }
    }

    def commitFile(ref: String,
                   path: String,
                   bytes: Array[Byte],
                   fileMode: FileMode = FileMode.REGULAR_FILE,
                   message: String,
                   person: PersonIdent,
                   force: Boolean = false,
                  ): IO[ObjectId] = IO.blocking {
      val inserter = repository.newObjectInserter()
      try {
        val resolvedRef = repository.resolve(ref)

        // insert blob
        val blobId = inserter.insert(Constants.OBJ_BLOB, bytes)

        // insert tree
        val treeId = inserter.insert(
          new TreeFormatter().tap { treeFormatter =>
            treeFormatter.append(path, fileMode, blobId)

            // add existing tree
            val treeWalk = new TreeWalk(repository)
            treeWalk.addTree(new RevWalk(repository).parseCommit(resolvedRef).getTree)
            while (treeWalk.next)
              treeFormatter.append(treeWalk.getNameString, treeWalk.getFileMode(0), treeWalk.getObjectId(0))
          }
        )

        // insert commit
        val commitId = inserter.insert(
          new CommitBuilder().tap { commitBuilder =>
            commitBuilder.setTreeId(treeId)
            commitBuilder.setMessage(message)
            commitBuilder.setAuthor(person)
            commitBuilder.setCommitter(person)
            commitBuilder.setParentId(resolvedRef)
          }
        )

        inserter.flush()

        val refUpdate = repository.updateRef(ref).tap { refUpdate =>
          refUpdate.setExpectedOldObjectId(resolvedRef)
          refUpdate.setNewObjectId(commitId)
          refUpdate.setForceUpdate(force)
        }
        refUpdate.update() match {
          case RefUpdate.Result.NEW | RefUpdate.Result.FORCED | RefUpdate.Result.FAST_FORWARD =>
            repository.getRepositoryState match {
              case RepositoryState.BARE => commitId
              case state =>
                throw new JGitInternalException(MessageFormat.format(JGitText.get().cannotCommitOnARepoWithState, state))
            }

          case result@(RefUpdate.Result.REJECTED | RefUpdate.Result.LOCK_FAILURE) =>
            throw new ConcurrentRefUpdateException(JGitText.get.couldNotLockHEAD, refUpdate.getRef, result)

          case result =>
            throw new JGitInternalException(MessageFormat.format(JGitText.get().updatingRefFailed, ref, commitId.toString, result))
        }
      } finally {
        inserter.close()
      }
    }

    def push(
              remote: String,
              credentialsProvider: Option[CredentialsProvider]
            ): IO[Unit] = IO.blocking {
      new Git(repository).push()
        .setRemote(remote)
        .setCredentialsProvider(credentialsProvider.orNull)
        .setPushDefault(PushDefault.MATCHING)
        .call()
    }
  }
}