package org.openintents.convertcsv.blockstack


import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.support.v4.app.NavUtils
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_account.*
import kotlinx.android.synthetic.main.content_account.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.android.UI
import org.blockstack.android.sdk.BlockstackSession
import org.blockstack.android.sdk.Executor
import org.jetbrains.anko.coroutines.experimental.Ref
import org.jetbrains.anko.coroutines.experimental.asReference
import org.openintents.convertcsv.R


class AccountActivity : AppCompatActivity() {
    private val TAG = AccountActivity::class.java.simpleName

    private var _blockstackSession: BlockstackSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)
        setSupportActionBar(toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        setLoadingUI(true)

        val ref: Ref<AccountActivity> = this.asReference()

        launch(UI) {
            async(v8Context) {
                _blockstackSession = BlockstackSession(ref(), defaultConfig, executor = object : Executor {
                    override fun onMainThread(function: (Context) -> Unit) {
                        runOnUiThread {
                            function(this@AccountActivity)
                        }
                    }

                    override fun onNetworkThread(function: suspend () -> Unit) {
                        try {
                            async(CommonPool) {
                                function()
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "error in network thread", e)
                        }
                    }

                    override fun onV8Thread(function: () -> Unit) {
                        runOnV8Thread {
                            function()
                        }
                    }

                }, sessionStore = getSessionStore(this@AccountActivity))
                if (intent?.action == Intent.ACTION_VIEW) {
                    handleAuthResponse(intent)
                }
            }.await()
            onLoaded()
        }

        signInButton.setOnClickListener { _ ->
            launch(UI) {
                runOnV8Thread {
                    blockstackSession().signUserOut()
                    blockstackSession().redirectUserToSignIn { _ ->
                        Log.d(TAG, "signed in redirect")
                    }
                }
            }
        }

        signOutButton.setOnClickListener { _ ->
            launch(UI) {
                runOnV8Thread {
                    blockstackSession().signUserOut()
                }.await()
                notifyDocumentUI()
                Log.d(TAG, "signed out!")
                finish()
            }
        }
    }

    private fun onLoaded() {
        launch(UI) {
            val signedIn = runOnV8Thread {
                blockstackSession().isUserSignedIn()
            }.await()
            setLoadingUI(false)

            if (signedIn) {
                signInButton.visibility = View.GONE
                signOutButton.visibility = View.VISIBLE
            } else {
                signInButton.visibility = View.VISIBLE
                signOutButton.visibility = View.GONE
            }
            notifyDocumentUI()
        }
    }

    private fun onSignIn() {
        launch(UI) {
            runOnV8Thread {
                blockstackSession().loadUserData()
            }.await()
            notifyDocumentUI()
            finish()
        }
    }

    private fun notifyDocumentUI() {
        val rootsUri = DocumentsContract.buildRootsUri("org.openintents.convertcsv.documents")
        this@AccountActivity.getContentResolver().notifyChange(rootsUri, null)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent")

        if (intent?.action == Intent.ACTION_VIEW) {
            handleAuthResponse(intent)
        }

    }

    private fun handleAuthResponse(intent: Intent?) {
        val authResponse = intent?.data?.getQueryParameter("authResponse")
        if (authResponse != null) {
            Log.d(TAG, "authResponse: ${authResponse}")
            setLoadingUI(true)
            runOnV8Thread {
                try {
                    Log.d(TAG, "before signed in!")
                    blockstackSession().handlePendingSignIn(authResponse, {
                        Log.d(TAG, "signed in result " + it.error + " " + it.value)
                        if (it.hasErrors) {
                            Toast.makeText(this@AccountActivity, it.error, Toast.LENGTH_SHORT).show()
                        } else {
                            Log.d(TAG, "signed in!")
                            runOnUiThread {
                                onSignIn()
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.d(TAG, "signed in error", e)
                }
            }
        }
    }

    private fun setLoadingUI(loading: Boolean) {
        if (loading) {
            signInButton.isEnabled = false
            signOutButton.isEnabled = false
            accountDescription.visibility = View.VISIBLE
        } else {
            signInButton.isEnabled = true
            signOutButton.isEnabled = true
            accountDescription.visibility = View.INVISIBLE
        }
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        if (item!!.itemId == android.R.id.home) {
            NavUtils.navigateUpFromSameTask(this)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    fun <T> runOnV8Thread(runnable: () -> T): Deferred<T> {
        return async(v8Context) {
            runnable()
        }

    }

    fun blockstackSession(): BlockstackSession {
        val session = _blockstackSession
        if (session != null) {
            return session
        } else {
            Log.d(TAG, "too early")
            throw IllegalStateException("No session.")
        }
    }
}

val v8Context = newFixedThreadPoolContext(1, "v8Thread")


