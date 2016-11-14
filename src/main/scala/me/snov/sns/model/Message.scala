package me.snov.sns.model

import java.util.concurrent.atomic.AtomicLong

case class Message(body: String) {
  val uuid = Message.counter.getAndIncrement()
}

object Message {
  val counter = new AtomicLong(0)
}
