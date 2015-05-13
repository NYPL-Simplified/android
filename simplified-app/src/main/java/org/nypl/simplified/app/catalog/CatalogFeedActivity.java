package org.nypl.simplified.app.catalog;

import java.net.URI;
import java.util.Calendar;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;

import org.nypl.simplified.app.R;
import org.nypl.simplified.app.Simplified;
import org.nypl.simplified.app.SimplifiedActivity;
import org.nypl.simplified.app.SimplifiedCatalogAppServicesType;
import org.nypl.simplified.app.utilities.LogUtilities;
import org.nypl.simplified.app.utilities.UIThread;
import org.nypl.simplified.assertions.Assertions;
import org.nypl.simplified.books.core.BookFeedListenerType;
import org.nypl.simplified.books.core.BooksType;
import org.nypl.simplified.books.core.FeedBlock;
import org.nypl.simplified.books.core.FeedEntryOPDS;
import org.nypl.simplified.books.core.FeedLoaderListenerType;
import org.nypl.simplified.books.core.FeedLoaderType;
import org.nypl.simplified.books.core.FeedMatcherType;
import org.nypl.simplified.books.core.FeedType;
import org.nypl.simplified.books.core.FeedWithBlocks;
import org.nypl.simplified.books.core.FeedWithoutBlocks;
import org.nypl.simplified.http.core.URIQueryBuilder;
import org.nypl.simplified.opds.core.OPDSSearchLink;
import org.nypl.simplified.stack.ImmutableStack;
import org.slf4j.Logger;

import android.app.ActionBar;
import android.app.Activity;
import android.app.FragmentManager;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.SearchView.OnQueryTextListener;

import com.io7m.jfunctional.OptionType;
import com.io7m.jfunctional.Some;
import com.io7m.jfunctional.Unit;
import com.io7m.jnull.NullCheck;
import com.io7m.jnull.Nullable;
import com.io7m.junreachable.UnreachableCodeException;

@SuppressWarnings("synthetic-access") public class CatalogFeedActivity extends
  CatalogActivity implements
  BookFeedListenerType,
  FeedMatcherType<Unit, UnreachableCodeException>,
  FeedLoaderListenerType
{
  /**
   * A handler for OpenSearch 1.1 searches.
   */

  private final class OpenSearchQueryHandler implements OnQueryTextListener
  {
    private final CatalogFeedArgumentsType args;
    private final URI                      base;

    OpenSearchQueryHandler(
      final CatalogFeedArgumentsType in_args,
      final URI in_base)
    {
      this.args = NullCheck.notNull(in_args);
      this.base = NullCheck.notNull(in_base);
    }

    @Override public boolean onQueryTextChange(
      final @Nullable String s)
    {
      return true;
    }

    @Override public boolean onQueryTextSubmit(
      final @Nullable String query)
    {
      final String qnn = NullCheck.notNull(query);

      final SortedMap<String, String> parameters =
        new TreeMap<String, String>();
      parameters.put("q", qnn);
      final URI target = URIQueryBuilder.encodeQuery(this.base, parameters);

      final CatalogFeedActivity cfa = CatalogFeedActivity.this;
      final FeedType f = NullCheck.notNull(cfa.feed);

      final ImmutableStack<CatalogUpStackEntry> us =
        cfa.newUpStack(f.getFeedURI(), this.args.getTitle());

      final CatalogFeedArgumentsRemote new_args =
        new CatalogFeedArgumentsRemote(false, us, "Search", target);
      CatalogFeedActivity.startNewActivity(cfa, new_args);
      return true;
    }
  }

  private static final String CATALOG_ARGS;
  private static final String LIST_STATE_ID;
  private static final Logger LOG;

  static {
    LOG = LogUtilities.getLog(CatalogFeedActivity.class);
  }

  static {
    CATALOG_ARGS = "org.nypl.simplified.app.CatalogFeedActivity.arguments";
    LIST_STATE_ID =
      "org.nypl.simplified.app.CatalogFeedActivity.list_view_state";
  }

  public static void setActivityArguments(
    final Bundle b,
    final CatalogFeedArgumentsType in_args)
  {
    NullCheck.notNull(b);
    NullCheck.notNull(in_args);

    b.putSerializable(CatalogFeedActivity.CATALOG_ARGS, in_args);

    in_args
      .matchArguments(new CatalogFeedArgumentsMatcherType<Unit, UnreachableCodeException>() {
        @Override public Unit onFeedArgumentsLocalBooks(
          final CatalogFeedArgumentsLocalBooks c)
        {
          SimplifiedActivity.setActivityArguments(b, false);
          final ImmutableStack<CatalogUpStackEntry> empty =
            ImmutableStack.empty();
          CatalogActivity.setActivityArguments(b, NullCheck.notNull(empty));
          return Unit.unit();
        }

        @Override public Unit onFeedArgumentsRemote(
          final CatalogFeedArgumentsRemote c)
        {
          SimplifiedActivity.setActivityArguments(b, c.isDrawerOpen());
          CatalogActivity.setActivityArguments(b, c.getUpStack());
          return Unit.unit();
        }
      });
  }

  /**
   * Start a new catalog feed activity, assuming that the user came from
   * <tt>from</tt>, with up stack <tt>up_stack</tt>, attempting to load the
   * feed at <tt>target</tt>.
   *
   * @param from
   *          The previous activity
   * @param up_stack
   *          The up stack for the new activity
   * @param title
   *          The title of the feed
   * @param target
   *          The URI of the feed
   */

  public static void startNewActivity(
    final Activity from,
    final CatalogFeedArgumentsType in_args)
  {
    final Bundle b = new Bundle();
    CatalogFeedActivity.setActivityArguments(b, in_args);
    final Intent i = new Intent(from, CatalogFeedActivity.class);
    i.putExtras(b);
    i.setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
    from.startActivity(i);
  }

  private @Nullable FeedType     feed;
  private @Nullable AbsListView  list_view;
  private @Nullable Future<Unit> loading;
  private @Nullable ViewGroup    progress_layout;
  private int                    saved_scroll_pos;

  private void configureUpButton(
    final ImmutableStack<CatalogUpStackEntry> up_stack,
    final String title)
  {
    final ActionBar bar = this.getActionBar();
    if (up_stack.isEmpty() == false) {
      bar.setDisplayHomeAsUpEnabled(true);
      bar.setHomeButtonEnabled(true);
    } else {
      bar.setDisplayHomeAsUpEnabled(false);
      bar.setHomeButtonEnabled(false);
    }

    bar.setTitle(title);
  }

  private CatalogFeedArgumentsType getArguments()
  {
    /**
     * Attempt to fetch arguments.
     */

    final Resources rr = NullCheck.notNull(this.getResources());
    final Intent i = NullCheck.notNull(this.getIntent());
    final Bundle a = i.getExtras();
    if (a != null) {
      return NullCheck.notNull((CatalogFeedArgumentsType) a
        .getSerializable(CatalogFeedActivity.CATALOG_ARGS));
    }

    /**
     * If there were no arguments (because, for example, this activity is the
     * initial one started for the app), synthesize some.
     */

    final SimplifiedCatalogAppServicesType app =
      Simplified.getCatalogAppServices();
    final boolean in_drawer_open = true;
    final ImmutableStack<CatalogUpStackEntry> empty = ImmutableStack.empty();
    final String in_title =
      NullCheck.notNull(rr.getString(R.string.app_name));
    final URI in_uri = app.getFeedInitialURI();

    return new CatalogFeedArgumentsRemote(
      in_drawer_open,
      NullCheck.notNull(empty),
      in_title,
      in_uri);
  }

  private ImmutableStack<CatalogUpStackEntry> newUpStack(
    final URI feed_uri,
    final String feed_title)
  {
    final ImmutableStack<CatalogUpStackEntry> up_stack = this.getUpStack();
    final ImmutableStack<CatalogUpStackEntry> new_up_stack =
      up_stack.push(new CatalogUpStackEntry(feed_uri, feed_title));
    return new_up_stack;
  }

  @Override public void onBookFeedFailure(
    final Throwable e)
  {
    if (e instanceof CancellationException) {
      CatalogFeedActivity.LOG.debug("Cancelled feed");
      return;
    }

    UIThread.runOnUIThread(new Runnable() {
      @Override public void run()
      {
        CatalogFeedActivity.this.onFeedLoadingFailureUI(e);
      }
    });
  }

  @Override public void onBookFeedSuccess(
    final FeedWithoutBlocks f)
  {
    this.onFeedWithoutBlocks(f);
  }

  @Override protected void onCreate(
    final @Nullable Bundle state)
  {
    super.onCreate(state);
    final CatalogFeedArgumentsType args = this.getArguments();
    final ImmutableStack<CatalogUpStackEntry> stack = this.getUpStack();
    this.configureUpButton(stack, args.getTitle());

    if (state != null) {
      CatalogFeedActivity.LOG.debug("received state");
      this.saved_scroll_pos = state.getInt(CatalogFeedActivity.LIST_STATE_ID);
    } else {
      this.saved_scroll_pos = 0;
    }

    /**
     * If this is the root of the catalog, attempt the initial load/login/sync
     * of books.
     */

    if (stack.isEmpty()) {
      final SimplifiedCatalogAppServicesType app =
        Simplified.getCatalogAppServices();
      app.syncInitial();
    }

    final LayoutInflater inflater = this.getLayoutInflater();
    final FrameLayout content_area = this.getContentFrame();
    final ViewGroup in_progress_layout =
      NullCheck.notNull((ViewGroup) inflater.inflate(
        R.layout.catalog_loading,
        content_area,
        false));

    content_area.addView(in_progress_layout);
    content_area.requestLayout();
    this.progress_layout = in_progress_layout;

    final Resources rr = NullCheck.notNull(this.getResources());
    final SimplifiedCatalogAppServicesType app =
      Simplified.getCatalogAppServices();
    final FeedLoaderType feed_loader = app.getFeedLoader();

    args
      .matchArguments(new CatalogFeedArgumentsMatcherType<Unit, UnreachableCodeException>() {
        @Override public Unit onFeedArgumentsLocalBooks(
          final CatalogFeedArgumentsLocalBooks c)
        {
          final BooksType books = app.getBooks();
          final URI dummy_uri = NullCheck.notNull(URI.create("Books"));
          final String dummy_id =
            NullCheck.notNull(rr.getString(R.string.books));
          final Calendar now = NullCheck.notNull(Calendar.getInstance());
          final String title =
            NullCheck.notNull(rr.getString(R.string.books));
          books.booksGetFeed(
            dummy_uri,
            dummy_id,
            now,
            title,
            CatalogFeedActivity.this);
          return Unit.unit();
        }

        @Override public Unit onFeedArgumentsRemote(
          final CatalogFeedArgumentsRemote c)
        {
          CatalogFeedActivity.this.loading =
            feed_loader.fromURI(c.getURI(), CatalogFeedActivity.this);
          return Unit.unit();
        }
      });
  }

  @Override public boolean onCreateOptionsMenu(
    final @Nullable Menu in_menu)
  {
    final Menu menu_nn = NullCheck.notNull(in_menu);

    if (this.feed == null) {
      CatalogFeedActivity.LOG
        .debug("menu creation requested but feed is not yet present");
      return true;
    }

    CatalogFeedActivity.LOG
      .debug("menu creation requested and feed is present");

    final MenuInflater inflater = this.getMenuInflater();
    inflater.inflate(R.menu.catalog, menu_nn);

    final MenuItem search_item = menu_nn.findItem(R.id.catalog_action_search);

    /**
     * If the feed actually has a search URI, then show the search field.
     * Otherwise, disable and hide it.
     */

    final FeedType feed_actual = NullCheck.notNull(this.feed);
    final OptionType<OPDSSearchLink> search_opt =
      feed_actual.getFeedSearchURI();
    boolean search_ok = false;
    if (search_opt.isSome()) {
      final Some<OPDSSearchLink> search_some =
        (Some<OPDSSearchLink>) search_opt;

      final SearchView sv = (SearchView) search_item.getActionView();
      sv.setSubmitButtonEnabled(true);

      /**
       * Set some placeholder text
       */

      final CatalogFeedArgumentsType args = this.getArguments();
      if (this.getUpStack().isEmpty()) {
        sv.setQueryHint("Search");
      } else {
        sv.setQueryHint("Search " + args.getTitle());
      }

      /**
       * Check that the search URI is of an understood type.
       */

      final OPDSSearchLink search = search_some.get();
      if ("application/opensearchdescription+xml".equals(search.getType())) {
        sv.setOnQueryTextListener(new OpenSearchQueryHandler(args, search
          .getURI()));
        search_ok = true;
      } else {

        /**
         * The application doesn't understand the search type.
         */

        CatalogFeedActivity.LOG.error(
          "unknown search type: {}",
          search.getType());
      }
    }

    if (search_ok == false) {
      search_item.setEnabled(false);
      search_item.setVisible(false);
    }

    return true;
  }

  @Override protected void onDestroy()
  {
    super.onDestroy();
    CatalogFeedActivity.LOG.debug("onDestroy");

    final Future<Unit> future = this.loading;
    if (future != null) {
      future.cancel(true);
    }
  }

  @Override public void onFeedLoadFailure(
    final URI u,
    final Throwable x)
  {
    UIThread.runOnUIThread(new Runnable() {
      @Override public void run()
      {
        CatalogFeedActivity.this.onFeedLoadingFailureUI(x);
      }
    });
  }

  private void onFeedLoadingFailureUI(
    final Throwable e)
  {
    UIThread.checkIsUIThread();

    CatalogFeedActivity.LOG.error("Failed to get feed: ", e);

    final FrameLayout content_area = this.getContentFrame();
    final ViewGroup progress = NullCheck.notNull(this.progress_layout);
    progress.setVisibility(View.GONE);
    content_area.removeAllViews();

    final LayoutInflater inflater = this.getLayoutInflater();
    final LinearLayout error =
      NullCheck.notNull((LinearLayout) inflater.inflate(
        R.layout.catalog_loading_error,
        content_area,
        false));
    content_area.addView(error);
    content_area.requestLayout();
  }

  @Override public void onFeedLoadSuccess(
    final URI u,
    final FeedType f)
  {
    CatalogFeedActivity.LOG.debug("received feed for {}", u);
    this.feed = f;
    f.matchFeed(this);
  }

  @Override public Unit onFeedWithBlocks(
    final FeedWithBlocks f)
  {
    CatalogFeedActivity.LOG.debug(
      "received feed with blocks: {}",
      f.getFeedURI());

    UIThread.runOnUIThread(new Runnable() {
      @Override public void run()
      {
        CatalogFeedActivity.this.onFeedWithBlocksUI(f);
      }
    });

    return Unit.unit();
  }

  private void onFeedWithBlocksUI(
    final FeedWithBlocks f)
  {
    CatalogFeedActivity.LOG.debug(
      "received feed with blocks: {}",
      f.getFeedURI());

    UIThread.checkIsUIThread();

    this.invalidateOptionsMenu();

    final FrameLayout content_area = this.getContentFrame();
    final ViewGroup progress = NullCheck.notNull(this.progress_layout);
    progress.setVisibility(View.GONE);
    content_area.removeAllViews();

    final LayoutInflater inflater = this.getLayoutInflater();
    final ViewGroup layout =
      NullCheck.notNull((ViewGroup) inflater.inflate(
        R.layout.catalog_feed_blocks_list,
        content_area,
        false));

    content_area.addView(layout);
    content_area.requestLayout();

    CatalogFeedActivity.LOG.debug(
      "restoring scroll position: {}",
      this.saved_scroll_pos);

    final ListView list =
      NullCheck.notNull((ListView) layout
        .findViewById(R.id.catalog_feed_blocks_list));
    list.post(new Runnable() {
      @Override public void run()
      {
        list.setSelection(CatalogFeedActivity.this.saved_scroll_pos);
      }
    });
    this.list_view = list;

    final SimplifiedCatalogAppServicesType app =
      Simplified.getCatalogAppServices();

    final CatalogFeedArgumentsType args = this.getArguments();
    final URI feed_uri = f.getFeedURI();
    final ImmutableStack<CatalogUpStackEntry> new_up_stack =
      this.newUpStack(feed_uri, args.getTitle());

    final CatalogFeedLaneListenerType in_lane_listener =
      new CatalogFeedLaneListenerType() {
        @Override public void onSelectBook(
          final FeedEntryOPDS e)
        {
          CatalogFeedActivity.this.onSelectedBook(app, new_up_stack, e);
        }

        @Override public void onSelectFeed(
          final FeedBlock in_block)
        {
          CatalogFeedActivity.this
            .onSelectedFeedBlock(new_up_stack, in_block);
        }
      };

    final CatalogFeedWithBlocks cfl =
      new CatalogFeedWithBlocks(
        this,
        app,
        app.getCoverProvider(),
        in_lane_listener,
        app.getBooks(),
        f);

    list.setAdapter(cfl);
    list.setOnScrollListener(cfl);
  }

  @Override public Unit onFeedWithoutBlocks(
    final FeedWithoutBlocks f)
  {
    CatalogFeedActivity.LOG.debug(
      "received feed without blocks: {}",
      f.getFeedURI());

    UIThread.runOnUIThread(new Runnable() {
      @Override public void run()
      {
        CatalogFeedActivity.this.onFeedWithoutBlocksUI(f);
      }
    });
    return Unit.unit();
  }

  private void onFeedWithoutBlocksEmptyUI(
    final FeedWithoutBlocks f)
  {
    CatalogFeedActivity.LOG.debug(
      "received feed without blocks (empty): {}",
      f.getFeedURI());

    UIThread.checkIsUIThread();
    Assertions.checkPrecondition(f.isEmpty(), "Feed is empty");

    this.invalidateOptionsMenu();

    final FrameLayout content_area = this.getContentFrame();
    final ViewGroup progress = NullCheck.notNull(this.progress_layout);
    progress.setVisibility(View.GONE);
    content_area.removeAllViews();

    final LayoutInflater inflater = this.getLayoutInflater();
    final ViewGroup layout =
      NullCheck.notNull((ViewGroup) inflater.inflate(
        R.layout.catalog_feed_noblocks_empty,
        content_area,
        false));

    content_area.addView(layout);
    content_area.requestLayout();
  }

  private void onFeedWithoutBlocksNonEmptyUI(
    final FeedWithoutBlocks f)
  {
    CatalogFeedActivity.LOG.debug(
      "received feed without blocks (non-empty): {}",
      f.getFeedURI());

    UIThread.checkIsUIThread();
    Assertions.checkPrecondition(f.isEmpty() == false, "Feed is non-empty");

    this.invalidateOptionsMenu();

    final FrameLayout content_area = this.getContentFrame();
    final ViewGroup progress = NullCheck.notNull(this.progress_layout);
    progress.setVisibility(View.GONE);
    content_area.removeAllViews();

    final LayoutInflater inflater = this.getLayoutInflater();
    final ViewGroup layout =
      NullCheck.notNull((ViewGroup) inflater.inflate(
        R.layout.catalog_feed_noblocks,
        content_area,
        false));

    content_area.addView(layout);
    content_area.requestLayout();

    CatalogFeedActivity.LOG.debug(
      "restoring scroll position: {}",
      this.saved_scroll_pos);

    final GridView grid_view =
      NullCheck.notNull((GridView) layout
        .findViewById(R.id.catalog_feed_noblocks_grid));
    grid_view.post(new Runnable() {
      @Override public void run()
      {
        grid_view.setSelection(CatalogFeedActivity.this.saved_scroll_pos);
      }
    });
    this.list_view = grid_view;

    final CatalogFeedArgumentsType args = this.getArguments();
    final URI feed_uri = f.getFeedURI();
    final ImmutableStack<CatalogUpStackEntry> new_up_stack =
      this.newUpStack(feed_uri, args.getTitle());

    final SimplifiedCatalogAppServicesType app =
      Simplified.getCatalogAppServices();

    final CatalogBookSelectionListenerType book_select_listener =
      new CatalogBookSelectionListenerType() {
        @Override public void onSelectBook(
          final CatalogFeedBookCellView v,
          final FeedEntryOPDS e)
        {
          CatalogFeedActivity.this.onSelectedBook(app, new_up_stack, e);
        }
      };

    final CatalogFeedWithoutBlocks without =
      new CatalogFeedWithoutBlocks(
        this,
        app.getCoverProvider(),
        book_select_listener,
        app.getBooks(),
        app.getFeedLoader(),
        f);
    grid_view.setAdapter(without);
    grid_view.setOnScrollListener(without);
  }

  private void onFeedWithoutBlocksUI(
    final FeedWithoutBlocks f)
  {
    UIThread.checkIsUIThread();

    if (f.isEmpty()) {
      this.onFeedWithoutBlocksEmptyUI(f);
      return;
    }

    this.onFeedWithoutBlocksNonEmptyUI(f);
  }

  @Override protected void onSaveInstanceState(
    final @Nullable Bundle state)
  {
    super.onSaveInstanceState(state);

    CatalogFeedActivity.LOG.debug("saving state");

    final Bundle nn_state = NullCheck.notNull(state);
    final AbsListView lv = this.list_view;
    if (lv != null) {
      final int position = lv.getFirstVisiblePosition();
      CatalogFeedActivity.LOG
        .debug("saving list view position: {}", position);
      nn_state.putInt(CatalogFeedActivity.LIST_STATE_ID, position);
    }
  }

  private void onSelectedBook(
    final SimplifiedCatalogAppServicesType app,
    final ImmutableStack<CatalogUpStackEntry> new_up_stack,
    final FeedEntryOPDS e)
  {
    CatalogFeedActivity.LOG.debug("onSelectedBook: {}", this);

    if (app.screenIsLarge()) {
      final CatalogBookDialog df = CatalogBookDialog.newDialog(e);
      final FragmentManager fm =
        CatalogFeedActivity.this.getFragmentManager();
      df.show(fm, "book-detail");
    } else {
      CatalogBookDetailActivity.startNewActivity(
        CatalogFeedActivity.this,
        new_up_stack,
        e);
    }
  }

  private void onSelectedFeedBlock(
    final ImmutableStack<CatalogUpStackEntry> new_up_stack,
    final FeedBlock f)
  {
    CatalogFeedActivity.LOG.debug("onSelectFeed: {}", this);

    final CatalogFeedArgumentsRemote remote =
      new CatalogFeedArgumentsRemote(
        false,
        new_up_stack,
        f.getBlockTitle(),
        f.getBlockURI());
    CatalogFeedActivity.startNewActivity(this, remote);
  }
}
