/*
 * Copyright (C) 2017  Fluence Labs Limited
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package fluence.kvstore

import cats.data.EitherT
import cats.effect.{IO, LiftIO}
import cats.syntax.flatMap._
import cats.{~>, Monad}
import fluence.kvstore.InMemoryKVStore.{InMemoryKVStoreRead, InMemoryKVStoreWrite, TrieMapKVStoreBase}
import fluence.kvstore.KVStore.TraverseOp
import fluence.kvstore.ops._

import scala.collection.concurrent.TrieMap
import scala.language.higherKinds

/**
 * Base thread-safe in memory KVStore implementation, that allow 'put', 'remove'
 * and 'get' by key. Note that K should be with correct hash function (Array[Byte]
 * shouldn't be used as is)
 *
 * @tparam K The type of keys
 * @tparam V The type of stored values
 */
class InMemoryKVStore[K, V]
    extends TrieMapKVStoreBase[K, V] with InMemoryKVStoreRead[K, V] with InMemoryKVStoreWrite[K, V]
    with ReadWriteKVStore[K, V]

object InMemoryKVStore {

  /**
   * Top type for in memory kvStore implementation,
   * just holds kvStore state.
   *
   * @tparam K A type of search key
   * @tparam V A type of value
   */
  private[kvstore] sealed trait InMemoryKVStoreBase[K, V] extends KVStore {

    protected def data: TrieMap[K, V]

    override def close(): IO[Unit] = IO.unit

  }

  /**
   * Allows reading keys and values from KVStore.
   *
   * @tparam K A type of search key
   * @tparam V A type of value
   */
  private[kvstore] trait InMemoryKVStoreRead[K, V] extends InMemoryKVStoreBase[K, V] with KVStoreRead[K, V] {

    /**
     * Returns lazy ''get'' representation (see [[Operation]])
     *
     * @param key Search key
     */
    override def get(key: K): Operation[Option[V]] = new Operation[Option[V]] {

      override def run[F[_]: Monad: LiftIO]: EitherT[F, StoreError, Option[V]] =
        EitherT(IO(data.get(key)).attempt.to[F])
          .leftMap(err ⇒ StoreError.forGet(key, Some(err)))

    }

    /**
     * Returns lazy ''traverse'' representation (see [[TraverseOperation]])
     * Storage takes a snapshot before making 'traverse' operation automatically.
     */
    override def traverse: TraverseOp[K, V] = new TraverseOp[K, V] {

      override def run[FS[_]: Monad: LiftIO](implicit liftIterator: ~>[Iterator, FS]): FS[(K, V)] =
        IO(liftIterator(data.snapshot().iterator)).to[FS].flatten

      override def runUnsafe: Iterator[(K, V)] =
        data.iterator

    }

  }

  /**
   * Allows writing and removing keys and values from KVStore.
   *
   * @tparam K A type of search key
   * @tparam V A type of value
   */
  private[kvstore] trait InMemoryKVStoreWrite[K, V] extends InMemoryKVStoreBase[K, V] with KVStoreWrite[K, V] {

    /**
     * Returns lazy ''put'' representation (see [[Operation]])
     *
     * @param key The specified key to be inserted
     * @param value The value associated with the specified key
     */
    override def put(key: K, value: V): Operation[Unit] = new Operation[Unit] {

      override def run[F[_]: Monad: LiftIO]: EitherT[F, StoreError, Unit] =
        EitherT(IO(data.put(key, value)).attempt.to[F])
          .leftMap(err ⇒ StoreError.forPut(key, value, Some(err)))
          .map(_ ⇒ ())

    }

    /**
     * Returns lazy ''remove'' representation (see [[Operation]])
     *
     * @param key A key to delete within database
     */
    override def remove(key: K): Operation[Unit] = new Operation[Unit] {

      override def run[F[_]: Monad: LiftIO]: EitherT[F, StoreError, Unit] =
        EitherT(IO(data.remove(key)).attempt.to[F])
          .leftMap(err ⇒ StoreError.forRemove(key, Some(err)))
          .map(_ ⇒ ())

    }

  }

  /**
   * Create base in memory KVStore implementation, without snapshot and traverse
   * functionality. See class [[InMemoryKVStore]]
   *
   * @tparam K A type of search key
   * @tparam V A type of value
   */
  def apply[K, V]: InMemoryKVStore[K, V] =
    new InMemoryKVStore[K, V]

  /**
   * Create base in memory KVStore implementation, with snapshot and traverse
   * functionality.
   *
   * @tparam K A type of search key
   * @tparam V A type of value
   */
  def withSnapshots[K, V]: InMemoryKVStore[K, V] with Snapshotable[K, V] = {

    /** Allows to create a point-in-time view of a storage. */
    new InMemoryKVStore[K, V] with Snapshotable[K, V] {
      override def createSnapshot[F[_]: Monad: LiftIO]: F[KVStoreRead[K, V]] =
        IO[KVStoreRead[K, V]](new TrieMapKVStoreBase(data.snapshot()) with InMemoryKVStoreRead[K, V]).to[F]
    }
  }

  /**
   * TrieMap based implementation of KVStore inner state holder.
   */
  private[kvstore] abstract class TrieMapKVStoreBase[K, V](
    override protected val data: TrieMap[K, V] = TrieMap.empty[K, V]
  ) extends InMemoryKVStoreBase[K, V]

}
