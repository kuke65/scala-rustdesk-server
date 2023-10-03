package com.akuk

import java.net._
import java.nio.channels.DatagramChannel

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.io.Inet.{DatagramChannelCreator, SocketOptionV2}
import akka.io.{IO, Udp}
import akka.util.ByteString
import hbb.RendezvousMessage
import com.akuk.RendezvousServer.objectRS
import com.akuk.UdpReplyActor.{Command, HandleUdpMsg, ReplyUdpArrayByteMsg, ReplyUdpStringMsg}

object UdpReplyActor {

  sealed trait Command extends akka.io.Udp.Event

  case class HandleUdpMsg(data: ByteString, udpReplyTo: ActorRef, remote: InetSocketAddress) extends Command

  case class ReplyUdpMsg(msg: RendezvousMessage, remote: InetSocketAddress) extends Command

  case class ReplyUdpStringMsg(msg: String, remote: InetSocketAddress) extends Command

  case class ReplyUdpArrayByteMsg(msg: Array[Byte], remote: InetSocketAddress) extends Command

  case class RelayServers0(rs: String) extends Command

  case class RelayServers(rs: Array[String]) extends Command

}

final case class Inet6ProtocolFamily() extends DatagramChannelCreator {
  override def create() =
    DatagramChannel.open(StandardProtocolFamily.INET6)
}

final case class MulticastGroup(address: String, interface: String) extends SocketOptionV2 {
  override def afterBind(s: DatagramSocket): Unit = {
    val group = InetAddress.getByName(address)
    val networkInterface = NetworkInterface.getByName(interface)
    s.getChannel.join(group, networkInterface)
  }
}

/**
 *
 * @Author Shuheng.Zhang
 *         2023/9/1 9:20
 */
class UdpReplyActor(inetSocketAddress: InetSocketAddress, receiveRef: akka.actor.typed.ActorRef[Command]) extends Actor {

  import UdpReplyActor.{RelayServers, RelayServers0, ReplyUdpMsg}
  import context.system

  //val opts = List(Inet6ProtocolFamily(), MulticastGroup(group, iface))
  //val opts = List(Inet6ProtocolFamily())
  IO(Udp) ! Udp.Bind(self, inetSocketAddress)

  def receive = {
    case Udp.Bound(local) =>
      context.become(ready(sender()))
  }

  def ready(socket: ActorRef): Receive = {
    case Udp.Received(data, remote) =>
      receiveRef ! HandleUdpMsg(data, self, remote)
    //socket ! Udp.Send(data, remote) // example server echoes back
    case Udp.Unbind => socket ! Udp.Unbind
    case Udp.Unbound => context.stop(self)
    case ReplyUdpMsg(msg, remote) =>
      socket ! Udp.Send(ByteString(msg.toByteArray), remote)
    case ReplyUdpStringMsg(msg, remote) =>
      socket ! Udp.Send(ByteString(msg), remote)
    case ReplyUdpArrayByteMsg(msg, remote) =>
      socket ! Udp.Send(ByteString(msg), remote)
    case RelayServers0(rs) =>
      val rsServers = rs.split(",").filter(_.nonEmpty)
      objectRS.relay_servers0 = rsServers
      objectRS.relay_servers = rsServers
    case RelayServers(rs) =>
      objectRS.relay_servers = rs

  }

}
