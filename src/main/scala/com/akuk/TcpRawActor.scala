package com.akuk

import akka.actor
import akka.actor.Actor
import akka.io.Tcp.Write
import com.akuk.TcpReplyActor.{RelayTcpRawRef, TcpRawMsg}

/**
 *
 * @Author Shuheng.Zhang
 *         2023/9/6 12:38
 */
class TcpRawActor() extends Actor {
  override def receive: Receive = {
    case RelayTcpRawRef(bytes, replyTo, peer) =>
      peer ! Write(bytes)
    case TcpRawMsg(data) =>

  }
}
