/*
 * Copyright © 2014-2020 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.zeapo.pwdstore.git

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.github.ajalt.timberkt.e
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.userauth.UserAuthException
import org.eclipse.jgit.api.CommitCommand
import org.eclipse.jgit.api.PullCommand
import org.eclipse.jgit.api.PushCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.StatusCommand
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteRefUpdate

class GitCommandExecutor(
    private val activity: AppCompatActivity,
    private val operation: GitOperation,
    private val credentialsProvider: CredentialsProvider?,
    private val finishWithResultOnEnd: Intent = Intent()
) {

    sealed class Result {
        object Ok : Result()
        data class Err(val err: Exception) : Result()
    }

    suspend fun execute(): Result {
        credentialsProvider?.let { provider ->
            operation.commands.filterIsInstance<TransportCommand<*, *>>().map { cmd ->
                cmd.setCredentialsProvider(provider)
            }
        }
        var nbChanges = 0
        var operationResult: Result = Result.Ok
        for (command in operation.commands) {
            try {
                when (command) {
                    is StatusCommand -> {
                        // in case we have changes, we want to keep track of it
                        val status = withContext(Dispatchers.IO) {
                            command.call()
                        }
                        nbChanges = status.changed.size + status.missing.size
                    }
                    is CommitCommand -> {
                        // the previous status will eventually be used to avoid a commit
                        withContext(Dispatchers.IO) {
                            if (nbChanges > 0) command.call()
                        }
                    }
                    is PullCommand -> {
                        val result = withContext(Dispatchers.IO) {
                            command.call()
                        }
                        val rr = result.rebaseResult
                        if (rr.status === RebaseResult.Status.STOPPED) {
                            operationResult = Result.Err(PullException(PullException.Reason.REBASE_FAILED))
                        }
                    }
                    is PushCommand -> {
                        val results = withContext(Dispatchers.IO) {
                            command.call()
                        }
                        for (result in results) {
                            // Code imported (modified) from Gerrit PushOp, license Apache v2
                            for (rru in result.remoteUpdates) {
                                val error = when (rru.status) {
                                    RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> {
                                        PushException(PushException.Reason.NON_FAST_FORWARD)
                                    }
                                    RemoteRefUpdate.Status.REJECTED_NODELETE,
                                    RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED,
                                    RemoteRefUpdate.Status.NON_EXISTING,
                                    RemoteRefUpdate.Status.NOT_ATTEMPTED,
                                    -> {
                                        PushException(PushException.Reason.GENERIC, rru.status.name)
                                    }
                                    RemoteRefUpdate.Status.REJECTED_OTHER_REASON -> {
                                        if ("non-fast-forward" == rru.message) {
                                            PushException(PushException.Reason.REMOTE_REJECTED)
                                        } else {
                                            PushException(PushException.Reason.GENERIC, rru.message)
                                        }
                                    }
                                    else -> null

                                }
                                if (error != null) {
                                    operationResult = Result.Err(error)
                                }
                            }
                        }
                    }
                    else -> {
                        withContext(Dispatchers.IO) {
                            command.call()
                        }
                    }
                }
            } catch (e: Exception) {
                operationResult = Result.Err(e)
            }
        }
        return operationResult
    }

    fun postExecute(operationResult: Result) {
        when (operationResult) {
            is Result.Err -> {
                if (isExplicitlyUserInitiatedError(operationResult.err)) {
                    // Currently, this is only executed when the user cancels a password prompt
                    // during authentication.
                    activity.setResult(Activity.RESULT_CANCELED)
                    activity.finish()
                } else {
                    e(operationResult.err)
                    operation.onError(rootCauseException(operationResult.err))
                    activity.setResult(Activity.RESULT_CANCELED)
                }
            }
            is Result.Ok -> {
                operation.onSuccess()
                activity.setResult(Activity.RESULT_OK, finishWithResultOnEnd)
                activity.finish()
            }
        }
    }

    private fun isExplicitlyUserInitiatedError(e: Exception): Boolean {
        var cause: Exception? = e
        while (cause != null) {
            if (cause is SSHException &&
                cause.disconnectReason == DisconnectReason.AUTH_CANCELLED_BY_USER)
                return true
            cause = cause.cause as? Exception
        }
        return false
    }

    private fun rootCauseException(e: Exception): Exception {
        var rootCause = e
        // JGit's TransportException hides the more helpful SSHJ exceptions.
        // Also, SSHJ's UserAuthException about exhausting available authentication methods hides
        // more useful exceptions.
        while ((rootCause is org.eclipse.jgit.errors.TransportException ||
                rootCause is org.eclipse.jgit.api.errors.TransportException ||
                (rootCause is UserAuthException &&
                    rootCause.message == "Exhausted available authentication methods"))) {
            rootCause = rootCause.cause as? Exception ?: break
        }
        return rootCause
    }
}
