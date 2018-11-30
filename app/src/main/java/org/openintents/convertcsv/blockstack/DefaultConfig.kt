package org.openintents.convertcsv.blockstack

import org.blockstack.android.sdk.Scope
import org.blockstack.android.sdk.toBlockstackConfig

val defaultConfig = "https://convertcsv.openintents.org".toBlockstackConfig(
        kotlin.arrayOf(Scope.StoreWrite))
