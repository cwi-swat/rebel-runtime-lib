package com.ing.rebel.consistency

import com.ing.corebank.rebel.simple_transaction.{Account, Transaction}

object AccountConsistencyChecker
  extends ConsistencyChecker[Account.type] {
  override val persistenceIdFilter: String = Account.label
}

object TransactionConsistencyChecker
  extends ConsistencyChecker[Transaction.type] {
  override val persistenceIdFilter: String = Transaction.label
}

