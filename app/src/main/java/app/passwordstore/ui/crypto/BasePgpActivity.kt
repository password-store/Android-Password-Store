/*
 * Copyright © 2014-2021 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.crypto

import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.CallSuper
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.injection.prefs.SettingsPreferences
import app.passwordstore.ui.pgp.PGPKeyImportActivity
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.extensions.clipboard
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.snackbar
import app.passwordstore.util.extensions.unsafeLazy
import app.passwordstore.util.services.ClipboardService
import app.passwordstore.util.settings.Constants
import app.passwordstore.util.settings.PreferenceKeys
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("Registered")
@AndroidEntryPoint
open class BasePgpActivity : AppCompatActivity() {

  /** Full path to the repository */
  val repoPath by unsafeLazy { intent.getStringExtra("REPO_PATH")!! }

  /** Full path to the password file being worked on */
  val fullPath by unsafeLazy { intent.getStringExtra("FILE_PATH")!! }

  /**
   * Name of the password file
   *
   * Converts personal/auth.foo.org/john_doe@example.org.gpg to john_doe.example.org
   */
  val name: String by unsafeLazy { File(fullPath).nameWithoutExtension }

  /** Action to invoke if [keyImportAction] succeeds. */
  var onKeyImport: (() -> Unit)? = null
  private val keyImportAction =
    registerForActivityResult(StartActivityForResult()) {
      if (it.resultCode == RESULT_OK) {
        onKeyImport?.invoke()
        onKeyImport = null
      } else {
        finish()
      }
    }

  /** [SharedPreferences] instance used by subclasses to persist settings */
  @SettingsPreferences @Inject lateinit var settings: SharedPreferences
  @Inject lateinit var repository: CryptoRepository
  @Inject lateinit var dispatcherProvider: DispatcherProvider

  /**
   * [onCreate] sets the window up with the right flags to prevent auth leaks through screenshots or
   * recent apps screen.
   */
  @CallSuper
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
  }

  /**
   * Copies provided [text] to the clipboard. Shows a [Snackbar] which can be disabled by passing
   * [showSnackbar] as false.
   */
  fun copyTextToClipboard(
    text: String?,
    showSnackbar: Boolean = true,
    @StringRes snackbarTextRes: Int = R.string.clipboard_copied_text
  ) {
    val clipboard = clipboard ?: return
    val clip = ClipData.newPlainText("pgp_handler_result_pm", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      clip.description.extras =
        PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    clipboard.setPrimaryClip(clip)
    if (showSnackbar && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      snackbar(message = resources.getString(snackbarTextRes))
    }
  }

  /**
   * Function to execute [onKeysExist] only if there are PGP keys imported in the app's key manager.
   */
  fun requireKeysExist(onKeysExist: () -> Unit) {
    lifecycleScope.launch {
      val hasKeys = repository.hasKeys()
      if (!hasKeys) {
        withContext(dispatcherProvider.main()) {
          MaterialAlertDialogBuilder(this@BasePgpActivity)
            .setTitle(resources.getString(R.string.no_keys_imported_dialog_title))
            .setMessage(resources.getString(R.string.no_keys_imported_dialog_message))
            .setPositiveButton(resources.getString(R.string.button_label_import)) { _, _ ->
              onKeyImport = onKeysExist
              keyImportAction.launch(Intent(this@BasePgpActivity, PGPKeyImportActivity::class.java))
            }
            .show()
        }
      } else {
        onKeysExist()
      }
    }
  }

  /**
   * Copies a provided [password] string to the clipboard. This wraps [copyTextToClipboard] to hide
   * the default [Snackbar] and starts off an instance of [ClipboardService] to provide a way of
   * clearing the clipboard.
   */
  fun copyPasswordToClipboard(password: String?) {
    copyTextToClipboard(password)
    val clearAfter =
      settings.getString(PreferenceKeys.GENERAL_SHOW_TIME)?.toIntOrNull()
        ?: Constants.DEFAULT_DECRYPTION_TIMEOUT

    if (clearAfter != 0) {
      val service =
        Intent(this, ClipboardService::class.java).apply {
          action = ClipboardService.ACTION_START
          putExtra(ClipboardService.EXTRA_NOTIFICATION_TIME, clearAfter)
        }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(service)
      } else {
        startService(service)
      }
    }
  }

  companion object {

    const val EXTRA_FILE_PATH = "FILE_PATH"
    const val EXTRA_REPO_PATH = "REPO_PATH"

    /** Gets the relative path to the repository */
    fun getRelativePath(fullPath: String, repositoryPath: String): String =
      fullPath.replace(repositoryPath, "").replace("/+".toRegex(), "/")

    /** Gets the Parent path, relative to the repository */
    fun getParentPath(fullPath: String, repositoryPath: String): String {
      val relativePath = getRelativePath(fullPath, repositoryPath)
      val index = relativePath.lastIndexOf("/")
      return "/${relativePath.substring(startIndex = 0, endIndex = index + 1)}/"
        .replace("/+".toRegex(), "/")
    }

    /** /path/to/store/social/facebook.gpg -> social/facebook */
    @JvmStatic
    fun getLongName(fullPath: String, repositoryPath: String, basename: String): String {
      var relativePath = getRelativePath(fullPath, repositoryPath)
      return if (relativePath.isNotEmpty() && relativePath != "/") {
        // remove preceding '/'
        relativePath = relativePath.substring(1)
        if (relativePath.endsWith('/')) {
          relativePath + basename
        } else {
          "$relativePath/$basename"
        }
      } else {
        basename
      }
    }
  }
}
