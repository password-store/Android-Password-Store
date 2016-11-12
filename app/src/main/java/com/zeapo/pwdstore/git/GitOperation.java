package com.zeapo.pwdstore.git;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import com.zeapo.pwdstore.R;
import com.zeapo.pwdstore.UserPreference;
import com.zeapo.pwdstore.git.config.GitConfigSessionFactory;
import com.zeapo.pwdstore.git.config.SshConfigSessionFactory;
import com.zeapo.pwdstore.utils.PasswordRepository;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.GitCommand;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;

public abstract class GitOperation implements GitTaskHandler {
    private static final String TAG = "GitOpt";
    public static final int GET_SSH_KEY_FROM_CLONE = 201;

    protected final Repository repository;
    protected final Activity callingActivity;
    protected UsernamePasswordCredentialsProvider provider;
    protected GitCommand command;

    /**
     * Creates a new git operation
     *
     * @param fileDir         the git working tree directory
     * @param callingActivity the calling activity
     */
    public GitOperation(File fileDir, Activity callingActivity) {
        this.repository = PasswordRepository.getRepository(fileDir);
        this.callingActivity = callingActivity;
    }

    /**
     * Sets the authentication using user/pwd scheme
     *
     * @param username the username
     * @param password the password
     * @return the current object
     */
    public GitOperation setAuthentication(String username, String password) {
        SshSessionFactory.setInstance(new GitConfigSessionFactory());
        this.provider = new UsernamePasswordCredentialsProvider(username, password);
        return this;
    }

    /**
     * Sets the authentication using ssh-key scheme
     *
     * @param sshKey     the ssh-key file
     * @param username   the username
     * @param passphrase the passphrase
     * @return the current object
     */
    public GitOperation setAuthentication(File sshKey, String username, String passphrase) {
        JschConfigSessionFactory sessionFactory = new SshConfigSessionFactory(sshKey.getAbsolutePath(), username, passphrase);
        SshSessionFactory.setInstance(sessionFactory);
        this.provider = null;
        return this;
    }

    /**
     * Executes the GitCommand in an async task
     *
     * @throws Exception
     */
    public abstract void execute() throws Exception;

    /**
     * Executes the GitCommand in an async task after creating the authentication
     *
     * @param connectionMode the server-connection mode
     * @param username       the username
     * @param sshKey         the ssh-key file
     * @throws Exception
     */
    public void executeAfterAuthentication(final String connectionMode, final String username, @Nullable final File sshKey) throws Exception {
        executeAfterAuthentication(connectionMode, username, sshKey, false);
    }

    /**
     * Executes the GitCommand in an async task after creating the authentication
     *
     * @param connectionMode the server-connection mode
     * @param username       the username
     * @param sshKey         the ssh-key file
     * @param showError      show the passphrase edit text in red
     * @throws Exception
     */
    private void executeAfterAuthentication(final String connectionMode, final String username, @Nullable final File sshKey, final boolean showError) throws Exception {
        if (connectionMode.equalsIgnoreCase("ssh-key")) {
            if (sshKey == null || !sshKey.exists()) {
                new AlertDialog.Builder(callingActivity)
                        .setMessage(callingActivity.getResources().getString(R.string.ssh_preferences_dialog_text))
                        .setTitle(callingActivity.getResources().getString(R.string.ssh_preferences_dialog_title))
                        .setPositiveButton(callingActivity.getResources().getString(R.string.ssh_preferences_dialog_import), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int id) {
                                try {
                                    // Ask the UserPreference to provide us with the ssh-key
                                    // onResult has to be handled by the callingActivity
                                    Intent intent = new Intent(callingActivity.getApplicationContext(), UserPreference.class);
                                    intent.putExtra("operation", "get_ssh_key");
                                    callingActivity.startActivityForResult(intent, GET_SSH_KEY_FROM_CLONE);
                                } catch (Exception e) {
                                    System.out.println("Exception caught :(");
                                    e.printStackTrace();
                                }
                            }
                        })
                        .setNegativeButton(callingActivity.getResources().getString(R.string.ssh_preferences_dialog_generate), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    // Duplicated code
                                    Intent intent = new Intent(callingActivity.getApplicationContext(), UserPreference.class);
                                    intent.putExtra("operation", "make_ssh_key");
                                    callingActivity.startActivityForResult(intent, GET_SSH_KEY_FROM_CLONE);
                                } catch (Exception e) {
                                    System.out.println("Exception caught :(");
                                    e.printStackTrace();
                                }
                            }
                        })
                        .setNeutralButton(callingActivity.getResources().getString(R.string.dialog_cancel), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int id) {
                                // Finish the blank GitActivity so user doesn't have to press back
                                callingActivity.finish();
                            }
                        }).show();
            } else {
                final EditText passphrase = new EditText(callingActivity);
                passphrase.setHint("Passphrase");
                passphrase.setWidth(LinearLayout.LayoutParams.MATCH_PARENT);
                passphrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                if (showError) {
                    passphrase.setError("Wrong passphrase");
                }
                JSch jsch = new JSch();
                final KeyPair keyPair = KeyPair.load(jsch, callingActivity.getFilesDir() + "/.ssh_key");
                if (keyPair.isEncrypted()) {
                    new AlertDialog.Builder(callingActivity)
                            .setTitle(callingActivity.getResources().getString(R.string.passphrase_dialog_title))
                            .setMessage(callingActivity.getResources().getString(R.string.passphrase_dialog_text))
                            .setView(passphrase)
                            .setPositiveButton(callingActivity.getResources().getString(R.string.dialog_ok), new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int whichButton) {
                                    try {
                                        if (keyPair.decrypt(passphrase.getText().toString())) {
                                            // Authenticate using the ssh-key and then execute the command
                                            setAuthentication(sshKey, username, passphrase.getText().toString()).execute();
                                        } else {
                                            // call back the method
                                            executeAfterAuthentication(connectionMode, username, sshKey, true);
                                        }
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }

                                }
                            }).setNegativeButton(callingActivity.getResources().getString(R.string.dialog_cancel), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            // Do nothing.
                        }
                    }).show();
                } else {
                    setAuthentication(sshKey, username, "").execute();
                }
            }
        } else {
            final EditText password = new EditText(callingActivity);
            password.setHint("Password");
            password.setWidth(LinearLayout.LayoutParams.MATCH_PARENT);
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

            new AlertDialog.Builder(callingActivity)
                    .setTitle(callingActivity.getResources().getString(R.string.passphrase_dialog_title))
                    .setMessage(callingActivity.getResources().getString(R.string.password_dialog_text))
                    .setView(password)
                    .setPositiveButton(callingActivity.getResources().getString(R.string.dialog_ok), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            // authenticate using the user/pwd and then execute the command
                            try {
                                setAuthentication(username, password.getText().toString()).execute();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                        }
                    }).setNegativeButton(callingActivity.getResources().getString(R.string.dialog_cancel), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    // Do nothing.
                }
            }).show();
        }
    }

    //TODO remove this once implemented in subclasses
    @Override
    public void onTaskEnded(String result) {
        new AlertDialog.Builder(callingActivity).
                setTitle(callingActivity.getResources().getString(R.string.jgit_error_dialog_title)).
                setMessage(callingActivity.getResources().getString(R.string.jgit_error_dialog_text) + result).
                setPositiveButton(callingActivity.getResources().getString(R.string.dialog_ok), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        if (this.getClass().equals(CloneCommand.class)) {
                            // if we were unable to finish the job
                            try {
                                FileUtils.deleteDirectory(PasswordRepository.getWorkTree());
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else {
                            callingActivity.setResult(Activity.RESULT_CANCELED);
                            callingActivity.finish();
                        }
                    }
                }).show();
    }
}
