package com.akuk

import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.time.Instant
import java.util.Base64

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, SupervisorStrategy}
import akka.util.ByteString
import hbb.RendezvousMessage
import hbb.RendezvousMessage.Union._
import org.slf4j.LoggerFactory
import rust.gensk.Gensk
import com.akuk.TcpReplyActor.{TcpArrayByteMsg, TcpListener2Data, TcpListener3Data, TcpMsg}

import scala.concurrent.duration._
import scala.util.{Failure, Success}
import com.akuk.RendezvousServer.objectRS


/**
 *
 * @Author Shuheng.Zhang
 *         2023/8/31 17:15
 */
class Listener2Actor {
  private val log = LoggerFactory.getLogger(getClass)

  def handle_online_request(tcpReplyTo: ActorRef, peers: Seq[String]) = {
    val states = Array.fill[Byte]((peers.length + 7)/8)(0)
    for ((peer_id, i) <- peers.zipWithIndex) {
      if( objectRS.pm.contains(peer_id) ) {
        val peer = objectRS.pm(peer_id)
        val now = Instant.now()
        val elapsed = now.getEpochSecond - peer.last_reg_time.getEpochSecond

        val states_idx:Int = i / 8
        val bit_idx:Int = 7 - i % 8
        if(elapsed < 30) {
          states(states_idx) = (states(states_idx) | (0x01 << bit_idx)).toByte
        }
      }
    }

    var msg_out = new RendezvousMessage()
    msg_out = msg_out.withOnlineResponse(hbb.OnlineResponse(_root_.com.google.protobuf.ByteString.copyFrom(states)))
    tcpReplyTo ! TcpArrayByteMsg(msg_out.toByteArray)
  }

  def check_cmd(cmd:String) = {
    var res = ""
    val fds = cmd.trim.split(" ")
    if(fds.headOption.nonEmpty) {
      fds(0) match {
        case "h" =>
        case "relay-servers" | "rs" =>
        case "ip-blocker" | "ib" =>
        case "ip-changes" | "ic" =>
        case "always-use-relay" | "aur" =>
        case "test-geo" | "tg" =>
        case _ =>
      }
    }
    res
  }

  def apply(): Behavior[com.akuk.TcpReplyActor.Command] = {
    Behaviors.supervise[com.akuk.TcpReplyActor.Command] {
      Behaviors.setup[com.akuk.TcpReplyActor.Command] { context =>
        //val system = context.system
        //preStart(context)

//        val inetSocketAddress = new InetSocketAddress("192.168.0.7", 21118)
//        val system = context.system.classicSystem
//        val tcpReplyActor = system.actorOf(Props(classOf[TcpReplyActor], inetSocketAddress, context.self), "tcp")

        Behaviors.receiveMessage[com.akuk.TcpReplyActor.Command] {

          // tl2d: TcpListener2Data(bytes: ByteString, tcpReplyTo: ActorRef, remote: InetSocketAddress)
          case TcpListener2Data(bytes: ByteString, tcpReplyTo: ActorRef, remote: InetSocketAddress) =>
            val decodeBytesStr = Gensk.getInstance().bytesdecode(Base64.getEncoder.encodeToString(bytes.toArray))
            val decodeByte = Base64.getDecoder.decode(decodeBytesStr)
            RendezvousMessage.validate(decodeByte) match {
              case Success(value: RendezvousMessage) =>
                log.info("\n\tTcpListener2Data {}", value.union)
                value.union match {
                  case TestNatRequest(tar) => {
                    var msg_out = new RendezvousMessage()
                    msg_out = msg_out.withTestNatResponse(hbb.TestNatResponse(port = remote.getPort))
                    tcpReplyTo ! TcpArrayByteMsg(msg_out.toByteArray)
                    Behaviors.same
                  }
                  case OnlineRequest(or) => {
                    handle_online_request(tcpReplyTo, or.peers)
                    Behaviors.same
                  }
                  case e: Object => {
                    if(bytes.length < 500)
                      log.error("\n\tTcpListener2Data unknown object !!! {} result {}. from {}",
                        e,
                        new String(bytes.toArray, Charset.defaultCharset()),
                        s"${remote.getHostString}:${remote.getPort}")
                    else
                      log.error("\n\tTcpListener2Data unknown object !!! error = {}. from {}",  e, s"${remote.getHostString}:${remote.getPort}")
                    Behaviors.same
                  }
                }
              case Failure(e: Throwable) =>
                if (bytes.length < 500)
                  log.error("\n\tTcpListener2Data Failure !!! {} result {}. from {}",
                    e,
                    new String(bytes.toArray, Charset.defaultCharset()),
                    s"${remote.getHostString}:${remote.getPort}")
                else
                  log.error("\n\tTcpListener2Data Failure !!! error = {}. from {}", e.getMessage, s"${remote.getHostString}:${remote.getPort}")
                //tcpReplyTo ! com.akuk.TcpReplyActor.TcpArrayByteMsg(bytes.toArray)
                Behaviors.same
            }
          case TcpListener3Data(bytes: ByteString, tcpReplyTo: ActorRef, remote: InetSocketAddress) =>
//            val decodeBytesStr = Gensk.getInstance().bytesdecode(Base64.getEncoder.encodeToString(bytes.toArray))
//            val decodeByte = Base64.getDecoder.decode(decodeBytesStr)
            log.info("\n\tTcpListener3Data ")
            if(bytes.length <= 1024) {
              val res = new String(bytes.toArray, Charset.defaultCharset())
              log.info("\n\tTcpListener3Data Failure !!! result {}, from {}", res, s"${remote.getHostString}:${remote.getPort}")
              // TODO check_cmd
              tcpReplyTo ! TcpArrayByteMsg(bytes.toArray)
            } else
              log.error("\n\tTcpListener3Data Failure !!! from {}", s"${remote.getHostString}:${remote.getPort}")
            Behaviors.same
          case _ => {
            log.error(" Receive Failure data !!!")
            Behaviors.same
          }
        }
        //Behaviors.empty
      }
    }.onFailure[Exception](SupervisorStrategy.restartWithBackoff(2.seconds,5.seconds,0.2).withMaxRestarts(10))
  }

}
