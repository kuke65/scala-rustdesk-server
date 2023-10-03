package com.akuk

import java.net.{Inet4Address, Inet6Address, InetSocketAddress}
import java.util.Base64

import akka.actor
import akka.actor.typed.ActorRef
import akka.actor.typed.javadsl.Adapter
import akka.actor.{Actor, ActorSystem, Props, ReceiveTimeout}
import akka.http.scaladsl.model.ws.BinaryMessage
import akka.io.Tcp.Event
import akka.io.{IO, Tcp}
import akka.remote.Ack
import akka.util.ByteString
import hbb.RendezvousMessage
import hbb.RendezvousMessage.Union.RequestRelay
import org.slf4j.{Logger, LoggerFactory}
import rust.gensk.Gensk
import com.akuk.TcpReplyActor.{Command, _}

import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}


object TcpReplyActor {

  import scala.concurrent.duration._

  sealed trait Command extends Event

  case class HandleRef(receiveRef: ActorRef[Command], ws: Boolean = false, receiveTimeout: Option[Duration] = None)

  type WhomTupleRef = (HandleRef, HandleRef)

  case class TcpListenerData(data: ByteString, tcpReplyTo: actor.ActorRef, remote: InetSocketAddress, ws: Boolean) extends Command

  case class TcpListener2Data(data: ByteString, tcpReplyTo: actor.ActorRef, remote: InetSocketAddress) extends Command

  case class TcpListener3Data(data: ByteString, tcpReplyTo: actor.ActorRef, remote: InetSocketAddress) extends Command

  case class RelayTcpListenerData(data: ByteString, tcpReplyTo: actor.ActorRef, remote: InetSocketAddress, ws: Boolean, connection: actor.ActorRef) extends Command

  case class RelayTcpListener3Data(data: ByteString, tcpReplyTo: actor.ActorRef, remote: InetSocketAddress, ws: Boolean) extends Command

  case class RelayTcpListenerRef(peerHandleRef: actor.ActorRef) extends Command

  case class RelayTcpConnRef(peerHandleRef: actor.ActorRef, connRef: actor.ActorRef) extends Command

  case class RelayTcpRawRef(data: ByteString, peerHandleRef: actor.ActorRef, connRef: actor.ActorRef) extends Command

  case class RelayTcpHandleRef(data: ByteString, tcpReplyTo: actor.ActorRef, remote: InetSocketAddress) extends Command

  case class TcpMsg(data: ByteString) extends Command

  case class TcpRawMsg(data: ByteString) extends Command

  case class TcpArrayByteMsg(msg: Array[Byte]) extends Command

  case class TcpBinaryMsg(data: ByteString) extends Command


  def is_loopback(remote: InetSocketAddress): Boolean = {
    remote.getAddress match {
      case ip: Inet4Address => ip.isLoopbackAddress
      case ip: Inet6Address => ip.isLoopbackAddress
      case _ =>
        throw new RuntimeException("unknown InetSocketAddress type or createUnresolved")
    }
  }


}

/**
 *
 * @Author Shuheng.Zhang
 *         2023/9/1 22:13
 */
class TcpReplyActor(classz: Class[_], inetSocketAddress: InetSocketAddress, whomRef: WhomTupleRef) extends Actor {

  import Tcp._
  import context.system


  IO(Tcp) ! Bind(self, inetSocketAddress)

  def receive = {
    case b@Bound(localAddress) =>
      context.parent ! b

    case CommandFailed(_: Bind) => context.stop(self)

    case c@Connected(remote, local) =>
      val handler = context.actorOf(Props(classOf[SimplisticHandler], classz, remote, whomRef, sender()))
      sender() ! Register(handler)
  }
}

class SimplisticHandler(classz: Class[_], remote: InetSocketAddress, whomRef: WhomTupleRef, connection: akka.actor.ActorRef) extends Actor {

  import Tcp._
  import context._

  val log: Logger = LoggerFactory.getLogger(getClass)

  val rawHandleRef: actor.ActorRef = context.actorOf(Props(classOf[TcpRawActor]))
  context.watch(connection)

  override def preStart(): Unit = {
    super.preStart()
    if (null != whomRef) {
      if (classOf[Listener3Actor] == classz) {
        context.setReceiveTimeout(whomRef._1.receiveTimeout.getOrElse(Duration.Undefined))
      } else if (classOf[Listener2Actor] == classz) {
        if (TcpReplyActor.is_loopback(remote)) {
          context.setReceiveTimeout(whomRef._1.receiveTimeout.getOrElse(Duration.Undefined))
        } else {
          context.setReceiveTimeout(whomRef._2.receiveTimeout.getOrElse(Duration.Undefined))
        }
      } else if (classOf[RelayTcpListenerActor] == classz) {
        //whomRef._1.receiveRef ! TcpListener3Data(data, self, remote)
        if (TcpReplyActor.is_loopback(remote)) {
          context.setReceiveTimeout(whomRef._1.receiveTimeout.getOrElse(Duration.Undefined))
        } else {
          context.setReceiveTimeout(whomRef._2.receiveTimeout.getOrElse(Duration.Undefined))
        }
      } else {
        log.info("not match classz!!! {}", classz.getName)
      }
    }

  }

  override def postStop(): Unit = {
    super.postStop()
  }

  var firstReq = 0
  var peer: akka.actor.ActorRef = _
  var peer_conn: akka.actor.ActorRef = _

  override def receive = {
    case Received(data) =>


      if (null != whomRef) {

        if (classOf[Listener3Actor] == classz) {
          log.info("111 not match data!!! {}, peer = {}", data.length, peer)
          whomRef._1.receiveRef ! TcpListenerData(data, self, remote, whomRef._1.ws)
        } else if (classOf[Listener2Actor] == classz) {
          log.info("222 not match data!!! {}, peer = {}", data.length, peer)
          //whomRef._1.receiveRef ! TcpListener3Data(data, self, remote)
          if (TcpReplyActor.is_loopback(remote)) {
            whomRef._1.receiveRef ! TcpListener3Data(data, self, remote)
          } else {
            whomRef._2.receiveRef ! TcpListener2Data(data, self, remote)
          }
        } else if (classOf[RelayTcpListenerActor] == classz) {
          log.info(s"333 not match data!!!{}. {}, peer = {} ",data.toArray.head.toInt, data.length, peer)
          //whomRef._1.receiveRef ! TcpListener3Data(data, self, remote)
          if (TcpReplyActor.is_loopback(remote)) {
            whomRef._1.receiveRef ! RelayTcpListener3Data(data, self, remote, whomRef._1.ws)
          } else {

            if(peer_conn == null) {
              whomRef._2.receiveRef ! RelayTcpListenerData(data, self, remote, whomRef._2.ws, connection)
            }else {
              rawHandleRef ! RelayTcpRawRef(data, peer, peer_conn)
            }
          }

        } else {
          log.info("not match classz!!! {}", classz.getName)
        }
      }


    case TcpRawMsg(data) =>
      connection ! Write(data)
    case TcpMsg(data) =>
      connection ! Write(ByteString(encodeByte(data.toArray)))
    case TcpArrayByteMsg(data) =>
      connection ! Write(ByteString(encodeByte(data)))
    case TcpBinaryMsg(data) =>
      connection ! Write(ByteString(BinaryMessage(ByteString(encodeByte(data.toArray))).toString().getBytes()))
    case RelayTcpListenerRef(peerRef) =>
      peer = peerRef
      log.info("RelayTcpListenerRef!!!")
    case RelayTcpConnRef(peerRef, conn) =>
      peer = peerRef
      peer_conn = conn
      //become(relay(peer, peer_conn), discardOld = true)
      peer_conn ! Write(ByteString(0.toByte))
      log.info("RelayTcpConnRef!!!")
    case PeerClosed =>
      self ! PeerClosed
      context.stop(self)
    case ReceiveTimeout =>
      log.info("receiveTimeout!!! {}", s"${remote.getHostString}:${remote.getPort}")
      context.stop(self)

  }

  def encodeByte(data: Array[Byte]) = {
    val encodeBytesStr = Gensk.getInstance().bytesencode(Base64.getEncoder.encodeToString(data))
    val encodeByte = Base64.getDecoder.decode(encodeBytesStr)
    encodeByte
  }
}
