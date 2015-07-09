package org.nypl.simplified.books.core;

import java.net.URI;
import java.util.Calendar;

import org.nypl.simplified.opds.core.OPDSAcquisition;
import org.nypl.simplified.opds.core.OPDSAcquisitionFeedEntry;

import com.io7m.jfunctional.OptionType;

/**
 * Interface to the book management functions.
 */

public interface BooksControllerType extends BooksStatusCacheType
{
  /**
   * Borrow the given book, delivering the results to the given
   * <tt>listener</tt>.
   */

  void bookBorrow(
    BookID id,
    OPDSAcquisition acq,
    String title,
    BookBorrowListenerType listener);

  /**
   * Delete the actual book file for the given book, if any.
   */

  void bookDeleteData(
    BookID id);

  /**
   * Cancel the download of the book with the given <tt>id</tt>.
   */

  void bookDownloadCancel(
    BookID id);

  /**
   * Acknowledge the failed download of book <tt>id</tt>, if any.
   */

  void bookDownloadAcknowledge(
    BookID id);

  /**
   * Retrieve an acquisition feed of all owned/borrowed books on the current
   * account, delivering the results to the given <tt>listener</tt>.
   */

  void booksGetFeed(
    URI in_uri,
    String in_id,
    Calendar in_updated,
    String in_title,
    FeedFacetPseudo.FacetType in_facet_active,
    String in_facet_group,
    FeedFacetPseudoTitleProviderType in_facet_titles,
    OptionType<String> in_search,
    BookFeedListenerType in_listener);

  /**
   * Update metadata for the given book.
   */

  void bookUpdateMetadata(
    BookID id,
    OPDSAcquisitionFeedEntry e);
}
