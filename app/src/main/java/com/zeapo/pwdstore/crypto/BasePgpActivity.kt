/*
 * Copyright © 2014-2020 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.zeapo.pwdstore.crypto

import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.os.Bundle
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import com.github.ajalt.timberkt.Timber.tag
import com.github.ajalt.timberkt.e
import com.github.ajalt.timberkt.i
import com.google.android.material.snackbar.Snackbar
import com.zeapo.pwdstore.R
import com.zeapo.pwdstore.UserPreference
import me.msfjarvis.openpgpktx.util.OpenPgpApi
import me.msfjarvis.openpgpktx.util.OpenPgpServiceConnection
import org.apache.commons.io.FilenameUtils
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.OpenPgpError

@Suppress("Registered")
open class BasePgpActivity : AppCompatActivity(), OpenPgpServiceConnection.OnBound {

    /**
     * Full path to the repository
     */
    val repoPath: String by lazy { intent.getStringExtra("REPO_PATH") }

    /**
     * Full path to the password file being worked on
     */
    val fullPath: String by lazy { intent.getStringExtra("FILE_PATH") }

    /**
     * Name of the password file
     *
     * Converts personal/auth.foo.org/john_doe@example.org.gpg to john_doe.example.org.gpg
     */
    val name: String by lazy { getName(fullPath) }

    /**
     * Get the timestamp for when this file was last modified.
     */
    val lastChangedString: CharSequence by lazy {
        getLastChangedString(
            intent.getLongExtra(
                "LAST_CHANGED_TIMESTAMP",
                -1L
            )
        )
    }

    /**
     * [SharedPreferences] instance used by subclasses to persist settings
     */
    val settings: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    /**
     * [ClipboardManager] instance used by subclasses. Most direct subclasses do not use it so we lazily
     * init this.
     */
    val clipboard by lazy { getSystemService<ClipboardManager>() }

    /**
     * Backing property and public read-only field for getting the list of OpenPGP key IDs that we
     * have access to.
     */
    private var _keyIDs = emptySet<String>()
    val keyIDs get() = _keyIDs

    /**
     * Handle to the [OpenPgpApi] instance that is used by subclasses to interface with OpenKeychain.
     */
    private var serviceConnection: OpenPgpServiceConnection? = null
    var api: OpenPgpApi? = null

    /**
     * [onCreate] sets the window up with the right flags to prevent auth leaks through screenshots
     * or recent apps screen and fills in [_keyIDs] from [settings]
     */
    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        tag(TAG)

        _keyIDs = settings.getStringSet("openpgp_key_ids_set", null) ?: emptySet()
    }

    /**
     * [onDestroy] handles unbinding from the OpenPgp service linked with [serviceConnection]. This
     * is annotated with [CallSuper] because it's critical to unbind the service to ensure we're not
     * leaking things.
     */
    @CallSuper
    override fun onDestroy() {
        super.onDestroy()
        serviceConnection?.unbindFromService()
    }

    /**
     * Sets up [api] once the service is bound. Downstream consumers must call super this to
     * initialize [api]
     */
    @CallSuper
    override fun onBound(service: IOpenPgpService2) {
        api = OpenPgpApi(this, service)
    }

    /**
     * Mandatory error handling from [OpenPgpServiceConnection.OnBound]. All subclasses must handle
     * their own errors, and hence this class simply logs and rethrows. Subclasses Must NOT call super.
     */
    override fun onError(e: Exception) {
        e { "Callers must handle their own exceptions" }
        throw e
    }

    /**
     * Method for subclasses to initiate binding with [OpenPgpServiceConnection]. The design choices
     * here are a bit dubious at first glance. We require passing a [ActivityResultLauncher] because
     * it lets us react to having a OpenPgp provider selected without relying on the now deprecated
     * [startActivityForResult].
     */
    fun bindToOpenKeychain(onBoundListener: OpenPgpServiceConnection.OnBound, activityResult: ActivityResultLauncher<Intent>) {
        val providerPackageName = settings.getString("openpgp_provider_list", "")
        if (providerPackageName.isNullOrEmpty()) {
            Toast.makeText(this, resources.getString(R.string.provider_toast_text), Toast.LENGTH_LONG).show()
            activityResult.launch(Intent(this, UserPreference::class.java))
        } else {
            serviceConnection = OpenPgpServiceConnection(this, providerPackageName, onBoundListener)
            serviceConnection?.bindToService()
        }
    }

    /**
     * Handle the case where OpenKeychain returns that it needs to interact with the user
     *
     * @param result The intent returned by OpenKeychain
     */
    fun getUserInteractionRequestIntent(result: Intent): IntentSender {
        i { "RESULT_CODE_USER_INTERACTION_REQUIRED" }
        return (result.getParcelableExtra(OpenPgpApi.RESULT_INTENT) as PendingIntent).intentSender
    }

    /**
     * Gets a relative string describing when this shape was last changed
     * (e.g. "one hour ago")
     */
    private fun getLastChangedString(timeStamp: Long): CharSequence {
        if (timeStamp < 0) {
            throw RuntimeException()
        }

        return DateUtils.getRelativeTimeSpanString(this, timeStamp, true)
    }

    /**
     * Shows a [Snackbar] with the provided [message] and [length]
     */
    fun showSnackbar(message: String, length: Int = Snackbar.LENGTH_SHORT) {
        runOnUiThread { Snackbar.make(findViewById(android.R.id.content), message, length).show() }
    }

    /**
     * Base handling of OpenKeychain errors based on the error contained in [result]. Subclasses
     * can use this when they want to default to sane error handling.
     */
    fun handleError(result: Intent) {
        val error: OpenPgpError? = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR)
        if (error != null) {
            when (error.errorId) {
                OpenPgpError.NO_OR_WRONG_PASSPHRASE -> {
                    showSnackbar(getString(R.string.openpgp_error_wrong_passphrase))
                }
                OpenPgpError.NO_USER_IDS -> {
                    showSnackbar(getString(R.string.openpgp_error_no_user_ids))
                }
                else -> {
                    showSnackbar(getString(R.string.openpgp_error_unknown, error.message))
                    e { "onError getErrorId: ${error.errorId}" }
                    e { "onError getMessage: ${error.message}" }
                }
            }
        }
    }

    companion object {
        private const val TAG = "APS/BasePgpActivity"
        const val KEY_PWGEN_TYPE_CLASSIC = "classic"
        const val KEY_PWGEN_TYPE_XKPASSWD = "xkpasswd"

        /**
         * Gets the relative path to the repository
         */
        fun getRelativePath(fullPath: String, repositoryPath: String): String =
            fullPath.replace(repositoryPath, "").replace("/+".toRegex(), "/")

        /**
         * Gets the Parent path, relative to the repository
         */
        fun getParentPath(fullPath: String, repositoryPath: String): String {
            val relativePath = getRelativePath(fullPath, repositoryPath)
            val index = relativePath.lastIndexOf("/")
            return "/${relativePath.substring(startIndex = 0, endIndex = index + 1)}/".replace("/+".toRegex(), "/")
        }

        /**
         * Gets the name of the password (excluding .gpg)
         */
        fun getName(fullPath: String): String {
            return FilenameUtils.getBaseName(fullPath)
        }

        /**
         * /path/to/store/social/facebook.gpg -> social/facebook
         */
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
