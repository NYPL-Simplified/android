package org.nypl.simplified.app.settings

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import com.google.common.util.concurrent.FluentFuture
import com.io7m.jfunctional.Some
import com.io7m.jfunctional.Unit
import com.tenmiles.helpstack.HSHelpStack
import com.tenmiles.helpstack.gears.HSDeskGear
import org.nypl.simplified.app.CardCreatorActivity
import org.nypl.simplified.app.MainEULAActivity
import org.nypl.simplified.app.NavigationDrawerActivity
import org.nypl.simplified.app.R
import org.nypl.simplified.app.ReportIssueActivity
import org.nypl.simplified.app.Simplified
import org.nypl.simplified.app.WebViewActivity
import org.nypl.simplified.app.login.LoginDialog
import org.nypl.simplified.app.utilities.ErrorDialogUtilities
import org.nypl.simplified.app.utilities.UIThread
import org.nypl.simplified.books.accounts.AccountAuthenticationCredentials
import org.nypl.simplified.books.accounts.AccountBarcode
import org.nypl.simplified.books.accounts.AccountEvent
import org.nypl.simplified.books.accounts.AccountEventLogin
import org.nypl.simplified.books.accounts.AccountEventLogin.AccountLoginFailed
import org.nypl.simplified.books.accounts.AccountEventLogin.AccountLoginSucceeded
import org.nypl.simplified.books.accounts.AccountEventLogout
import org.nypl.simplified.books.accounts.AccountEventLogout.AccountLogoutFailed
import org.nypl.simplified.books.accounts.AccountEventLogout.AccountLogoutSucceeded
import org.nypl.simplified.books.accounts.AccountID
import org.nypl.simplified.books.accounts.AccountPIN
import org.nypl.simplified.books.accounts.AccountType
import org.nypl.simplified.books.eula.EULAType
import org.nypl.simplified.books.profiles.ProfileNoneCurrentException
import org.nypl.simplified.futures.FluentFutureExtensions.map
import org.nypl.simplified.futures.FluentFutureExtensions.onException
import org.nypl.simplified.observable.ObservableSubscriptionType
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * An activity displaying settings for a specific account.
 */

class SettingsAccountActivity : NavigationDrawerActivity() {

  private val logger = LoggerFactory.getLogger(SettingsAccountActivity::class.java)

  private lateinit var accountNameText: TextView
  private lateinit var accountSubtitleText: TextView
  private lateinit var accountIcon: ImageView
  private lateinit var barcodeText: EditText
  private lateinit var pinText: EditText
  private lateinit var tableWithCode: TableLayout
  private lateinit var tableSignup: TableLayout
  private lateinit var login: Button
  private lateinit var reportIssue: TableRow
  private lateinit var supportCenter: TableRow
  private lateinit var eulaCheckbox: CheckBox
  private lateinit var barcodeLabel: TextView
  private lateinit var pinLabel: TextView
  private lateinit var pinReveal: CheckBox
  private lateinit var signup: Button
  private lateinit var privacy: TableRow
  private lateinit var license: TableRow
  private lateinit var account: AccountType
  private lateinit var syncSwitch: Switch

  private val accountEventSubscription: ObservableSubscriptionType<AccountEvent>? = null

  override fun navigationDrawerGetActivityTitle(resources: Resources): String {
    return resources.getString(R.string.settings)
  }

  override fun navigationDrawerShouldShowIndicator(): Boolean {
    return true
  }

  override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent) {

    /*
     * Retrieve the PIN from the activity that was launched to collect it.
     */

    if (requestCode == 1) {
      val pinReveal = this.findViewById<CheckBox>(R.id.settings_reveal_password)

      if (resultCode == Activity.RESULT_OK) {
        val pinText = this.findViewById<TextView>(R.id.settings_pin_text)
        pinText.transformationMethod = HideReturnsTransformationMethod.getInstance()
        pinReveal.isChecked = true
      } else {
        // The user canceled or didn't complete the lock screen
        // operation. Go to error/cancellation flow.
        pinReveal.isChecked = false
      }
    }
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId == R.id.show_eula) {
      val eulaIntent = Intent(this, MainEULAActivity::class.java)
      this.account.provider().eula().map_ { eula_uri ->
        val argumentBundle = Bundle()
        MainEULAActivity.setActivityArguments(argumentBundle, eula_uri.toString())
        eulaIntent.putExtras(argumentBundle)
        this.startActivity(eulaIntent)
      }
      return true
    }

    return when (item.itemId) {
      android.R.id.home -> {
        this.onBackPressed()
        true
      }

      else -> {
        super.onOptionsItemSelected(item)
      }
    }
  }

  override fun onCreate(state: Bundle?) {
    super.onCreate(state)

    val inflater = this.layoutInflater
    val contentArea = this.contentFrame
    val layout =
      inflater.inflate(R.layout.settings_account, contentArea, false) as ViewGroup
    contentArea.addView(layout)
    contentArea.requestLayout()

    val extras = this.intent.extras
    this.account = getAccount(extras)

    this.accountNameText =
      this.findViewById(android.R.id.text1)
    this.accountSubtitleText =
      this.findViewById(android.R.id.text2)
    this.accountIcon =
      this.findViewById(R.id.account_icon)
    this.tableWithCode =
      this.findViewById(R.id.settings_login_table_with_code)
    this.barcodeLabel =
      this.findViewById(R.id.settings_barcode_label)
    this.barcodeText =
      this.findViewById(R.id.settings_barcode_text)
    this.pinText =
      this.findViewById(R.id.settings_pin_text)
    this.pinLabel =
      this.findViewById(R.id.settings_pin_label)
    this.pinReveal =
      this.findViewById(R.id.settings_reveal_password)
    this.login =
      this.findViewById(R.id.settings_login)
    this.tableSignup =
      this.findViewById(R.id.settings_signup_table)
    this.reportIssue =
      this.findViewById(R.id.report_issue)
    this.supportCenter =
      this.findViewById(R.id.support_center)
    this.eulaCheckbox =
      this.findViewById(R.id.eula_checkbox)
    this.signup =
      this.findViewById(R.id.settings_signup)
    this.privacy =
      this.findViewById(R.id.link_privacy)
    this.license =
      this.findViewById(R.id.link_license)
    this.syncSwitch =
      this.findViewById(R.id.sync_switch)

    val bar = this.supportActionBar
    if (bar != null) {
      bar.setHomeAsUpIndicator(R.drawable.ic_arrow_back_white_24dp)
      bar.setDisplayHomeAsUpEnabled(true)
      bar.setHomeButtonEnabled(false)
    }

    val accountProvider = this.account.provider()
    this.accountNameText.text = accountProvider.displayName()

    val subtitleOpt = accountProvider.subtitle()
    if (subtitleOpt is Some<String>) {
      this.accountSubtitleText.text = subtitleOpt.get()
    } else {
      this.accountSubtitleText.text = ""
    }

    /*
     * Show the "Support Center" section if the provider offers one.
     */

    if (accountProvider.supportEmail().isSome) {
      this.reportIssue.visibility = View.VISIBLE
      this.reportIssue.setOnClickListener {
        val intent = Intent(this, ReportIssueActivity::class.java)
        val argumentBundle = Bundle()
        argumentBundle.putSerializable("selected_account", this.account.id())
        intent.putExtras(argumentBundle)
        this.startActivity(intent)
      }
    } else {
      this.reportIssue.visibility = View.GONE
    }

    /*
     * Show the "Help Center" section if the provider offers one.
     */

    if (accountProvider.supportsHelpCenter()) {
      this.supportCenter.visibility = View.VISIBLE
      this.supportCenter.setOnClickListener {
        val stack = HSHelpStack.getInstance(this)
        val gear = HSDeskGear(" ", " ", null)
        stack.gear = gear
        stack.showHelp(this)
      }
    } else {
      this.supportCenter.visibility = View.GONE
    }

    /*
     * Show the "Card Creator" section if the provider supports it.
     */

    if (accountProvider.supportsCardCreator()) {
      this.tableSignup.visibility = View.VISIBLE
      this.signup.setOnClickListener {
        val cardcreator = Intent(this, CardCreatorActivity::class.java)
        this.startActivity(cardcreator)
      }
      this.signup.setText(R.string.need_card_button)
    } else {
      this.tableSignup.visibility = View.GONE
    }

    /*
     * Configure the barcode and PIN entry section. This will be hidden entirely if the
     * provider doesn't support/require authentication.
     */

    // Get labels from the current authentication document.
    // XXX: This should be per-account
    val docs = Simplified.getDocumentStore()
    val auth_doc = docs.authenticationDocument
    this.barcodeLabel.text = auth_doc.labelLoginUserID
    this.pinLabel.text = auth_doc.labelLoginPassword

    this.pinText.transformationMethod = PasswordTransformationMethod.getInstance()
    this.handlePinReveal(this.pinText, this.pinReveal)

    if (accountProvider.authentication().isSome) {
      this.tableWithCode.visibility = View.VISIBLE
      this.login.visibility = View.VISIBLE
      this.configureLoginFieldVisibilityAndContents()
    } else {
      this.tableWithCode.visibility = View.GONE
      this.login.visibility = View.GONE
    }

    /*
     * Show the "Privacy Policy" section if the provider has one.
     */

    val privacyPolicy = accountProvider.privacyPolicy()
    if (privacyPolicy is Some<URI>) {
      this.privacy.visibility = View.VISIBLE
      this.privacy.setOnClickListener {
        val intent = Intent(this, WebViewActivity::class.java)
        val intentBundle = Bundle()
        WebViewActivity.setActivityArguments(
          intentBundle,
          privacyPolicy.get().toString(),
          "Privacy Policy")
        intent.putExtras(intentBundle)
        this.startActivity(intent)
      }
    } else {
      this.privacy.visibility = View.GONE
    }

    /*
     * Show the "Content License" section if the provider has one.
     */

    val license = accountProvider.license()
    if (license is Some<URI>) {
      this.license.visibility = View.VISIBLE
      this.license.setOnClickListener {
        val intent = Intent(this, WebViewActivity::class.java)
        val intentBundle = Bundle()
        WebViewActivity.setActivityArguments(
          intentBundle,
          license.get().toString(),
          "Content Licenses")
        intent.putExtras(intentBundle)
        this.startActivity(intent)
      }
    } else {
      this.license.visibility = View.GONE
    }

    /*
     * Configure the EULA views if there is one.
     */

    val eulaOpt = docs.eula
    if (eulaOpt is Some<EULAType>) {
      val eula = eulaOpt.get()
      this.eulaCheckbox.isChecked = eula.eulaHasAgreed()
      this.eulaCheckbox.isEnabled = true
      this.eulaCheckbox.setOnCheckedChangeListener { _, checked -> eula.eulaSetHasAgreed(checked) }

      if (eula.eulaHasAgreed()) {
        this.logger.debug("EULA: agreed")
      } else {
        this.logger.debug("EULA: not agreed")
      }
    } else {
      this.logger.debug("EULA: unavailable")
    }

    /*
     * Configure the syncing switch.
     */

    if (this.account.provider().supportsSimplyESynchronization()) {
      this.syncSwitch.isEnabled = true
      this.syncSwitch.isChecked = this.account.preferences().bookmarkSyncingPermitted
      this.syncSwitch.setOnCheckedChangeListener { _, isEnabled ->
        this.account.setPreferences(
          this.account.preferences().copy(bookmarkSyncingPermitted = isEnabled))
      }
    } else {
      this.syncSwitch.isEnabled = false
      this.syncSwitch.isChecked = false
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    this.accountEventSubscription?.unsubscribe()
  }

  private fun onAccountEvent(event: AccountEvent): Unit {
    this.logger.debug("onAccountEvent: {}", event)

    if (event is AccountEventLogin) {
      if (event is AccountLoginSucceeded) {
        return this.onAccountEventLoginSucceeded(event)
      } else if (event is AccountLoginFailed) {
        return this.onAccountEventLoginFailed(event)
      }
    }

    if (event is AccountEventLogout) {
      if (event is AccountLogoutSucceeded) {
        return this.onAccountEventLogoutSucceeded(event)
      } else if (event is AccountLogoutFailed) {
        return this.onAccountEventLogoutFailed(event)
      }
    }

    return Unit.unit()
  }

  private fun onAccountEventLoginFailed(failed: AccountLoginFailed): Unit {
    this.logger.debug("onLoginFailed: {}", failed)

    ErrorDialogUtilities.showErrorWithRunnable(
      this,
      this.logger,
      LoginDialog.loginErrorCodeToLocalizedMessage(this.resources, failed.errorCode()), null)
    { this.login.isEnabled = true }

    UIThread.runOnUIThread { this.configureLoginFieldVisibilityAndContents() }
    return Unit.unit()
  }

  private fun onAccountEventLoginSucceeded(succeeded: AccountLoginSucceeded): Unit {
    this.logger.debug("onLoginSucceeded: {}", succeeded)

    UIThread.runOnUIThread { this.configureLoginFieldVisibilityAndContents() }
    return Unit.unit()
  }

  private fun onAccountEventLogoutFailed(failed: AccountLogoutFailed): Unit {
    this.logger.debug("onLogoutFailed: {}", failed)

    ErrorDialogUtilities.showErrorWithRunnable(
      this,
      this.logger,
      this.resources.getString(R.string.settings_logout_failed), null
    ) { this.login.isEnabled = true }

    UIThread.runOnUIThread { this.configureLoginFieldVisibilityAndContents() }
    return Unit.unit()
  }

  private fun onAccountEventLogoutSucceeded(succeeded: AccountLogoutSucceeded): Unit {
    this.logger.debug("onLogoutSucceeded: {}", succeeded)

    UIThread.runOnUIThread { this.configureLoginFieldVisibilityAndContents() }
    return Unit.unit()
  }

  private fun configureLoginFieldVisibilityAndContents() {
    val credentialsOpt = this.account.credentials()
    if (credentialsOpt is Some<AccountAuthenticationCredentials>) {
      val credentials = credentialsOpt.get()

      this.pinText.setText(credentials.pin().value())
      this.pinText.isEnabled = false

      this.barcodeText.setText(credentials.barcode().value())
      this.barcodeText.isEnabled = false

      this.login.isEnabled = true
      this.login.setText(R.string.settings_log_out)
      this.login.setOnClickListener {
        this.configureDisableLoginForm()
        this.tryLogout()
      }
    } else {
      this.pinText.isEnabled = true
      this.barcodeText.isEnabled = true

      this.login.isEnabled = true
      this.login.setText(R.string.settings_log_in)
      this.login.setOnClickListener {
        this.configureDisableLoginForm()
        this.tryLogin()
      }
    }
  }

  private fun configureDisableLoginForm() {
    this.login.isEnabled = false
    this.pinText.isEnabled = false
    this.barcodeText.isEnabled = false
  }

  private fun tryLogout(): Unit {
    FluentFuture
      .from(Simplified.getProfilesController().profileAccountLogout(this.account.id()))
      .onException(Exception::class.java) { event -> AccountLogoutFailed.ofException(event) }
      .map { event -> this.onAccountEvent(event) }

    return Unit.unit()
  }

  private fun tryLogin(): Unit {
    val credentials =
      AccountAuthenticationCredentials.builder(
        AccountPIN.create(this.pinText.text.toString()),
        AccountBarcode.create(this.barcodeText.text.toString()))
        .build()

    FluentFuture
      .from(Simplified.getProfilesController().profileAccountLogin(this.account.id(), credentials))
      .onException(Exception::class.java) { event -> AccountLoginFailed.ofException(event) }
      .map { event -> this.onAccountEvent(event) }

    return Unit.unit()
  }

  private fun handlePinReveal(
    pinText: TextView,
    pinReveal: CheckBox) {

    /*
     * Add a listener that reveals/hides the password field.
     */

    pinReveal.setOnCheckedChangeListener { _, checked ->
      if (checked) {
        val keyguardManager =
          this.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (!keyguardManager.isKeyguardSecure) {
          // Show a message that the user hasn't set up a lock screen.
          Toast.makeText(this, R.string.settings_screen_Lock_not_setup, Toast.LENGTH_LONG).show()
          pinReveal.isChecked = false
        } else {
          val intent = keyguardManager.createConfirmDeviceCredentialIntent(null, null)
          if (intent != null) {
            this.startActivityForResult(intent, 1)
          }
        }
      } else {
        pinText.transformationMethod = PasswordTransformationMethod.getInstance()
      }
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    val inflater = this.menuInflater
    inflater.inflate(R.menu.eula, menu)
    return true
  }

  companion object {

    const val ACCOUNT_ID = "org.nypl.simplified.app.MainSettingsAccountActivity.account_id"

    /**
     * Get either the currently selected account, or the account that was passed explicitly to the
     * activity.
     */

    private fun getAccount(extras: Bundle?): AccountType {
      return try {
        val profile = Simplified.getProfilesController().profileCurrent()
        if (extras != null && extras.containsKey(this.ACCOUNT_ID)) {
          val accountID = extras.getSerializable(this.ACCOUNT_ID) as AccountID
          profile.accounts()[accountID]!!
        } else {
          profile.accountCurrent()
        }
      } catch (e: ProfileNoneCurrentException) {
        throw IllegalStateException(e)
      }
    }
  }
}