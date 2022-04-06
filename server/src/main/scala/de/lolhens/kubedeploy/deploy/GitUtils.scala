package de.lolhens.kubedeploy.deploy

import cats.effect.IO
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.{ConcurrentRefUpdateException, JGitInternalException}
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.internal.storage.dfs.{DfsRepositoryDescription, InMemoryRepository}
import org.eclipse.jgit.lib._
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.PushConfig.PushDefault
import org.eclipse.jgit.transport.{CredentialsProvider, RefSpec}
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter

import java.text.MessageFormat

object GitUtils {
  def fetchRepository(remote: String, credentialsProvider: CredentialsProvider): IO[Repository] = IO.blocking {
    val repo = new InMemoryRepository(new DfsRepositoryDescription())
    new Git(repo).fetch
      .setRemote(remote)
      .setCredentialsProvider(credentialsProvider)
      .setRefSpecs(new RefSpec(s"+${Constants.R_HEADS}*:${Constants.R_HEADS}*"))
      .call
    repo
  }

  def branchRef(branchName: String): String = Constants.R_HEADS + branchName

  implicit class RepositoryOps(val repository: Repository) extends AnyVal {
    def readFile(ref: String, path: String): IO[Option[Array[Byte]]] = IO {
      val treeWalk = new TreeWalk(repository)
      treeWalk.addTree(new RevWalk(repository).parseCommit(repository.resolve(ref)).getTree)
      treeWalk.setRecursive(true)
      treeWalk.setFilter(PathFilter.create(path))
      Option.when(treeWalk.next)(treeWalk.getObjectId(0)).map { objectId =>
        repository.open(objectId).getBytes
      }
    }

    def commitFile(ref: String,
                   path: String,
                   bytes: Array[Byte],
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
        val treeFormatter = new TreeFormatter()
        treeFormatter.append(path, FileMode.REGULAR_FILE, blobId)
        val treeId = inserter.insert(treeFormatter)

        // insert commit
        val commitBuilder = new CommitBuilder()
        commitBuilder.setTreeId(treeId)
        commitBuilder.setMessage(message)
        commitBuilder.setAuthor(person)
        commitBuilder.setCommitter(person)
        commitBuilder.setParentId(resolvedRef)
        val commitId = inserter.insert(commitBuilder)

        inserter.flush()

        val refUpdate = repository.updateRef(ref)
        refUpdate.setExpectedOldObjectId(resolvedRef)
        refUpdate.setNewObjectId(commitId)
        refUpdate.setForceUpdate(force)
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

    def push(remote: String, credentialsProvider: CredentialsProvider): IO[Unit] = IO.blocking {
      new Git(repository).push()
        .setRemote(remote)
        .setCredentialsProvider(credentialsProvider)
        .setPushDefault(PushDefault.MATCHING)
        .call()
    }
  }
}