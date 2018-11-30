package org.openintents.convertcsv.blockstack


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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.blockstack.android.sdk.BlockstackSession
import org.jetbrains.anko.coroutines.experimental.*
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

        GlobalScope.launch(Dispatchers.IO) {
            _blockstackSession = BlockstackSession(ref(), defaultConfig)
            if (intent?.action == Intent.ACTION_VIEW) {
                handleAuthResponse(intent)
            }
            runOnUiThread {
                onLoaded()
            }
        }

        signInButton.setOnClickListener { _ ->
            blockstackSession().redirectUserToSignIn { _ ->
                Log.d(TAG, "signed in error!")
            }
        }

        signOutButton.setOnClickListener { _ ->
            blockstackSession().signUserOut()
            Log.d(TAG, "signed out!")
            finish()
        }
    }

    private fun onLoaded() {
        signInButton.isEnabled = true
        signOutButton.isEnabled = true
        GlobalScope.launch(Dispatchers.IO) {
            val signedIn = blockstackSession().isUserSignedIn()
            runOnUiThread {
                if (signedIn) {
                    signInButton.visibility = View.GONE
                    signOutButton.visibility = View.VISIBLE
                } else {
                    signInButton.visibility = View.VISIBLE
                    signOutButton.visibility = View.GONE
                }
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


