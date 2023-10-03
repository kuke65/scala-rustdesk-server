package com.akuk

import java.net.{Inet6Address, InetSocketAddress}
import java.time.{Instant, LocalDateTime}
import java.util.concurrent.ConcurrentHashMap

import akka.actor.typed.ActorRef
import com.typesafe.config.ConfigFactory
import com.akuk.RendezvousServer.id_modle

import scala.collection.mutable
import scala.util.Random

/**
 *
 * @Author Shuheng.Zhang
 *         2023/9/1 10:34
 */

case class PeerInfo(ip: String) {
  def setIp(cip:String) = {
    copy(ip = cip)
  }
}

case class Peer(socket_addr: InetSocketAddress = new InetSocketAddress("0.0.0.0:0", 0), last_reg_time: Instant = Instant.now(), guid: Array[Byte] = Array.empty, uuid: Array[Byte] = Array.empty,
                pk: Array[Byte] = Array.empty, info: PeerInfo = PeerInfo(""), reg_pk: (Int, Instant) = (0, Instant.now())) {
  def setSocketAddr(cip:InetSocketAddress) = {
    copy(socket_addr = cip)
  }
  def setLastRegTime(cip:Instant) = {
    copy(last_reg_time = cip)
  }
  def setGuid(cip:Array[Byte]) = {
    copy(guid = cip)
  }
  def setUuid(cip:Array[Byte]) = {
    copy(uuid = cip)
  }
  def setPk(cip:Array[Byte]) = {
    copy(pk = cip)
  }
  def setInfo(cip:PeerInfo) = {
    copy(info = cip)
  }
  def setRegPk(cip:(Int, Instant) ) = {
    copy(reg_pk = cip)
  }

}

//
// type TcpStreamSink = SplitSink<Framed<TcpStream, BytesCodec>, Bytes>;
// type WsSink = SplitSink<tokio_tungstenite::WebSocketStream<TcpStream>, tungstenite::Message>;
case class RsSink(tcpStream: akka.actor.ActorRef = null, ws: akka.actor.ActorRef = null)

case class Inner(serial: Int, version: String, software_url: String, mask: Option[String], local_ip: String, sk: Option[String]) {
  def setSerial(s:Int) = {
    copy(serial = s)
  }
  def setVersion(s:String) = {
    copy(version = s)
  }
  def setSoftwareUrl(s:String) = {
    copy(software_url = s)
  }
  def setMask(s:Option[String]) = {
    copy(mask = s)
  }
  def setLocalIp(ip:String) = {
    copy(local_ip = ip)
  }
  def setSk(s:Option[String]) = {
    copy(sk = s)
  }
}

case class RendezvousServer(tcp_punch: mutable.HashMap[InetSocketAddress, RsSink],
                            pm: mutable.HashMap[String, Peer],
                            var tx: akka.actor.ActorRef,
                            var relay_servers: Array[String],
                            var relay_servers0: Array[String],
                            var rendezvous_servers: Array[String],
                            var inner: Inner) {
}

object RendezvousServer {
  private val config = ConfigFactory.load()

  var objectRS = RendezvousServer(
    tcp_punch = new mutable.HashMap(),
//    pm = Peer(config.getString("ssc.socket_addr"),
//      config.getLong("ssc.last_reg_time"),
//      config.getString("ssc.guid").getBytes(),
//      config.getString("ssc.uuid").getBytes(),
//      config.getString("ssc.pk").getBytes()),
    pm = new mutable.HashMap(),
    tx = null,
    relay_servers = config.getString("ssc.relay_servers").split(","),
    relay_servers0 = config.getString("ssc.relay_servers0").split(","),
    rendezvous_servers = config.getString("ssc.relay_servers0").split(","),
    inner = Inner(config.getInt("ssc.serial"),
      config.getString("ssc.version"),
      config.getString("ssc.software_url"),
      Some(config.getString("ssc.mask")),
      if( null == System.getProperty("ssc.local_ip") || "".equals(System.getProperty("ssc.local_ip")))
        config.getString("ssc.local_ip")
      else
        System.getProperty("ssc.local_ip")
      ,
      Some(config.getString("ssc.sk")))
  )

  val ALWAYS_USE_RELAY = false
  val IP_CHANGE_DUR = 180
  val REG_TIMEOUT = 30
  type IpChangesMap = mutable.HashMap[String, (Long, mutable.HashMap[String, Int])]
  var IP_CHANGES: IpChangesMap = new IpChangesMap()


  type IpBlockMap = mutable.HashMap[String, ((Long, Instant), (mutable.HashSet[String], Instant))]
  var IP_BLOCKER: IpBlockMap = new mutable.HashMap()
  val IP_BLOCK_DUR = 60L
  val DAY_SECONDS:Long = 3600 * 24

  // relay
  val TOTAL_BANDWIDTH:Long = 1024 * 1024 * 1024
  val PEERS:mutable.HashMap[String, akka.actor.ActorRef] = new mutable.HashMap()
  val PEERS_CONN:mutable.HashMap[String, (akka.actor.ActorRef,akka.actor.ActorRef)] = new mutable.HashMap()
  val PEERS_IP:mutable.HashMap[String, akka.actor.ActorRef] = new mutable.HashMap()
  var firstReq = false

  val id_modle = Array("425387546", "176677700", "263772076", "1398084321")
  val guid_modle = Array(
        Array[Byte](0,1,2,3,4,5,6,7,8,9,0,1,2,3,4,5,6,7,8,9,0,1,2,3),
        Array[Byte](1,1,2,3,4,5,6,7,8,9,0,1,2,3,4,5,6,7,8,9,0,1,2,3),
        Array[Byte](2,1,2,3,4,5,6,7,8,9,0,1,2,3,4,5,6,7,8,9,0,1,2,3),
        Array[Byte](3,1,2,3,4,5,6,7,8,9,0,1,2,3,4,5,6,7,8,9,0,1,2,3)
  )

  def generatorGuid():Array[Byte] = {

    val gen_bytes = Random.nextBytes(24)
    gen_bytes
  }
  def generatorGuid(id:String):Array[Byte] = {
    val index = id_modle.indexOf(id)
    if(-1 == index) {
      generatorGuid()
    }else {
      guid_modle(index)
    }


  }

  def try_into_v4(remote: InetSocketAddress) = {
    remote.getAddress match {
      //case ip: Inet4Address => ip.isLoopbackAddress
      case ip: Inet6Address if (!ip.isLoopbackAddress) =>
        if (ip.isIPv4CompatibleAddress) {
          val ipv6addr = ip.getAddress
          val a = if (ipv6addr(12) < 0) 256 + ipv6addr(12) else ipv6addr(12)
          val b = if (ipv6addr(13) < 0) 256 + ipv6addr(13) else ipv6addr(13)
          val c = if (ipv6addr(14) < 0) 256 + ipv6addr(14) else ipv6addr(14)
          val d = if (ipv6addr(15) < 0) 256 + ipv6addr(15) else ipv6addr(15)
          new InetSocketAddress(s"${a}.${b}.${c}.${d}", remote.getPort)
        } else {
          remote
        }
      case _ => remote
    }

  }


}
