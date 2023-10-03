package com.akuk

import java.net.{Inet4Address, Inet6Address, InetSocketAddress}
import java.nio.charset.Charset
import java.time.Instant
import java.util.Base64

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, SupervisorStrategy}
import akka.io.Udp
import akka.util.ByteString
import hbb.PunchHoleResponse.Failure.{LICENSE_MISMATCH, OFFLINE}
import hbb.RegisterPkResponse.Result
import hbb.RegisterPkResponse.Result.{Recognized, TOO_FREQUENT, UUID_MISMATCH}
import hbb.RendezvousMessage.Union._
import hbb.{ConfigUpdate, IdPk, RendezvousMessage}
import org.slf4j.LoggerFactory
import rust.gensk.Gensk
import com.akuk.RendezvousServer.{IP_BLOCKER, objectRS}
import com.akuk.TcpReplyActor.{TcpBinaryMsg, TcpMsg}
import com.akuk.UdpReplyActor.{HandleUdpMsg, ReplyUdpStringMsg}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}

/**
 *
 * @Author Shuheng.Zhang
 *         2023/8/31 17:15
 */
object UdpMsgHandleActor {
  val log = LoggerFactory.getLogger(getClass)


  def update_addr(id: String, addr: InetSocketAddress, udpReplyRef: ActorRef): Unit = {
    val (request_pk, ip_change_opt) = if (objectRS.pm.contains(id)) {
      val old = objectRS.pm(id)

      val ip = addr.getHostString
      val ip_change = if (old.socket_addr.getPort != 0) {
        !(ip equals old.socket_addr.getHostString)
      } else {
        !(ip equals old.info.ip)
      } && !TcpReplyActor.is_loopback(addr)
      val request_pk = old.pk.isEmpty || ip_change
      if (!request_pk) {
        objectRS.pm.put(id, old.setSocketAddr(addr).setLastRegTime(Instant.now()))
      }
      val ip_change_opt = if (ip_change && old.reg_pk._1 <= 2) {
        Some(if (old.socket_addr.getPort == 0) {
          old.info.ip
        } else {
          old.socket_addr.getHostString
        })
      } else {
        None
      }
      (request_pk, ip_change_opt)
    } else {
      (true, None)
    }

    ip_change_opt match {
      case Some(old) => log.info("IP change of {} from {} to {}", id, old, addr.getHostString)
      case _ =>
    }

    var msg_out = new RendezvousMessage()
    msg_out = msg_out.withRegisterPeerResponse(hbb.RegisterPeerResponse(request_pk))
    udpReplyRef ! com.akuk.UdpReplyActor.ReplyUdpMsg(msg_out, addr)

  }

  // 检查ip清理
  def check_ip_blocker(ip: String, id: String): Boolean = {
    val now = Instant.now()
    if(IP_BLOCKER.contains(ip)) {
      val old = IP_BLOCKER(ip)
      //val blockerVal : ((Long, Instant), (mutable.HashSet[String], Instant)) =
      val counter = old._1
      var old_1_1_1 = counter._1
      var old_1_2_1 = counter._2


      if((now.getEpochSecond - counter._2.getEpochSecond) > RendezvousServer.IP_BLOCK_DUR) {
        old_1_1_1 = 0
      }else if (counter._1 > 30) {
        return false
      }

      old_1_1_1 += 1
      old_1_2_1 = now

      val counter2  = old._2
      var old_2_1 = counter2._1
      var old_2_2 = counter2._2

      var is_new = true
      if(old_2_1.contains(id)) {
        is_new = old_2_1.exists(_.equals(id))
      }
      if ((now.getEpochSecond  - counter2._2.getEpochSecond) > RendezvousServer.DAY_SECONDS) {
        old_2_1.clear()
      }else if(old_2_1.size > 300) {
        IP_BLOCKER.put(id, ((old_1_1_1, old_1_2_1) ,(old_2_1, old_2_2)))
        return !is_new
      }
      if(is_new) {
        old_2_1.addOne(id)
      }
      old_2_2 = now
      IP_BLOCKER.put(id, ((old_1_1_1, old_1_2_1) ,(old_2_1, old_2_2)))

    }else {
      IP_BLOCKER.put(ip, ((0,now), (mutable.HashSet.empty,now)))
    }
    true
  }

  def send_rk_res(udpReplyRef: ActorRef, addr: InetSocketAddress, result: Recognized) = {
    var msg_out = new RendezvousMessage()
    msg_out = msg_out.withRegisterPkResponse(hbb.RegisterPkResponse(result))
    udpReplyRef ! com.akuk.UdpReplyActor.ReplyUdpMsg(msg_out, addr)
  }

  def send_to_sink(sinkRef: Option[RsSink], msg: RendezvousMessage) = {
    if (sinkRef.nonEmpty) {
      val rssink = sinkRef.get
      if (null != rssink.tcpStream) {
        rssink.tcpStream ! TcpMsg(ByteString(msg.toByteArray))
      } else if (null != rssink.ws) {
        rssink.ws ! TcpBinaryMsg(ByteString(msg.toByteArray))
      }
    }
  }

  def send_to_tcp_sync(msg: RendezvousMessage, addr: InetSocketAddress) = {
    // 需要判断是ipv4还是ipv6，如果是ipv6看是否是127开头的ipv4，是则需要转ipv4
    // TODO 需要考虑同步返回
    val sink = objectRS.tcp_punch.remove(addr)
    send_to_sink(sink, msg)
  }

  def handle_udp_punch_hole_request(udpReplyRef: ActorRef,
                                    addr: InetSocketAddress,
                                    ph: hbb.PunchHoleRequest,
                                    key: String) = {
    val (msg, to_addr) = handle_punch_hole_request(addr, ph, key, false)
    if (to_addr.nonEmpty) {
      objectRS.tx ! com.akuk.UdpReplyActor.ReplyUdpMsg(msg, to_addr.get)
      //udpReplyRef ! com.akuk.UdpReplyActor.ReplyUdpMsg(msg, to_addr.get)
    } else {
      objectRS.tx ! com.akuk.UdpReplyActor.ReplyUdpMsg(msg, addr)
      //send_to_tcp_sync(msg, addr)
    }
  }

  def is_lan_fun(addr: InetSocketAddress): Boolean = {
    // --mask=[MASK] 'Determine if the connection comes from LAN, e.g. 192.168.0.0/16'
    // 暂时不判断局域网 objectRS.inner.mask.contains()
    var islan = true
    if(objectRS.inner.mask.nonEmpty) {
      addr.getAddress match {
        case ip: Inet4Address =>
          val maskarr = objectRS.inner.mask.get.split("/")
          val ipstr    = maskarr(0)
          val bitcount = maskarr(1).toInt/8
          val maskiparr = ipstr.split("\\.")
          val iparr = ip.getHostAddress.split("\\.")

          for (i <- 0 until bitcount) {
            if(!maskiparr(i).equals(iparr(i))) {
              return false
            }
          }
          return true

        case ipv6: Inet6Address =>
          val toIpv4 = RendezvousServer.try_into_v4(addr)
          toIpv4.getAddress match {
            case ipv4: Inet4Address =>
              val maskarr = objectRS.inner.mask.get.split("/")
              val ipstr    = maskarr(0)
              val bitcount = maskarr(1).toInt/8
              val maskiparr = ipstr.split("\\.")
              val iparr = ipv4.getHostAddress.split("\\.")

              for (i <- 0 until bitcount) {
                if(!maskiparr(i).equals(iparr(i))) {
                  return false
                }
              }
              return true
            case _ =>
          }

        case _ =>
          throw new RuntimeException("unknown InetSocketAddress type or createUnresolved")
      }
    }
    !islan
  }

  def get_relay_server(_pa: InetSocketAddress, _pb: InetSocketAddress): String = {
    if (objectRS.relay_servers.isEmpty) {
      return ""
    } else if (objectRS.relay_servers.length == 1) {
      return objectRS.relay_servers(0)
    }
    //    let i = unsafe {
    //      ROTATION_RELAY_SERVER += 1;
    //      ROTATION_RELAY_SERVER % self.relay_servers.len()
    //    };
    objectRS.relay_servers(Random.nextInt(objectRS.relay_servers.length))
  }

  def handle_punch_hole_request(addr: InetSocketAddress,
                                ph: hbb.PunchHoleRequest,
                                key: String,
                                ws: Boolean): (RendezvousMessage, Option[InetSocketAddress]) = {
    var natType = ph.natType

    if (key.nonEmpty && !(ph.licenceKey equals key)) {
      var msg_out = new RendezvousMessage()
      msg_out = msg_out.withPunchHoleResponse(hbb.PunchHoleResponse(failure = LICENSE_MISMATCH))
      (msg_out, None)
    }

    val id = ph.id
    if (objectRS.pm.contains(id)) {
      val peer = objectRS.pm(id)
      val now = Instant.now()
      val (elapsed, peer_addr) = (now.getEpochSecond - peer.last_reg_time.getEpochSecond, peer.socket_addr)
      if (elapsed >= RendezvousServer.REG_TIMEOUT) {
        var msg_out = new RendezvousMessage()
        msg_out = msg_out.withPunchHoleResponse(hbb.PunchHoleResponse(failure = OFFLINE))
        (msg_out, None)
      }
      var msg_out = new RendezvousMessage()
      val peer_is_lan = is_lan_fun(peer_addr)
      val is_lan = is_lan_fun(addr)
      var relayServer = get_relay_server(addr, peer_addr)
      if (RendezvousServer.ALWAYS_USE_RELAY || (peer_is_lan ^ is_lan)) {
        if (peer_is_lan) {
          relayServer = objectRS.inner.local_ip
        }
        natType = hbb.NatType.SYMMETRIC
      }
      // 是否内部网络
      val same_intranet = !ws && (peer_is_lan && is_lan || (peer_addr.getHostString equals addr.getHostString))
      //val socket_addr_data = AddrMangle.encode(addr).into();
      val base64data = Gensk.getInstance().addrencode(s"${addr.getHostString}:${addr.getPort}")
      if (same_intranet) {
        log.debug(
          "Fetch local addr {} {} request from {}",
          id,
          peer_addr.getHostString,
          addr.getHostString
        )
        val socket_addr_data = Base64.getDecoder.decode(base64data)
        //val java_list = scala.jdk.CollectionConverters.SeqHasAsJava(socket_addr_data.toSeq).asJava
        val data_bytestring = _root_.com.google.protobuf.ByteString.copyFrom(socket_addr_data)
        msg_out = msg_out.withFetchLocalAddr(hbb.FetchLocalAddr(data_bytestring, relayServer))

      } else {
        log.debug(
          "Punch hole {} {} request from {}",
          id,
          peer_addr,
          addr
        );
        val socket_addr_data = Base64.getDecoder.decode(base64data)
        //val java_list = scala.jdk.CollectionConverters.SeqHasAsJava(socket_addr_data.toSeq).asJava
        val data_bytestring = _root_.com.google.protobuf.ByteString.copyFrom(socket_addr_data)
        msg_out = msg_out.withPunchHole(hbb.PunchHole(
          data_bytestring,
          relayServer,
          natType
        ))
      }
      (msg_out, Some(peer_addr))
    } else {
      var msg_out = new RendezvousMessage()
      msg_out = msg_out.withPunchHoleResponse(hbb.PunchHoleResponse(failure = hbb.PunchHoleResponse.Failure.ID_NOT_EXIST))
      (msg_out, None)
    }


  }

  def get_pk(version: String, id: String) = {
    if (version.isEmpty || objectRS.inner.sk.isEmpty) {
      _root_.com.google.protobuf.ByteString.EMPTY
    } else {
      if (objectRS.pm.contains(id)) {
        val peer = objectRS.pm(id)
        val pk = peer.pk
        val m = Base64.getEncoder.encodeToString(IdPk(id, _root_.com.google.protobuf.ByteString.copyFrom(pk)).toByteArray)
        val sk = objectRS.inner.sk.get
        _root_.com.google.protobuf.ByteString.copyFrom(Base64.getDecoder.decode(Gensk.getInstance().sign(m, sk)))
      } else {
        _root_.com.google.protobuf.ByteString.EMPTY
      }
    }

  }

  def send_to_tcp(msg_out: RendezvousMessage, addr: InetSocketAddress) = {
    val sink = objectRS.tcp_punch.remove(addr)
    send_to_sink(sink, msg_out)
  }

  def handle_hole_sent(phs: hbb.PunchHoleSent, addr: InetSocketAddress, udpReplyRefOpt: Option[ActorRef]) = {
    val addr_decode = Gensk.getInstance().addrdecode(Base64.getEncoder.encodeToString(phs.socketAddr.toByteArray))
    val ipaddrsplit = addr_decode.split(":")
    val hostName = ipaddrsplit(0)
    val port = ipaddrsplit(1).toInt
    val addr_new = new InetSocketAddress(hostName, port)

    log.debug(
      "{} punch hole response to {} from {}",
      if (udpReplyRefOpt.isEmpty) {
        "TCP"
      } else {
        "UDP"
      },
      addr_decode,
      addr.getHostString
    )

    val addrencode = Gensk.getInstance().addrencode(s"${addr.getHostString}:${addr.getPort}")
    var msg_out = new RendezvousMessage()
    var p = hbb.PunchHoleResponse(
      socketAddr = _root_.com.google.protobuf.ByteString.copyFrom(Base64.getDecoder.decode(addrencode)),
      pk = get_pk(phs.version, phs.id),
      relayServer = phs.relayServer
    )
    p = p.withNatType(phs.natType)
    msg_out = msg_out.withPunchHoleResponse(p)

    if (udpReplyRefOpt.nonEmpty) {
      udpReplyRefOpt.get ! com.akuk.UdpReplyActor.ReplyUdpMsg(msg_out, addr_new)
    } else {
      send_to_tcp(msg_out, addr_new)
    }
  }

  def test_if_valid_server(host: String, tagName: String) = {
    try {
      if (host.contains(":")) {
        val splitAddr = host.split(":")
        InetSocketAddress.createUnresolved(splitAddr(0), splitAddr(1).toInt)
        true
      } else {
        InetSocketAddress.createUnresolved(host, 0)
        true
      }
    } catch {
      case e: Exception =>
        log.error("Invalid {} {}: {}", tagName, host, e.getMessage)
        false
      case e: Throwable =>
        log.error("Invalid {} {}: {}", tagName, host, e.getMessage);
        false
    }

  }


  def handle_local_addr(la: hbb.LocalAddr, addr: InetSocketAddress, udpReplyRefOpt: Option[ActorRef]) = {
    val addr_decode = Gensk.getInstance().addrdecode(Base64.getEncoder.encodeToString(la.socketAddr.toByteArray))

    val ipaddrsplit = addr_decode.split(":")
    val hostName = ipaddrsplit(0)
    val port = ipaddrsplit(1).toInt
    val addr_new = new InetSocketAddress(hostName, port)

    log.debug(
      "{} punch hole response to {} from {}",
      if (udpReplyRefOpt.isEmpty) {
        "TCP"
      } else {
        "UDP"
      },
      addr_decode,
      addr.getHostString
    )

    //val addrencode = Gensk.getInstance().addrencode(s"${addr.getHostString}:${addr.getPort}")
    var msg_out = new RendezvousMessage()
    var p = hbb.PunchHoleResponse(
      socketAddr = la.localAddr,
      pk = get_pk(la.version, la.id),
      relayServer = la.relayServer
    )
    p = p.withIsLocal(true)
    msg_out = msg_out.withPunchHoleResponse(p)

    if (udpReplyRefOpt.nonEmpty) {
      udpReplyRefOpt.get ! com.akuk.UdpReplyActor.ReplyUdpMsg(msg_out, addr_new)
    } else {
      send_to_tcp(msg_out, addr_new)
    }
  }



  def update_pk(id: String,
                peer: com.akuk.Peer,
                addr: InetSocketAddress,
                uuid: Array[Byte],
                pk: Array[Byte],
                ip: String) = {

    log.info("update_pk {} {} {} {}", id, addr.getHostString, new String(uuid), new String(pk))

    var w = com.akuk.Peer(addr)
    val (info_str, guid) = {
      w = com.akuk.Peer (
        socket_addr=addr,
        last_reg_time=Instant.now(),
        uuid=uuid,
        pk=pk,
        info=PeerInfo(ip)
      )
      (s"""{"ip":"${ip}"}""", peer.guid)
    }
    if(guid.length == 0 || guid.isEmpty) {
      w = w.setGuid(RendezvousServer.generatorGuid(id))
    }else {
      log.info("pk updated instead of insert");
    }
    w
//    if guid.is_empty() {
//      match self.db.insert_peer(&id, &uuid, &pk, &info_str).await {
//        Err(err) => {
//          log::error!("db.insert_peer failed: {}", err);
//          return register_pk_response::Result::SERVER_ERROR;
//        }
//        Ok(guid) => {
//          peer.write().await.guid = guid;
//        }
//      }
//    } else {
//      if let Err(err) = self.db.update_pk(&guid, &id, &pk, &info_str).await {
//        log::error!("db.update_pk failed: {}", err);
//        return register_pk_response::Result::SERVER_ERROR;
//      }
//      log::info!("pk updated instead of insert");
//    }

  }

  def handleUdpMessage(value: RendezvousMessage, udpReplyRef: ActorRef, remote: InetSocketAddress, key: String): Any = {
    value.union match {
      case RegisterPeer(rp) => {
        if (rp.id.nonEmpty) {
          //log.info("New peer registered: {} {}", rp.id, remote)
          update_addr(rp.id, remote, udpReplyRef)
          if (objectRS.inner.serial > rp.serial) {
            var msg_out = new RendezvousMessage()
            msg_out = msg_out.withConfigureUpdate(ConfigUpdate(objectRS.inner.serial, objectRS.rendezvous_servers))
            udpReplyRef ! com.akuk.UdpReplyActor.ReplyUdpMsg(msg_out, remote)
          }
        }
      }
      case RegisterPk(rk) => {
        if (rk.uuid.isEmpty || rk.pk.isEmpty) {
          return
        }

        val id = rk.id
        val ip = remote.getHostString
        if (id.length < 6) {
          send_rk_res(udpReplyRef, remote, UUID_MISMATCH)
          return
        } else if (!check_ip_blocker(ip, id)) {
          send_rk_res(udpReplyRef, remote, TOO_FREQUENT)
          return
        }
        var peer = Peer(remote)
        if(objectRS.pm.contains(id)) {
          peer = objectRS.pm(id)
        }
        //var peer = objectRS.pm(id)
        val (changed, ip_changed) = {
          if (peer.uuid.isEmpty) {
            (true, false)
          } else {
            val uuidEqBool = new String(peer.uuid) equals new String(rk.uuid.toByteArray)
            val pkEqBool = !(new String(peer.pk) equals new String(rk.pk.toByteArray))
            val ipEqBool = !(peer.info.ip equals ip)

            if (uuidEqBool) {
              if (ipEqBool && pkEqBool) {
                log.warn("Peer {} ip/pk mismatch: {}/{} vs {}/{}",
                  id,
                  ip,
                  rk.pk,
                  peer.info.ip,
                  new String(peer.pk))
                send_rk_res(udpReplyRef, remote, UUID_MISMATCH)
                return
              }
            } else {
              log.warn(
                "Peer {} uuid mismatch: {:?} vs {:?}",
                id,
                rk.uuid,
                peer.uuid
              );
              send_rk_res(udpReplyRef, remote, UUID_MISMATCH)
              return
            }

            val ip_changed = ipEqBool
            (uuidEqBool || pkEqBool || ip_changed, ip_changed)
          }
        }

        val now = Instant.now()
        val req_pk = peer.reg_pk
        var req_pk_1 = req_pk._1
        var req_pk_2 = req_pk._2
        if ((now.getEpochSecond - req_pk._2.getEpochSecond) > 6) {
          req_pk_1 = 0
          //objectRS.pm.put(id, peer.setRegPk((0, peer.reg_pk._2)))
        } else if(req_pk._1 > 2) {
          send_rk_res(udpReplyRef, remote, TOO_FREQUENT)
          return
        }
        req_pk_1 += 1
        req_pk_2 = now
        peer = peer.setRegPk(req_pk_1, req_pk_2)
        objectRS.pm.put(id, peer)

        /*
        统计ip变更集合
        if(ip_changed) {
          if(IP_CHANGES.contains(id)) {

            IP_CHANGES(id) match {
              case (tm, ips)=> {
                if(tm > RendezvousServer.IP_CHANGE_DUR) {


                }
              }
              case _ =>
            }
          }
        }*/


        //更新pk
        if (changed) {
          peer = update_pk(id, peer, remote, rk.uuid.toByteArray, rk.pk.toByteArray, ip)
          objectRS.pm.put(id, peer)
          //objectRS.pm.update_pk(id, peer, addr, rk.uuid, rk.pk, ip)
        }

        var msg_out = new RendezvousMessage()
        msg_out = msg_out.withRegisterPkResponse(hbb.RegisterPkResponse(result=Result.OK))
        udpReplyRef ! com.akuk.UdpReplyActor.ReplyUdpMsg(msg_out, remote)
      }
      case PunchHoleRequest(ph) => {
        if (objectRS.pm.contains(ph.id)) {
          handle_udp_punch_hole_request(udpReplyRef, remote, ph, key)
        } else {
          // not in memory, fetch from db with spawn in case blocking me
          handle_udp_punch_hole_request(udpReplyRef, remote, ph, key)
        }
      }
      case PunchHoleSent(phs) => {
        handle_hole_sent(phs, remote, Some(udpReplyRef))
      }
      case LocalAddr(la) => {
        handle_local_addr(la, remote, Some(udpReplyRef))
      }
      case ConfigureUpdate(cu) => {
        if (TcpReplyActor.is_loopback(remote) && cu.serial > objectRS.inner.serial) {
          objectRS.inner = objectRS.inner.setSerial(cu.serial)
          objectRS.rendezvous_servers = cu.rendezvousServers.filter(_.nonEmpty).filter(test_if_valid_server(_, "rendezvous-server")).toArray
          log.info(
            "configure updated: serial={} rendezvous-servers={}",
            objectRS.inner.serial,
            objectRS.rendezvous_servers
          )
        }
      }
      case SoftwareUpdate(su) => {
        if (objectRS.inner.version.nonEmpty && !(su.url equals objectRS.inner.version)) {
          var msg_out = new RendezvousMessage()
          msg_out = msg_out.withSoftwareUpdate(hbb.SoftwareUpdate(url = objectRS.inner.software_url))
          udpReplyRef ! com.akuk.UdpReplyActor.ReplyUdpMsg(msg_out, remote)
        }
      }
      case _ => {
        log.error("unknown case msg ")
      }
    }
  }


  def apply(key: String): Behavior[com.akuk.UdpReplyActor.Command] = {
    Behaviors.supervise[com.akuk.UdpReplyActor.Command] {
      Behaviors.setup[com.akuk.UdpReplyActor.Command] { context =>

        //val system = context.system
        //preStart(context)

        Behaviors.receiveMessage[com.akuk.UdpReplyActor.Command] {

          case HandleUdpMsg(bytes: ByteString, udpReplyTo: ActorRef, remote: InetSocketAddress) =>
            RendezvousMessage.validate(bytes.toArray) match {
              case Success(value: RendezvousMessage) =>
                //log.info("\n\rHandleUdpMsg {}", value.union)
                handleUdpMessage(value, udpReplyTo, remote, key)
                Behaviors.same
              case Failure(e: Throwable) =>
                if (bytes.length < 500) {
                  val msg = new String(bytes.toArray, Charset.defaultCharset())
                  log.error("HandleUdpMsg Failure !!! {} result {}. from {}", e.getMessage, msg, s"${remote.getHostString}:${remote.getPort}")
                  udpReplyTo ! ReplyUdpStringMsg(msg, remote)
                } else
                  log.error("HandleUdpMsg Failure !!! error = {}. from {}", e.getMessage, s"${remote.getHostString}:${remote.getPort}")
                Behaviors.same
            }
          case _ => {
            log.error(" Receive Failure data !!!")
            Behaviors.same
          }

        }
      }
    }.onFailure[Exception](SupervisorStrategy.restartWithBackoff(2.seconds, 5.seconds, 0.2).withMaxRestarts(10))
  }


}
