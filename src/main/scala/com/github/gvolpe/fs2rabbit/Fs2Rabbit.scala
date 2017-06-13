package com.github.gvolpe.fs2rabbit

import cats.effect.{Effect, IO}
import com.github.gvolpe.fs2rabbit.Fs2Utils._
import com.github.gvolpe.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitConfigManager}
import model.ExchangeType.ExchangeType
import model._
import com.rabbitmq.client.AMQP.{Exchange, Queue}
import com.rabbitmq.client._
import fs2.async.mutable
import fs2.{Pipe, Sink, Stream}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.language.higherKinds

object Fs2Rabbit extends Fs2Rabbit with UnderlyingAmqpClient {
  protected override val factory = createRabbitConnectionFactory

  protected override lazy val fs2RabbitConfig = Fs2RabbitConfigManager.config

  private[Fs2Rabbit] def createRabbitConnectionFactory: ConnectionFactory = {
    val factory = new ConnectionFactory()
    factory.setHost(fs2RabbitConfig.host)
    factory.setPort(fs2RabbitConfig.port)
    factory.setConnectionTimeout(fs2RabbitConfig.connectionTimeout)
    factory
  }
}

trait UnderlyingAmqpClient {
  protected val factory: ConnectionFactory
  protected val fs2RabbitConfig: Fs2RabbitConfig

  protected def defaultConsumer(channel: Channel,
                                Q: mutable.Queue[IO, Either[Throwable, AmqpEnvelope]]): Consumer = new DefaultConsumer(channel) {

    override def handleCancel(consumerTag: String): Unit = {
      Q.enqueue1(Left(new Exception(s"Queue might have been DELETED! $consumerTag"))).unsafeRunSync()
    }

    override def handleDelivery(consumerTag: String,
                                envelope: Envelope,
                                properties: AMQP.BasicProperties,
                                body: Array[Byte]): Unit = {
      val msg   = new String(body, "UTF-8")
      val tag   = envelope.getDeliveryTag
      val props = AmqpProperties.from(properties)
      Q.enqueue1(Right(AmqpEnvelope(tag, msg, props))).unsafeRunSync()
    }

  }

  protected def createAcker[F[_]](channel: Channel)
                                 (implicit F: Effect[F]): Sink[F, AckResult] =
    liftSink[F, AckResult] {
      case Ack(tag)   => F.delay(channel.basicAck(tag, false))
      case NAck(tag)  => F.delay(channel.basicNack(tag, false, fs2RabbitConfig.requeueOnNack))
    }

  protected def createConsumer[F[_]](queueName: QueueName,
                                     channel: Channel,
                                     autoAck: Boolean)
                                    (implicit F: Effect[F], ec: ExecutionContext): StreamConsumer[F] = {
    val daQ = fs2.async.boundedQueue[IO, Either[Throwable, AmqpEnvelope]](100).unsafeRunSync()
    val dc  = defaultConsumer(channel, daQ)
    for {
      _         <- asyncF[F, String](channel.basicConsume(queueName, autoAck, dc))
      consumer  <- Stream.repeatEval(daQ.dequeue1.to[F]) through resilientConsumer
    } yield consumer
  }

  protected def acquireConnection[F[_]](implicit F: Effect[F]): F[(Connection, Channel)] =
    F.delay {
      val conn    = factory.newConnection
      val channel = conn.createChannel
      (conn, channel)
    }

  protected def resilientConsumer[F[_]](implicit F: Effect[F]): Pipe[F, Either[Throwable, AmqpEnvelope], AmqpEnvelope] =
    streamMsg =>
      streamMsg.flatMap {
        case Left(err)  => Stream.fail(err)
        case Right(env) => asyncF[F, AmqpEnvelope](env)
      }

}

trait Fs2Rabbit {
  self: UnderlyingAmqpClient =>

  /**
    * Creates a connection and a channel in a safe way using Stream.bracket.
    * In case of failure, the resources will be cleaned up properly.
    *
    * @return A tuple (@Connection, @Channel) as a Stream
    * */
  def createConnectionChannel[F[_]]()(implicit F: Effect[F]): Stream[F, (Connection, Channel)] =
    Stream.bracket(acquireConnection)(
      cc => asyncF[F, (Connection, Channel)](cc),
      cc => F.delay {
        val (conn, channel) = cc
        if (channel.isOpen) channel.close()
        if (conn.isOpen) conn.close()
      }
    )

  /**
    * Creates a consumer and an acker to handle the acknowledgments with RabbitMQ.
    *
    * @param channel the channel where the consumer is going to be created
    * @param queueName the name of the queue
    *
    * @return A tuple (@StreamAcker, @StreamConsumer) represented as @StreamAckerConsumer
    * */
  def createAckerConsumer[F[_]](channel: Channel, queueName: QueueName)
                               (implicit F: Effect[F], ec: ExecutionContext): StreamAckerConsumer[F] = {
    channel.basicQos(1)
    val consumer = createConsumer(queueName, channel, autoAck = false)
    (createAcker(channel), consumer)
  }

  /**
    * Creates a consumer with an auto acknowledgment mechanism.
    *
    * @param channel the channel where the consumer is going to be created
    * @param queueName the name of the queue
    *
    * @return A @StreamConsumer with data type represented as @AmqpEnvelope
    * */
  def createAutoAckConsumer[F[_]](channel: Channel, queueName: QueueName)
                                 (implicit F: Effect[F], ec: ExecutionContext): StreamConsumer[F] = {
    createConsumer(queueName, channel, autoAck = true)
  }

  /**
    * Creates a simple publisher.
    *
    * @param channel the channel where the publisher is going to be created
    * @param exchangeName the exchange name
    * @param routingKey the routing key name
    *
    * @return A sink where messages of type @AmqpMessage[String] can be published represented as @StreamPublisher
    * */
  def createPublisher[F[_]](channel: Channel,
                            exchangeName: ExchangeName,
                            routingKey: RoutingKey)
                           (implicit F: Effect[F], ec: ExecutionContext): StreamPublisher[F] = { streamMsg =>
    for {
      msg   <- streamMsg
      _     <- asyncF[F, Unit](channel.basicPublish(exchangeName, routingKey, msg.properties.asBasicProps, msg.payload.getBytes("UTF-8")))
    } yield ()
  }

  /**
    * Creates an exchange.
    *
    * @param channel the channel where the exchange is going to be declared
    * @param exchangeName the exchange name
    * @param exchangeType the exchange type: Direct, FanOut, Headers, Topic.
    *
    * @return a Stream of data type @Exchange.DeclareOk
    * */
  def declareExchange[F[_]](channel: Channel, exchangeName: ExchangeName, exchangeType: ExchangeType)
                           (implicit F: Effect[F]): Stream[F, Exchange.DeclareOk] =
    asyncF[F, Exchange.DeclareOk] {
      channel.exchangeDeclare(exchangeName, exchangeType.toString.toLowerCase)
    }

  /**
    * Creates a queue.
    *
    * @param channel the channel where the queue is going to be declared
    * @param queueName the queue name
    *
    * @return a Stream of data type @Queue.DeclareOk
    * */
  def declareQueue[F[_]](channel: Channel, queueName: QueueName)
                        (implicit F: Effect[F]): Stream[F, Queue.DeclareOk] =
    asyncF[F, Queue.DeclareOk] {
      channel.queueDeclare(queueName, false, false, false, Map.empty[String, AnyRef].asJava)
    }

}
