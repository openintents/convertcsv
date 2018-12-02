/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.openintents.convertcsv.blockstack

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import org.blockstack.android.sdk.BlockstackSession
import org.blockstack.android.sdk.Executor
import org.blockstack.android.sdk.GetFileOptions
import org.blockstack.android.sdk.PutFileOptions
import org.openintents.convertcsv.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.*

/**
 * Manages documents and exposes them to the Android system for sharing.
 */
class ConvertCSVProvider : DocumentsProvider() {

    // A file object at the root of the file hierarchy.  Depending on your implementation, the root
    // does not need to be an existing file system directory.  For example, a tag-based document
    // provider might return a directory containing all tags, represented as child directories.
    private var mBaseDir: GaiaFile? = null

    private var mSession: BlockstackSession? = null

    private lateinit var handlerThread: HandlerThread

    private lateinit var handler: Handler

    override fun onCreate(): Boolean {
        Log.v(TAG, "onCreate")
        handlerThread = HandlerThread("ConvertCSVProvider")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        runOnV8Thread {
            mSession = BlockstackSession(context, defaultConfig, executor = object : Executor {
                override fun onMainThread(function: (Context) -> Unit) {
                    launch(UI) {
                        function.invoke(getContext())
                    }
                }

                override fun onV8Thread(function: () -> Unit) {
                    runOnV8Thread {
                        function.invoke()
                    }
                }

                override fun onNetworkThread(function: suspend () -> Unit) {
                    async(CommonPool) {
                        function.invoke()
                    }
                }
            }, sessionStore = getSessionStore(context))
        }
        mBaseDir = GaiaFile("", true)

        return true
    }


    fun runOnV8Thread(runnable: () -> Unit) {
        handler.post(runnable)
    }

    @Throws(FileNotFoundException::class)
    override fun queryRoots(projection: Array<String>?): Cursor {
        Log.v(TAG, "queryRoots")

        // Create a cursor with either the requested fields, or the default projection.  This
        // cursor is returned to the Android system picker UI and used to display all roots from
        // this provider.
        val result = MatrixCursor(resolveRootProjection(projection))

        var isUserLoggedIn = false
        var resultSet = false
        var title: String? = null
        runOnV8Thread {
            isUserLoggedIn = mSession?.isUserSignedIn() ?: false
            Log.d(TAG, "signed in " + isUserLoggedIn + " " + mSession)
            if (isUserLoggedIn) {
                title = mSession?.loadUserData()?.profile?.name
                if (title == null) {
                    title = mSession?.loadUserData()?.decentralizedID
                } else {
                    title = title + "(" + mSession?.loadUserData()?.decentralizedID + ")"
                }
                if (title == null) {
                    title = "unkown user"
                }
            } else {
                title = "unkown user2"
            }
            resultSet = true
        }

        while (!resultSet) {
            Thread.sleep(500)
        }

        // If user is not logged in, return an empty root cursor.  This removes our provider from
        // the list entirely.
        if (!isUserLoggedIn) {
            Log.d(TAG, "Not signed in")
            return result
        }

        // It's possible to have multiple roots (e.g. for multiple accounts in the same app) -
        // just add multiple cursor rows.
        // Construct one row for a root called "MyCloud".
        val row = result.newRow()

        row.add(Root.COLUMN_ROOT_ID, ROOT)
        row.add(Root.COLUMN_SUMMARY, title)

        // FLAG_SUPPORTS_CREATE means at least one directory under the root supports creating
        // documents.  FLAG_SUPPORTS_RECENTS means your application's most recently used
        // documents will show up in the "Recents" category.  FLAG_SUPPORTS_SEARCH allows users
        // to search all documents the application shares.
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or
                Root.FLAG_SUPPORTS_RECENTS or
                Root.FLAG_SUPPORTS_SEARCH)

        // COLUMN_TITLE is the root title (e.g. what will be displayed to identify your provider).
        row.add(Root.COLUMN_TITLE, context!!.getString(org.openintents.convertcsv.R.string.app_name))

        // This document id must be unique within this provider and consistent across time.  The
        // system picker UI may save it and refer to it later.
        row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(mBaseDir!!))

        // The child MIME types are used to filter the roots and only present to the user roots
        // that contain the desired type somewhere in their file hierarchy.
        row.add(Root.COLUMN_MIME_TYPES, getChildMimeTypes(mBaseDir!!))
        //row.add(Root.COLUMN_AVAILABLE_BYTES, mBaseDir!!.freeSpace)
        row.add(Root.COLUMN_ICON, R.drawable.ic_menu_convert_csv)

        return result
    }
    // END_INCLUDE(query_roots)

    // BEGIN_INCLUDE(query_recent_documents)
    @Throws(FileNotFoundException::class)
    override fun queryRecentDocuments(rootId: String, projection: Array<String>?): Cursor {
        Log.v(TAG, "queryRecentDocuments")

        // This example implementation walks a local file structure to find the most recently
        // modified files.  Other implementations might include making a network call to query a
        // server.

        // Create a cursor with the requested projection, or the default projection.
        val result = MatrixCursor(resolveDocumentProjection(projection))

        val parent = getFileForDocId(rootId)

        // Create a queue to store the most recent documents, which orders by last modified.
        val lastModifiedFiles = PriorityQueue(5, Comparator<GaiaFile> { i, j -> String.CASE_INSENSITIVE_ORDER.compare(i.path, j.path) })

        // Iterate through all files and directories in the file structure under the root.  If
        // the file is more recent than the least recently modified, add it to the queue,
        // limiting the number of results.
        val pending = LinkedList<GaiaFile>()

        var listFilesDone = false

        runOnV8Thread {
            mSession?.listFiles({ result ->
                if (result.hasValue) {
                    pending.add(GaiaFile(result.value!!, false))
                }
                true
            }, { count -> listFilesDone = true })
        }

        // Do while we still have unexamined files
        while (!listFilesDone) {
            if (pending.size > 0) {
                // Take a file from the list of unprocessed files
                val file = pending.removeFirst()
                // If it's a file, add it to the ordered queue.
                lastModifiedFiles.add(file)
            } else {
                Thread.sleep(500)
            }
        }

        // Add the most recent files to the cursor, not exceeding the max number of results.
        for (i in 0 until Math.min(MAX_LAST_MODIFIED + 1, lastModifiedFiles.size)) {
            val file = lastModifiedFiles.remove()
            includeFile(result, null, file)
        }
        return result
    }
    // END_INCLUDE(query_recent_documents)

    // BEGIN_INCLUDE(query_search_documents)
    @Throws(FileNotFoundException::class)
    override fun querySearchDocuments(rootId: String, query: String, projection: Array<String>?): Cursor {
        Log.v(TAG, "querySearchDocuments")

        // Create a cursor with the requested projection, or the default projection.
        val result = MatrixCursor(resolveDocumentProjection(projection))
        val parent = getFileForDocId(rootId)

        // This example implementation searches file names for the query and doesn't rank search
        // results, so we can stop as soon as we find a sufficient number of matches.  Other
        // implementations might use other data about files, rather than the file name, to
        // produce a match; it might also require a network call to query a remote server.

        // Iterate through all files in the file structure under the root until we reach the
        // desired number of matches.
        val pending = LinkedList<GaiaFile>()

        var listFilesDone = false

        runOnV8Thread {
            mSession?.listFiles({ result ->
                if (result.hasValue) {
                    pending.add(GaiaFile(result.value!!, false))
                }
                true
            }, { count -> listFilesDone = true })
        }

        // Do while we still have unexamined files
        while (!listFilesDone && result.count < MAX_SEARCH_RESULTS) {
            // Take a file from the list of unprocessed files
            val file = pending.removeFirst()
            // If it's a file, add it to the ordered queue.
            if (file.path.toLowerCase().contains(query)) {
                includeFile(result, null, file)
            }
        }

        return result
    }
    // END_INCLUDE(query_search_documents)

    // BEGIN_INCLUDE(open_document_thumbnail)
    @Throws(FileNotFoundException::class)
    override fun openDocumentThumbnail(documentId: String, sizeHint: Point,
                                       signal: CancellationSignal): AssetFileDescriptor {
        Log.v(TAG, "openDocumentThumbnail")

        val file = getFileForDocId(documentId)
        val pfd = ParcelFileDescriptor.open(File("/"), ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }
    // END_INCLUDE(open_document_thumbnail)

    // BEGIN_INCLUDE(query_document)
    @Throws(FileNotFoundException::class)
    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        Log.v(TAG, "queryDocument")

        // Create a cursor with the requested projection, or the default projection.
        val result = MatrixCursor(resolveDocumentProjection(projection))
        includeFile(result, documentId, null)
        return result
    }
    // END_INCLUDE(query_document)

    // BEGIN_INCLUDE(query_child_documents)
    @Throws(FileNotFoundException::class)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?,
                                     sortOrder: String): Cursor {
        Log.v(TAG, "queryChildDocuments, parentDocumentId: " +
                parentDocumentId +
                " sortOrder: " +
                sortOrder)

        val result = MatrixCursor(resolveDocumentProjection(projection))
        val parent = getFileForDocId(parentDocumentId)

        val pending = LinkedList<GaiaFile>()

        var listFilesDone = false

        runOnV8Thread {
            mSession?.listFiles({ result ->
                if (result.hasValue) {
                    Log.d(TAG, "found " + result.value)
                    pending.add(GaiaFile(result.value!!, false))
                }
                true
            }, { count -> listFilesDone = true })
        }

        // Do while we still have unexamined files
        while (!listFilesDone || pending.size > 0) {
            if (pending.size > 0) {
                // Take a file from the list of unprocessed files
                val file = pending.removeFirst()
                includeFile(result, null, file)
            } else {
                Thread.sleep(500)
            }
        }
        Log.d(TAG, "size " + result.count)
        return result
    }
    // END_INCLUDE(query_child_documents)


    // BEGIN_INCLUDE(open_document)
    @Throws(FileNotFoundException::class)
    override fun openDocument(documentId: String, mode: String,
                              signal: CancellationSignal?): ParcelFileDescriptor {
        Log.v(TAG, "openDocument $documentId, mode: $mode")
        // It's OK to do network operations in this method to download the document, as long as you
        // periodically check the CancellationSignal.  If you have an extremely large file to
        // transfer from the network, a better solution may be pipes or sockets
        // (see ParcelFileDescriptor for helper methods).

        val file = getFileForDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)

        val outputDir = context.cacheDir // context being the Activity pointer
        val outputFile = File.createTempFile("gaia", file!!.path, outputDir)

        val isWrite = mode.indexOf('w') != -1
        if (isWrite) {
            val mainHandler = Handler(context!!.mainLooper)
            return ParcelFileDescriptor.open(outputFile, accessMode, mainHandler) {
                Log.d(TAG, "closing file " + it?.toString())

                val options = PutFileOptions()
                var putFileDone = false
                val inputStream: InputStream = File(outputFile.path).inputStream()
                val inputString = inputStream.bufferedReader().use { it.readText() }

                try {
                    runOnV8Thread {
                        try {
                            mSession?.putFile(file.path, inputString, options) {
                                Log.d(TAG, it.toString())
                                putFileDone = true
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "in v8", e)
                        }
                    }

                    while (!putFileDone) {
                        Thread.sleep(500)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "in closing ", e)
                }
            }
        } else {
            val options = GetFileOptions()
            var getFileDone = false

            runOnV8Thread {
                mSession?.getFile(file.path, options) {
                    if (it.hasValue) {
                        it.value
                        if (it.value is String) {
                            outputFile.writeText(it.value as String)
                        } else {
                            outputFile.writeBytes(it.value as ByteArray)
                        }
                    }
                    getFileDone = true
                }
            }

            while (!getFileDone) {
                Thread.sleep(500)
            }

            return ParcelFileDescriptor.open(outputFile, accessMode)
        }
    }
    // END_INCLUDE(open_document)


    // BEGIN_INCLUDE(create_document)
    @Throws(FileNotFoundException::class)
    override fun createDocument(documentId: String, mimeType: String, displayName: String): String {
        Log.v(TAG, "createDocument")

        val parent = getFileForDocId(documentId)
        val file = GaiaFile(parent!!.path + "/" + displayName, false)
        try {
            // TODO create file
        } catch (e: IOException) {
            throw FileNotFoundException("Failed to create document with name " +
                    displayName + " and documentId " + documentId)
        }

        return getDocIdForFile(file)
    }
    // END_INCLUDE(create_document)


    @Throws(FileNotFoundException::class)
    override fun getDocumentType(documentId: String): String {
        val file = getFileForDocId(documentId)
        return getTypeForFile(file!!)
    }

    /**
     * Gets a string of unique MIME data types a directory supports, separated by newlines.  This
     * should not change.
     *
     * @param parent the File for the parent directory
     * @return a string of the unique MIME data types the parent directory supports
     */
    private fun getChildMimeTypes(parent: GaiaFile): String {
        val mimeTypes = HashSet<String>()
        mimeTypes.add("image/*")
        mimeTypes.add("text/*")
        mimeTypes.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document")

        // Flatten the list into a string and insert newlines between the MIME type strings.
        val mimeTypesString = StringBuilder()
        for (mimeType in mimeTypes) {
            mimeTypesString.append(mimeType).append("\n")
        }

        return mimeTypesString.toString()
    }

    /**
     * Get the document ID given a File.  The document id must be consistent across time.  Other
     * applications may save the ID and use it to reference documents later.
     *
     *
     * This implementation is specific to this demo.  It assumes only one root and is built
     * directly from the file structure.  However, it is possible for a document to be a child of
     * multiple directories (for example "android" and "images"), in which case the file must have
     * the same consistent, unique document ID in both cases.
     *
     * @param file the File whose document ID you want
     * @return the corresponding document ID
     */
    private fun getDocIdForFile(file: GaiaFile): String {
        var path = file.path

        // Start at first char of path under root
        val rootPath = mBaseDir!!.path
        if (rootPath == path) {
            return ROOT
        }

        return path
    }

    /**
     * Add a representation of a file to a cursor.
     *
     * @param result the cursor to modify
     * @param docId  the document ID representing the desired file (may be null if given file)
     * @param file   the File object representing the desired file (may be null if given docID)
     * @throws java.io.FileNotFoundException
     */
    @Throws(FileNotFoundException::class)
    private fun includeFile(result: MatrixCursor, docId: String?, file: GaiaFile?) {
        var docId = docId
        var file = file
        if (docId == null) {
            docId = getDocIdForFile(file!!)
        } else {
            file = getFileForDocId(docId)
        }

        var flags = 0

        if (file!!.isDirectory) {
            // Request the folder to lay out as a grid rather than a list. This also allows a larger
            // thumbnail to be displayed for each image.
            //            flags |= Document.FLAG_DIR_PREFERS_GRID;

            // Add FLAG_DIR_SUPPORTS_CREATE if the file is a writable directory.
            if (file.isDirectory && file.canWrite()) {
                flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            }
        } else if (file.canWrite()) {
            // If the file is writable set FLAG_SUPPORTS_WRITE and
            // FLAG_SUPPORTS_DELETE
            flags = flags or Document.FLAG_SUPPORTS_WRITE
            flags = flags or Document.FLAG_SUPPORTS_DELETE
        }

        val displayName = if (file.path.length == 0) {
            ROOT
        } else {
            file.path
        }
        val mimeType = getTypeForFile(file)

        if (mimeType.startsWith("image/")) {
            // Allow the image to be represented by a thumbnail rather than an icon
            flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }

        val row = result.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, docId)
        row.add(Document.COLUMN_DISPLAY_NAME, displayName)
        row.add(Document.COLUMN_SIZE, 100)
        row.add(Document.COLUMN_MIME_TYPE, mimeType)
        //row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(Document.COLUMN_FLAGS, flags)

        // Add a custom icon
        row.add(Document.COLUMN_ICON, R.drawable.ic_menu_convert_csv)
        Log.d(TAG, "added " + displayName)
    }

    /**
     * Translate your custom URI scheme into a File object.
     *
     * @param docId the document ID representing the desired file
     * @return a File represented by the given document ID
     * @throws java.io.FileNotFoundException
     */
    @Throws(FileNotFoundException::class)
    private fun getFileForDocId(docId: String): GaiaFile? {
        var target = mBaseDir
        if (docId == ROOT) {
            return target
        }

        target = GaiaFile(docId, false)
        return target

    }

    companion object {
        private val TAG = "ConvertCSVProvider"

        // Use these as the default columns to return information about a root if no specific
        // columns are requested in a query.
        private val DEFAULT_ROOT_PROJECTION = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES)

        // Use these as the default columns to return information about a document if no specific
        // columns are requested in a query.
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)

        // No official policy on how many to return, but make sure you do limit the number of recent
        // and search results.
        private val MAX_SEARCH_RESULTS = 20
        private val MAX_LAST_MODIFIED = 5

        private val ROOT = "root"

        /**
         * @param projection the requested root column projection
         * @return either the requested root column projection, or the default projection if the
         * requested projection is null.
         */
        private fun resolveRootProjection(projection: Array<String>?): Array<String> {
            return projection ?: DEFAULT_ROOT_PROJECTION
        }

        private fun resolveDocumentProjection(projection: Array<String>?): Array<String> {
            return projection ?: DEFAULT_DOCUMENT_PROJECTION
        }

        /**
         * Get a file's MIME type
         *
         * @param file the File object whose type we want
         * @return the MIME type of the file
         */
        private fun getTypeForFile(file: GaiaFile): String {
            return if (file.isDirectory) {
                Document.MIME_TYPE_DIR
            } else {
                getTypeForName(file.path)
            }
        }

        /**
         * Get the MIME data type of a document, given its filename.
         *
         * @param name the filename of the document
         * @return the MIME data type of a document
         */
        private fun getTypeForName(name: String): String {
            val lastDot = name.lastIndexOf('.')
            if (lastDot >= 0) {
                val extension = name.substring(lastDot + 1)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                if (mime != null) {
                    return mime
                }
            }
            return "text/plain"
        }
    }
}

data class GaiaFile(
        val path: String,
        val isDirectory: Boolean
) {
    fun canWrite(): Boolean {
        return true
    }
}
