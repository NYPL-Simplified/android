package org.nypl.simplified.tests.local.books.accounts

import org.nypl.simplified.tests.books.accounts.AccountProviderDescriptionCollectionParserContract
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AccountProviderDescriptionCollectionParserTest :
  AccountProviderDescriptionCollectionParserContract() {
  override val logger: Logger =
    LoggerFactory.getLogger(AccountProviderDescriptionCollectionParserTest::class.java)
}
