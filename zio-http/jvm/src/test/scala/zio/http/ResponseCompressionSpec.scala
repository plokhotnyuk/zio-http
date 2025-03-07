/*
 * Copyright 2021 - 2023 Sporta Technologies PVT LTD & the ZIO HTTP contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.http

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

import zio.test.TestAspect.withLiveClock
import zio.test.assertTrue
import zio.{Chunk, ZIO, ZInputStream, ZLayer}

import zio.stream.ZStream

import zio.http.netty.NettyConfig

object ResponseCompressionSpec extends ZIOHttpSpec {

  private val text: Routes[Any, Response] =
    Routes(
      Method.GET / "text" -> handler(Response.text("Hello World!\n")),
    )

  private val stream =
    Routes(
      Method.GET / "stream-chunked" ->
        handler(
          Response(
            Status.Ok,
            Headers(
              Header.ContentType(MediaType.text.plain),
            ),
            Body.fromCharSequenceStreamChunked(
              ZStream
                .unfold[Long, String](0L) { s =>
                  if (s < 1000) Some((s"$s\n", s + 1)) else None
                }
                .grouped(10)
                .map(_.mkString),
            ),
          ),
        ),
      Method.GET / "stream"         ->
        handler(
          Response(
            Status.Ok,
            Headers(
              Header.ContentType(MediaType.text.plain),
            ),
            Body.fromStream(
              stream = ZStream
                .unfold[Long, String](0L) { s =>
                  if (s < 1000) Some((s"$s\n", s + 1)) else None
                }
                .grouped(10)
                .mapChunks(_.flatMap(_.mkString.getBytes)),
              contentLength = 2000L,
            ),
          ),
        ),
    )

  private val file: Routes[Any, Response] =
    Routes(
      Method.GET / "file" -> Handler.fromResource("TestStatic/TestFile1.txt"),
    ).sandbox

  private val app                              = text ++ stream ++ file
  private lazy val serverConfig: Server.Config = Server.Config.default.port(0).responseCompression()

  override def spec =
    suite("Response compression")(
      test("with Response.text") {
        for {
          server       <- ZIO.service[Server]
          client       <- ZIO.service[Client]
          _            <- server.installInternal(app)
          port         <- server.port
          response     <- client.batched(
            Request(
              method = Method.GET,
              url = URL(Path.root / "text", kind = URL.Location.Absolute(Scheme.HTTP, "localhost", Some(port))),
            )
              .addHeader(Header.AcceptEncoding(Header.AcceptEncoding.GZip(), Header.AcceptEncoding.Deflate())),
          )
          res          <- response.body.asChunk
          decompressed <- decompressed(res)
        } yield assertTrue(decompressed == "Hello World!\n")
      },
      test("with Response.stream") {
        streamTest("stream")
      },
      test("with Response.stream (chunked)") {
        streamTest("stream-chunked")
      },
      test("with files") {
        for {
          server       <- ZIO.service[Server]
          client       <- ZIO.service[Client]
          _            <- server.installInternal(app)
          port         <- server.port
          response     <- client.batched(
            Request(
              method = Method.GET,
              url = URL(Path.root / "file", kind = URL.Location.Absolute(Scheme.HTTP, "localhost", Some(port))),
            )
              .addHeader(Header.AcceptEncoding(Header.AcceptEncoding.GZip(), Header.AcceptEncoding.Deflate())),
          )
          res          <- response.body.asChunk
          decompressed <- decompressed(res)
        } yield assertTrue(decompressed == "This file is added for testing Static File Server.")
      },
    ).provide(
      ZLayer.succeed(serverConfig),
      Server.customized,
      ZLayer.succeed(NettyConfig.defaultWithFastShutdown),
      Client.default,
    ) @@ withLiveClock

  def streamTest(endpoint: String) =
    for {
      server       <- ZIO.service[Server]
      client       <- ZIO.service[Client]
      _            <- server.installInternal(app)
      port         <- server.port
      response     <- client.batched(
        Request(
          method = Method.GET,
          url = URL(Path.root / endpoint, kind = URL.Location.Absolute(Scheme.HTTP, "localhost", Some(port))),
        )
          .addHeader(Header.AcceptEncoding(Header.AcceptEncoding.GZip(), Header.AcceptEncoding.Deflate())),
      )
      res          <- response.body.asChunk
      decompressed <- decompressed(res)
      expected = (0 until 1000).mkString("\n") + "\n"
    } yield assertTrue(decompressed == expected)

  private def decompressed(bytes: Chunk[Byte]): ZIO[Any, Throwable, String] =
    ZIO.attempt {
      val inputStream = new ByteArrayInputStream(bytes.toArray)
      new java.util.zip.GZIPInputStream(inputStream)
    }.mapError(Some(_))
      .flatMap { stream =>
        ZInputStream.fromInputStream(stream).readAll(4096).map { decompressedBytes =>
          new String(decompressedBytes.toArray, StandardCharsets.UTF_8)
        }
      }
      .unsome
      .map(_.getOrElse(""))
}
