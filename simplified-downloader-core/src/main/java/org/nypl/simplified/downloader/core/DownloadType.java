package org.nypl.simplified.downloader.core;

/**
 * The type of a download in progress.
 */

public interface DownloadType
{
  /**
   * Cancel the download in progress.
   */

  void cancel();
}
