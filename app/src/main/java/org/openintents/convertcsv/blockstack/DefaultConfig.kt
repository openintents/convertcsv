package org.openintents.convertcsv.blockstack

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import org.blockstack.android.sdk.Scope
import org.blockstack.android.sdk.SessionStore
import org.blockstack.android.sdk.toBlockstackConfig

val defaultConfig = "https://convertcsv.openintents.org".toBlockstackConfig(
        kotlin.arrayOf(Scope.StoreWrite))

private var sessionStore: SessionStore? = null
fun getSessionStore(context:Context):SessionStore {
    if (sessionStore == null) {
        sessionStore = SessionStore(PreferenceManager.getDefaultSharedPreferences(context))
    }
    return sessionStore!!
}

