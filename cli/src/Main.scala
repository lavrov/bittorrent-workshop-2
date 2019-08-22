package com.github.lavrov.bittorrent

import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.Executors

import cats.data.Validated
import cats.effect._
import cats.syntax.all._
import com.monovore.decline.{Command, Opts}
import fs2.Stream
import fs2.io.udp.AsynchronousSocketGroup
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import scodec.bits.{Bases, BitVector, ByteVector}

import scala.util.Random

import com.github.lavrov.bittorrent.dht.PeerDiscovery
import com.github.lavrov.bencode.{decode, encode, Bencode, BencodeCodec}
import com.github.lavrov.bittorrent.dht.{NodeId, MessageSocket, Client => DhtClient}

object Main extends IOApp {

  val rnd = new Random
  val selfId = PeerId.generate(rnd)

  val topLevelCommand: Command[IO[Unit]] = {
    val torrentFileOpt =
      Opts.option[String]("torrent", help = "Path to torrent file").map(Paths.get(_))

    val infoHashOpt = Opts
      .option[String]("info-hash", "Info-hash of the torrent file")
      .mapValidated(
        string => Validated.fromEither(ByteVector.fromHexDescriptive(string)).toValidatedNel
      )
      .validate("Must be 20 bytes long")(_.size == 20)
      .map(InfoHash)

    val findPeersCommand = Opts.subcommand(
      name = "find-peers",
      help = "Find peers by info-hash"
    ) {
      infoHashOpt.map(findPeers)
    }

    val readTorrentCommand = Opts.subcommand(
      name = "read-torrent",
      help = "Read torrent file from file and print it out"
    ) {
      torrentFileOpt.map(printTorrentMetadata)
    }

    Command(
      name = "bittorrent",
      header = "Bittorrent client"
    )(
      findPeersCommand <+> readTorrentCommand
    )
  }

  def run(args: List[String]): IO[ExitCode] = {
    topLevelCommand.parse(args) match {
      case Right(thunk) => thunk as ExitCode.Success
      case Left(help) =>
        IO(println(help)) as {
          if (help.errors.isEmpty) ExitCode.Success else ExitCode.Error
        }
    }
  }

  def asynchronousSocketGroupResource =
    Resource.make(IO(AsynchronousSocketGroup()))(
      r => IO(r.close())
    )

  def asynchronousChannelGroupResource =
    Resource.make(
      IO(
        AsynchronousChannelProvider
          .provider()
          .openAsynchronousChannelGroup(2, Executors.defaultThreadFactory())
      )
    )(g => IO(g.shutdown()))

  def makeLogger: IO[Logger[IO]] = Slf4jLogger.fromClass[IO](getClass)

  def getMetaInfo(torrentPath: Path): IO[(InfoHash, TorrentMetadata.Info)] = {
    for {
      bytes <- IO(BitVector(Files.readAllBytes(torrentPath)))
      bc <- IO.fromEither(decode(bytes).left.map(e => new Exception(e.message)))
      infoDict <- IO.fromEither(TorrentMetadata.RawInfoFormat.read(bc).left.map(new Exception(_)))
      torrentMetadata <- IO.fromEither(
        TorrentMetadata.TorrentMetadataFormat.read(bc).left.map(new Exception(_))
      )
    } yield (InfoHash(encode(infoDict).digest("SHA-1").bytes), torrentMetadata.info)
  }

  def printTorrentMetadata(torrentPath: Path): IO[Unit] = {
    getMetaInfo(torrentPath)
      .flatMap {
        case (infoHash, torrentMetadata) =>
          IO {
            println("Info-hash:")
            pprint.pprintln(infoHash.bytes.toHex(Bases.Alphabets.HexUppercase))
            println()
            pprint.pprintln(torrentMetadata)
          }
      }
  }

  def getPeers(
      infoHash: InfoHash
  )(implicit asynchronousSocketGroup: AsynchronousSocketGroup): Stream[IO, PeerInfo] = {
    val selfId = NodeId.generate(rnd)
    for {
      logger <- Stream.eval(makeLogger)
      client <- Stream.resource(DhtClient.start[IO](selfId, port = 6881))
      peers <- Stream.eval(PeerDiscovery.start(infoHash, client))
      peer <- peers
    } yield peer
  }

  def findPeers(infoHash: InfoHash): IO[Unit] =
    for {
      logger <- makeLogger
      _ <- asynchronousSocketGroupResource.use { implicit asg =>
        getPeers(infoHash)
          .evalTap(peerInfo => logger.info(s"Found peer $peerInfo"))
          .compile
          .drain
      }
    } yield ()

}
