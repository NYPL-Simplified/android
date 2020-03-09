package org.nypl.simplified.accounts.registry.api

import io.reactivex.Observable
import org.nypl.simplified.accounts.api.AccountProviderDescriptionType
import org.nypl.simplified.accounts.api.AccountProviderType

import java.net.URI
import javax.annotation.concurrent.ThreadSafe

/**
 * The interface exposing a set of account providers.
 *
 * Implementations are required to be safe to access from multiple threads.
 */

@ThreadSafe
interface AccountProviderRegistryType {

  /**
   * A source of registry events.
   */

  val events: Observable<AccountProviderRegistryEvent>

  /**
   * The default, guaranteed-to-exist account provider.
   */

  val defaultProvider: AccountProviderType

  /**
   * A read-only view of the currently resolved providers.
   */

  val resolvedProviders: Map<URI, AccountProviderType>

  /**
   * The status of the account registry.
   */

  val status: AccountProviderRegistryStatus

  /**
   * Refresh the available account providers from all sources.
   */

  fun refresh()

  /**
   * Return an immutable read-only of the account provider descriptions.
   *
   * Implementations are required to implicitly call [refresh] if the method has not previously
   * been called.
   */

  fun accountProviderDescriptions(): Map<URI, AccountProviderDescriptionType>

  /**
   * Find the account provider with the given `id`.
   *
   * Implementations are required to implicitly call [refresh] if the method has not previously
   * been called.
   */

  fun findAccountProviderDescription(id: URI): AccountProviderDescriptionType? =
    this.accountProviderDescriptions()[id]

  /**
   * Introduce the given account provider to the registry. If an existing, newer version of the
   * given account provider already exists in the registry, the newer version is returned.
   */

  fun updateProvider(accountProvider: AccountProviderType): AccountProviderType

  /**
   * Introduce the given account provider description to the registry. If an existing, newer
   * version of the given account provider description already exists in the registry, the newer
   * version is returned.
   */

  fun updateDescription(description: AccountProviderDescriptionType): AccountProviderDescriptionType
}
