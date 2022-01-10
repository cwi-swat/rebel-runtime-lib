package com.ing.rebel.util.persistence

import akka.persistence._
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.snapshot.SnapshotStore

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try

object NoOp {
  val emptySuccess: Future[Unit] = Future.successful(())
  val emptyListSuccess: Future[immutable.Seq[Try[Unit]]] = Future.successful(Nil)
  val emptyLongSuccess: Future[Long] = Future.successful(0L)
  val noneSuccess: Future[None.type] = Future.successful(None)
}

class NoOpJournal extends AsyncWriteJournal {

  context.system.log.info("Starting NoOpJournal")

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] =
    NoOp.emptyListSuccess

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
    NoOp.emptySuccess

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)
                                  (recoveryCallback: PersistentRepr => Unit): Future[Unit] =
    NoOp.emptySuccess

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    NoOp.emptyLongSuccess
}

class NoOpSnapshotStore extends SnapshotStore {

  context.system.log.info("Starting NoOpSnapshotStore")

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] =
    NoOp.noneSuccess


  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] =
    NoOp.emptySuccess


  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] =
    NoOp.emptySuccess


  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] =
    NoOp.emptySuccess
}