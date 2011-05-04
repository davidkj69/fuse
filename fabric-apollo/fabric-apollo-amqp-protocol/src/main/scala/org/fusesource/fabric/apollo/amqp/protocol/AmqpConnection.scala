/**
 * Copyright (C) 2010-2011, FuseSource Corp.  All rights reserved.
 *
 *     http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */

package org.fusesource.fabric.apollo.amqp.protocol

import org.fusesource.hawtdispatch._
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.fusesource.fabric.apollo.amqp.codec.types.TypeFactory._
import org.apache.activemq.apollo.util.Logging
import java.io.{EOFException, IOException}
import org.fusesource.fabric.apollo.amqp.codec.types._
import org.apache.activemq.apollo.transport._
import scala.math._
import scala.util.Random
import collection.mutable.{Queue, LinkedList, HashMap, SynchronizedMap}
import tcp.{TcpTransportServer, TcpTransportFactory}
import org.fusesource.fabric.apollo.amqp.api._
import org.apache.activemq.apollo.broker.{OverflowSink, TransportSink, SinkMux, Sink}
import org.fusesource.fabric.apollo.amqp.codec._
import java.net.URI

/**
 *
 */
object AmqpConnection {
  def connection() = {
    val rc = new AmqpConnection
    rc.asInstanceOf[Connection]
  }

  def serverConnection(listener:ConnectionListener) = {
    val rc = new AmqpServerConnection(listener)
    rc.asInstanceOf[ServerConnection]
  }
}

// TODO - Heartbeats
class AmqpConnection extends Connection with ConnectionHandler with SessionConnection with TransportListener with Logging {

  var dispatchQueue: DispatchQueue =  Dispatch.createQueue

  var outbound_sessions: SinkMux[AnyRef] = null
  var connection_session: Sink[AnyRef] = null
  var transport_sink:TransportSink = null

  var containerId: String = null
  var peerContainerId: String = null
  var transport: Transport = null
  var last_error: Throwable = null
  val sessions: HashMap[Int, SessionHandler] = new HashMap[Int, SessionHandler]
  val channels: HashMap[Int, Int] = new HashMap[Int, Int]
  val connectionHandler: AmqpCommandHandler = new AmqpCommandHandler(this, null)
  val headerSent: AtomicBoolean = new AtomicBoolean(false)
  val openSent: AtomicBoolean = new AtomicBoolean(false)
  val closeSent: AtomicBoolean = new AtomicBoolean(false)
  var uri:String = null
  var hostname:Option[String] = None
  var maxFrameSize: Long = 0
  var operationTimeout: Long = 10000
  var sessionListener:Option[SessionListener] = None
  var connectedTask:Option[Runnable] = None
  var disconnectedTask:Option[Runnable] = None
  var channel_max = 32767
  var connecting = false

  def onConnected(task:Runnable) = connectedTask = Option(task)
  def onDisconnected(task:Runnable) = disconnectedTask = Option(task)

  def connected = transport.isConnected
  def error = last_error

  def init(uri: String) = {
    this.uri = uri
    Option(uri) match {
      case Some(uri) =>
        hostname = Option(new URI(uri).getHost.asInstanceOf[String])
      case None =>
    }
    // TODO - containerID from URI string
    Option(containerId) match {
      case Some(id) =>
      case None =>
        containerId = UUID.randomUUID.toString
    }
  }

  def connect(t:Option[Transport], uri:String) = {

    init(uri)
    t match {
      case Some(t) =>
        transport = t
      case None =>
        transport = TransportFactory.connect(uri)
    }
    transport.setProtocolCodec(new AmqpCodec)
    transport.setTransportListener(this)
    transport.setDispatchQueue(dispatchQueue)
    transport_sink = new TransportSink(transport)
    transport.start
  }

  def connect(uri:String, onConnected:Runnable): Unit = {
    this.onConnected(onConnected)
    connecting = true
    connect(None, uri)
  }

  def createSession:Session = session(false, 0)

  def session(fromRemote:Boolean, remoteChannel:Int): Session = {
    def random_ushort = {
      val bytes:Array[Byte] = Array(0, 0)
      Random.nextBytes(bytes)
      abs(BitUtils.getUShort(bytes, 0))
    }
    var channel = random_ushort
    val keys = sessions.keys
    while (channel > channel_max && keys.exists(x => (x == channel))) {
      channel = random_ushort
    }
    val session = new AmqpSession(this, channel)
    val handler = new SessionHandler(this, session)
    sessions.put(channel, handler)
    if (fromRemote) {
      session.remote_channel = remoteChannel
      channels.put(remoteChannel,session.channel)
    }
    //trace("Session created : %s", handler.session);
    sessionListener.foreach((x) => x.sessionCreated(this, session))
    session
  }

  def release(channel: Int): Unit = dispatchQueue {
    sessions.remove(channel) match {
      case Some(handler) =>
        val remote_channel = channels.remove(handler.session.remote_channel)
        //trace("Session released : %s", handler.session)
        sessionListener.foreach((x) => x.sessionReleased(this, handler.session))
      case None =>
    }
  }

  def handle_frame(frame:AmqpFrame) = {
    val channel = frame.getChannel

    frame.getBody match {
      case o:AmqpOpen =>
        open(o)
      case c:AmqpClose =>
        close
      case _ =>
        get_session(channel, frame.getBody) match {
          case Some(handler) =>
            frame.handle(handler.handler)
          case None =>
            frame.getBody match {
              case e:AmqpEnd =>
              case f:AmqpFlow =>
              case _ =>
                val error = "Received frame for session that doesn't exist"
                warn("%s : %s", error, frame)
                throw new RuntimeException(error)
            }
        }
    }
  }

  def session_from_remote_channel(channel:Int) = {
    channels.get(channel) match {
      case Some(channel) =>
        sessions.get(channel)
      case None =>
        None
    }
  }

  def get_session(channel:Int, command:AnyRef):Option[SessionHandler] = {
    command match {
      case b:AmqpBegin =>
        Option(b.getRemoteChannel) match {
          case Some(local_channel) =>
            channels.put(channel, local_channel.intValue)
            //trace("Received response to begin frame sent from local_channel=%s from remote_channel=%s", local_channel.intValue, channel)
            session_from_remote_channel(channel) match {
              case Some(h) =>
                h.session.remote_channel = channel
                Option(h)
              case None =>
                None
            }
          case None =>
            val s = session(true, channel)
            //trace("Created session from remote begin request %s", s)
            session_from_remote_channel(channel)
        }
      case _ =>
        session_from_remote_channel(channel)
    }
  }

  def onTransportCommand(command:Object): Unit = {
    try {
      command match {
        case a:AmqpProtocolHeader =>
          header(a)
        case frame:AmqpFrame =>
          if ( closeSent.get && frame.getBody(classOf[AmqpClose]) == null ) {
            warn("disposing of frame : " + frame + ", connection is closed")
            return
          }
          handle_frame(frame)
        case _ =>
          throw new RuntimeException("Received invalid frame : " + command)
      }
    } catch {
      case e: Exception => {
        warn("frame processing error, closing connection", e)
        close(e)
      }
    }
  }

  def header(protocolHeader: AmqpProtocolHeader): Unit = {
    if ( !headerSent.getAndSet(true) ) {
      dispatchQueue !! {
        val response = new AmqpProtocolHeader
        /*
        Option(protocolHeader) match {
          case Some(h) =>
            trace("Received header {%s}, responding with {%s}", h, response)
          case None =>
            trace("Sending protocol header {%s}", response)

        }
        */
        val rc = send(response)
      }
    }

    if ( protocolHeader != null ) {
      if ( protocolHeader.major != AmqpDefinitions.MAJOR && protocolHeader.minor != AmqpDefinitions.MINOR && protocolHeader.revision != AmqpDefinitions.REVISION ) {
        val e = new RuntimeException("Unexpected protocol version received!")
        close(e)
        throw e
      }
    }
    open(null)
  }

  def open(open: AmqpOpen): Unit = {
    Option(open).foreach((open) => {
      Option(open.getMaxFrameSize).foreach((x) => setMaxFrameSize(x.asInstanceOf[Long]))
      peerContainerId = open.getContainerId
      Option(open.getChannelMax).foreach((x) => channel_max = min(channel_max, x.intValue))
    })
    if ( !openSent.getAndSet(true) ) {
      val response: AmqpOpen = createAmqpOpen
      response.setChannelMax(channel_max)
      Option(containerId).foreach((x) => response.setContainerId(x.asInstanceOf[String]))
      response.setContainerId(containerId)
      hostname.foreach((x) => response.setHostname(x))
      if ( getMaxFrameSize != 0 ) {
        response.setMaxFrameSize(getMaxFrameSize)
      }
      dispatchQueue !! {
        /*
        Option(open) match {
          case Some(o) =>
            trace("Received open frame {%s}, responding with {%s}", o, response)
          case None =>
            trace("Sending open frame {%s}", response)
        }
        */
        val rc = send(response)
      }
    }
    Option(open).foreach((o) => {
      connecting = false
      connectedTask.foreach((x) => dispatchQueue << x)
    })
  }

  def setOnClose(task:Runnable) = onDisconnected(task)

  def close: Unit = close(None)

  def close(reason:String):Unit = {
    val error = createAmqpError
    error.setCondition(reason)
    error.setDescription(reason)
    last_error = new RuntimeException(reason)
    close(Option(error))
  }

  def close(t:Throwable):Unit = {
    val error = createAmqpError
    error.setCondition(t.getClass + " : " + t.getMessage)
    error.setDescription(t.getStackTraceString)
    last_error = t
    close(Option(error))
  }

  def close(error:Option[AmqpError]):Unit = {
    if (!closeSent.getAndSet(true)) {
      sessions.foreach {
        case (channel, handler) =>
          handler.session.end(error)
          handler.session.on_end.foreach((x) => dispatchQueue << x)
      }
      val close = createAmqpClose
      error match {
        case Some(e) =>
          close.setError(e)
          warn("Closing connection due to error : %s", e)
        case None =>
          info("Closing connection")
      }
      send(close)
      dispatchQueue {
        stop(disconnectedTask.getOrElse(NOOP))
      }
    }
  }

  protected def stop(on_stop:Runnable):Unit = transport.stop(on_stop)

  def send(command:AnyRef):Boolean = send(0, command)

  def send(channel:Int, command:AmqpCommand):Boolean = send(channel, command.asInstanceOf[AnyRef])

  def send(channel:Int, command:AnyRef):Boolean = {

    def createFrame(command:AmqpCommand):AmqpFrame={
      val frame:AmqpFrame = new AmqpFrame(command)
      //trace("Setting outgoing frame channel to %s" ,channel)
      frame.setChannel(channel)
      frame
    }

    command match {
      case header:AmqpProtocolHeader =>
        doSend(header)
      case open:AmqpOpen =>
        doSend(createFrame(open))
      case close:AmqpClose =>
        doSend(createFrame(close))
      case command:AmqpCommand =>
        if ( closeSent.get ) {
          // can silently ignore detach/end frames
          command match {
            case d:AmqpDetach =>
            case e:AmqpEnd =>
            case _ =>
              warn("Connection is already closed, discarding outgoing frame body %s for channel %s", command, channel)
          }
          return false
        }
        if ( last_error != null ) {
          command match {
            case d:AmqpDetach =>
              return false
            case e:AmqpEnd =>
              return false
            case _ =>
              warn("Transport has failed, discarding outgoing frame body %s for channel %s", command, channel)
              throw last_error
          }
        }
        if (closeSent.get) {
          command match {
            case d:AmqpDetach =>
              return false
            case e:AmqpEnd =>
              return false
            case _ =>
              warn("Transport is not connected, discarding outgoing frame body %s for channel %s", command, channel)
              throw new RuntimeException("Transport connection not established")
          }
        }

        //header(null)
        //open(null)
        doSend(createFrame(command))
      case _ =>
        warn("Unknown frame body passed to send : %s", command)
        false
    }
  }

  def doSend(frame:AnyRef) = {
    if ( getCurrentQueue != dispatchQueue ) {
      var rc = false
      dispatchQueue !! {
        rc = connection_session.offer(frame)
      }
      rc
    } else {
      connection_session.offer(frame)
    }
  }

  def getOperationTimeout = operationTimeout

  def setOperationTimeout(timeout: Long): Unit = operationTimeout = timeout

  def getContainerId = containerId
  def setContainerId(id:String) = containerId = id

  def getPeerContainerId = peerContainerId

  def onRefill: Unit = {
    //trace("onRefill called...")
    if( transport_sink.refiller !=null ) {
      transport_sink.refiller.run
    }
  }

  def onTransportFailure(some_error: IOException): Unit = {
    some_error match {
      case e:EOFException =>
        info("Peer closed connection")
        close
      case _ =>
        info("Transport failure received : %s", some_error)
        close(some_error)
        this.last_error = some_error
        if (connecting) {
          connectedTask.foreach((x) => dispatchQueue << x)
        }
    }
  }

  def onTransportConnected: Unit = {
    //trace("Connected to %s:/%s", transport.getTypeId, transport.getRemoteAddress)
    outbound_sessions = new SinkMux[AnyRef](transport_sink.map {
      x =>
        x
    }, dispatchQueue, AmqpCodec)
    connection_session = new OverflowSink(outbound_sessions.open(dispatchQueue));
    connection_session.refiller = NOOP
    header(null)
    transport.resumeRead
  }

  def onTransportDisconnected: Unit = {
    //trace("Disconnected from %s", transport.getRemoteAddress)
    close
  }

  def getMaxFrameSize = maxFrameSize

  def getDispatchQueue = dispatchQueue

  def setSessionListener(l:SessionListener) = sessionListener = Option(l)

  def setMaxFrameSize(size: Long): Unit = {
    maxFrameSize = if (maxFrameSize != 0) {
      min(maxFrameSize, size)
    } else {
      size
    }
  }

  override def toString = {
    val rc = new StringBuilder("AmqpConnection{")

    Option(transport) match {
      case Some(transport) =>
        rc.append(transport.getTypeId + ":/" + transport.getRemoteAddress)
      case None =>
    }
    rc.append("}")
    rc.toString
  }

}

class AmqpServerConnection(listener:ConnectionListener) extends AmqpConnection with ServerConnection with TransportAcceptListener {
  var transportServer:TransportServer = null

  def getListenPort = {
    transportServer match {
      case t:TcpTransportServer =>
        t.getSocketAddress.getPort
      case _ =>
        0
    }
  }

  def getListenHost = {
    transportServer match {
      case t:TcpTransportServer =>
        t.getSocketAddress.getHostName
      case _ =>
        ""
    }
  }

  def bind(uri:String) = {
    init(uri)
    transportServer = TransportFactory.bind(uri)
    transportServer.setDispatchQueue(dispatchQueue)
    transportServer.setAcceptListener(this)
    transportServer.start()
    info("AMQP Server listening on %s:%s", getListenHost, getListenPort)
  }

  def onAccept(transport:Transport) = {
    val connection = new AmqpServerConnection(null)
    connection.setContainerId(containerId)
    val clientUri = transport.getTypeId + ":/" + transport.getRemoteAddress
    info("Client connected from %s", clientUri)
    connection.connect(Option(transport), uri)
    //trace("Created AmqpConnection %s", connection)
    listener.connectionCreated(connection)
  }

  def onAcceptError(error:Exception) = {

  }

  override def toString = {
    val rc = new StringBuilder("AmqpServerConnection{")
    Option(transportServer) match {
      case Some(transport) =>
        rc.append("local=")
        rc.append(transport.getConnectAddress)
      case None =>
    }
    Option(transport) match {
      case Some(transport) =>
        rc.append(" remote=")
        rc.append(transport.getRemoteAddress)
      case None =>
    }
    rc.append("}")
    rc.toString
  }

}