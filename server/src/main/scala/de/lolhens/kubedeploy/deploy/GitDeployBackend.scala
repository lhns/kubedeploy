package de.lolhens.kubedeploy.deploy

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.apply._
import de.lolhens.kubedeploy.deploy.GitUtils._
import de.lolhens.kubedeploy.model.DeployAction.{JsonAction, RegexAction, YamlAction}
import de.lolhens.kubedeploy.model.DeployResult.{DeployFailure, DeploySuccess}
import de.lolhens.kubedeploy.model.DeployTarget.GitDeployTarget
import de.lolhens.kubedeploy.model.{Deploy, DeployResult, DeployTarget}
import org.eclipse.jgit.lib._
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.log4s.getLogger

import java.nio.charset.StandardCharsets

class GitDeployBackend(
                        val target: DeployTarget,
                        git: GitDeployTarget,
                      ) extends DeployBackend with ConsolidateActions {
  private val logger = getLogger

  private val credentialsProvider = new UsernamePasswordCredentialsProvider(git.username, git.password.value)
  private val personIdent = new PersonIdent(git.committer.name, git.committer.email)

  override def deploy(request: Deploy): EitherT[IO, DeployResult.DeployFailure, DeployResult.DeploySuccess] = {
    val result: IO[Unit] = updateFile(
      git.url.renderString,
      Some(credentialsProvider),
      git.branch,
      request.resource,
      { bytesOption =>
        val string = bytesOption.map(new String(_, StandardCharsets.UTF_8)).getOrElse("")

        val newStringIO = request.actions.foldLeft(IO {
          string
        })((acc, action) => acc.map { string =>
          action match {
            case regexAction: RegexAction =>
              regexAction.transform(string)
            case yamlAction: YamlAction =>
              yamlAction.transform(string)
            case jsonAction: JsonAction =>
              jsonAction.transform(string)
            case e =>
              logger.warn("unsupported action: " + e)
              string
          }
        })

        newStringIO.map { newString =>
          Some(newString.getBytes(StandardCharsets.UTF_8))
        }
      },
      s"Update ${request.resource}",
      personIdent
    )

    EitherT.right[DeployFailure](result) *>
      EitherT.rightT[IO, DeployFailure](DeploySuccess(awaitedStatus = false))
  }
}
