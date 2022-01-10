package com.ing.rebel.sync

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props, Terminated}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.fsm.PersistentFSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.testkit.TestActor.AutoPilot
import akka.testkit.{ImplicitSender, TestActor, TestKitBase, TestProbe}
import akka.util.Timeout
import cats.data.NonEmptyList
import cats.data.Validated.Valid
import com.ing.rebel.RebelError.PreConditionFailed
import com.ing.rebel.config.{Parallel, RebelConfig}
import com.ing.rebel.messages._
import com.ing.rebel.specification.Specification
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.TwoPhaseCommitSpec.Dummy.MyRequest
import com.ing.rebel.sync.TwoPhaseCommitSpec.{Dummy, _}
import com.ing.rebel.sync.twophasecommit.TransactionManager.Stop
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.{GlobalCommitAck, _}
import com.ing.rebel.sync.twophasecommit.{ActorRefContactPoint, TransactionManager, TransactionParticipant}
import com.ing.rebel.util.MessageLogger
import com.ing.rebel.util.MessageLogger.MessageEvent
import com.ing.rebel.{IsInitialized, RebelConditionCheck, RebelDomainEvent, RebelErrors, RebelKeyable, RebelState, RebelSyncEvent, SpecificationEvent}
import com.ing.util.{CassandraClusterTestKit, CassandraLifecycle, IsolatedCluster, MessageLoggerAutoPilot}
import com.typesafe.config.ConfigFactory
import io.circe.Encoder
import org.joda.time.DateTime
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{AppendedClues, FlatSpecLike, Matchers}

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.util.Try

object TwoPhaseCommitSpec {
  def uniqueId(): TransactionId = TransactionId(UniqueId.apply())

  object Dummy extends Specification {
    case object MyRequest extends SpecificationEvent
    override type Event = MyRequest.type
    override type Data = this.type
    case object State extends RebelState
    override type Key = String

    override def keyable: RebelKeyable[Key] = implicitly

    override val specificationName: String = "Dummy"
    override implicit val dataEncoder: Encoder[Data] = implicitly
    override implicit val stateEncoder: Encoder[State] = implicitly
  }

  val tId = TransactionId("ID")
  val myRequestSyncEvent: RebelSyncEvent[_ <: Specification] = RebelDomainEvent(MyRequest, tId)

  def createParticipantId(actorRef: ActorRef): ParticipantId =
    ParticipantId(tId, actorRef.path.toStringWithoutAddress)

  def createInitializeMsg(participantRefs: ActorRef*): Initialize = {
    val actions = participantRefs.map{ ref =>
      SyncAction(createParticipantId(ref),
        ActorRefContactPoint(ref),
        myRequestSyncEvent)
    }
    Initialize(tId, Set(actions: _*))
  }

  def entityActor(behavior: RebelSyncEvent[_] => Boolean)(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case CheckPreconditions(m, _) => sender() ! CheckPreconditionsResult(m.transactionId,
          if (behavior(m)) Valid(Set()) else RebelConditionCheck.failure(PreConditionFailed(m.toString)))
//        case RebelCommand(de)       => sender() ! EntityCommandSuccess(de)
        case ReleaseEntity(event, true) => // local apply
      }
    }))

  def expectSuccess()(implicit requester: TestProbe): Unit = {
    requester.expectMsgPF(hint = "TransactionResult with success") {
      case TransactionResult(_, RebelConditionCheck.success) =>
    }
  }

  def expectFailure()(implicit requester: TestProbe): Unit = {
    requester.expectMsgPF(hint = "TransactionResult with failure") {
      case TransactionResult(_, result) if result.isInvalid =>
    }
  }
}

trait TwoPhaseCommitSpec extends Matchers with ScalaFutures with MessageLoggerAutoPilot {
  this: FlatSpecLike with TestKitBase with ImplicitSender =>
  behavior of "TwoPhaseCommit"

  implicit val requester: TestProbe = TestProbe("requester")

  import TwoPhaseCommitSpec._

  def simple2PC(a1Behaviour: RebelSyncEvent[_] => Boolean, a2Behaviour: RebelSyncEvent[_] => Boolean): (ActorRef, ActorRef, ActorRef) = {
    val coordinatorShardingParent = TestProbe()
    coordinatorShardingParent.setAutoPilot((sender: ActorRef, msg: Any) => msg match {
      case Passivate(stop) =>
        system.log.info("receive message, replying with {}", stop)
        sender ! stop
        TestActor.KeepRunning
    })
    val coordinator = coordinatorShardingParent.childActorOf(TransactionManager.props, "coordinator")
    watch(coordinator)
    val ccp = ActorRefContactPoint(coordinator)
    val a1 = system.actorOf(TransactionParticipant.props(ActorRefContactPoint(entityActor(a1Behaviour)), ccp), "participant1")
    val a2 = system.actorOf(TransactionParticipant.props(ActorRefContactPoint(entityActor(a2Behaviour)), ccp), "participant2")
    watch(a1)
    watch(a2)

    requester.send(coordinator, createInitializeMsg(a1, a2))
    (a1, a2, coordinator)
  }

  def expectAllTerminated(all: (ActorRef, ActorRef, ActorRef)): Any = {
    val (a1, a2, coordinator) = all

    val refs: Seq[ActorRef] = expectMsgAllConformingOf(classOf[Terminated], classOf[Terminated], classOf[Terminated]).map(_.actor)
    refs should contain allOf(a1, a2, coordinator)
  }

  //noinspection ConvertExpressionToSAM does not work if removed
  setAutoPilot(new TestActor.AutoPilot {
    override def run(sender: ActorRef, msg: Any): AutoPilot = {
      system.eventStream.publish(MessageEvent(sender, self, msg.toString))
      this
    }
  })

  it should "complete a transaction and shutdown" in {
    system.actorOf(MessageLogger.props("target/complete-transaction.html"))

    val all = simple2PC(_ => true, _ => true)
    expectSuccess()
    expectAllTerminated(all)
  }

  it should "fail when first participant aborts" in {
    val all = simple2PC(_ => false, _ => true)
    expectFailure()
    expectAllTerminated(all)
  }

  it should "fail when second participant aborts" in {
    val all = simple2PC(_ => false, _ => true)
    expectFailure()
    expectAllTerminated(all)
  }

  it should "fail when both participants abort" in {
    val all = simple2PC(_ => false, _ => false)
    expectFailure()
    expectAllTerminated(all)
  }

  it should "respond with the same answer when asked again (without side effects)" in {
    system.actorOf(MessageLogger.props("target/2pc-same-response.html"))
    addTimer()

    val supervisor = TestProbe("supervisor")

    val coordinator = supervisor.childActorOf(TransactionManager.props, "coordinator")
    supervisor.watch(coordinator)

    val a1 = system.actorOf(TransactionParticipant.props(ActorRefContactPoint(entityActor(_ => true)), ActorRefContactPoint(coordinator)), "participant1")
    val a2 = system.actorOf(TransactionParticipant.props(ActorRefContactPoint(entityActor(_ => true)), ActorRefContactPoint(coordinator)), "participant2")

    (1 to 10).foldLeft(coordinator) { case (currentCoordinator, _) =>
      //      val coordinator =
      //      supervisor.watch(coordinator)

      requester.send(currentCoordinator, createInitializeMsg(a1, a2))
      expectSuccess()
      val passivate = supervisor.expectMsgType[Passivate]
      supervisor.reply(passivate.stopMessage)
      supervisor.expectTerminated(currentCoordinator)

      val newCoordinator = supervisor.childActorOf(TransactionManager.props, "coordinator")
      supervisor.watch(newCoordinator)
      newCoordinator
    }
  }

  it should "respond with same answer (commit) after termination" in {
    val all = simple2PC(_ => true, _ => true)
    expectSuccess()
    expectAllTerminated(all)

    val recoveredCoordinator = system.actorOf(TransactionManager.props, "coordinator")
    watch(recoveredCoordinator)
    requester.send(recoveredCoordinator, createInitializeMsg(TestProbe("a1").ref))
    expectSuccess()
  }

  it should "respond with same answer (abort) after termination" in {
//    system.actorOf(MessageLogger.props("target/2pc-same-response-abort.html"))
//    addTimer()
    val all = simple2PC(_ => false, _ => false)
    expectFailure()
    expectAllTerminated(all)

    val recoveredCoordinator = system.actorOf(TransactionManager.props, "coordinator")
    watch(recoveredCoordinator)
    requester.send(recoveredCoordinator, createInitializeMsg(TestProbe("a1").ref))
    expectFailure()
  }

  it should "finish a whole transaction with coordinator failure and recovery" in {
    val coordinator = system.actorOf(TransactionManager.props, "coordinator")
    watch(coordinator)

    val p1 = system.actorOf(TransactionParticipant.props(ActorRefContactPoint(entityActor(_ => true)),
      ActorRefContactPoint(coordinator)), "participant1")

    def proxy(coordinator: ActorRef, participant: ActorRef): ActorRef = system.actorOf(Props(new Actor {

      override def receive: Receive = toParticipant orElse toCoordinator orElse {
        case m => system.log.error("No known recipient {} from {}", m, sender())
      }

      val toParticipant: Receive = {
        case m if sender() == coordinator =>
          system.log.info("Forwarding {} to {}", m, coordinator)
          participant ! m
      }

      var voteCommitCount = 0

      val toCoordinator: Receive = {
        case msg if sender() == participant =>
          // recover coordinator if it is dead
          implicit val timeout: Timeout = Timeout(3.seconds)
          val eventualRef: Boolean = Try(context.actorSelection(coordinator.path).resolveOne().futureValue).isFailure
          if (eventualRef) {
            system.actorOf(TransactionManager.props, "coordinator")
            system.log.info("Recovered coordinator")
          }

          msg match {
            case m: VoteCommit =>
              // coordinator dies after sending vote request and participant already committed.
              voteCommitCount += 1
              system.stop(coordinator)
              system.log.info("Stop coordinator - crash simulation")
              coordinator ! m
            case m             =>
              system.log.info("Forwarding {} to {}", m, participant)
              coordinator ! m
          }
      }
    }))

    val requester: TestProbe = TestProbe("requester")
    requester.send(coordinator, createInitializeMsg(proxy(coordinator, p1)))

    requester.expectMsgPF() {
      case TransactionResult(_, RebelConditionCheck.success) =>
    }
  }

  it should "not deadlock" in {
    val supervisor = TestProbe()
    val coordinator = supervisor.childActorOf(TransactionManager.props, "coordinator")
    watch(coordinator)
    val ccp = ActorRefContactPoint(coordinator)
    val a1 = system.actorOf(TransactionParticipant.props(ActorRefContactPoint(entityActor(_ => true)), ccp), "participant1")
    val a2 = system.actorOf(TransactionParticipant.props(ActorRefContactPoint(entityActor(_ => true)), ccp), "participant2")
    watch(a1)
    watch(a2)

    requester.send(coordinator, createInitializeMsg(a1, a2))
    expectSuccess()
    val passivate = supervisor.expectMsgType[Passivate]
    supervisor.reply(passivate.stopMessage)
    expectAllTerminated(coordinator, a1, a2)
  }
}

trait TransactionManagerSpec extends Matchers with MessageLoggerAutoPilot {
  this: FlatSpecLike with TestKitBase with ImplicitSender =>
  behavior of "TransactionManager"

  implicit val requester: TestProbe = TestProbe("requester")

  val coordinator: ActorRef = createCoordinator()

  private def createCoordinator() = {
    val coordinatorShardingParent = TestProbe()
    coordinatorShardingParent.setAutoPilot((sender: ActorRef, msg: Any) => msg match {
      case Passivate(stop) =>
        system.log.info("receive message, replying with ", stop)
        sender ! stop
        TestActor.KeepRunning
    })
    val coordinator = coordinatorShardingParent.childActorOf(TransactionManager.props, "coordinator")
    watch(coordinator)
    coordinator
  }

  val participant: TestProbe = TestProbe("participant")
  val participant2: TestProbe = TestProbe("participant")

  // for debugging
  requester.setAutoPilot(messageLoggerPilot)
  participant.setAutoPilot(messageLoggerPilot)
  participant2.setAutoPilot(messageLoggerPilot)

  it should "redeliver messages when no VoteCommit or GlobalCommit is received" in {
    val initializeMsg = createInitializeMsg(participant.ref)
    requester.send(coordinator, initializeMsg)
    participant.expectMsgType[VoteRequest]
    participant.expectMsgType[VoteRequest]
    participant.expectMsgType[VoteRequest]
    val request: VoteRequest = participant.expectMsgType[VoteRequest]

    val id = request.transactionId
    val participantId = initializeMsg.participants.head.participantId
    participant.send(coordinator, VoteCommit(id, participantId, Set()))
    participant.expectMsg(GlobalCommit(id))
    participant.expectMsg(GlobalCommit(id))
    participant.expectMsg(GlobalCommit(id))
    participant.expectMsg(GlobalCommit(id))

    coordinator ! GlobalCommitAck(id, participantId)

    expectSuccess()
  }

  it should "redeliver messages when no VoteAbort or GlobalAbort is received" in {
    val initializeMsg = createInitializeMsg(participant.ref)
    requester.send(coordinator, initializeMsg)
    participant.expectMsgType[VoteRequest]
    participant.expectMsgType[VoteRequest]
    participant.expectMsgType[VoteRequest]
    val request: VoteRequest = participant.expectMsgType[VoteRequest]

    val id = request.transactionId
    val participantId = initializeMsg.participants.head.participantId
    participant.send(coordinator, VoteAbort(id, participantId, RebelErrors.of(PreConditionFailed("P != NP"))))
    participant.expectMsg(GlobalAbort(id))
    participant.expectMsg(GlobalAbort(id))
//    participant.expectMsg(GlobalAbort(id))
//    participant.expectMsg(GlobalAbort(id))

    coordinator ! GlobalAbortAck(id, participantId)

    expectFailure()
  }

  it should "shutdown after 2 seconds of timeout" in {
    requester.send(coordinator, createInitializeMsg(participant.ref))
    val maximumTimeoutDuration = RebelConfig(system).rebelConfig.sync.twoPC.managerTimeout
    participant.receiveWhile(maximumTimeoutDuration / 2) { // hacky way to circumvent dilated
      case VoteRequest(_, _, _) => ()
      case msg                  => fail(s"received $msg, while expecting VoteRequest's only")
    }
    expectTerminated(coordinator)
  }

  it should "recover and start retrying" in {
    val initializeMsg = createInitializeMsg(participant.ref)
    requester.send(coordinator, initializeMsg)
    participant.expectMsgType[VoteRequest]
    coordinator ! PoisonPill
    expectTerminated(coordinator)
    participant.expectNoMessage(1.second)

    val recoveredCoordinator = createCoordinator()

    val request: VoteRequest = participant.expectMsgType[VoteRequest]
    val transactionId = initializeMsg.transactionId
    val participantId = initializeMsg.participants.head.participantId
    request.transactionId should equal(transactionId)
    participant.send(recoveredCoordinator, VoteCommit(transactionId, participantId, Set()))
    val globalCommit = GlobalCommit(transactionId)
    participant.expectMsg(globalCommit)

    recoveredCoordinator ! PoisonPill
    expectTerminated(recoveredCoordinator)
    participant.expectNoMessage(1.second)

    val recoveredCoordinator2 = createCoordinator()

    participant.expectMsg(globalCommit)
    participant.send(recoveredCoordinator2, GlobalCommitAck(transactionId, participantId))
    // to make sure ack is persisted, https://doc.akka.io/docs/akka/current/persistence.html?language=scala#safely-shutting-down-persistent-actors
    participant.expectNoMessage(1.second)

    recoveredCoordinator2 ! PoisonPill
    expectTerminated(recoveredCoordinator2)
    participant.expectNoMessage(1.second)

    val recoveredCoordinator3 = createCoordinator()
    participant.expectNoMessage(1.second)
  }

  it should "abort after timeout when not all participants respond" in {
    /**
      * Likewise, the coordinator can be blocked in state WAIT, waiting for the votes of each participant.
      * If not all votes have been collected after a certain period of time, the coordinator should vote
      * for an abort as well, and subsequently send GLOBAL_ABORT to all participants.
      */
    requester.send(coordinator, createInitializeMsg(participant.ref, participant2.ref))
    val request: VoteRequest = participant.expectMsgType[VoteRequest]
    if (RebelConfig(system).rebelConfig.sync.twoPC.lockMechanism == Parallel) participant2.expectMsgType[VoteRequest]

    // only participant responds
    val id = request.transactionId
    val pid = createParticipantId(participant.ref)
    participant.send(coordinator, VoteCommit(id, pid, Set()))
//    participant.ignoreMsg { case m => m.isInstanceOf[VoteRequest] }
    participant2.ignoreMsg { case m => m.isInstanceOf[VoteRequest] }

    // after ${abortTimeout} seconds
    val abortTimeout: FiniteDuration = RebelConfig(system).rebelConfig.sync.twoPC.managerTimeout
//    Thread.sleep(abortTimeout.toMillis)
    participant.expectNoMessage(abortTimeout)
    participant2.ignoreMsg { case m => m.isInstanceOf[VoteRequest] }
    participant.expectMsg(GlobalAbort(id))
    participant2.expectMsg(GlobalAbort(id))
  }

  it should "succeed with empty participants list" in {
    val coordinator = system.actorOf(TransactionManager.props, "coordinator2")
    watch(coordinator)
    requester.send(coordinator, createInitializeMsg())

    expectSuccess()
  }

  it should "allow for participants to increase via VoteCommit" in {
    val initializeMsg = createInitializeMsg(participant.ref)
    requester.send(coordinator, initializeMsg)
    val transactionId = initializeMsg.transactionId
    participant.expectMsgType[VoteRequest]

    val nestedParticipant = TestProbe("nested")
    val nestedParticipantId = ParticipantId(transactionId, nestedParticipant.ref.path.toStringWithoutAddress)

    participant.send(coordinator, VoteCommit(transactionId, initializeMsg.participants.head.participantId,
        Set(SyncAction.syncInitialized(nestedParticipantId, transactionId,
          ActorRefContactPoint(nestedParticipant.ref)))))

    nestedParticipant.expectMsg(VoteRequest(transactionId, nestedParticipantId, IsInitialized(transactionId)))

    nestedParticipant.send(coordinator, VoteCommit(transactionId, nestedParticipantId, Set()))

    participant.expectMsg(GlobalCommit(transactionId))
//    nestedParticipant.ignoreMsg { case m => m.isInstanceOf[VoteRequest] }
    nestedParticipant.expectMsg(GlobalCommit(transactionId))
  }

  val manangerRetryDuration: FiniteDuration = RebelConfig(system).rebelConfig.sync.twoPC.managerRetryDuration

  it should "handle deadlocks, on EntityTooBusy, by aborting" in {
    // 2 participants with ordering (implicit by Ordering)
    // participant 2 is locked by other transaction
    val initializeMsg = createInitializeMsg(participant.ref, participant2.ref)
    requester.send(coordinator, initializeMsg)
    val transactionId = initializeMsg.transactionId
    participant.expectMsgType[VoteRequest]
    if (RebelConfig(system).rebelConfig.sync.twoPC.lockMechanism == Parallel) participant2.expectMsgType[VoteRequest]

    participant.send(coordinator, VoteCommit(transactionId, createParticipantId(participant.ref), Set()))

    // currently p2 either timeouts or send EntityTooBusy
    participant2.send(coordinator, EntityTooBusy)

    participant.expectNoMessage(manangerRetryDuration)
    participant2.ignoreMsg { case m => m.isInstanceOf[VoteRequest] }

    participant.expectMsgType[GlobalAbort]
    // TODO fix weird bug with participant2 getting VoteRequest, but should ignore it
//    participant2.expectMsgType[GlobalAbort]
  }

  // TODO reduce duplicate code
  it should "handle deadlocks, also on no response (because of stash)" in {
    system.actorOf(MessageLogger.props("target/2pc-deadlock.html"))
    addTimer()

    // 2 participants with ordering (implicit by Ordering)
    // participant 2 is locked by other transaction
    val initializeMsg = createInitializeMsg(participant.ref, participant2.ref)
    requester.send(coordinator, initializeMsg)
    val transactionId = initializeMsg.transactionId
    participant.expectMsgType[VoteRequest]
    if (RebelConfig(system).rebelConfig.sync.twoPC.lockMechanism == Parallel) participant2.expectMsgType[VoteRequest]

    participant.send(coordinator, VoteCommit(transactionId, createParticipantId(participant.ref), Set()))

    // currently p2 either timeouts or send EntityTooBusy
    // p2 time outs

    participant.expectNoMessage(manangerRetryDuration)
    participant2.ignoreMsg { case m => m.isInstanceOf[VoteRequest] }

    participant.expectMsgType[GlobalAbort]
    // TODO fix weird bug with participant2 getting VoteRequest, but should ignore it
//    participant2.expectMsgType[GlobalAbort]
  }
}

trait TransactionParticipantSpec extends Matchers with MessageLoggerAutoPilot with ScalaFutures {
  this: FlatSpecLike with TestKitBase with ImplicitSender =>
  behavior of "TransactionParticipant"

  val coordinator: TestProbe = TestProbe("coordinator")

  private def createParticipant(coordinator: ActorRef) = {
    val participantRef = system.actorOf(TransactionParticipant.props(ActorRefContactPoint(entityActor(_ => true)), ActorRefContactPoint(coordinator)), "participant")
    watch(participantRef)
    participantRef
  }

  val participant: ActorRef = createParticipant(coordinator.ref)

  it should "redeliver messages" in {
    system.actorOf(MessageLogger.props("target/redeliver-timeout.html"))

    val id = TransactionId("ID")
    val participantId = ParticipantId(tId, "p1")
    coordinator.send(participant, VoteRequest(id, participantId, myRequestSyncEvent))
    val voteCommit = VoteCommit(id, participantId, Set())
    coordinator.expectMsg(voteCommit)
    coordinator.expectMsg(150.millis, voteCommit)
    coordinator.expectMsg(450.millis, voteCommit)
    coordinator.expectMsg(600.millis, voteCommit)

    coordinator.send(participant, GlobalCommit(id))
    coordinator.expectMsg(GlobalCommitAck(id, participantId))
  }

  it should "shutdown after 2 seconds of timeouts" in {
    system.actorOf(MessageLogger.props("target/shutdown-timeout.html"))
    addTimer()

    val id = TransactionId("ID")
    val participantId = ParticipantId(tId, "p1")
    coordinator.send(participant, VoteRequest(id, participantId, myRequestSyncEvent))
    coordinator.receiveWhile(RebelConfig(system).rebelConfig.sync.twoPC.managerTimeout) {
      case VoteCommit(`id`, `participantId`, _) => ()
      case _                                 => fail()
    }
    //    0 to 10 foreach (_ => coordinator.expectMsg(VoteCommit(id, "p1")))
    expectTerminated(participant)
  }

  it should "recover and start retrying" in {
    val participantId = ParticipantId(tId, "p1")
    coordinator.send(participant, VoteRequest(tId, participantId, myRequestSyncEvent))
    coordinator.expectMsg(VoteCommit(tId, participantId, Set()))

    participant ! PoisonPill
    expectTerminated(participant)
    coordinator.expectNoMessage(1.second)

    val recoveredParticipant = createParticipant(coordinator.ref)

    coordinator.expectMsg(VoteCommit(tId, participantId, Set()))

    // Continue and kill again in next step
    coordinator.send(recoveredParticipant, GlobalCommit(tId))
    coordinator.expectMsg(GlobalCommitAck(tId, participantId))

    recoveredParticipant ! PoisonPill
    expectTerminated(recoveredParticipant)
    coordinator.expectNoMessage(1.second)

    val recoveredParticipant2 = createParticipant(coordinator.ref)
    coordinator.send(recoveredParticipant2, GlobalCommit(tId))
    coordinator.expectMsg(GlobalCommitAck(tId, participantId))

    expectTerminated(recoveredParticipant2)
  }

  /**
    * First, a participant may be waiting in its INIT state for a VOTE_REQUEST message from the coordinator.
    * If that message is not received after some time, the partici- pant will simply decide to locally abort
    * the transaction, and thus send a VOTE_ABORT message to the coordinator.
    *
    * NB This does not work here, since the participant does not know who the coordinator is. So only local abort.
    */
  ignore should "VoteAbort after timeout when waiting" in {
    //    Thread.sleep(abortTimeout.toMillis)
    //    coordinator.expectMsg(VoteAbort)
  }

  it should "locally abort after timeout when waiting" in {
    system.actorOf(MessageLogger.props("target/abort-timeout.html"))
    addTimer()

    participant ! SubscribeTransitionCallBack(self)
    expectMsgType[CurrentState[TransactionParticipant.ParticipantState]]
    //    Thread.sleep(abortTimeout.toMillis)
    // wait a little shorter to account for lag
    //    expectNoMessage(abortTimeout)
    expectMsg(Transition(participant, TransactionParticipant.Init, TransactionParticipant.Abort, None))
  }

  it should "GlobalAbortAck any message when not initialized and timed out" in {
    system.actorOf(MessageLogger.props("target/timeout-and-respond.html"))
    addTimer()

    participant ! SubscribeTransitionCallBack(self)
    expectMsgType[CurrentState[TransactionParticipant.ParticipantState]]
    expectMsg(Transition(participant, TransactionParticipant.Init, TransactionParticipant.Abort, None))

    val id = TransactionId("ID")
    val participantId = ParticipantId(id,"p1")
    coordinator.send(participant, VoteRequest(id, participantId, myRequestSyncEvent))
    coordinator.expectMsgType[GlobalAbortAck]
  }
}


class InMemTwoPhaseCommitSpec extends IsolatedCluster with TwoPhaseCommitSpec

class InMemPsacSpec extends IsolatedCluster(ConfigFactory.parseString(
  """rebel.sync.max-transactions-in-progress = 8
    |rebel.sync.command-decider = dynamic
  """.stripMargin)) with TwoPhaseCommitSpec

class CassandraTwoPhaseCommitSpec extends CassandraClusterTestKit with TwoPhaseCommitSpec with CassandraLifecycle {
  override def systemName: String = "2PC-CassandraClusterTestKit"
}

class InMemTransactionManagerSpec extends IsolatedCluster with TransactionManagerSpec

class CassandraTransactionManagerSpec extends CassandraClusterTestKit with TransactionManagerSpec with CassandraLifecycle {
  override def systemName: String = "2PC-CassandraClusterTestKit"
}

class InMemTransactionParticipantSpec extends IsolatedCluster with TransactionParticipantSpec

class CassandraTransactionParticipantSpec extends CassandraClusterTestKit with TransactionParticipantSpec with CassandraLifecycle {
  override def systemName: String = "2PC-CassandraClusterTestKit"
}

class InMemSeqLocksTwoPhaseCommitSpec
  extends IsolatedCluster(ConfigFactory.parseString("rebel.sync.two-pc.lock-mechanism=sequential")) with TwoPhaseCommitSpec
class InMemSeqLocksTransactionManagerSpec
  extends IsolatedCluster(ConfigFactory.parseString("rebel.sync.two-pc.lock-mechanism=sequential")) with TransactionManagerSpec
class InMemSeqLocksTransactionParticipantSpec
  extends IsolatedCluster(ConfigFactory.parseString("rebel.sync.two-pc.lock-mechanism=sequential")) with TransactionParticipantSpec
