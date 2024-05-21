package org.proof_of_attendance_metagraph.data_l1.daemons.fetcher

import cats.effect.{Async, Resource}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import fs2.io.net.Network
import org.http4s.Request
import org.http4s.client.Client
import org.proof_of_attendance_metagraph.shared_data.app.ApplicationConfig
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe._
import org.proof_of_attendance_metagraph.shared_data.types.DataUpdates.{IntegrationnetNodeOperatorUpdate, ProofOfAttendanceUpdate}
import org.tessellation.node.shared.resources.MkHttpClient
import org.typelevel.ci.CIString
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.proof_of_attendance_metagraph.shared_data.types.IntegrationnetOperatorsTypes.{IntegrationnetOperatorsApiResponse, OperatorInQueue}

object IntegrationnetNodesOperatorsFetcher {

  def make[F[_] : Async : Network](applicationConfig: ApplicationConfig): Fetcher[F] =
    new Fetcher[F] {
      private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass(IntegrationnetNodesOperatorsFetcher.getClass)

      def fetchOperatorsInQueue(url: String): F[IntegrationnetOperatorsApiResponse] = {
        val integrationnetOperatorsConfig = applicationConfig.integrationnetNodesOperatorsDaemon
        val authorizationHeader = CIString("Authorization")

        val clientResource: Resource[F, Client[F]] = MkHttpClient.forAsync[F].newEmber(applicationConfig.http4s.client)

        clientResource.use { client =>
          val request = Request[F](
            method = Method.GET,
            uri = Uri.unsafeFromString(url)
          ).withHeaders(Header.Raw(authorizationHeader, s"ApiKey ${integrationnetOperatorsConfig.apiKey.get}"))

          client.expect[IntegrationnetOperatorsApiResponse](request)(jsonOf[F, IntegrationnetOperatorsApiResponse])
        }
      }

      override def getAddressesAndBuildUpdates: F[List[ProofOfAttendanceUpdate]] = {
        val integrationnetOperatorsConfig = applicationConfig.integrationnetNodesOperatorsDaemon
        val url = s"${integrationnetOperatorsConfig.apiUrl.get}/proof-of-attendance-metagraph/integrationnet-nodes-in-queue"

        for {
          _ <- logger.info(s"Fetching from Integrationnet nodes in queue using URL: $url")
          integrationnetOperatorsApiResponse <- fetchOperatorsInQueue(url).handleErrorWith { err =>
            logger.error(s"Error when fetching from Lattice Integrationnet operators API: ${err.getMessage}")
              .as(IntegrationnetOperatorsApiResponse(List.empty[OperatorInQueue]))
          }
          _ <- logger.info(s"Found ${integrationnetOperatorsApiResponse.data.length} operators in queue")
          dataUpdates = integrationnetOperatorsApiResponse.data.foldLeft(List.empty[ProofOfAttendanceUpdate]) { (acc, operatorInQueue) =>
            acc :+ IntegrationnetNodeOperatorUpdate(operatorInQueue.walletAddress, operatorInQueue)
          }

          _ <- logger.info(s"Integrationnet Operators Updates: $dataUpdates")
        } yield dataUpdates
      }
    }
}
