package com.akuk

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}


object DecodeState extends Enumeration {

  case object Head extends super.Val

  case class Data(n: Int) extends super.Val {
    def getVal: Int = n
  }

}


case class BytesCodec(state: Any = DecodeState.Head, raw: Boolean = false, max_packet_length: Int = Int.MaxValue) {
  def set_raw(raw: Boolean) = {
    copy(raw = raw)
  }
  def set_max_packet_length(n: Int) = {
    copy(max_packet_length = n)
  }
  def decode_head(src: Array[Byte]): Try[Option[Int]] = {
    if(src.isEmpty) {
      return Success(None)
    }
    val head_len = (src(0) & 0x3+ 1).toInt
    if(src.length < head_len) {
      return Success(None)
    }
    var n = src(0).toInt
    if(head_len > 1) {
      n |= (src(1).toInt) << 8
    }
    if(head_len > 2) {
      n |= (src(2).toInt) << 16
    }
    if(head_len > 3) {
      n |= (src(3).toInt) << 24
    }
    n >>= 2
    if(n > max_packet_length) {
      return Failure(new RuntimeException("Too big packet"))
    }

    return Success(None)

  }

}


