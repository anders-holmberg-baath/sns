package me.snov.sns

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import me.snov.sns.actor._
import me.snov.sns.api._
import me.snov.sns.service.DbService
import me.snov.sns.util.ToStrict

import scala.concurrent.{Await, Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.Properties

class EmbeddedServer(dbPath: String, interface: String, port: Int) extends ToStrict {
  implicit val system = ActorSystem("sns")
  implicit val executor: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val logger: LoggingAdapter = Logging(system, getClass)
  implicit val timeout = new Timeout(1.second)

  def start(): EmbeddedSnsServer = {
    val dbService = new DbService(dbPath)

    val dbActor = system.actorOf(DbActor.props(dbService), name = "DbActor")
    val homeActor = system.actorOf(HomeActor.props, name = "HomeActor")
    val subscribeActor = system.actorOf(SubscribeActor.props(dbActor), name = "SubscribeActor")
    val publishActor = system.actorOf(PublishActor.props(subscribeActor), name = "PublishActor")

    val routes: Route =
      toStrict {
        TopicApi.route(subscribeActor) ~
        SubscribeApi.route(subscribeActor) ~
        PublishApi.route(publishActor) ~
        HealthCheckApi.route ~
        HomeApi.route(homeActor)
      }

    val appStartFuture = Http().bindAndHandle(
      handler = logRequestResult("akka-http-sns")(routes),
      interface = interface,
      port = port
    )

    EmbeddedSnsServer(appStartFuture, () => {
      appStartFuture.flatMap { sb =>
        system.shutdown()
        system.awaitTermination()
        sb.unbind()
      }
    })
  }
}

case class EmbeddedSnsServer(startFuture: Future[Http.ServerBinding], stopAndGetFuture: () => Future[Any]) {
  def waitUntilStarted() = {
    Await.result(startFuture, 1.minute)
  }

  def stopAndWait() = {
    val stopFuture = stopAndGetFuture()
    Await.result(stopFuture, 1.minute)
  }
}
