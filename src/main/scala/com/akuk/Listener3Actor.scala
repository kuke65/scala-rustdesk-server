package com.akuk

import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.Base64

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior, SupervisorStrategy}
import akka.util.ByteString
import hbb.RegisterPkResponse.Result
import hbb.RendezvousMessage
import hbb.RendezvousMessage.Union._
import org.slf4j.LoggerFactory
import rust.gensk.Gensk
import com.akuk.RendezvousServer.objectRS
import com.akuk.TcpReplyActor.TcpListenerData
import com.akuk.UdpMsgHandleActor.{get_pk, handle_punch_hole_request, is_lan_fun, send_to_tcp, send_to_tcp_sync}

import scala.concurrent.duration._
import scala.util.{Failure, Success}


object Listener3Actor {


}

/**
 *
 * @Author Shuheng.Zhang
 *         2023/8/31 17:15
 */
class Listener3Actor {
  private val log = LoggerFactory.getLogger(getClass)


  def handle_tcp_punch_hole_request(addr: InetSocketAddress, ph: hbb.PunchHoleRequest, key: String, ws: Boolean) = {
    val (msg, to_addr) = handle_punch_hole_request(addr, ph, key, ws)
    if (to_addr.nonEmpty) {
      objectRS.tx ! com.akuk.UdpReplyActor.ReplyUdpMsg(msg, to_addr.get)
    } else {
      send_to_tcp_sync(msg, addr)
    }
  }


  def handleTcpMessage(value: RendezvousMessage, tcpReplyTo: ActorRef, remote: InetSocketAddress, key: String, ws: Boolean) = {
    value.union match {
      case PunchHoleRequest(ph: hbb.PunchHoleRequest) => {
        // 暂时全局peer
        objectRS.tcp_punch.put(remote, RsSink(tcpStream = tcpReplyTo))
        handle_tcp_punch_hole_request(remote, ph, key, ws)
      }
      case RequestRelay(rf) => {
        var rf_copy: hbb.RequestRelay = rf.copy()
        objectRS.tcp_punch.put(remote, RsSink(tcpStream = tcpReplyTo))
        if (objectRS.pm.contains(rf.id)) {
          val peer = objectRS.pm(rf.id)

          val addrencode = Gensk.getInstance().addrencode(s"${remote.getHostString}:${remote.getPort}")
          var msg_out = new RendezvousMessage()
          rf_copy = rf_copy.withSocketAddr(_root_.com.google.protobuf.ByteString.copyFrom(Base64.getDecoder.decode(addrencode)))
          msg_out = msg_out.withRequestRelay(rf_copy)
          val peer_addr = peer.socket_addr
          objectRS.tx ! com.akuk.UdpReplyActor.ReplyUdpMsg(msg_out, peer_addr)

        }
      }
      case RelayResponse(rr) => {
        var rr_copy: hbb.RelayResponse = rr.copy()
        val addr_b = Gensk.getInstance().addrdecode(Base64.getEncoder.encodeToString(rr.socketAddr.toByteArray))
        val splitAddr = addr_b.split(":")
        val addr_b_object = new InetSocketAddress(splitAddr(0), splitAddr(1).toInt)

        rr_copy = rr_copy.withSocketAddr(_root_.com.google.protobuf.ByteString.EMPTY)
        val id = rr.getId
        if (id.nonEmpty) {
          val pk = get_pk(rr.version, id)
          rr_copy = rr_copy.withPk(pk)
        }
        var msg_out = new RendezvousMessage()

        if (rr.relayServer.nonEmpty) {
          if (is_lan_fun(addr_b_object)) {
            rr_copy = rr_copy.withRelayServer(objectRS.inner.local_ip)
          } else {
            rr_copy = rr_copy.withRelayServer(UdpMsgHandleActor.get_relay_server(remote, addr_b_object))
          }
        }
        msg_out = msg_out.withRelayResponse(rr_copy)
        send_to_tcp_sync(msg_out, addr_b_object)
      }
      case PunchHoleSent(phs) => {
        UdpMsgHandleActor.handle_hole_sent(phs, remote, None)
      }
      case LocalAddr(la) => {
        UdpMsgHandleActor.handle_local_addr(la, remote, None)
      }
      case TestNatRequest(tar) => {
        var msg_out = new RendezvousMessage()
        var res = hbb.TestNatResponse(port = remote.getPort)
        if (objectRS.inner.serial > tar.serial) {
          val cu = hbb.ConfigUpdate(serial = objectRS.inner.serial, rendezvousServers = objectRS.rendezvous_servers)
          res = res.withCu(cu)
        }
        msg_out = msg_out.withTestNatResponse(res)
        UdpMsgHandleActor.send_to_sink(Some(RsSink(tcpStream = tcpReplyTo)), msg_out)
      }
      case RegisterPk(_) => {
        val res = Result.NOT_SUPPORT
        var msg_out = new RendezvousMessage()
        msg_out = msg_out.withRegisterPkResponse(hbb.RegisterPkResponse(result = res))
        UdpMsgHandleActor.send_to_sink(Some(RsSink(tcpStream = tcpReplyTo)), msg_out)
      }
      case e: Object => {
        if (value.toByteArray.length < 500)
          log.error("TcpListenerData unknown object !!! {} result {}. from {}",
            e,
            new String(value.toByteArray, Charset.defaultCharset()),
            s"${remote.getHostString}:${remote.getPort}")
        else
          log.error("TcpListenerData unknown object !!! error = {}. from {}", e, s"${remote.getHostString}:${remote.getPort}")
      }
    }
  }


  def apply(key: String): Behavior[com.akuk.TcpReplyActor.Command] = {
    Behaviors.supervise[com.akuk.TcpReplyActor.Command] {
      Behaviors.setup[com.akuk.TcpReplyActor.Command] { context =>
        //val system = context.system
        //preStart(context)
        //val inetSocketAddress = new InetSocketAddress("192.168.0.7", 21116)
        //val system = context.system.classicSystem

        //val tcpReplyActor = system.actorOf(Props(classOf[TcpReplyActor], inetSocketAddress, context.self), "tcp")

        Behaviors.receiveMessage[com.akuk.TcpReplyActor.Command] {

          case TcpListenerData(bytes: ByteString, tcpReplyTo: ActorRef, remote: InetSocketAddress, ws: Boolean) =>
            val decodeBytesStr = Gensk.getInstance().bytesdecode(Base64.getEncoder.encodeToString(bytes.toArray))
            val decodeByte = Base64.getDecoder.decode(decodeBytesStr)
            RendezvousMessage.validate(decodeByte) match {
              case Success(value: RendezvousMessage) =>
                log.info("\n\tTcpListenerData {}. from {}", value.union, s"${remote.getHostString}:${remote.getPort}")
                handleTcpMessage(value, tcpReplyTo, remote, key, ws)
                Behaviors.same
              case Failure(e: Throwable) =>
                if (bytes.length < 500)
                  log.error("TcpListenerData Failure !!! {} result {}. from {}",
                    e,
                    new String(bytes.toArray, Charset.defaultCharset()),
                    s"${remote.getHostString}:${remote.getPort}")
                else
                  log.error("TcpListenerData Failure !!! error = {}. from {}", e.getMessage, s"${remote.getHostString}:${remote.getPort}")
                //tcpReplyTo ! com.akuk.TcpReplyActor.TcpArrayByteMsg(bytes.toArray)
                Behaviors.same
            }
          case _ => {
            log.error("TcpListenerData Receive Failure data !!!")
            Behaviors.same
          }
        }
        //Behaviors.unhandled
      }
    }.onFailure[Exception](SupervisorStrategy.restartWithBackoff(2.seconds, 5.seconds, 0.2).withMaxRestarts(10))
  }

}
