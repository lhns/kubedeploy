package de.lolhens.kubedeploy.deploy

import cats.effect.unsafe.IORuntime
import de.lolhens.kubedeploy.deploy.GitUtils._
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib._
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

import scala.jdk.CollectionConverters._

object GitDeployBackend {


  def updateFile(
                  remote: String,
                  username: String,
                  password: String,
                  branch: String,
                  path: String,
                  update: Array[Byte] => Array[Byte],
                  message: String,
                  person: PersonIdent,
                  force: Boolean = false,
                ) = {
    for {
      repository <- fetchRepository(remote, new UsernamePasswordCredentialsProvider(username, password))
      bytes <- repository.readFile(repository.getBranch, path)
    } yield ()
  }

  def main(args: Array[String]): Unit = {


    val repo = fetchRepository(remote, credentials).unsafeRunSync()(IORuntime.global)
    val refs = repo.getRefDatabase.getRefsByPrefix("refs/").asScala.toList
    println(refs)
    println(repo.getConfig.getSections.asScala.toList)

    println(repo.getRefDatabase.getRefs.asScala.map(_.getName))
    println(Git.lsRemoteRepository().setRemote(remote)
      .setCredentialsProvider(credentials).callAsMap().get("HEAD"))
    //repo.getConfig.getNames().asScala.toList
    /*
        println(repo.readFile("main", "README.md").unsafeRunSync()(IORuntime.global).map(new String(_, StandardCharsets.UTF_8)))

        val commitId = repo.commitFile("main", "README.md", "Hello World 3!".getBytes(StandardCharsets.UTF_8)).unsafeRunSync()(IORuntime.global)
        repo.push(remote, credentials).unsafeRunSync()(IORuntime.global)
    */
  }
}
