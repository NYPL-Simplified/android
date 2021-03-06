package org.nypl.simplified.ui.catalog

import android.app.Activity
import org.nypl.simplified.books.api.Book
import org.nypl.simplified.books.api.BookFormat
import org.nypl.simplified.books.api.BookID
import org.nypl.simplified.feeds.api.FeedEntry.FeedEntryOPDS
import org.nypl.simplified.navigation.api.NavigationControllerType
import org.nypl.simplified.ui.accounts.AccountNavigationControllerType
import java.net.URI

/**
 * Navigation functions for the catalog screens.
 */

interface CatalogNavigationControllerType :
  NavigationControllerType,
  AccountNavigationControllerType {

  /**
   * The catalog wants to open a book detail page.
   */

  fun openBookDetail(
    feedArguments: CatalogFeedArguments,
    entry: FeedEntryOPDS
  )

  /**
   * The catalog wants to open an external login form for downloading a book.
   */

  fun openBookDownloadLogin(
    bookID: BookID,
    downloadURI: URI
  )

  /**
   * A catalog screen wants to open a feed.
   */

  fun openFeed(feedArguments: CatalogFeedArguments)

  /**
   * A catalog screen wants to open a viewer for a book
   */

  fun openViewer(
    activity: Activity,
    book: Book,
    format: BookFormat
  )

  /**
   * Clear the history for the catalog.
   */

  fun clearHistory()
}
