package com.github.lavrov.bittorrent.dht
import org.scalatest.FlatSpec
import cats.effect.SyncIO
import cats.effect.concurrent.Ref
import com.github.lavrov.bittorrent.PeerInfo
import com.github.lavrov.bittorrent.InfoHash
import scodec.bits.ByteVector
import com.github.lavrov.bittorrent.dht.message.Response
import java.net.InetSocketAddress

import org.scalatest.Matchers._
import cats.data.State
import cats.{Id, Monad}
import cats.implicits._
import cats.mtl.MonadState
import cats.mtl.instances.all._
import _root_.com.github.lavrov.bittorrent.dht.message.Message
import scala.concurrent.duration.FiniteDuration
import com.github.lavrov.bittorrent.dht.message.Query

class RequestResponseSpec extends FlatSpec {

  import RequestResponse.Behaviour
  import RequestResponse.Timeout

  it should "provide request response semantic" in {

    sealed trait Op
    object Op {
      case object GenTransactionId extends Op
      case class SendOut(message: Message) extends Op
      case class Schedule(transactionId: ByteVector) extends Op
      case class RunCallback(result: Either[Throwable, Response]) extends Op
    }
    type Callback = Unit
    type St = Map[ByteVector, Callback]
    type F[A] = State[St, A]

    val opsBuffer = scala.collection.mutable.ListBuffer.empty[Op]

    val someTransactionId = ByteVector.encodeUtf8("a").right.get

    val behaviour = new Behaviour[F, Callback](
      generateTransactionId = State { s =>
        opsBuffer += Op.GenTransactionId
        (s, someTransactionId)
      },
      (_, message) =>
        State { s =>
          opsBuffer += Op.SendOut(message)
          (s, ())
        },
      (duration, transactionId) =>
        State { s =>
          opsBuffer += Op.Schedule(transactionId)
          (s, ())
        },
      (callback, result) =>
        State { s =>
          opsBuffer += Op.RunCallback(result)
          (s, ())
        }
    )

    val someAddress = InetSocketAddress.createUnresolved("1.1.1.1", 1)
    val selfId = NodeId(ByteVector.encodeUtf8("self").right.get)
    val otherNodeId = NodeId(ByteVector.encodeUtf8("other").right.get)

    val sendRequestProgram =
      behaviour.sendQuery(
        someAddress,
        Query.Ping(selfId),
        ()
      )

    val program =
      for {
        _ <- sendRequestProgram
        s <- State.get
        _ = s.size shouldBe 1
        _ <- behaviour.timeout(someTransactionId)
        s <- State.get
        _ = s.size shouldBe 0
        _ <- behaviour.receive(
          Message.ResponseMessage(
            someTransactionId,
            Response.Ping(otherNodeId)
          )
        )
      } yield ()

    val (finalState, _) = program.run(Map.empty).value

    opsBuffer.toList shouldBe List(
      Op.GenTransactionId,
      Op.SendOut(
        Message.QueryMessage(
          someTransactionId,
          Query.Ping(selfId)
        )
      ),
      Op.Schedule(someTransactionId),
      Op.RunCallback(Left(Timeout()))
    )
  }
}
