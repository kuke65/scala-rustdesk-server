package com.akuk

import java.net.{Inet6Address, InetSocketAddress}
import java.nio.charset.Charset
import java.util.Base64

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{Behavior, SupervisorStrategy}
import akka.io.Tcp.{Received, Write}
import akka.util.ByteString
import hbb.RendezvousMessage
import hbb.RendezvousMessage.Union.RequestRelay
import org.slf4j.LoggerFactory
import rust.gensk.Gensk
import com.akuk.TcpReplyActor.{RelayTcpConnRef, RelayTcpListener3Data, RelayTcpListenerData, RelayTcpListenerRef, TcpArrayByteMsg}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 *
 * @Author Shuheng.Zhang
 *         2023/9/3 16:26
 */
class RelayTcpListenerActor {
  private val log = LoggerFactory.getLogger(getClass)


  def handleTcpMessage(bytes: ByteString, tcpReplyTo: ActorRef, remote: InetSocketAddress, key: String, ws: Boolean, connection: ActorRef): Unit = {
    val decodeBytesStr = Gensk.getInstance().bytesdecode(Base64.getEncoder.encodeToString(bytes.toArray))
    val decodeByte = Base64.getDecoder.decode(decodeBytesStr)
    RendezvousMessage.validate(decodeByte) match {
      case Success(value: RendezvousMessage) =>

        log.info("\n\t RelayTcpListenerData {}. from {}", value.union, s"${remote.getHostString}:${remote.getPort}")
        value.union match {
          case RequestRelay(rf) =>
            if (key.nonEmpty && !rf.licenceKey.equals(key)) {
              return
            }
            if (rf.uuid.nonEmpty) {
              val peer = RendezvousServer.PEERS_CONN.remove(rf.uuid)

              if (null != peer && peer.nonEmpty) {

                log.info("Relayrequest {} from {} got paired", rf.uuid, s"${remote.getHostString}:${remote.getPort}")
                peer.get._1 ! RelayTcpConnRef(tcpReplyTo, connection)
                tcpReplyTo ! RelayTcpConnRef(peer.get._1, peer.get._2)


              } else {

                log.info("New relay request {} from {}", rf.uuid, s"${remote.getHostString}:${remote.getPort}")
                RendezvousServer.PEERS_CONN.put(rf.uuid, (tcpReplyTo, connection))
                Thread.sleep(1000)
              }

            }
          case o: Object =>
            log.info("\n\t RelayTcpListenerData {}, length {} ip {}", o.getClass.getName, bytes.length, remote.getHostString)
        }
      case Failure(e: Throwable) =>
        if (bytes.length < 500)
          log.error("\n\t RelayTcpListenerData Failure !!! {} result {}. from {}",
            e,
            new String(bytes.toArray, Charset.defaultCharset()),
            s"${remote.getHostString}:${remote.getPort}")
        else
          log.error("\n\t RelayTcpListenerData Failure !!! error = {}. from {}", e.getMessage, s"${remote.getHostString}:${remote.getPort}")
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

          case RelayTcpListenerData(bytes: ByteString, tcpReplyTo: ActorRef, remote: InetSocketAddress, ws: Boolean, connection: ActorRef) =>
            //val decodeBytesStr = Gensk.getInstance().bytesdecode(Base64.getEncoder.encodeToString(bytes.toArray))
            //val decodeByte = Base64.getDecoder.decode(decodeBytesStr)
            handleTcpMessage(bytes, tcpReplyTo, remote, key, ws, connection)
            Behaviors.same

          case RelayTcpListener3Data(bytes: ByteString, tcpReplyTo: ActorRef, remote: InetSocketAddress, ws: Boolean) =>
            val ip = RendezvousServer.try_into_v4(remote)
            if (!ws && ip.getAddress.isLoopbackAddress) {

              log.info("\n\t RelayTcpListener3Data ")
              if (bytes.length <= 1024) {
                val res = new String(bytes.toArray, Charset.defaultCharset())
                log.info("\n\t RelayTcpListener3Data Failure !!! result {}, from {}", res, s"${remote.getHostString}:${remote.getPort}")
                // TODO check_cmd
                tcpReplyTo ! TcpArrayByteMsg(bytes.toArray)
              } else
                log.error("\n\t RelayTcpListener3Data Failure !!! from {}", s"${remote.getHostString}:${remote.getPort}")
            }
            Behaviors.same
          case _ => {
            log.error("RelayTcpListenerActor Receive Failure data !!!")
            Behaviors.same
          }
        }
        //Behaviors.unhandled
      }
    }.onFailure[Exception](SupervisorStrategy.restartWithBackoff(2.seconds, 5.seconds, 0.2).withMaxRestarts(10))
  }
}
