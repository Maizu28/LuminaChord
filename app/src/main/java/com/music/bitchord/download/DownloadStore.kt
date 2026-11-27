package com.music.bitchord.download

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Where a downloaded track goes, and how it gets there.
 *
 * Downloads save outside app-private data so songs survive app reinstalls and stay easy to find:
 *
 *  - When **Local music folder** has been chosen via SAF, files are staged locally for
 *    transcoding/tagging and committed directly into that picked folder via [DocumentsContract].
 *  - When **Local music folder** is unset (`All audio folders`), files save directly to
 *    `/storage/emulated/0/LuminaChord/Music` (and are scanned by `MediaScannerConnection`).
 */
object DownloadStore {

    private const val TAG = "BitChord"

    /** Legacy subfolder kept for migrating older `Music/BitChord` downloads. */
    const val FOLDER = "BitChord"

    /** Default public home subfolder used when Local Music is set to "All audio folders". */
    const val DEFAULT_PUBLIC_RELATIVE_PATH = "LuminaChord/Music"
    const val DEFAULT_PUBLIC_PATH_LABEL = "/storage/emulated/0/LuminaChord/Music"

    fun defaultPublicDirectory(): File =
        File(Environment.getExternalStorageDirectory(), DEFAULT_PUBLIC_RELATIVE_PATH)

    fun folderDisplayLabel(context: Context): String {
        val treeUri = AppSettings.localMusicFolderUri.value
        if (treeUri.isBlank()) return DEFAULT_PUBLIC_PATH_LABEL
        return LocalMediaRepository.selectedFolderAbsolutePath(treeUri)
            ?: LocalMediaRepository.selectedFolderLabel(treeUri)
            ?: context.getString(R.string.local_music_folder)
    }

    /** Whether API <= 29 runtime `WRITE_EXTERNAL_STORAGE` should be requested instead of All Files Access. */
    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    /** Whether Android 11+ `MANAGE_EXTERNAL_STORAGE` is required because default home storage is active. */
    fun needsAllFilesPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && AppSettings.localMusicFolderUri.value.isBlank()

    fun hasWriteAccess(context: Context): Boolean {
        val treeUri = AppSettings.localMusicFolderUri.value
        if (treeUri.isNotBlank()) {
            return hasTreeWritePermission(context, treeUri)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasTreeWritePermission(context: Context, treeUriString: String): Boolean {
        if (treeUriString.isBlank()) return false
        val parsed = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return false
        return context.contentResolver.persistedUriPermissions.any { perm ->
            perm.uri == parsed && perm.isWritePermission
        }
    }

    // ---- Naming -------------------------------------------------------------

    /**
     * What the file is called: `Artist - Title.ext`.
     */
    fun fileNameFor(song: Song, extension: String): String {
        val artist = sanitise(song.artist)
        val title = sanitise(song.title)
        val stem = when {
            artist.isEmpty() -> title
            title.isEmpty() -> artist
            else -> "$artist - $title"
        }.ifEmpty { song.videoId }
        return "${stem.take(MAX_STEM_CHARS).trimEnd()}.$extension"
    }

    private fun sanitise(raw: String): String = raw
        .replace(ILLEGAL, " ")
        .replace(WHITESPACE, " ")
        .trim()
        .trim('.')

    private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    private val WHITESPACE = Regex("""\s+""")
    private const val MAX_STEM_CHARS = 120

    class Storable(val extension: String, val mimeType: String)

    fun storable(codec: String?): Storable? = when (codec?.lowercase(Locale.ROOT)?.trim()) {
        "flac", "x-flac" -> Storable("flac", "audio/flac")
        "wav", "x-wav", "wave" -> Storable("wav", "audio/x-wav")
        "alac", "m4a", "mp4", "eac3-joc", "ec3-joc", "dolby-atmos" -> Storable("m4a", "audio/mp4")
        else -> null
    }

    // ---- Lookup -------------------------------------------------------------

    fun existing(context: Context, name: String): Uri? {
        val treeUri = AppSettings.localMusicFolderUri.value
        if (treeUri.isNotBlank()) {
            return findDocumentInTree(context, treeUri, name)
        }
        val direct = File(defaultPublicDirectory(), name)
        if (direct.isFile && direct.length() > 0L) return Uri.fromFile(direct)
        return null
    }

    private fun findDocumentInTree(context: Context, treeUriString: String, name: String): Uri? = runCatching {
        val treeUri = Uri.parse(treeUriString)
        val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            if (idCol < 0 || nameCol < 0) return@use null
            while (cursor.moveToNext()) {
                val docName = cursor.getString(nameCol) ?: continue
                if (docName.equals(name, ignoreCase = false)) {
                    val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 1L
                    if (size > 0L) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                    }
                }
            }
            null
        }
    }.onFailure { Log.w(TAG, "SAF lookup failed for $name: ${it.message}") }.getOrNull()

    fun exists(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File) ?: return false
            return file.isFile && file.length() > 0L
        }
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            pfd.statSize != 0L
        } == true
    }.getOrDefault(false)

    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") {
            uri.path?.let { path ->
                val file = File(path)
                if (file.name == "playlist.m3u8" && file.parentFile?.parentFile?.name == "offline-hls") {
                    file.parentFile?.deleteRecursively() == true
                } else {
                    file.delete()
                }
            } == true
        } else if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
    }.onFailure { Log.w(TAG, "could not delete $uri: ${it.message}") }.getOrDefault(false)

    /** True when [uri] already lives inside the currently selected destination directory. */
    fun isInCurrentTarget(context: Context, uri: Uri): Boolean {
        val treeUri = AppSettings.localMusicFolderUri.value
        if (treeUri.isNotBlank()) {
            val parsedTree = Uri.parse(treeUri)
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(parsedTree) }.getOrNull()
            if (uri.scheme == "content" && uri.authority == parsedTree.authority &&
                treeId != null && DocumentsContract.isDocumentUri(context, uri)
            ) {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                if (docId != null && docId.substringBeforeLast('/', "") == treeId) return true
            }
            val selectedPath = LocalMediaRepository.selectedFolderAbsolutePath(treeUri)
            val filePath = uri.takeIf { it.scheme == "file" }?.path
            if (selectedPath != null && filePath != null) {
                val targetCanonical = runCatching { File(selectedPath).canonicalPath }.getOrNull()
                val fileParentCanonical = runCatching { File(filePath).parentFile?.canonicalPath }.getOrNull()
                if (targetCanonical != null && targetCanonical.equals(fileParentCanonical, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
        if (uri.scheme != "file") return false
        val parent = uri.path?.let(::File)?.parentFile ?: return false
        return runCatching {
            parent.canonicalPath.equals(defaultPublicDirectory().canonicalPath, ignoreCase = true)
        }.getOrDefault(false)
    }

    // ---- Writing ------------------------------------------------------------

    /**
     * Stages bytes inside app cache first so conversion ([DownloadEncoder]) and metadata tagging
     * ([MediaTagger]) always operate on a local seekable file before atomic publication to either
     * `/storage/emulated/0/LuminaChord/Music` or the user's SAF `Local music folder`.
     */
    class Pending internal constructor(
        private val context: Context,
        val name: String,
        private val mimeType: String,
        val stagingFile: File,
        private val treeUriString: String,
    ) {
        val tagUri: Uri get() = Uri.fromFile(stagingFile)

        fun openStream(): OutputStream {
            stagingFile.parentFile?.mkdirs()
            return stagingFile.outputStream().buffered()
        }

        fun commit(): Uri {
            check(stagingFile.isFile && stagingFile.length() > 0L) {
                "Download failed: nothing was written"
            }
            try {
                if (treeUriString.isNotBlank()) {
                    check(hasTreeWritePermission(context, treeUriString)) {
                        "Local music folder lacks write access — re-select the folder in Settings"
                    }
                    val treeUri = Uri.parse(treeUriString)
                    val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri),
                    )
                    findDocumentInTree(context, treeUriString, name)?.let { existingUri ->
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, existingUri) }
                    }
                    val created = DocumentsContract.createDocument(
                        context.contentResolver,
                        parentDocUri,
                        mimeType,
                        name,
                    ) ?: error("Could not create $name in selected Local music folder")
                    try {
                        context.contentResolver.openOutputStream(created, "w")?.use { out ->
                            stagingFile.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                        } ?: error("Could not open $name for writing")
                    } catch (e: Throwable) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, created) }
                        throw e
                    }
                    return created
                }

                check(hasWriteAccess(context)) {
                    context.getString(R.string.all_files_permission_required_save)
                }
                val dir = defaultPublicDirectory()
                if (!dir.exists() && !dir.mkdirs()) {
                    error("Could not create ${dir.absolutePath}")
                }
                val target = File(dir, name)
                val part = File(dir, ".$name.part")
                part.delete()
                try {
                    stagingFile.inputStream().use { input ->
                        part.outputStream().buffered().use { out -> input.copyTo(out, 64 * 1024) }
                    }
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) error("Could not finish writing $name")
                } catch (e: Throwable) {
                    part.delete()
                    throw e
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(target.absolutePath),
                    arrayOf(mimeType),
                    null,
                )
                return Uri.fromFile(target)
            } finally {
                stagingFile.delete()
            }
        }

        fun abort() {
            stagingFile.delete()
        }
    }

    fun begin(context: Context, name: String, mimeType: String): Pending {
        val treeUri = AppSettings.localMusicFolderUri.value
        if (treeUri.isNotBlank()) {
            check(hasTreeWritePermission(context, treeUri)) {
                "Local music folder lacks write access — re-select the folder in Settings"
            }
        } else {
            check(hasWriteAccess(context)) {
                context.getString(R.string.all_files_permission_required_save)
            }
        }
        val stageDir = File(context.cacheDir, "download-stage").apply { mkdirs() }
        val safeStageName = "stage-${System.nanoTime()}-${name.replace(ILLEGAL, "_")}"
        val stageFile = File(stageDir, safeStageName)
        stageFile.delete()
        return Pending(
            context = context,
            name = name,
            mimeType = mimeType,
            stagingFile = stageFile,
            treeUriString = treeUri,
        )
    }

    /** Copies an existing [sourceUri] into the active destination folder and returns its new [Uri]. */
    fun importFile(context: Context, sourceUri: Uri, name: String, mimeType: String): Uri? {
        if (!hasWriteAccess(context)) return null
        existing(context, name)?.let { return it }
        var pending: Pending? = null
        return runCatching {
            val dest = begin(context, name, mimeType)
            pending = dest
            val input = if (sourceUri.scheme == "file") {
                File(requireNotNull(sourceUri.path)).inputStream()
            } else {
                context.contentResolver.openInputStream(sourceUri)
            } ?: return null
            input.use { src ->
                dest.openStream().use { out -> src.copyTo(out, 64 * 1024) }
            }
            val committed = dest.commit()
            pending = null
            committed
        }.onFailure {
            pending?.abort()
            Log.w(TAG, "migration copy failed for $name: ${it.message}")
        }.getOrNull()
    }
}
