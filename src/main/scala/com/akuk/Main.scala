package com.akuk

import java.net.InetSocketAddress

import akka.actor.typed.javadsl.Adapter
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import rust.gensk.Gensk
import com.akuk.TcpReplyActor.HandleRef

import scala.concurrent.duration._

/**
 *
 * @Author Shuheng.Zhang
 *         2023/9/1 15:24
 */
object Main {

  def main(args: Array[String]): Unit = {
    val key2 = Gensk.getInstance.sk2("-").split(";")
    val key = key2(0)
    val sk = key2(1)
    RendezvousServer.objectRS.inner = RendezvousServer.objectRS.inner.setSk(Some(sk))

    val osystem = ActorSystem("user-system")
    val system = Adapter.toTyped(osystem)

    val config = ConfigFactory.load()
    var deploy_ip = config.getString("ssc.deploy_ip")

    if( null != System.getProperty("ssc.deploy_ip") && "".equals(System.getProperty("ssc.deploy_ip")))
      deploy_ip = System.getProperty("ssc.deploy_ip")


    val udpAddress = new InetSocketAddress(deploy_ip, 21116)

    val tcpAddress = new InetSocketAddress(deploy_ip, 21116)
    val wsAddress = new InetSocketAddress(deploy_ip, 21118)
    val natAddress = new InetSocketAddress(deploy_ip, 21115)

    // 创建UDP Actor
    val udpRef = system.systemActorOf(UdpMsgHandleActor(key), "udpRef")
    val udpListenerRef = osystem.actorOf(Props(classOf[UdpReplyActor], udpAddress, udpRef), "udp")
    RendezvousServer.objectRS.tx = udpListenerRef

    // 创建TCP Actor
    val listener3Ref = system.systemActorOf(new Listener3Actor().apply(key), "listener3")
    val listener2Ref = system.systemActorOf(new Listener2Actor().apply(), "listener2")

    val tcpReplyActor = osystem.actorOf(Props(classOf[TcpReplyActor], classOf[Listener3Actor], tcpAddress,
          (HandleRef(listener3Ref, false, Some(30.seconds)), null)), "tcpAddress")
//    val wsReplyActor = osystem.actorOf(Props(classOf[TcpReplyActor], classOf[Listener3Actor], wsAddress,
//          (HandleRef(listener3Ref, true, Some(30.seconds)), null)), "wsAddress")
    val whomRef = (HandleRef(listener2Ref, receiveTimeout = Some(10.seconds)), HandleRef(listener2Ref, receiveTimeout = Some(30.seconds)))
    val tcp2ReplyActor = osystem.actorOf(Props(classOf[TcpReplyActor], classOf[Listener2Actor], natAddress, whomRef), "natAddress2")


    /**
     * relay server & relay websocket
     *
     */
    val relayAddress = new InetSocketAddress(deploy_ip, 21117)
    val websocketAddress = new InetSocketAddress(deploy_ip, 21119)

    val relayListener3Ref = system.systemActorOf(new RelayTcpListenerActor().apply(""), "relayListener3Ref")
    val whom2Ref = (HandleRef(relayListener3Ref, receiveTimeout = Some(10.seconds)), HandleRef(relayListener3Ref, receiveTimeout = Some(30.seconds)))
    val relayTcp2ReplyActor = osystem.actorOf(Props(classOf[TcpReplyActor], classOf[RelayTcpListenerActor], relayAddress, whom2Ref), "relayAddress")


  }

}
