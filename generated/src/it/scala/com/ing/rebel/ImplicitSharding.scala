//package com.ing.rebel
//
//import akka.actor.ActorSystem
//import com.ing.corebank.rebel.sharding.AccountSharding
//import com.ing.corebank.rebel.simple_transaction.Account
//import com.ing.rebel.RebelSharding.RebelExternalShardingExtension
//import io.circe.Decoder
//import squants.market.EUR
//
//import scala.reflect.ClassTag
//
//object ImplicitSharding {
//
//
//
//  def usage(): Unit = {
//    val iban: Iban = Iban("NL1")
//    val accountSharding: AccountSharding = receptionist[Account.type]
//    val deposit: RebelMessage = Account.Deposit(EUR(100))
//    accountSharding.tell(iban, deposit)
//  }
//
//
//  // TODO use functional dependencies to map `Account` -> `(Key, Command)` in order to determine which `RebelExternalShardingExtension[Key, Command]` to fetch from implicit scope?
//  // https://milessabin.com/blog/2011/07/16/fundeps-in-scala/
//  // TODO alternatively implement as in Akka-DDD, or use Akka-DDD as starting point.
//
//
//  implicit def accountSharding(implicit actorSystem: ActorSystem) : Sharding[Account.type] = new Sharding[Account.type] {
//    val sharding = AccountSharding(actorSystem)
//  }
//  trait Sharding[T] {
//    val sharding: RebelExternalShardingExtension[_,_]
//  }
//  def receptionist[T](implicit actorSystem: ActorSystem, tSharding : Sharding[T]) : AccountSharding = ??? // tSharding.sharding
//
//
//}
