package de.lolhens.kubedeploy.deploy

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.eclipse.jgit.lib._
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

import java.nio.charset.StandardCharsets
import scala.util.Try

object GitDeployBackend {

  import org.eclipse.jgit.api.Git
  import org.eclipse.jgit.internal.storage.dfs.{DfsRepositoryDescription, InMemoryRepository}
  import org.eclipse.jgit.lib.ObjectId
  import org.eclipse.jgit.transport.RefSpec
  import org.eclipse.jgit.treewalk.TreeWalk
  import org.eclipse.jgit.treewalk.filter.PathFilter

  def fetch(uri: String, username: String, password: String): IO[Repository] = IO.blocking {
    val repo = new InMemoryRepository(new DfsRepositoryDescription())
    new Git(repo).fetch
      .setRemote(uri)
      .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password))
      .setRefSpecs(new RefSpec(s"+${Constants.R_HEADS}*:${Constants.R_HEADS}*"))
      .call
    repo
  }

  implicit class RepositoryOps(val repo: Repository) extends AnyVal {
    def getBranchHead(branch: String): ObjectId = repo.resolve(Constants.R_HEADS + branch)


    def readFile(branch: String, path: String): IO[Option[Array[Byte]]] = IO {
      val treeWalk = new TreeWalk(repo)
      treeWalk.addTree(new RevWalk(repo).parseCommit(getBranchHead(branch)).getTree)
      treeWalk.setRecursive(true)
      treeWalk.setFilter(PathFilter.create(path))
      Option.when(treeWalk.next)(treeWalk.getObjectId(0)).map { objectId =>
        repo.open(objectId).getBytes
      }
    }

    def commitFile(branch: String, path: String, bytes: Array[Byte]): IO[ObjectId] = IO.blocking {
      //https://stackoverflow.com/questions/29149653/fetch-file-modify-contents-and-commit-with-jgit
      val branchHead = getBranchHead(branch)
      val inserter = repo.newObjectInserter()
      try {
        val objectId = inserter.insert(Constants.OBJ_BLOB, bytes)
        val treeFormatter = new TreeFormatter()
        treeFormatter.append(path, FileMode.REGULAR_FILE, objectId)
        val treeWalk = new TreeWalk(repo)
        treeWalk.addTree(new RevWalk(repo).parseCommit(branchHead).getTree)
        while (treeWalk.next)
          treeFormatter.append(treeWalk.getNameString, treeWalk.getFileMode(0), treeWalk.getObjectId(0))
        val treeId = treeFormatter.insertTo(inserter)
        val commitBuilder = new CommitBuilder()
        commitBuilder.setParentId(branchHead)
        val committer = new PersonIdent("Test", "test@test.de")
        commitBuilder.setCommitter(committer)
        commitBuilder.setAuthor(committer)
        commitBuilder.setTreeId(treeId)
        commitBuilder.setMessage("test")
        val commitId = Try {
          inserter.insert(commitBuilder)
        }
        inserter.flush()
        commitId
      } finally {
        inserter.close()
      }
    }.flatMap(IO.fromTry)

    def push(branch: String, commitId: ObjectId): IO[Unit] = IO.blocking {
      val branchHead = getBranchHead(branch)
      val refUpdate = repo.updateRef(Constants.HEAD)
      refUpdate.setNewObjectId(commitId)
      refUpdate.setRefLogMessage(s"commit: ${new RevWalk(repo).parseCommit(commitId).getShortMessage}", false)
      refUpdate.setExpectedOldObjectId(branchHead)
      refUpdate.forceUpdate()
      repo.getRepositoryState match {
        case state =>
          println(state)
      }
    }
  }

  def main(args: Array[String]): Unit = {
    val repo = fetch().unsafeRunSync()(IORuntime.global)

    //repo.newObjectInserter().insert(new CommitBuilder().)
    println(repo.readFile("main", "README.md").unsafeRunSync()(IORuntime.global).map(new String(_, StandardCharsets.UTF_8)))

    val commitId = repo.commitFile("main", "README.md", "Hello World!".getBytes(StandardCharsets.UTF_8)).unsafeRunSync()(IORuntime.global)
    repo.push("main", commitId).unsafeRunSync()(IORuntime.global)

  }
}
