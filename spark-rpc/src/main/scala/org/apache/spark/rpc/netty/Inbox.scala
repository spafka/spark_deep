package org.apache.spark.rpc.netty

import javax.annotation.concurrent.GuardedBy
import org.apache.spark.RpcException
import org.apache.spark.rpc.{RpcAddress, RpcEndpoint, ThreadSafeRpcEndpoint}
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal


private[netty] sealed trait InboxMessage

private[netty] case class OneWayMessage(
                                         senderAddress: RpcAddress,
                                         content: Any) extends InboxMessage

private[netty] case class RpcMessage(
                                      senderAddress: RpcAddress,
                                      content: Any,
                                      context: NettyRpcCallContext) extends InboxMessage

private[netty] case object OnStart extends InboxMessage

private[netty] case object OnStop extends InboxMessage

/** A message to tell all endpoints that a remote process has connected. */
private[netty] case class RemoteProcessConnected(remoteAddress: RpcAddress) extends InboxMessage

/** A message to tell all endpoints that a remote process has disconnected. */
private[netty] case class RemoteProcessDisconnected(remoteAddress: RpcAddress) extends InboxMessage

/** A message to tell all endpoints that a network error has happened. */
private[netty] case class RemoteProcessConnectionError(cause: Throwable, remoteAddress: RpcAddress)
  extends InboxMessage

/**
  * An inbox that stores messages for an [[RpcEndpoint]] and posts messages to it thread-safely.
  * 邮件入站 ，发送到适当的[[RpcEndpoint]]
  * 主要包含入站消息
  */
private[netty] class Inbox(val endpointRef: NettyRpcEndpointRef,
                            val endpoint: RpcEndpoint) {
  inbox =>
  // Give this an alias so we can use it more clearly in closures.

  private val log = LoggerFactory.getLogger(classOf[Inbox])

  @GuardedBy("this")
  protected val messages = new java.util.LinkedList[InboxMessage]()

  /** True if the inbox (and its associated endpoint) is stopped. */
  @GuardedBy("this")
  private var stopped = false

  /** Allow multiple threads to process messages at the same time. */
  @GuardedBy("this")
  private var enableConcurrent = false

  /** The number of threads processing messages for this inbox. */
  @GuardedBy("this")
  private var numActiveThreads = 0

  // OnStart should be the first message to process
  inbox.synchronized {
    messages.add(OnStart)
  }

  /**
    * Process stored messages.
    * 对于入站的消息的处理 依据模式匹配可以对不同消息[[InboxMessage]]进行不同的操作
    */
  def process(dispatcher: Dispatcher): Unit = {
    var message: InboxMessage = null
    inbox.synchronized {
      if (!enableConcurrent && numActiveThreads != 0) {
        return
      }
      message = messages.poll()
      if (message != null) {
        log.trace(s" dispatcher inbox's LinkedList[InboxMessage] recieve an inbox message,  is ${message.getClass.getSimpleName}")
        numActiveThreads += 1
      } else {
        return
      }
    }
    while (true) {
      log.debug("whiletrue")
      safelyCall(endpoint) {
        log.trace(s"${endpoint.getClass.getSimpleName} to proceess the inbox ${message.getClass.getSimpleName}")
        message match {
            // rpc 消息
          case RpcMessage(_sender, content, context) =>
            try {
              log.trace(s" got an rpc message from ${_sender},content=${content.getClass.getSimpleName}")
              endpoint.receiveAndReply(context).applyOrElse[Any, Unit](content, { msg =>
                throw new RpcException(s"Unsupported message $message from ${_sender}")

              })
            } catch {
              case NonFatal(e) =>
                context.sendFailure(e)
                // Throw the exception -- this exception will be caught by the safelyCall java.java.util.function.
                // The endpoint's onError java.java.util.function will be called.
                throw e
            }

          case OneWayMessage(_sender, content) =>
            endpoint.receive.applyOrElse[Any, Unit](content, { msg =>
              throw new RpcException(s"Unsupported message $message from ${_sender}")
            })

          case OnStart =>
            endpoint.onStart()
            if (!endpoint.isInstanceOf[ThreadSafeRpcEndpoint]) {
              inbox.synchronized {
                if (!stopped) {
                  enableConcurrent = true
                }
              }
            }

          case OnStop =>
            val activeThreads = inbox.synchronized {
              inbox.numActiveThreads
            }
            assert(activeThreads == 1,
              s"There should be only a single active thread but found $activeThreads threads.")
            dispatcher.removeRpcEndpointRef(endpoint)
            endpoint.onStop()
            assert(isEmpty, "OnStop should be the last message")

          case RemoteProcessConnected(remoteAddress) =>
            log.trace(s"dispatcher inbox's recieve ${RemoteProcessConnected.getClass.getSimpleName} -> ${remoteAddress} ")
            endpoint.onConnected(remoteAddress)

          case RemoteProcessDisconnected(remoteAddress) =>
            log.trace(s"dispatcher inbox's recieve ${RemoteProcessDisconnected.getClass.getSimpleName} -> ${remoteAddress} ")
            endpoint.onDisconnected(remoteAddress)

          case RemoteProcessConnectionError(cause, remoteAddress) =>
            log.trace(s"dispatcher inbox's recieve ${RemoteProcessConnectionError.getClass.getSimpleName} -> ${remoteAddress} ")
            endpoint.onNetworkError(cause, remoteAddress)
        }
      }

      inbox.synchronized {
        // "enableConcurrent" will be set to false after `onStop` is called, so we should check it
        // every time.
        if (!enableConcurrent && numActiveThreads != 1) {
          // If we are not the only one worker, exit
          numActiveThreads -= 1
          return
        }
        message = messages.poll()
        if (message == null) {
          numActiveThreads -= 1
          return
        }
      }
    }
  }

  def post(message: InboxMessage): Unit = inbox.synchronized {
    if (stopped) {
      // We already put "OnStop" into "messages", so we should drop further messages
      onDrop(message)
    } else {
      messages.add(message)
      false
    }
  }

  def stop(): Unit = inbox.synchronized {
    // The following codes should be in `synchronized` so that we can make sure "OnStop" is the last
    // message
    if (!stopped) {
      // We should disable concurrent here. Then when RpcEndpoint.onStop is called, it's the only
      // thread that is processing messages. So `RpcEndpoint.onStop` can release its resources
      // safely.
      enableConcurrent = false
      stopped = true
      messages.add(OnStop)
      // Note: The concurrent events in messages will be processed one by one.
    }
  }

  def isEmpty: Boolean = inbox.synchronized {
    messages.isEmpty
  }

  /**
    * Called when we are dropping a message. Test cases override this to test message dropping.
    * Exposed for testing.
    */
  protected def onDrop(message: InboxMessage): Unit = {
    log.warn(s"Drop $message because $endpointRef is stopped")
  }

  /**
    * Calls action closure, and calls the endpoint's onError java.java.util.function in the case of exceptions.
    */
  private def safelyCall(endpoint: RpcEndpoint)(action: => Unit): Unit = {
    try action catch {
      case NonFatal(e) =>
        try endpoint.onError(e) catch {
          case NonFatal(ee) => log.error(s"Ignoring error", ee)
        }
    }
  }

}
