/*
 * Copyright 2020 Docspell Contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package docspell.common

import java.nio.file.{Path => JPath}

import cats.FlatMap
import cats.Monad
import cats.effect._
import cats.implicits._
import fs2.Stream
import fs2.io.file.{Files, Flags, Path}

import docspell.common.syntax.all._

import io.circe.Decoder

object File {

  def path(jp: JPath): Path = Path.fromNioPath(jp)

  def mkDir[F[_]: Files](dir: Path): F[Unit] =
    Files[F].createDirectories(dir)

  def exists[F[_]: Files](file: Path): F[Boolean] =
    Files[F].exists(file)

  def size[F[_]: Files](file: Path): F[Long] =
    Files[F].size(file)

  def existsNonEmpty[F[_]: Files: Monad](file: Path, minSize: Long = 0): F[Boolean] =
    exists[F](file).flatMap(b => if (b) size[F](file).map(_ > minSize) else false.pure[F])

  def delete[F[_]: Files: FlatMap](path: Path): F[Unit] =
    for {
      isDir <- Files[F].isDirectory(path)
      _ <-
        if (isDir) Files[F].deleteRecursively(path)
        else Files[F].deleteIfExists(path)
    } yield ()

  def withTempDir[F[_]: Files](parent: Path, prefix: String): Resource[F, Path] =
    Resource
      .eval(mkDir[F](parent))
      .flatMap(_ => Files[F].tempDirectory(parent.some, prefix, None))

  def listFiles[F[_]: Files](pred: Path => Boolean, dir: Path): Stream[F, Path] =
    Files[F].list(dir).filter(pred)

  def readAll[F[_]: Files](
      file: Path,
      chunkSize: Int
  ): Stream[F, Byte] =
    Files[F].readAll(file, chunkSize, Flags.Read)

  def readAll[F[_]: Files](
      file: Path
  ): Stream[F, Byte] =
    Files[F].readAll(file)

  def readText[F[_]: Files: Concurrent](file: Path): F[String] =
    readAll[F](file, 8192).through(fs2.text.utf8.decode).compile.foldMonoid

  def writeString[F[_]: Files: Concurrent](file: Path, content: String): F[Path] =
    Stream
      .emit(content)
      .through(fs2.text.utf8.encode)
      .through(Files[F].writeAll(file))
      .compile
      .drain
      .map(_ => file)

  def readJson[F[_]: Async, A](file: Path)(implicit d: Decoder[A]): F[A] =
    readText[F](file).map(_.parseJsonAs[A]).rethrow

}
