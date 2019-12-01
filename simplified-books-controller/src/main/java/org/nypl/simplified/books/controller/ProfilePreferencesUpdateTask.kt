package org.nypl.simplified.books.controller

import io.reactivex.subjects.Subject
import org.nypl.simplified.profiles.api.ProfileEvent
import org.nypl.simplified.profiles.api.ProfileID
import org.nypl.simplified.profiles.api.ProfileNonexistentException
import org.nypl.simplified.profiles.api.ProfilePreferences
import org.nypl.simplified.profiles.api.ProfileUpdated
import org.nypl.simplified.profiles.api.ProfilesDatabaseType
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.Callable

class ProfilePreferencesUpdateTask(
  private val events: Subject<ProfileEvent>,
  private val requestedProfileId: ProfileID?,
  private val profiles: ProfilesDatabaseType,
  private val preferencesUpdate: (ProfilePreferences) -> ProfilePreferences
) : Callable<ProfileUpdated> {

  private val logger = LoggerFactory.getLogger(ProfilePreferencesUpdateTask::class.java)

  @Throws(Exception::class)
  override fun call(): ProfileUpdated {
    try {
      val profileId =
        this.requestedProfileId ?: this.profiles.currentProfileUnsafe().id

      val profile = this.profiles.profiles()[profileId]
        ?: throw ProfileNonexistentException("No such profile: " + profileId.uuid)

      val oldPreferences = profile.preferences()
      val newPreferences = this.preferencesUpdate.invoke(oldPreferences)
      profile.preferencesUpdate(newPreferences)

      val event =
        ProfileUpdated.Succeeded(
          profileID = profileId,
          oldPreferences = oldPreferences,
          newPreferences = newPreferences,
          oldDisplayName = profile.displayName,
          newDisplayName = profile.displayName
        )

      this.events.onNext(event)
      return event
    } catch (e: Exception) {
      this.logger.error("could not update preferences: ", e)
      val event =
        ProfileUpdated.Failed(
          profileID = this.requestedProfileId ?: ProfileID(UUID(0L, 0L)),
          exception = e)
      this.events.onNext(event)
      return event
    }
  }
}
