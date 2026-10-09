package com.gabrielifrim.brut.library

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.gabrielifrim.brut.audio.WavInfo
import com.gabrielifrim.brut.audio.WavReader
import com.gabrielifrim.brut.storage.RecordingStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/** Une prise de la bibliothèque. */
data class Take(
    val uri: Uri,
    val name: String,
    val dateMillis: Long,
    val sizeBytes: Long,
    /** En-tête WAV lu ; null si le fichier est illisible (corrompu, autre format). */
    val info: WavInfo?,
    /** Fichier direct, seulement avant Android 10 (dossier propre à l'application). */
    val file: File? = null,
    /** Prise rangée dans un dossier choisi (sélecteur de documents) : pas de corbeille système. */
    val isDocument: Boolean = false,
) {
    /** La suppression peut-elle être annulée (corbeille système, Android 11+) ? */
    val canUndoDelete: Boolean get() = file == null && !isDocument && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val key: String get() = uri.toString()
    val baseName: String get() = name.removeSuffix(".wav").removeSuffix(".WAV")
}

/** Résultat d'une modification : faite, ou à confirmer par l'utilisateur via le système. */
sealed interface EditResult {
    data object Done : EditResult
    data class NeedsConsent(val intent: IntentSender) : EditResult
    data object Failed : EditResult
}

/**
 * Accès aux prises rangées dans `Musique/Brut`. Les prises créées par cette installation
 * sont toujours visibles ; celles d'une installation précédente demandent la permission
 * de lecture audio, et leur modification passe par une confirmation du système.
 */
class TakeRepository(
    private val context: Context,
    /** Dossier choisi par l'utilisateur, listé en plus du dossier par défaut. */
    private val customFolder: () -> Uri?,
) {

    private val resolver = context.contentResolver

    suspend fun list(): List<Take> = withContext(Dispatchers.IO) {
        val default = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listFromMediaStore() else listLegacy()
        val custom = customFolder()?.let { runCatching { listTree(it) }.getOrDefault(emptyList()) }.orEmpty()
        (custom + default).sortedByDescending { it.dateMillis }
    }

    /** Prises d'un dossier choisi via le sélecteur de documents (carte SD comprise). */
    private fun listTree(tree: Uri): List<Take> {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val takes = mutableListOf<Take>()
        resolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!name.endsWith(".wav", ignoreCase = true)) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                takes += Take(uri, name, c.getLong(3), c.getLong(2), readInfo(uri), isDocument = true)
            }
        }
        return takes
    }

    private fun listFromMediaStore(): List<Take> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
        val args = arrayOf("${Environment.DIRECTORY_MUSIC}/${RecordingStorage.FOLDER}/")
        val takes = mutableListOf<Take>()
        resolver.query(collection, projection, selection, args, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val date = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(id))
                takes += Take(uri, c.getString(name), c.getLong(date) * 1000, c.getLong(size), readInfo(uri))
            }
        }
        return takes
    }

    private fun listLegacy(): List<Take> {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, RecordingStorage.FOLDER)
        return dir.listFiles { f -> f.extension.equals("wav", true) }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { f ->
                val info = runCatching { FileInputStream(f).channel.use { WavReader.readInfo(it) } }.getOrNull()
                Take(Uri.fromFile(f), f.name, f.lastModified(), f.length(), info, f)
            }
    }

    private fun readInfo(uri: Uri): WavInfo? = runCatching {
        resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { WavReader.readInfo(it) }
        }
    }.getOrNull()

    /** Réécrit en place l'heure de départ (bext + iXML) ; les octets audio ne bougent pas. */
    suspend fun patchTimestamp(take: Take, speed: com.gabrielifrim.brut.audio.IxmlSpeed): EditResult = withContext(Dispatchers.IO) {
        take.file?.let { f ->
            return@withContext runCatching {
                java.io.RandomAccessFile(f, "rw").channel.use { com.gabrielifrim.brut.audio.WavMetadata.patchTimestamp(it, it, speed) }
            }.getOrDefault(false).let { if (it) EditResult.Done else EditResult.Failed }
        }
        guarded(take) {
            resolver.openFileDescriptor(take.uri, "rw")!!.use { pfd ->
                val input = FileInputStream(pfd.fileDescriptor).channel
                val output = java.io.FileOutputStream(pfd.fileDescriptor).channel
                if (com.gabrielifrim.brut.audio.WavMetadata.patchTimestamp(input, output, speed)) 1 else 0
            }
        }
    }

    suspend fun rename(take: Take, newBaseName: String): EditResult = withContext(Dispatchers.IO) {
        val clean = sanitize(newBaseName).ifBlank { return@withContext EditResult.Failed }
        val newName = "$clean.wav"
        take.file?.let { f ->
            return@withContext if (f.renameTo(File(f.parentFile, newName))) EditResult.Done else EditResult.Failed
        }
        if (take.isDocument) {
            return@withContext runCatching { DocumentsContract.renameDocument(resolver, take.uri, newName) }
                .getOrNull()?.let { EditResult.Done } ?: EditResult.Failed
        }
        guarded(take) {
            resolver.update(take.uri, ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, newName) }, null, null)
        }
    }

    /**
     * Met la prise à la corbeille du système (Android 11+) : elle disparaît des lecteurs
     * mais reste récupérable 30 jours, et [restore] l'annule immédiatement.
     */
    suspend fun trash(take: Take): EditResult = withContext(Dispatchers.IO) {
        take.file?.let { return@withContext if (it.delete()) EditResult.Done else EditResult.Failed }
        if (take.isDocument) {
            return@withContext if (runCatching { DocumentsContract.deleteDocument(resolver, take.uri) }.getOrDefault(false)) {
                EditResult.Done
            } else {
                EditResult.Failed
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            guarded(take, trash = true) {
                resolver.update(take.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null)
            }
        } else {
            guarded(take) { resolver.delete(take.uri, null, null) }
        }
    }

    suspend fun restore(take: Take): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || take.file != null) return@withContext false
        runCatching {
            resolver.update(take.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null) > 0
        }.getOrDefault(false)
    }

    /** Toute prise est partageable : un lien de lecture temporaire est accordé à l'appli choisie. */
    fun shareIntent(take: Take): Intent {
        val uri = take.file?.let { FileProvider.getUriForFile(context, "${context.packageName}.prises", it) } ?: take.uri
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, take.name)
    }

    /**
     * Une prise d'une installation précédente n'appartient plus à l'application :
     * Android exige alors l'accord explicite de l'utilisateur pour la modifier.
     */
    private fun guarded(take: Take, trash: Boolean = false, action: () -> Int): EditResult = try {
        if (action() > 0) EditResult.Done else EditResult.Failed
    } catch (e: SecurityException) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val request = if (trash) {
                    MediaStore.createTrashRequest(resolver, listOf(take.uri), true)
                } else {
                    MediaStore.createWriteRequest(resolver, listOf(take.uri))
                }
                EditResult.NeedsConsent(request.intentSender)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                EditResult.NeedsConsent(e.userAction.actionIntent.intentSender)
            else -> EditResult.Failed
        }
    }

    companion object {
        /** Caractères refusés par les systèmes de fichiers courants (FAT des cartes SD compris). */
        fun sanitize(name: String): String =
            name.trim().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(120)
    }
}
