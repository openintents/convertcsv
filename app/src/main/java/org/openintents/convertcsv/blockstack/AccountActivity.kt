package org.openintents.convertcsv.blockstack


import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.NavUtils
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_account.*
import kotlinx.android.synthetic.main.content_account.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import org.blockstack.android.sdk.BlockstackSession
import org.blockstack.android.sdk.Executor
import org.blockstack.android.sdk.PutFileOptions
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

        signInButton.isEnabled = false
        signOutButton.isEnabled = false

        val ref: Ref<AccountActivity> = this.asReference()

        launch(UI) {
            async(CommonPool) {
                _blockstackSession = BlockstackSession(ref(), defaultConfig, executor = object: Executor {
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
                        } catch (e:Exception) {
                            Log.d(TAG, "error in network thread", e)
                        }
                    }

                    override fun onV8Thread(function: () -> Unit) {
                        async(CommonPool) {
                            function()
                        }
                    }

                })
                if (intent?.action == Intent.ACTION_VIEW) {
                    handleAuthResponse(intent)
                }
            }.await()
            onLoaded()
        }

        signInButton.setOnClickListener { _ ->
            blockstackSession().redirectUserToSignIn { _ ->
                Log.d(TAG, "signed in error!")
            }
        }

        signOutButton.setOnClickListener { _ ->
            launch(UI) {
                async(CommonPool) {
                    blockstackSession().signUserOut()
                }.await()
                Log.d(TAG, "signed out!")
                finish()
            }
        }

        putFileButton.setOnClickListener { _ ->
            launch(UI) {
                async(CommonPool) {
                    blockstackSession().putFile("test.csv", "no data", PutFileOptions(false)) {
                        Log.d(TAG, "put done " + it.value + " " + it.error)
                    }
                }
            }
        }
    }

    private fun onLoaded() {
        signInButton.isEnabled = true
        signOutButton.isEnabled = true
        launch(UI) {
            val signedIn = async(CommonPool) {
                blockstackSession().isUserSignedIn()
            }.await()

            if (signedIn) {
                signInButton.visibility = View.GONE
                signOutButton.visibility = View.VISIBLE
            } else {
                signInButton.visibility = View.VISIBLE
                signOutButton.visibility = View.GONE
            }
        }
    }

    private fun onSignIn() {
        blockstackSession().loadUserData()
        finish()
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
            blockstackSession().handlePendingSignIn(authResponse, {
                if (it.hasErrors) {
                    Toast.makeText(this, it.error, Toast.LENGTH_SHORT).show()
                } else {
                    Log.d(TAG, "signed in!")
                    runOnUiThread {
                        onSignIn()
                    }
                }
            })
        }
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        if (item!!.itemId == android.R.id.home) {
            NavUtils.navigateUpFromSameTask(this)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    fun blockstackSession(): BlockstackSession {
        val session = _blockstackSession
        if (session != null) {
            return session
        } else {
            throw IllegalStateException("No session.")
        }
    }
}


