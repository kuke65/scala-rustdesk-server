package com.akuk

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import hbb.RendezvousMessage
import hbb.RendezvousMessage.Union._

import scala.concurrent.duration._
import scala.reflect.ClassTag



/**
 *
 * @Author Shuheng.Zhang
 *         2023/8/31 17:15
 *
 */
object TimerCheckRelayActor {


  sealed trait Command
  private case object RequestTimeout extends Command
  private case class RequestMessagesTimeout(bytes: Array[Byte]) extends Command
  private case object FinalTimeout extends Command
  private case class WrappedReply[R](reply: R) extends Command

  def apply[Reply: ClassTag](sendRequest: (Int, ActorRef[Reply]) => Boolean,
                             nextRequestAfter: FiniteDuration,
                             replyTo: ActorRef[Reply],
                             finalTimeout: FiniteDuration,
                             timeoutReply: Reply): Behavior[Command] = {
    Behaviors.supervise[Command] {
      Behaviors.setup[Command] { context =>
        //val system = context.system
        //preStart(context)

        Behaviors.withTimers[Command]{timers =>
          val replyAdapter = context.messageAdapter[Reply](WrappedReply(_))

          def waiting(requestCount: Int): Behavior[Command] = {
            Behaviors.receiveMessage {
              case RequestMessagesTimeout(bytes) =>
                RendezvousMessage.parseFrom(bytes).union match {
                  case TestNatRequest(tar) => {
                    Behaviors.empty
                  }
                  case OnlineRequest(or) => {
                    Behaviors.empty
                  }
                  case _ => {
                    Behaviors.empty
                  }
                }
              case WrappedReply(reply) =>
                replyTo ! reply.asInstanceOf[Reply]
                Behaviors.stopped

              case RequestTimeout =>
                println("TimerCheckRelayActor count = " + requestCount)
                sendNextRequest(requestCount + 1)
              case FinalTimeout =>
                //replyTo ! timeoutReply
                Behaviors.stopped
              case _ => {
                Behaviors.empty
              }
            }

          }

          def sendNextRequest(requestCount: Int): Behavior[Command] = {
            if (sendRequest(requestCount, replyAdapter)) {
              timers.startSingleTimer(RequestTimeout, nextRequestAfter)
            } else {
              timers.startSingleTimer(FinalTimeout, finalTimeout)
            }
            waiting(requestCount)
          }

          sendNextRequest(1)
        }

        //Behaviors.empty
      }
    }.onFailure[Exception](SupervisorStrategy.restartWithBackoff(2.seconds,5.seconds,0.2).withMaxRestarts(10))
  }

}
