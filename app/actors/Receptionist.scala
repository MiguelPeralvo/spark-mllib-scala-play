package actors

import akka.actor.{Actor, ActorRef, Props}
import org.apache.spark.SparkContext
import org.apache.spark.ml.Model
import org.apache.spark.mllib.classification.LogisticRegressionModel
import play.api.Logger
import play.api.Play.{configuration, current}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object Receptionist {
  def props(sparkContext: SparkContext, eventServer: ActorRef, statisticsServer: ActorRef) = Props(new Receptionist(sparkContext, eventServer, statisticsServer))

  case object GetClassifier

  case object TrainingFinished

  val trainOnline = configuration.getBoolean("ml.trainer.online").getOrElse(false)

}

class Receptionist(sparkContext: SparkContext, eventServer: ActorRef, statisticsServer: ActorRef) extends Actor {

  import Receptionist._

  val log = Logger(this.getClass)

  val twitterHandler = context.actorOf(TwitterHandler.props(sparkContext), "twitter-handler")
  val trainer = context.actorOf(if (trainOnline) OnlineTrainer.props(sparkContext, self) else BatchTrainer.props(sparkContext, self), "trainer")
  val classifier = context.actorOf(Classifier.props(sparkContext, twitterHandler, trainer), "classifier")
  context.actorOf(CorpusInitializer.props(sparkContext, trainer, eventServer, statisticsServer), "corpus-initializer")

  override def receive = {

    case GetClassifier => sender ! classifier

    case TrainingFinished => context.system.scheduler.schedule(0 seconds, 1 seconds)(trainer ! GetLatestModel)

    case m: LogisticRegressionModel  => statisticsServer ! m

    case m: Model[_] => statisticsServer ! m

    case undefined => log.info(s"Unexpected message $undefined")
  }

}
