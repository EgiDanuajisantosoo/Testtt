package com.egidanuajisantoso.test.storage

import android.content.Context
import android.net.Uri

class TreeUriStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun saveDatasetTreeUri(uri: Uri?) {
        prefs.edit().putString(KEY_DATASET_TREE_URI, uri?.toString()).apply()
    }

    fun loadDatasetTreeUri(): Uri? {
        val value = prefs.getString(KEY_DATASET_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(value) }.getOrNull()
    }

    companion object {
        private const val PREF_NAME = "scanner_tree_store"
        private const val KEY_DATASET_TREE_URI = "dataset_tree_uri"
    }
}

