package org.nypl.simplified.books.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Observable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.nypl.simplified.downloader.core.DownloadAbstractListener;
import org.nypl.simplified.downloader.core.DownloadSnapshot;
import org.nypl.simplified.downloader.core.DownloaderType;
import org.nypl.simplified.http.core.HTTPAuthBasic;
import org.nypl.simplified.http.core.HTTPAuthType;
import org.nypl.simplified.http.core.HTTPResultError;
import org.nypl.simplified.http.core.HTTPResultException;
import org.nypl.simplified.http.core.HTTPResultMatcherType;
import org.nypl.simplified.http.core.HTTPResultOKType;
import org.nypl.simplified.http.core.HTTPResultToException;
import org.nypl.simplified.http.core.HTTPResultType;
import org.nypl.simplified.http.core.HTTPType;
import org.nypl.simplified.opds.core.OPDSAcquisitionFeed;
import org.nypl.simplified.opds.core.OPDSAcquisitionFeedEntry;
import org.nypl.simplified.opds.core.OPDSFeedParserType;
import org.nypl.simplified.opds.core.OPDSFeedType;
import org.nypl.simplified.opds.core.OPDSNavigationFeed;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.TreeTraverser;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.io7m.jfunctional.Option;
import com.io7m.jfunctional.OptionType;
import com.io7m.jfunctional.Pair;
import com.io7m.jfunctional.Some;
import com.io7m.jfunctional.Unit;
import com.io7m.jnull.NullCheck;

@SuppressWarnings("synthetic-access") public final class Books extends
  Observable implements BooksType
{
  private static final class DataLoadTask implements Runnable
  {
    private final BooksRegistryType           books;
    private final BooksDirectory              books_directory;
    private final BooksStatusCacheType        books_status;
    private final BooksConfiguration          config;
    private final DownloaderType              downloader;
    private final AccountDataLoadListenerType listener;
    private final AtomicBoolean               logged_in;

    public DataLoadTask(
      final BooksRegistryType in_books,
      final BooksDirectory in_books_directory,
      final BooksStatusCacheType in_books_status,
      final DownloaderType in_downloader,
      final AccountDataLoadListenerType in_listener,
      final BooksConfiguration in_config,
      final AtomicBoolean in_logged_in)
    {
      this.books = NullCheck.notNull(in_books);
      this.books_directory = NullCheck.notNull(in_books_directory);
      this.books_status = NullCheck.notNull(in_books_status);
      this.downloader = NullCheck.notNull(in_downloader);
      this.config = NullCheck.notNull(in_config);
      this.listener = NullCheck.notNull(in_listener);
      this.logged_in = NullCheck.notNull(in_logged_in);
    }

    @Override public void run()
    {
      this.logged_in.set(this.books_directory.credentialsExist());
      if (this.logged_in.get() == false) {
        try {
          this.listener.onAccountUnavailable();
        } catch (final Throwable x) {
          // Ignore
        }
        return;
      }

      final List<BookDirectory> book_list = this.books_directory.getBooks();
      for (final BookDirectory book_dir : book_list) {
        final BookID id = book_dir.getID();
        try {
          final BookSnapshot snap = book_dir.getSnapshot();
          final BookStatusType status =
            BookStatus.fromBookSnapshot(this.downloader, id, snap);
          this.books.booksStatusUpdate(id, status);
          this.listener.onAccountDataBookLoadSucceeded(id, snap);
        } catch (final Throwable e) {
          this.listener.onAccountDataBookLoadFailed(
            id,
            Option.some(e),
            e.getMessage());
        }
      }

      this.listener.onAccountDataBookLoadFinished();
    }
  }

  private static final class DataSetupTask implements Runnable
  {
    private final BooksDirectory               books_directory;
    private final BooksConfiguration           config;
    private final AccountDataSetupListenerType listener;

    public DataSetupTask(
      final BooksConfiguration in_config,
      final BooksDirectory in_books_directory,
      final AccountDataSetupListenerType in_listener)
    {
      this.books_directory = NullCheck.notNull(in_books_directory);
      this.config = NullCheck.notNull(in_config);
      this.listener = NullCheck.notNull(in_listener);
    }

    @Override public void run()
    {
      try {
        this.books_directory.create();
        this.listener.onAccountDataSetupSuccess();
      } catch (final Throwable x) {
        this.listener.onAccountDataSetupFailure(
          Option.some(x),
          x.getMessage());
      }
    }
  }

  private static final class DownloadOpenAccessTask extends
    DownloadAbstractListener implements Runnable
  {
    private final BookDirectory       book_directory;
    private final BookID              book_id;
    private final BooksDirectory      books_directory;
    private final BooksConfiguration  config;
    private final DownloaderType      downloader;
    private final BooksObservableType observable;
    private final BooksRegistryType   registry;
    private final URI                 uri;

    DownloadOpenAccessTask(
      final BookID in_book_id,
      final URI in_uri,
      final BooksConfiguration in_config,
      final BooksDirectory in_books_directory,
      final BooksObservableType in_observable,
      final BooksRegistryType in_registry,
      final DownloaderType in_downloader)
    {
      this.book_id = NullCheck.notNull(in_book_id);
      this.books_directory = NullCheck.notNull(in_books_directory);
      this.observable = NullCheck.notNull(in_observable);
      this.uri = NullCheck.notNull(in_uri);
      this.config = NullCheck.notNull(in_config);
      this.registry = NullCheck.notNull(in_registry);
      this.downloader = NullCheck.notNull(in_downloader);
      this.book_directory =
        this.books_directory.getBookDirectory(this.book_id);
    }

    private void download()
      throws IOException
    {
      final BookSnapshot snap = this.book_directory.getSnapshot();
      final Pair<AccountBarcode, AccountPIN> p =
        this.books_directory.credentialsGet();
      final AccountBarcode barcode = p.getLeft();
      final AccountPIN pin = p.getRight();

      final HTTPAuthType auth =
        new HTTPAuthBasic(barcode.toString(), pin.toString());

      final OPDSAcquisitionFeedEntry entry = snap.getEntry();
      final String title = entry.getTitle();

      final long did =
        this.downloader.downloadEnqueue(
          Option.some(auth),
          this.uri,
          title,
          this);

      this.book_directory.setDownloadID(did);
    }

    @Override public void downloadCancelled(
      final DownloadSnapshot snap)
    {
      final BookStatusCancelled status =
        new BookStatusCancelled(this.book_id, snap);
      this.registry.booksStatusUpdate(this.book_id, status);
      this.observable.booksNotifyObserversUnconditionally(status);
    }

    @Override public void downloadCleanedUp(
      final DownloadSnapshot snap)
    {

    }

    @Override public void downloadCompleted(
      final DownloadSnapshot snap)
    {
      final BookStatusDownloading status =
        new BookStatusDownloading(this.book_id, snap);
      this.registry.booksStatusUpdate(this.book_id, status);
      this.observable.booksNotifyObserversUnconditionally(status);
    }

    @Override public void downloadCompletedTake(
      final DownloadSnapshot snap,
      final File file_data)
    {
      try {
        this.book_directory.copyInBook(file_data);
        final BookStatusDone status = new BookStatusDone(this.book_id);
        this.registry.booksStatusUpdate(this.book_id, status);
        this.observable.booksNotifyObserversUnconditionally(status);
      } catch (final IOException e) {
        throw new IOError(e);
      }
    }

    @Override public void downloadCompletedTakeFailed(
      final DownloadSnapshot snap,
      final Throwable x)
    {
      final BookStatusFailed status =
        new BookStatusFailed(this.book_id, snap, Option.some(x));
      this.registry.booksStatusUpdate(this.book_id, status);
      this.observable.booksNotifyObserversUnconditionally(status);
    }

    @Override public void downloadFailed(
      final DownloadSnapshot snap,
      final Throwable e)
    {
      final BookStatusFailed status =
        new BookStatusFailed(this.book_id, snap, Option.some(e));
      this.registry.booksStatusUpdate(this.book_id, status);
      this.observable.booksNotifyObserversUnconditionally(status);
    }

    @Override public void downloadPaused(
      final DownloadSnapshot snap)
    {
      final BookStatusPaused status =
        new BookStatusPaused(this.book_id, snap);
      this.registry.booksStatusUpdate(this.book_id, status);
      this.observable.booksNotifyObserversUnconditionally(status);
    }

    @Override public void downloadReceivedData(
      final DownloadSnapshot snap)
    {
      final BookStatusDownloading status =
        new BookStatusDownloading(this.book_id, snap);
      this.registry.booksStatusUpdate(this.book_id, status);
      this.observable.booksNotifyObserversUnconditionally(status);
    }

    @Override public void downloadResumed(
      final DownloadSnapshot snap)
    {
      final BookStatusDownloading status =
        new BookStatusDownloading(this.book_id, snap);
      this.registry.booksStatusUpdate(this.book_id, status);
      this.observable.booksNotifyObserversUnconditionally(status);
    }

    @Override public void downloadStarted(
      final DownloadSnapshot snap)
    {
      final BookStatusDownloading status =
        new BookStatusDownloading(this.book_id, snap);
      this.registry.booksStatusUpdate(this.book_id, status);
      this.observable.booksNotifyObserversUnconditionally(status);
    }

    @Override public void downloadStartedReceivingData(
      final DownloadSnapshot snap)
    {
      final BookStatusDownloading status =
        new BookStatusDownloading(this.book_id, snap);
      this.registry.booksStatusUpdate(this.book_id, status);
      this.observable.booksNotifyObserversUnconditionally(status);
    }

    @Override public void run()
    {
      try {
        this.download();
      } catch (final IOException e) {
        throw new IOError(e);
      }
    }
  }

  private static final class LoginTask implements
    Runnable,
    AccountDataSetupListenerType
  {
    private final AccountBarcode           barcode;
    private final Books                    books;
    private final BooksDirectory           books_directory;
    private final BooksConfiguration       config;
    private final HTTPType                 http;
    private final AccountLoginListenerType listener;
    private final AtomicBoolean            logged_in;
    private final AccountPIN               pin;

    public LoginTask(
      final Books in_books,
      final BooksDirectory in_books_directory,
      final HTTPType in_http,
      final BooksConfiguration in_config,
      final AccountBarcode in_barcode,
      final AccountPIN in_pin,
      final AccountLoginListenerType in_listener,
      final AtomicBoolean in_logged_in)
    {
      this.books = NullCheck.notNull(in_books);
      this.books_directory = NullCheck.notNull(in_books_directory);
      this.http = NullCheck.notNull(in_http);
      this.config = NullCheck.notNull(in_config);
      this.barcode = NullCheck.notNull(in_barcode);
      this.pin = NullCheck.notNull(in_pin);
      this.listener = NullCheck.notNull(in_listener);
      this.logged_in = NullCheck.notNull(in_logged_in);
    }

    private void loginCheckCredentials()
      throws Exception
    {
      final HTTPAuthType auth =
        new HTTPAuthBasic(this.barcode.toString(), this.pin.toString());
      final HTTPResultType<Unit> r =
        this.http.head(Option.some(auth), this.config.getLoansURI());

      r.matchResult(new HTTPResultMatcherType<Unit, Unit, Exception>() {
        @Override public Unit onHTTPError(
          final HTTPResultError<Unit> e)
          throws Exception
        {
          final String m =
            NullCheck.notNull(String.format(
              "%d: %s",
              e.getStatus(),
              e.getMessage()));

          switch (e.getStatus()) {
            case HttpURLConnection.HTTP_UNAUTHORIZED:
            {
              throw new AccountAuthenticationPINRejectedError(
                "Invalid barcode or PIN");
            }
            default:
            {
              throw new IOException(m);
            }
          }
        }

        @Override public Unit onHTTPException(
          final HTTPResultException<Unit> e)
          throws Exception
        {
          throw e.getError();
        }

        @Override public Unit onHTTPOK(
          final HTTPResultOKType<Unit> e)
          throws Exception
        {
          /**
           * Credentials were accepted, write them to files.
           */

          LoginTask.this.saveCredentials(LoginTask.this.pin);
          LoginTask.this.logged_in.set(true);
          return Unit.unit();
        }
      });
    }

    @Override public void onAccountDataSetupFailure(
      final OptionType<Throwable> error,
      final String message)
    {
      this.listener.onAccountLoginFailure(error, message);
    }

    @Override public void onAccountDataSetupSuccess()
    {
      try {
        this.loginCheckCredentials();
        this.listener.onAccountLoginSuccess(this.barcode, this.pin);
      } catch (final Throwable e) {
        this.listener.onAccountLoginFailure(Option.some(e), e.getMessage());
      }
    }

    @Override public void run()
    {
      this.books.submitRunnable(new DataSetupTask(
        this.config,
        this.books_directory,
        this));
    }

    private void saveCredentials(
      final AccountPIN actual_pin)
      throws IOException
    {
      this.books_directory.credentialsSet(this.barcode, actual_pin);
    }
  }

  private static final class LogoutTask implements Runnable
  {
    private final File                      base;
    private final BooksConfiguration        config;
    private final AccountLogoutListenerType listener;
    private final AtomicBoolean             logged_in;

    public LogoutTask(
      final BooksConfiguration in_config,
      final AtomicBoolean in_logged_in,
      final AccountLogoutListenerType in_listener)
    {
      this.config = NullCheck.notNull(in_config);
      this.listener = NullCheck.notNull(in_listener);
      this.logged_in = NullCheck.notNull(in_logged_in);
      this.base = new File(this.config.getDirectory(), "data");
    }

    @Override public void run()
    {
      try {
        this.logged_in.set(false);

        if (this.base.isDirectory()) {
          final TreeTraverser<File> trav = Files.fileTreeTraverser();
          final ImmutableList<File> list =
            trav.postOrderTraversal(this.base).toList();

          for (int index = 0; index < list.size(); ++index) {
            final File file = list.get(index);
            final boolean ok = file.delete();
            if (ok == false) {
              throw new IOException("Unable to delete: " + file);
            }
          }
        } else {
          throw new IllegalStateException("Not logged in");
        }

        this.listener.onAccountLogoutSuccess();
      } catch (final Throwable e) {
        this.listener.onAccountLogoutFailure(Option.some(e), e.getMessage());
      }
    }
  }

  private static final class SyncTask implements Runnable
  {
    private static OptionType<File> makeCover(
      final HTTPType http,
      final OptionType<URI> cover_opt)
      throws Exception
    {
      if (cover_opt.isSome()) {
        final Some<URI> some = (Some<URI>) cover_opt;
        final URI cover_uri = some.get();

        final File cover_file_tmp = File.createTempFile("cover", "jpg");
        cover_file_tmp.deleteOnExit();
        SyncTask.makeCoverDownload(http, cover_file_tmp, cover_uri);
        return Option.some(cover_file_tmp);
      }

      return Option.none();
    }

    private static void makeCoverDownload(
      final HTTPType http,
      final File cover_file_tmp,
      final URI cover_uri)
      throws Exception
    {
      final OptionType<HTTPAuthType> no_auth = Option.none();
      final HTTPResultOKType<InputStream> r =
        http.get(no_auth, cover_uri, 0).matchResult(
          new HTTPResultToException<InputStream>());

      try {
        final FileOutputStream fs = new FileOutputStream(cover_file_tmp);
        try {
          ByteStreams.copy(r.getValue(), fs);
          fs.flush();
        } finally {
          fs.close();
        }
      } finally {
        r.close();
      }
    }

    private final BooksRegistryType       books;
    private final BooksDirectory          books_directory;
    private final BooksConfiguration      config;
    private final DownloaderType          downloader;
    private final OPDSFeedParserType      feed_parser;
    private final HTTPType                http;
    private final AccountSyncListenerType listener;

    public SyncTask(
      final BooksConfiguration in_config,
      final BooksRegistryType in_books,
      final BooksDirectory in_books_directory,
      final HTTPType in_http,
      final OPDSFeedParserType in_feed_parser,
      final DownloaderType in_downloader,
      final AccountSyncListenerType in_listener)
    {
      this.books = NullCheck.notNull(in_books);
      this.books_directory = NullCheck.notNull(in_books_directory);
      this.config = NullCheck.notNull(in_config);
      this.http = NullCheck.notNull(in_http);
      this.feed_parser = NullCheck.notNull(in_feed_parser);
      this.listener = NullCheck.notNull(in_listener);
      this.downloader = NullCheck.notNull(in_downloader);
    }

    @Override public void run()
    {
      try {
        this.sync();
        this.listener.onAccountSyncSuccess();
      } catch (final Throwable x) {
        this.listener.onAccountSyncFailure(Option.some(x), x.getMessage());
      }
    }

    private void sync()
      throws Exception
    {
      final Pair<AccountBarcode, AccountPIN> pair =
        this.books_directory.credentialsGet();
      final AccountBarcode barcode = pair.getLeft();
      final AccountPIN pin = pair.getRight();

      final AccountSyncListenerType in_listener = this.listener;
      final URI loans_uri = this.config.getLoansURI();

      final HTTPAuthType auth =
        new HTTPAuthBasic(barcode.toString(), pin.toString());
      final HTTPResultType<InputStream> r =
        this.http.get(Option.some(auth), this.config.getLoansURI(), 0);

      r
        .matchResult(new HTTPResultMatcherType<InputStream, Unit, Exception>() {
          @Override public Unit onHTTPError(
            final HTTPResultError<InputStream> e)
            throws Exception
          {
            final String m =
              NullCheck.notNull(String.format(
                "%d: %s",
                e.getStatus(),
                e.getMessage()));

            switch (e.getStatus()) {
              case HttpURLConnection.HTTP_UNAUTHORIZED:
              {
                in_listener.onAccountSyncAuthenticationFailure("Invalid PIN");
                return Unit.unit();
              }
              default:
              {
                throw new IOException(m);
              }
            }
          }

          @Override public Unit onHTTPException(
            final HTTPResultException<InputStream> e)
            throws Exception
          {
            throw e.getError();
          }

          @Override public Unit onHTTPOK(
            final HTTPResultOKType<InputStream> e)
            throws Exception
          {
            try {
              SyncTask.this.syncFeedEntries(loans_uri, e);
              return Unit.unit();
            } finally {
              e.close();
            }
          }
        });
    }

    private void syncFeedEntries(
      final URI loans_uri,
      final HTTPResultOKType<InputStream> r_feed)
      throws Exception
    {
      final OPDSFeedType feed =
        this.feed_parser.parse(loans_uri, r_feed.getValue());

      if (feed instanceof OPDSNavigationFeed) {
        throw new IOException(
          "Expected an acquisition feed, but received a navigation feed");
      }

      final OPDSAcquisitionFeed acq_feed = (OPDSAcquisitionFeed) feed;
      final List<OPDSAcquisitionFeedEntry> entries =
        acq_feed.getFeedEntries();

      for (final OPDSAcquisitionFeedEntry e : entries) {
        this.syncFeedEntry(NullCheck.notNull(e));
      }
    }

    private void syncFeedEntry(
      final OPDSAcquisitionFeedEntry e)
      throws Exception
    {
      final BookID book_id = BookID.newIDFromEntry(e);
      final BookDirectory book_dir =
        new BookDirectory(this.books_directory.getDirectory(), book_id);

      final OptionType<File> cover =
        SyncTask.makeCover(this.http, e.getCover());

      if (book_dir.exists() == false) {
        book_dir.create();
      }

      book_dir.setData(cover, e);

      final BookSnapshot snap = book_dir.getSnapshot();
      final BookStatusType status =
        BookStatus.fromBookSnapshot(this.downloader, book_id, snap);
      this.books.booksStatusUpdate(book_id, status);
      this.listener.onAccountSyncBook(book_id);
    }
  }

  public static BooksType newBooks(
    final ExecutorService in_exec,
    final OPDSFeedParserType in_feeds,
    final HTTPType in_http,
    final DownloaderType in_downloader,
    final BooksConfiguration in_config)
  {
    return new Books(in_exec, in_feeds, in_http, in_downloader, in_config);
  }

  private final BooksDirectory       books_directory;
  private final BooksStatusCacheType books_status;
  private final BooksConfiguration   config;
  private final File                 data_directory;
  private final DownloaderType       downloader;
  private final ExecutorService      exec;
  private final OPDSFeedParserType   feed_parser;
  private final HTTPType             http;
  private final AtomicBoolean        logged_in;
  private final List<Future<?>>      tasks;

  private Books(
    final ExecutorService in_exec,
    final OPDSFeedParserType in_feeds,
    final HTTPType in_http,
    final DownloaderType in_downloader,
    final BooksConfiguration in_config)
  {
    this.exec = NullCheck.notNull(in_exec);
    this.feed_parser = NullCheck.notNull(in_feeds);
    this.http = NullCheck.notNull(in_http);
    this.config = NullCheck.notNull(in_config);
    this.downloader = NullCheck.notNull(in_downloader);
    this.tasks = new ArrayList<Future<?>>();
    this.logged_in = new AtomicBoolean(false);
    this.books_status = BooksStatusCache.newStatusCache();
    this.data_directory = new File(this.config.getDirectory(), "data");
    this.books_directory = new BooksDirectory(this.data_directory);
  }

  @Override public boolean accountIsLoggedIn()
  {
    return this.logged_in.get();
  }

  @Override public void accountLoadBooks(
    final AccountDataLoadListenerType listener)
  {
    NullCheck.notNull(listener);
    this.submitRunnable(new DataLoadTask(
      this,
      this.books_directory,
      this.books_status,
      this.downloader,
      listener,
      this.config,
      this.logged_in));
  }

  @Override public void accountLogin(
    final AccountBarcode barcode,
    final AccountPIN pin,
    final AccountLoginListenerType listener)
  {
    NullCheck.notNull(barcode);
    NullCheck.notNull(pin);
    NullCheck.notNull(listener);

    this.submitRunnable(new LoginTask(
      this,
      this.books_directory,
      this.http,
      this.config,
      barcode,
      pin,
      listener,
      this.logged_in));
  }

  @Override public void accountLogout(
    final AccountLogoutListenerType listener)
  {
    NullCheck.notNull(listener);

    synchronized (this) {
      this.stopAllTasks();
      this.books_status.booksStatusClearAll();
      this.downloader.downloadDestroyAll();
      this.submitRunnable(new LogoutTask(
        this.config,
        this.logged_in,
        listener));
    }
  }

  @Override public void accountSync(
    final AccountSyncListenerType listener)
  {
    NullCheck.notNull(listener);
    this.submitRunnable(new SyncTask(
      this.config,
      this,
      this.books_directory,
      this.http,
      this.feed_parser,
      this.downloader,
      listener));
  }

  @Override public void bookDownloadAcknowledge(
    final BookID id)
  {
    final OptionType<BookStatusType> s_opt = this.booksStatusGet(id);
    if (s_opt.isSome()) {
      final Some<BookStatusType> some = (Some<BookStatusType>) s_opt;
      final BookStatusType status = some.get();
      if (status instanceof BookStatusWithSnapshotType) {
        final BookStatusWithSnapshotType wsnap =
          (BookStatusWithSnapshotType) status;
        this.downloader
          .downloadAcknowledge(wsnap.getSnapshot().statusGetID());
      }
    }
  }

  @Override public void bookDownloadCancel(
    final BookID id)
  {
    final OptionType<BookStatusType> s_opt = this.booksStatusGet(id);
    if (s_opt.isSome()) {
      final Some<BookStatusType> some = (Some<BookStatusType>) s_opt;
      final BookStatusType status = some.get();
      if (status instanceof BookStatusWithSnapshotType) {
        final BookStatusWithSnapshotType wsnap =
          (BookStatusWithSnapshotType) status;
        this.downloader.downloadCancel(wsnap.getSnapshot().statusGetID());
      }
    }
  }

  @Override public void bookDownloadOpenAccess(
    final BookID id,
    final String title,
    final URI uri)
  {
    NullCheck.notNull(id);
    NullCheck.notNull(title);
    NullCheck.notNull(uri);

    final OptionType<BookStatusType> s = this.books_status.booksStatusGet(id);
    if (s.isSome()) {
      this.submitRunnable(new DownloadOpenAccessTask(
        id,
        uri,
        this.config,
        this.books_directory,
        this,
        this,
        this.downloader));
    } else {
      throw new IllegalStateException("Unknown book");
    }
  }

  @Override public void booksNotifyObserversUnconditionally(
    final BookStatusType status)
  {
    super.setChanged();
    super.notifyObservers(status);
  }

  @Override public void booksStatusClearAll()
  {
    this.books_status.booksStatusClearAll();
  }

  @Override public OptionType<BookStatusType> booksStatusGet(
    final BookID id)
  {
    return this.books_status.booksStatusGet(id);
  }

  @Override public void booksStatusUpdate(
    final BookID id,
    final BookStatusType s)
  {
    this.books_status.booksStatusUpdate(id, s);
  }

  @Override public void booksStatusUpdateOwned(
    final BookID id)
  {
    this.books_status.booksStatusUpdateOwned(id);
  }

  private void stopAllTasks()
  {
    synchronized (this) {
      final Iterator<Future<?>> iter = this.tasks.iterator();
      while (iter.hasNext()) {
        try {
          final Future<?> f = iter.next();
          f.cancel(true);
          iter.remove();
        } catch (final Throwable x) {
          // Ignore
        }
      }
    }
  }

  private void submitRunnable(
    final Runnable r)
  {
    synchronized (this) {
      this.tasks.add(this.exec.submit(r));
    }
  }
}