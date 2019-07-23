package org.nypl.simplified.tests.books.controller

import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.io7m.jfunctional.Option
import com.io7m.jfunctional.OptionType
import com.io7m.jfunctional.Some
import org.joda.time.DateTime
import org.joda.time.Instant
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.mockito.Mockito
import org.mockito.internal.verification.Times
import org.nypl.drm.core.AdobeAdeptConnectorType
import org.nypl.drm.core.AdobeAdeptExecutorType
import org.nypl.drm.core.AdobeAdeptLoan
import org.nypl.drm.core.AdobeAdeptLoanReturnListenerType
import org.nypl.drm.core.AdobeAdeptProcedureType
import org.nypl.drm.core.AdobeDeviceID
import org.nypl.drm.core.AdobeLoanID
import org.nypl.drm.core.AdobeUserID
import org.nypl.drm.core.AdobeVendorID
import org.nypl.simplified.accounts.api.AccountAuthenticationAdobeClientToken
import org.nypl.simplified.accounts.api.AccountAuthenticationAdobePostActivationCredentials
import org.nypl.simplified.accounts.api.AccountAuthenticationAdobePreActivationCredentials
import org.nypl.simplified.accounts.api.AccountAuthenticationCredentials
import org.nypl.simplified.accounts.api.AccountBarcode
import org.nypl.simplified.accounts.api.AccountID
import org.nypl.simplified.accounts.api.AccountLoginState
import org.nypl.simplified.accounts.api.AccountPIN
import org.nypl.simplified.accounts.database.api.AccountType
import org.nypl.simplified.books.api.Book
import org.nypl.simplified.books.api.BookEvent
import org.nypl.simplified.books.api.BookFormat.BookFormatEPUB
import org.nypl.simplified.books.api.BookID
import org.nypl.simplified.books.book_database.api.BookDatabaseEntryFormatHandle.BookDatabaseEntryFormatHandleEPUB
import org.nypl.simplified.books.book_database.api.BookDatabaseEntryType
import org.nypl.simplified.books.book_database.api.BookDatabaseType
import org.nypl.simplified.books.book_database.api.BookFormats
import org.nypl.simplified.books.book_registry.BookRegistry
import org.nypl.simplified.books.book_registry.BookRegistryType
import org.nypl.simplified.books.book_registry.BookStatusRevokeErrorDetails.*
import org.nypl.simplified.books.book_registry.BookStatusRevokeErrorDetails.DRMError.*
import org.nypl.simplified.books.book_registry.BookStatusType
import org.nypl.simplified.books.bundled.api.BundledContentResolverType
import org.nypl.simplified.books.controller.BookRevokeTask
import org.nypl.simplified.downloader.core.DownloaderHTTP
import org.nypl.simplified.downloader.core.DownloaderType
import org.nypl.simplified.feeds.api.FeedLoader
import org.nypl.simplified.feeds.api.FeedLoaderType
import org.nypl.simplified.files.DirectoryUtilities
import org.nypl.simplified.opds.core.OPDSAcquisition
import org.nypl.simplified.opds.core.OPDSAcquisitionFeedEntry
import org.nypl.simplified.opds.core.OPDSAcquisitionFeedEntryParser
import org.nypl.simplified.opds.core.OPDSAvailabilityOpenAccess
import org.nypl.simplified.opds.core.OPDSFeedParser
import org.nypl.simplified.opds.core.OPDSSearchParser
import org.nypl.simplified.taskrecorder.api.TaskResult
import org.nypl.simplified.tests.MockRevokeStringResources
import org.nypl.simplified.tests.http.MockingHTTP
import org.slf4j.Logger
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Contract for the `BookRevokeTask` class that doesn't involve DRM.
 */

abstract class BookRevokeTaskAdobeDRMContract {

  @JvmField
  @Rule
  val expected = ExpectedException.none()

  val accountID =
    AccountID(UUID.fromString("46d17029-14ba-4e34-bcaa-def02713575a"))

  protected abstract val logger: Logger

  private lateinit var adobeExecutor: AdobeAdeptExecutorType
  private lateinit var adobeConnector: AdobeAdeptConnectorType
  private lateinit var executorFeeds: ListeningExecutorService
  private lateinit var executorDownloads: ListeningExecutorService
  private lateinit var executorBooks: ListeningExecutorService
  private lateinit var directoryDownloads: File
  private lateinit var directoryProfiles: File
  private lateinit var http: MockingHTTP
  private lateinit var downloader: DownloaderType
  private lateinit var bookRegistry: BookRegistryType
  private lateinit var bookEvents: MutableList<BookEvent>
  private lateinit var executorTimer: ListeningExecutorService
  private lateinit var bundledContent: BundledContentResolverType
  private lateinit var feedLoader: FeedLoaderType
  private lateinit var clock: () -> Instant
  private lateinit var cacheDirectory: File

  private val bookRevokeStrings = MockRevokeStringResources()

  @Before
  @Throws(Exception::class)
  fun setUp() {
    this.http = MockingHTTP()
    this.adobeExecutor = Mockito.mock(AdobeAdeptExecutorType::class.java)
    this.adobeConnector = Mockito.mock(AdobeAdeptConnectorType::class.java)
    this.executorDownloads = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool())
    this.executorBooks = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool())
    this.executorTimer = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool())
    this.executorFeeds = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool())
    this.directoryDownloads = DirectoryUtilities.directoryCreateTemporary()
    this.directoryProfiles = DirectoryUtilities.directoryCreateTemporary()
    this.bookEvents = Collections.synchronizedList(ArrayList())
    this.bookRegistry = BookRegistry.create()
    this.bundledContent = BundledContentResolverType { uri -> throw FileNotFoundException("missing") }
    this.cacheDirectory = File.createTempFile("book-borrow-tmp", "dir")
    this.cacheDirectory.delete()
    this.cacheDirectory.mkdirs()
    this.downloader = DownloaderHTTP.newDownloader(this.executorDownloads, this.directoryDownloads, this.http)
    this.feedLoader = this.createFeedLoader(this.executorFeeds)
    this.clock = { Instant.now() }
  }

  @After
  @Throws(Exception::class)
  fun tearDown() {
    this.executorBooks.shutdown()
    this.executorFeeds.shutdown()
    this.executorDownloads.shutdown()
    this.executorTimer.shutdown()
  }



  private fun createFeedLoader(executorFeeds: ListeningExecutorService): FeedLoaderType {
    val entryParser =
      OPDSAcquisitionFeedEntryParser.newParser(BookFormats.supportedBookMimeTypes())
    val parser =
      OPDSFeedParser.newParser(entryParser)
    val searchParser =
      OPDSSearchParser.newParser()
    val transport =
      org.nypl.simplified.feeds.api.FeedHTTPTransport.newTransport(this.http)

    return FeedLoader.create(
      exec = executorFeeds,
      parser = parser,
      searchParser = searchParser,
      transport = transport,
      bookRegistry = this.bookRegistry,
      bundledContent = this.bundledContent)
  }

  /**
   * Attempting to revoke a loan that requires DRM, but is not returnable, succeeds trivially.
   */

  @Test
  fun testRevokeDRMNonReturnable() {
    val account =
      Mockito.mock(AccountType::class.java)
    val bookDatabase =
      Mockito.mock(BookDatabaseType::class.java)
    val bookDatabaseEntry =
      Mockito.mock(BookDatabaseEntryType::class.java)
    val bookDatabaseFormatHandle =
      Mockito.mock(BookDatabaseEntryFormatHandleEPUB::class.java)
    val bookFormat =
      BookFormatEPUB(
        adobeRightsFile = null,
        adobeRights =
        AdobeAdeptLoan(
          AdobeLoanID("a6a0f12f-cae0-46fd-afc8-e52b8b024e6c"),
          ByteBuffer.allocate(32),
          false),
        file = null,
        lastReadLocation = null,
        bookmarks = listOf())

    val adobeExecutor =
      Mockito.mock(AdobeAdeptExecutorType::class.java)

    val bookId =
      BookID.create("a")

    val acquisition =
      OPDSAcquisition(
        OPDSAcquisition.Relation.ACQUISITION_BORROW,
        URI.create("http://www.example.com/0.feed"),
        Option.some("application/epub+zip"),
        listOf())

    val opdsEntryBuilder =
      OPDSAcquisitionFeedEntry.newBuilder(
        "a",
        "Title",
        DateTime.now(),
        OPDSAvailabilityOpenAccess.get(Option.none()))
    opdsEntryBuilder.addAcquisition(acquisition)

    val opdsEntry =
      opdsEntryBuilder.build()

    this.logBookEventsFor(bookId)

    val book =
      Book(
        id = bookId,
        account = this.accountID,
        cover = null,
        thumbnail = null,
        entry = opdsEntry,
        formats = listOf())

    Mockito.`when`(account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(account.bookDatabase)
      .thenReturn(bookDatabase)
    Mockito.`when`(bookDatabase.entry(bookId))
      .thenReturn(bookDatabaseEntry)
    Mockito.`when`(bookDatabaseEntry.book)
      .thenReturn(book)
    Mockito.`when`(bookDatabaseEntry.findPreferredFormatHandle())
      .thenReturn(bookDatabaseFormatHandle)
    Mockito.`when`(bookDatabaseFormatHandle.format)
      .thenReturn(bookFormat)

    val task =
      BookRevokeTask(
        account = account,
        adobeDRM = adobeExecutor,
        bookID = bookId,
        bookRegistry = this.bookRegistry,
        feedLoader = this.feedLoader,
        revokeStrings = this.bookRevokeStrings)

    val result = task.call()
    TaskDumps.dump(logger, result)

    result as TaskResult.Success
    Assert.assertEquals(Option.none<BookStatusType>(), this.bookRegistry.book(bookId))

    Mockito.verify(bookDatabaseEntry, Times(1)).delete()
    Mockito.verify(bookDatabaseFormatHandle, Times(1))
      .setAdobeRightsInformation(null)
  }

  /**
   * Attempting to revoke a loan that requires DRM, without DRM support, succeeds trivially.
   */

  @Test
  fun testRevokeDRMUnsupported() {
    val account =
      Mockito.mock(AccountType::class.java)
    val bookDatabase =
      Mockito.mock(BookDatabaseType::class.java)
    val bookDatabaseEntry =
      Mockito.mock(BookDatabaseEntryType::class.java)
    val bookDatabaseFormatHandle =
      Mockito.mock(BookDatabaseEntryFormatHandleEPUB::class.java)
    val bookFormat =
      BookFormatEPUB(
        adobeRightsFile = null,
        adobeRights =
        AdobeAdeptLoan(
          AdobeLoanID("a6a0f12f-cae0-46fd-afc8-e52b8b024e6c"),
          ByteBuffer.allocate(32),
          true),
        file = null,
        lastReadLocation = null,
        bookmarks = listOf())

    val bookId =
      BookID.create("a")

    val acquisition =
      OPDSAcquisition(
        OPDSAcquisition.Relation.ACQUISITION_BORROW,
        URI.create("http://www.example.com/0.feed"),
        Option.some("application/epub+zip"),
        listOf())

    val opdsEntryBuilder =
      OPDSAcquisitionFeedEntry.newBuilder(
        "a",
        "Title",
        DateTime.now(),
        OPDSAvailabilityOpenAccess.get(Option.none()))
    opdsEntryBuilder.addAcquisition(acquisition)

    val opdsEntry =
      opdsEntryBuilder.build()

    this.logBookEventsFor(bookId)

    val book =
      Book(
        id = bookId,
        account = this.accountID,
        cover = null,
        thumbnail = null,
        entry = opdsEntry,
        formats = listOf())

    Mockito.`when`(account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(account.bookDatabase)
      .thenReturn(bookDatabase)
    Mockito.`when`(bookDatabase.entry(bookId))
      .thenReturn(bookDatabaseEntry)
    Mockito.`when`(bookDatabaseEntry.book)
      .thenReturn(book)
    Mockito.`when`(bookDatabaseEntry.findPreferredFormatHandle())
      .thenReturn(bookDatabaseFormatHandle)
    Mockito.`when`(bookDatabaseFormatHandle.format)
      .thenReturn(bookFormat)

    val task =
      BookRevokeTask(
        account = account,
        adobeDRM = null,
        bookID = bookId,
        bookRegistry = this.bookRegistry,
        feedLoader = this.feedLoader,
        revokeStrings = this.bookRevokeStrings)

    val result = task.call()
    TaskDumps.dump(logger, result)

    result as TaskResult.Success
    Assert.assertEquals(Option.none<BookStatusType>(), this.bookRegistry.book(bookId))

    Mockito.verify(bookDatabaseEntry, Times(1)).delete()
    Mockito.verify(bookDatabaseFormatHandle, Times(1))
      .setAdobeRightsInformation(null)
  }

  /**
   * If the DRM connector says everything succeeded, and there's no revocation URI, then everything
   * succeeded.
   */

  @Test
  fun testRevokeDRMOK() {
    val account =
      Mockito.mock(AccountType::class.java)
    val bookDatabase =
      Mockito.mock(BookDatabaseType::class.java)
    val bookDatabaseEntry =
      Mockito.mock(BookDatabaseEntryType::class.java)
    val bookDatabaseFormatHandle =
      Mockito.mock(BookDatabaseEntryFormatHandleEPUB::class.java)
    val bookFormat =
      BookFormatEPUB(
        adobeRightsFile = null,
        adobeRights =
        AdobeAdeptLoan(
          AdobeLoanID("a6a0f12f-cae0-46fd-afc8-e52b8b024e6c"),
          ByteBuffer.allocate(32),
          true),
        file = null,
        lastReadLocation = null,
        bookmarks = listOf())

    val bookId =
      BookID.create("a")

    val acquisition =
      OPDSAcquisition(
        OPDSAcquisition.Relation.ACQUISITION_BORROW,
        URI.create("http://www.example.com/0.feed"),
        Option.some("application/epub+zip"),
        listOf())

    val opdsEntryBuilder =
      OPDSAcquisitionFeedEntry.newBuilder(
        "a",
        "Title",
        DateTime.now(),
        OPDSAvailabilityOpenAccess.get(Option.none()))
    opdsEntryBuilder.addAcquisition(acquisition)

    val opdsEntry =
      opdsEntryBuilder.build()

    this.logBookEventsFor(bookId)

    val book =
      Book(
        id = bookId,
        account = this.accountID,
        cover = null,
        thumbnail = null,
        entry = opdsEntry,
        formats = listOf())

    Mockito.`when`(account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(account.loginState)
      .thenReturn(AccountLoginState.AccountLoggedIn(
        AccountAuthenticationCredentials.builder(
          AccountPIN.create("pin"),
          AccountBarcode.create("barcode"))
          .setAdobeCredentials(AccountAuthenticationAdobePreActivationCredentials(
            vendorID = AdobeVendorID("NYPL"),
            clientToken = AccountAuthenticationAdobeClientToken.create("NYNYPL|536818535|b54be3a5-385b-42eb-9496-3879cb3ac3cc|TWFuIHN1ZmZlcnMgb25seSBiZWNhdXNlIGhlIHRha2VzIHNlcmlvdXNseSB3aGF0IHRoZSBnb2RzIG1hZGUgZm9yIGZ1bi4K"),
            deviceManagerURI = null,
            postActivationCredentials = AccountAuthenticationAdobePostActivationCredentials(
              AdobeDeviceID("4361a1f6-aea8-4681-ad8b-df7e6923049f"),
              AdobeUserID("2bb42d71-42aa-4eb7-b8af-366171adcae8"))
          )).build()
      ))

    Mockito.`when`(account.bookDatabase)
      .thenReturn(bookDatabase)
    Mockito.`when`(bookDatabase.entry(bookId))
      .thenReturn(bookDatabaseEntry)
    Mockito.`when`(bookDatabaseEntry.book)
      .thenReturn(book)
    Mockito.`when`(bookDatabaseEntry.findPreferredFormatHandle())
      .thenReturn(bookDatabaseFormatHandle)
    Mockito.`when`(bookDatabaseFormatHandle.format)
      .thenReturn(bookFormat)

    /*
     * When the code tells the connector to return the loan, it succeeds if the connector reports
     * success.
     */

    Mockito.`when`(this.adobeConnector.loanReturn(
      this.anyNonNull(),
      this.anyNonNull(),
      this.anyNonNull()
    )).then { invocation ->
      val receiver = invocation.arguments[0] as AdobeAdeptLoanReturnListenerType
      receiver.onLoanReturnSuccess()
      Unit
    }

    Mockito.`when`(this.adobeExecutor.execute(anyNonNull()))
      .then { invocation ->
        val procedure = invocation.arguments[0] as AdobeAdeptProcedureType
        procedure.executeWith(this.adobeConnector)
      }

    val task =
      BookRevokeTask(
        account = account,
        adobeDRM = adobeExecutor,
        bookID = bookId,
        bookRegistry = this.bookRegistry,
        feedLoader = this.feedLoader,
        revokeStrings = this.bookRevokeStrings)

    val result = task.call()
    TaskDumps.dump(logger, result)

    result as TaskResult.Success
    Assert.assertEquals(Option.none<BookStatusType>(), this.bookRegistry.book(bookId))

    Mockito.verify(bookDatabaseEntry, Times(1)).delete()
    Mockito.verify(bookDatabaseFormatHandle, Times(1))
      .setAdobeRightsInformation(null)
  }

  /**
   * If the DRM connector doesn't respond, then the revocation fails.
   */

  @Test
  fun testRevokeDRMDidNothing() {
    val account =
      Mockito.mock(AccountType::class.java)
    val bookDatabase =
      Mockito.mock(BookDatabaseType::class.java)
    val bookDatabaseEntry =
      Mockito.mock(BookDatabaseEntryType::class.java)
    val bookDatabaseFormatHandle =
      Mockito.mock(BookDatabaseEntryFormatHandleEPUB::class.java)
    val bookFormat =
      BookFormatEPUB(
        adobeRightsFile = null,
        adobeRights =
        AdobeAdeptLoan(
          AdobeLoanID("a6a0f12f-cae0-46fd-afc8-e52b8b024e6c"),
          ByteBuffer.allocate(32),
          true),
        file = null,
        lastReadLocation = null,
        bookmarks = listOf())

    val bookId =
      BookID.create("a")

    val acquisition =
      OPDSAcquisition(
        OPDSAcquisition.Relation.ACQUISITION_BORROW,
        URI.create("http://www.example.com/0.feed"),
        Option.some("application/epub+zip"),
        listOf())

    val opdsEntryBuilder =
      OPDSAcquisitionFeedEntry.newBuilder(
        "a",
        "Title",
        DateTime.now(),
        OPDSAvailabilityOpenAccess.get(Option.none()))
    opdsEntryBuilder.addAcquisition(acquisition)

    val opdsEntry =
      opdsEntryBuilder.build()

    this.logBookEventsFor(bookId)

    val book =
      Book(
        id = bookId,
        account = this.accountID,
        cover = null,
        thumbnail = null,
        entry = opdsEntry,
        formats = listOf())

    Mockito.`when`(account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(account.loginState)
      .thenReturn(AccountLoginState.AccountLoggedIn(
        AccountAuthenticationCredentials.builder(
          AccountPIN.create("pin"),
          AccountBarcode.create("barcode"))
          .setAdobeCredentials(AccountAuthenticationAdobePreActivationCredentials(
            vendorID = AdobeVendorID("NYPL"),
            clientToken = AccountAuthenticationAdobeClientToken.create("NYNYPL|536818535|b54be3a5-385b-42eb-9496-3879cb3ac3cc|TWFuIHN1ZmZlcnMgb25seSBiZWNhdXNlIGhlIHRha2VzIHNlcmlvdXNseSB3aGF0IHRoZSBnb2RzIG1hZGUgZm9yIGZ1bi4K"),
            deviceManagerURI = null,
            postActivationCredentials = AccountAuthenticationAdobePostActivationCredentials(
              AdobeDeviceID("4361a1f6-aea8-4681-ad8b-df7e6923049f"),
              AdobeUserID("2bb42d71-42aa-4eb7-b8af-366171adcae8"))
          )).build()
      ))

    Mockito.`when`(account.bookDatabase)
      .thenReturn(bookDatabase)
    Mockito.`when`(bookDatabase.entry(bookId))
      .thenReturn(bookDatabaseEntry)
    Mockito.`when`(bookDatabaseEntry.book)
      .thenReturn(book)
    Mockito.`when`(bookDatabaseEntry.findPreferredFormatHandle())
      .thenReturn(bookDatabaseFormatHandle)
    Mockito.`when`(bookDatabaseFormatHandle.format)
      .thenReturn(bookFormat)

    /*
     * When the code tells the connector to return the loan, it fails if the connector does nothing.
     */

    Mockito.`when`(this.adobeConnector.loanReturn(
      this.anyNonNull(),
      this.anyNonNull(),
      this.anyNonNull()
    )).then { invocation ->
      val receiver = invocation.arguments[0] as AdobeAdeptLoanReturnListenerType
      Unit
    }

    Mockito.`when`(this.adobeExecutor.execute(anyNonNull()))
      .then { invocation ->
        val procedure = invocation.arguments[0] as AdobeAdeptProcedureType
        procedure.executeWith(this.adobeConnector)
      }

    val task =
      BookRevokeTask(
        account = account,
        adobeDRM = adobeExecutor,
        bookID = bookId,
        bookRegistry = this.bookRegistry,
        feedLoader = this.feedLoader,
        revokeStrings = this.bookRevokeStrings)

    val result = task.call()
    TaskDumps.dump(logger, result)
    result as TaskResult.Failure

    Mockito.verify(bookDatabaseEntry, Times(0)).delete()
    Mockito.verify(bookDatabaseFormatHandle, Times(0))
      .setAdobeRightsInformation(null)
  }

  /**
   * If the DRM connector crashes, then the revocation fails.
   */

  @Test
  fun testRevokeDRMRaisedException() {
    val account =
      Mockito.mock(AccountType::class.java)
    val bookDatabase =
      Mockito.mock(BookDatabaseType::class.java)
    val bookDatabaseEntry =
      Mockito.mock(BookDatabaseEntryType::class.java)
    val bookDatabaseFormatHandle =
      Mockito.mock(BookDatabaseEntryFormatHandleEPUB::class.java)
    val bookFormat =
      BookFormatEPUB(
        adobeRightsFile = null,
        adobeRights =
        AdobeAdeptLoan(
          AdobeLoanID("a6a0f12f-cae0-46fd-afc8-e52b8b024e6c"),
          ByteBuffer.allocate(32),
          true),
        file = null,
        lastReadLocation = null,
        bookmarks = listOf())

    val bookId =
      BookID.create("a")

    val acquisition =
      OPDSAcquisition(
        OPDSAcquisition.Relation.ACQUISITION_BORROW,
        URI.create("http://www.example.com/0.feed"),
        Option.some("application/epub+zip"),
        listOf())

    val opdsEntryBuilder =
      OPDSAcquisitionFeedEntry.newBuilder(
        "a",
        "Title",
        DateTime.now(),
        OPDSAvailabilityOpenAccess.get(Option.none()))
    opdsEntryBuilder.addAcquisition(acquisition)

    val opdsEntry =
      opdsEntryBuilder.build()

    this.logBookEventsFor(bookId)

    val book =
      Book(
        id = bookId,
        account = this.accountID,
        cover = null,
        thumbnail = null,
        entry = opdsEntry,
        formats = listOf())

    Mockito.`when`(account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(account.loginState)
      .thenReturn(AccountLoginState.AccountLoggedIn(
        AccountAuthenticationCredentials.builder(
          AccountPIN.create("pin"),
          AccountBarcode.create("barcode"))
          .setAdobeCredentials(AccountAuthenticationAdobePreActivationCredentials(
            vendorID = AdobeVendorID("NYPL"),
            clientToken = AccountAuthenticationAdobeClientToken.create("NYNYPL|536818535|b54be3a5-385b-42eb-9496-3879cb3ac3cc|TWFuIHN1ZmZlcnMgb25seSBiZWNhdXNlIGhlIHRha2VzIHNlcmlvdXNseSB3aGF0IHRoZSBnb2RzIG1hZGUgZm9yIGZ1bi4K"),
            deviceManagerURI = null,
            postActivationCredentials = AccountAuthenticationAdobePostActivationCredentials(
              AdobeDeviceID("4361a1f6-aea8-4681-ad8b-df7e6923049f"),
              AdobeUserID("2bb42d71-42aa-4eb7-b8af-366171adcae8"))
          )).build()
      ))

    Mockito.`when`(account.bookDatabase)
      .thenReturn(bookDatabase)
    Mockito.`when`(bookDatabase.entry(bookId))
      .thenReturn(bookDatabaseEntry)
    Mockito.`when`(bookDatabaseEntry.book)
      .thenReturn(book)
    Mockito.`when`(bookDatabaseEntry.findPreferredFormatHandle())
      .thenReturn(bookDatabaseFormatHandle)
    Mockito.`when`(bookDatabaseFormatHandle.format)
      .thenReturn(bookFormat)

    /*
     * When the code tells the connector to return the loan, it fails if the connector crashes.
     */

    Mockito.`when`(this.adobeConnector.loanReturn(
      this.anyNonNull(),
      this.anyNonNull(),
      this.anyNonNull()
    )).then { invocation ->
      throw IOException("I/O error")
    }

    Mockito.`when`(this.adobeExecutor.execute(anyNonNull()))
      .then { invocation ->
        val procedure = invocation.arguments[0] as AdobeAdeptProcedureType
        procedure.executeWith(this.adobeConnector)
      }

    val task =
      BookRevokeTask(
        account = account,
        adobeDRM = adobeExecutor,
        bookID = bookId,
        bookRegistry = this.bookRegistry,
        feedLoader = this.feedLoader,
        revokeStrings = this.bookRevokeStrings)

    val result = task.call()
    TaskDumps.dump(logger, result)
    result as TaskResult.Failure

    Mockito.verify(bookDatabaseEntry, Times(0)).delete()
    Mockito.verify(bookDatabaseFormatHandle, Times(0))
      .setAdobeRightsInformation(null)
  }

  /**
   * If the DRM connector raises an error code, then the revocation fails.
   */

  @Test
  fun testRevokeDRMRaisedErrorCode() {
    val account =
      Mockito.mock(AccountType::class.java)
    val bookDatabase =
      Mockito.mock(BookDatabaseType::class.java)
    val bookDatabaseEntry =
      Mockito.mock(BookDatabaseEntryType::class.java)
    val bookDatabaseFormatHandle =
      Mockito.mock(BookDatabaseEntryFormatHandleEPUB::class.java)
    val bookFormat =
      BookFormatEPUB(
        adobeRightsFile = null,
        adobeRights =
        AdobeAdeptLoan(
          AdobeLoanID("a6a0f12f-cae0-46fd-afc8-e52b8b024e6c"),
          ByteBuffer.allocate(32),
          true),
        file = null,
        lastReadLocation = null,
        bookmarks = listOf())

    val bookId =
      BookID.create("a")

    val acquisition =
      OPDSAcquisition(
        OPDSAcquisition.Relation.ACQUISITION_BORROW,
        URI.create("http://www.example.com/0.feed"),
        Option.some("application/epub+zip"),
        listOf())

    val opdsEntryBuilder =
      OPDSAcquisitionFeedEntry.newBuilder(
        "a",
        "Title",
        DateTime.now(),
        OPDSAvailabilityOpenAccess.get(Option.none()))
    opdsEntryBuilder.addAcquisition(acquisition)

    val opdsEntry =
      opdsEntryBuilder.build()

    this.logBookEventsFor(bookId)

    val book =
      Book(
        id = bookId,
        account = this.accountID,
        cover = null,
        thumbnail = null,
        entry = opdsEntry,
        formats = listOf())

    Mockito.`when`(account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(account.loginState)
      .thenReturn(AccountLoginState.AccountLoggedIn(
        AccountAuthenticationCredentials.builder(
          AccountPIN.create("pin"),
          AccountBarcode.create("barcode"))
          .setAdobeCredentials(AccountAuthenticationAdobePreActivationCredentials(
            vendorID = AdobeVendorID("NYPL"),
            clientToken = AccountAuthenticationAdobeClientToken.create("NYNYPL|536818535|b54be3a5-385b-42eb-9496-3879cb3ac3cc|TWFuIHN1ZmZlcnMgb25seSBiZWNhdXNlIGhlIHRha2VzIHNlcmlvdXNseSB3aGF0IHRoZSBnb2RzIG1hZGUgZm9yIGZ1bi4K"),
            deviceManagerURI = null,
            postActivationCredentials = AccountAuthenticationAdobePostActivationCredentials(
              AdobeDeviceID("4361a1f6-aea8-4681-ad8b-df7e6923049f"),
              AdobeUserID("2bb42d71-42aa-4eb7-b8af-366171adcae8"))
          )).build()
      ))

    Mockito.`when`(account.bookDatabase)
      .thenReturn(bookDatabase)
    Mockito.`when`(bookDatabase.entry(bookId))
      .thenReturn(bookDatabaseEntry)
    Mockito.`when`(bookDatabaseEntry.book)
      .thenReturn(book)
    Mockito.`when`(bookDatabaseEntry.findPreferredFormatHandle())
      .thenReturn(bookDatabaseFormatHandle)
    Mockito.`when`(bookDatabaseFormatHandle.format)
      .thenReturn(bookFormat)

    /*
     * When the code tells the connector to return the loan, it fails if the connector fails.
     */

    Mockito.`when`(this.adobeConnector.loanReturn(
      this.anyNonNull(),
      this.anyNonNull(),
      this.anyNonNull()
    )).then { invocation ->
      val receiver = invocation.arguments[0] as AdobeAdeptLoanReturnListenerType
      receiver.onLoanReturnFailure("E_DEFECTIVE")
      Unit
    }

    Mockito.`when`(this.adobeExecutor.execute(anyNonNull()))
      .then { invocation ->
        val procedure = invocation.arguments[0] as AdobeAdeptProcedureType
        procedure.executeWith(this.adobeConnector)
      }

    val task =
      BookRevokeTask(
        account = account,
        adobeDRM = adobeExecutor,
        bookID = bookId,
        bookRegistry = this.bookRegistry,
        feedLoader = this.feedLoader,
        revokeStrings = this.bookRevokeStrings)

    val result = task.call()
    TaskDumps.dump(logger, result)
    result as TaskResult.Failure

    Assert.assertEquals(
      DRMFailure("Adobe ACS", "E_DEFECTIVE"),
      result.errors().last())

    Mockito.verify(bookDatabaseEntry, Times(0)).delete()
    Mockito.verify(bookDatabaseFormatHandle, Times(0))
      .setAdobeRightsInformation(null)
  }

  /**
   * If the device is not activated, then the revocation fails.
   */

  @Test
  fun testRevokeDRMNotActivated() {
    val account =
      Mockito.mock(AccountType::class.java)
    val bookDatabase =
      Mockito.mock(BookDatabaseType::class.java)
    val bookDatabaseEntry =
      Mockito.mock(BookDatabaseEntryType::class.java)
    val bookDatabaseFormatHandle =
      Mockito.mock(BookDatabaseEntryFormatHandleEPUB::class.java)
    val bookFormat =
      BookFormatEPUB(
        adobeRightsFile = null,
        adobeRights =
        AdobeAdeptLoan(
          AdobeLoanID("a6a0f12f-cae0-46fd-afc8-e52b8b024e6c"),
          ByteBuffer.allocate(32),
          true),
        file = null,
        lastReadLocation = null,
        bookmarks = listOf())

    val bookId =
      BookID.create("a")

    val acquisition =
      OPDSAcquisition(
        OPDSAcquisition.Relation.ACQUISITION_BORROW,
        URI.create("http://www.example.com/0.feed"),
        Option.some("application/epub+zip"),
        listOf())

    val opdsEntryBuilder =
      OPDSAcquisitionFeedEntry.newBuilder(
        "a",
        "Title",
        DateTime.now(),
        OPDSAvailabilityOpenAccess.get(Option.none()))
    opdsEntryBuilder.addAcquisition(acquisition)

    val opdsEntry =
      opdsEntryBuilder.build()

    this.logBookEventsFor(bookId)

    val book =
      Book(
        id = bookId,
        account = this.accountID,
        cover = null,
        thumbnail = null,
        entry = opdsEntry,
        formats = listOf())

    Mockito.`when`(account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(account.loginState)
      .thenReturn(AccountLoginState.AccountLoggedIn(
        AccountAuthenticationCredentials.builder(
          AccountPIN.create("pin"),
          AccountBarcode.create("barcode"))
          .setAdobeCredentials(AccountAuthenticationAdobePreActivationCredentials(
            vendorID = AdobeVendorID("NYPL"),
            clientToken = AccountAuthenticationAdobeClientToken.create("NYNYPL|536818535|b54be3a5-385b-42eb-9496-3879cb3ac3cc|TWFuIHN1ZmZlcnMgb25seSBiZWNhdXNlIGhlIHRha2VzIHNlcmlvdXNseSB3aGF0IHRoZSBnb2RzIG1hZGUgZm9yIGZ1bi4K"),
            deviceManagerURI = null,
            postActivationCredentials = null))
          .build()
          ))

    Mockito.`when`(account.bookDatabase)
      .thenReturn(bookDatabase)
    Mockito.`when`(bookDatabase.entry(bookId))
      .thenReturn(bookDatabaseEntry)
    Mockito.`when`(bookDatabaseEntry.book)
      .thenReturn(book)
    Mockito.`when`(bookDatabaseEntry.findPreferredFormatHandle())
      .thenReturn(bookDatabaseFormatHandle)
    Mockito.`when`(bookDatabaseFormatHandle.format)
      .thenReturn(bookFormat)

    val task =
      BookRevokeTask(
        account = account,
        adobeDRM = this.adobeExecutor,
        bookID = bookId,
        bookRegistry = this.bookRegistry,
        feedLoader = this.feedLoader,
        revokeStrings = this.bookRevokeStrings)

    val result = task.call()
    TaskDumps.dump(logger, result)
    result as TaskResult.Failure

    Assert.assertEquals(
      DRMDeviceNotActive("Adobe ACS"),
      result.errors().last())

    Mockito.verify(bookDatabaseEntry, Times(0)).delete()
    Mockito.verify(bookDatabaseFormatHandle, Times(0))
      .setAdobeRightsInformation(null)
  }

  /**
   * If the user is not authenticated, then the revocation fails.
   */

  @Test
  fun testRevokeDRMNotAuthenticated() {
    val account =
      Mockito.mock(AccountType::class.java)
    val bookDatabase =
      Mockito.mock(BookDatabaseType::class.java)
    val bookDatabaseEntry =
      Mockito.mock(BookDatabaseEntryType::class.java)
    val bookDatabaseFormatHandle =
      Mockito.mock(BookDatabaseEntryFormatHandleEPUB::class.java)
    val bookFormat =
      BookFormatEPUB(
        adobeRightsFile = null,
        adobeRights =
        AdobeAdeptLoan(
          AdobeLoanID("a6a0f12f-cae0-46fd-afc8-e52b8b024e6c"),
          ByteBuffer.allocate(32),
          true),
        file = null,
        lastReadLocation = null,
        bookmarks = listOf())

    val bookId =
      BookID.create("a")

    val acquisition =
      OPDSAcquisition(
        OPDSAcquisition.Relation.ACQUISITION_BORROW,
        URI.create("http://www.example.com/0.feed"),
        Option.some("application/epub+zip"),
        listOf())

    val opdsEntryBuilder =
      OPDSAcquisitionFeedEntry.newBuilder(
        "a",
        "Title",
        DateTime.now(),
        OPDSAvailabilityOpenAccess.get(Option.none()))
    opdsEntryBuilder.addAcquisition(acquisition)

    val opdsEntry =
      opdsEntryBuilder.build()

    this.logBookEventsFor(bookId)

    val book =
      Book(
        id = bookId,
        account = this.accountID,
        cover = null,
        thumbnail = null,
        entry = opdsEntry,
        formats = listOf())

    Mockito.`when`(account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(account.loginState)
      .thenReturn(AccountLoginState.AccountNotLoggedIn)

    Mockito.`when`(account.bookDatabase)
      .thenReturn(bookDatabase)
    Mockito.`when`(bookDatabase.entry(bookId))
      .thenReturn(bookDatabaseEntry)
    Mockito.`when`(bookDatabaseEntry.book)
      .thenReturn(book)
    Mockito.`when`(bookDatabaseEntry.findPreferredFormatHandle())
      .thenReturn(bookDatabaseFormatHandle)
    Mockito.`when`(bookDatabaseFormatHandle.format)
      .thenReturn(bookFormat)

    val task =
      BookRevokeTask(
        account = account,
        adobeDRM = this.adobeExecutor,
        bookID = bookId,
        bookRegistry = this.bookRegistry,
        feedLoader = this.feedLoader,
        revokeStrings = this.bookRevokeStrings)

    val result = task.call()
    TaskDumps.dump(logger, result)
    result as TaskResult.Failure

    Assert.assertEquals(
      NoCredentialsAvailable,
      result.errors().last())

    Mockito.verify(bookDatabaseEntry, Times(0)).delete()
    Mockito.verify(bookDatabaseFormatHandle, Times(0))
      .setAdobeRightsInformation(null)
  }

  /**
   * If the DRM connector fails to delete credentials, revocation fails.
   */

  @Test
  fun testRevokeDRMDeleteCredentials() {
    val account =
      Mockito.mock(AccountType::class.java)
    val bookDatabase =
      Mockito.mock(BookDatabaseType::class.java)
    val bookDatabaseEntry =
      Mockito.mock(BookDatabaseEntryType::class.java)
    val bookDatabaseFormatHandle =
      Mockito.mock(BookDatabaseEntryFormatHandleEPUB::class.java)
    val bookFormat =
      BookFormatEPUB(
        adobeRightsFile = null,
        adobeRights =
        AdobeAdeptLoan(
          AdobeLoanID("a6a0f12f-cae0-46fd-afc8-e52b8b024e6c"),
          ByteBuffer.allocate(32),
          true),
        file = null,
        lastReadLocation = null,
        bookmarks = listOf())

    val bookId =
      BookID.create("a")

    val acquisition =
      OPDSAcquisition(
        OPDSAcquisition.Relation.ACQUISITION_BORROW,
        URI.create("http://www.example.com/0.feed"),
        Option.some("application/epub+zip"),
        listOf())

    val opdsEntryBuilder =
      OPDSAcquisitionFeedEntry.newBuilder(
        "a",
        "Title",
        DateTime.now(),
        OPDSAvailabilityOpenAccess.get(Option.none()))
    opdsEntryBuilder.addAcquisition(acquisition)

    val opdsEntry =
      opdsEntryBuilder.build()

    this.logBookEventsFor(bookId)

    val book =
      Book(
        id = bookId,
        account = this.accountID,
        cover = null,
        thumbnail = null,
        entry = opdsEntry,
        formats = listOf())

    Mockito.`when`(account.id)
      .thenReturn(this.accountID)
    Mockito.`when`(account.loginState)
      .thenReturn(AccountLoginState.AccountLoggedIn(
        AccountAuthenticationCredentials.builder(
          AccountPIN.create("pin"),
          AccountBarcode.create("barcode"))
          .setAdobeCredentials(AccountAuthenticationAdobePreActivationCredentials(
            vendorID = AdobeVendorID("NYPL"),
            clientToken = AccountAuthenticationAdobeClientToken.create("NYNYPL|536818535|b54be3a5-385b-42eb-9496-3879cb3ac3cc|TWFuIHN1ZmZlcnMgb25seSBiZWNhdXNlIGhlIHRha2VzIHNlcmlvdXNseSB3aGF0IHRoZSBnb2RzIG1hZGUgZm9yIGZ1bi4K"),
            deviceManagerURI = null,
            postActivationCredentials = AccountAuthenticationAdobePostActivationCredentials(
              AdobeDeviceID("4361a1f6-aea8-4681-ad8b-df7e6923049f"),
              AdobeUserID("2bb42d71-42aa-4eb7-b8af-366171adcae8"))
          )).build()
      ))

    Mockito.`when`(account.bookDatabase)
      .thenReturn(bookDatabase)
    Mockito.`when`(bookDatabase.entry(bookId))
      .thenReturn(bookDatabaseEntry)
    Mockito.`when`(bookDatabaseEntry.book)
      .thenReturn(book)
    Mockito.`when`(bookDatabaseEntry.findPreferredFormatHandle())
      .thenReturn(bookDatabaseFormatHandle)
    Mockito.`when`(bookDatabaseFormatHandle.format)
      .thenReturn(bookFormat)
    Mockito.`when`(bookDatabaseFormatHandle.setAdobeRightsInformation(Mockito.any()))
      .then {
        throw IOException("I/O error")
      }

    /*
     * When the code tells the connector to return the loan, it succeeds if the connector reports
     * success.
     */

    Mockito.`when`(this.adobeConnector.loanReturn(
      this.anyNonNull(),
      this.anyNonNull(),
      this.anyNonNull()
    )).then { invocation ->
      val receiver = invocation.arguments[0] as AdobeAdeptLoanReturnListenerType
      receiver.onLoanReturnSuccess()
      Unit
    }

    Mockito.`when`(this.adobeExecutor.execute(anyNonNull()))
      .then { invocation ->
        val procedure = invocation.arguments[0] as AdobeAdeptProcedureType
        procedure.executeWith(this.adobeConnector)
      }

    val task =
      BookRevokeTask(
        account = account,
        adobeDRM = adobeExecutor,
        bookID = bookId,
        bookRegistry = this.bookRegistry,
        feedLoader = this.feedLoader,
        revokeStrings = this.bookRevokeStrings)

    val result = task.call()
    TaskDumps.dump(logger, result)
    result as TaskResult.Failure

    Assert.assertEquals(
      IOException::class.java,
      result.steps.last().resolution.exception!!::class.java)

    Mockito.verify(bookDatabaseEntry, Times(0)).delete()
    Mockito.verify(bookDatabaseFormatHandle, Times(1))
      .setAdobeRightsInformation(null)
  }

  private fun <T> optionUnsafe(opt: OptionType<T>): T {
    return if (opt is Some<T>) {
      opt.get()
    } else {
      throw IllegalStateException("Expected something, got nothing!")
    }
  }

  private fun <T> anyNonNull(): T =
    Mockito.argThat { x -> x != null }

  private fun logBookEventsFor(bookId: BookID?) {
    this.bookRegistry.bookEvents().subscribe {
      this.bookRegistry.bookStatus(bookId).map_ { status ->
        this.logger.debug("status: {}", status)
      }
    }
  }

  private fun resource(file: String): InputStream {
    return BookRevokeTaskAdobeDRMContract::class.java.getResourceAsStream(file)
  }

  @Throws(IOException::class)
  private fun resourceSize(file: String): Long {
    var total = 0L
    val buffer = ByteArray(8192)
    this.resource(file).use { stream ->
      while (true) {
        val r = stream.read(buffer)
        if (r <= 0) {
          break
        }
        total += r.toLong()
      }
    }
    return total
  }
}