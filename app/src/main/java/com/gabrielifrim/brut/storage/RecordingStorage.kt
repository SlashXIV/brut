package com.gabrielifrim.brut.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.core.net.toUri
import com.gabrielifrim.brut.audio.WavRepair
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Un fichier en cours d'écriture, à finaliser ([publish]) ou à abandonner ([discard]). */
class RecordingFile(
    val displayName: String,
    val channel: FileChannel,
    private val onPublish: () -> Unit,
    private val onDiscard: () -> Unit,
) {
    fun publish() = onPublish()
    fun discard() = onDiscard()
}

/** Prise interrompue puis récupérée au démarrage. */
data class RecoveredTake(val name: String, val seconds: Double)

/**
 * Range les prises. Par défaut dans `Musique/Brut`, visible des autres applications
 * (lecteurs, explorateurs, ordinateur en USB) ; ou dans un dossier choisi par
 * l'utilisateur (carte SD, dossier synchronisé…) via le sélecteur de documents.
 *
 * Avant Android 10, le dossier public exige une permission : le dossier par défaut est
 * alors le dossier Musique propre à l'application.
 *
 * Chaque prise en cours est notée dans les préférences : si l'application meurt en
 * pleine prise, [recoverInterrupted] la répare et la publie au démarrage suivant.
 */
class RecordingStorage(private val context: Context) {

    private val prefs = context.getSharedPreferences("stockage", Context.MODE_PRIVATE)

    /** Dossier choisi par l'utilisateur ; null = dossier par défaut. */
    val customFolder: Uri?
        get() = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)?.takeIf(::isAccessible)

    /** Nom lisible du dossier de destination (« Musique/Brut » ou le nom choisi). */
    fun folderLabel(): String = customFolder?.let(::treeLabel) ?: "${defaultMusicLabel()}/$FOLDER"

    fun setCustomFolder(tree: Uri?) {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        customFolder?.let { old -> runCatching { resolver.releasePersistableUriPermission(old, flags) } }
        if (tree != null) resolver.takePersistableUriPermission(tree, flags)
        prefs.edit { if (tree == null) remove(KEY_FOLDER) else putString(KEY_FOLDER, tree.toString()) }
    }

    /** [suffix] distingue les fichiers d'une même prise (ex. « _securite »). */
    fun create(start: LocalDateTime = LocalDateTime.now(), suffix: String = ""): RecordingFile {
        val name = "Brut_" + start.format(NAME_FORMAT) + suffix + ".wav"
        val tree = customFolder
        return when {
            tree != null -> createInTree(tree, name)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> createInMediaStore(name)
            else -> createLegacy(name)
        }
    }

    /** Octets libres sur le volume de destination. */
    fun freeBytes(): Long {
        customFolder?.let { tree -> return treeFreeBytes(tree) ?: 0L }
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Environment.getExternalStorageDirectory() else legacyDir()
        return runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)
    }

    // --- Récupération -------------------------------------------------------------

    // Une prise peut compter plusieurs fichiers (piste de sécurité) : une ligne par fichier.
    private fun inProgress(): List<String> =
        prefs.getString(KEY_IN_PROGRESS, null)?.lines()?.filter { it.isNotBlank() }.orEmpty()

    private fun markInProgress(kind: String, location: String) =
        prefs.edit(commit = true) { putString(KEY_IN_PROGRESS, (inProgress() + "$kind|$location").joinToString("\n")) }

    private fun clearInProgress(location: String) = prefs.edit(commit = true) {
        val rest = inProgress().filterNot { it.endsWith("|$location") }
        if (rest.isEmpty()) remove(KEY_IN_PROGRESS) else putString(KEY_IN_PROGRESS, rest.joinToString("\n"))
    }

    /**
     * Répare et publie la prise restée en cours lors d'un arrêt brutal. À appeler au
     * démarrage, quand aucune prise ne peut être active.
     */
    fun recoverInterrupted(): List<RecoveredTake> {
        val entries = inProgress()
        prefs.edit(commit = true) { remove(KEY_IN_PROGRESS) }
        return entries.mapNotNull(::recover)
    }

    private fun recover(entry: String): RecoveredTake? {
        val (kind, location) = entry.split('|', limit = 2).takeIf { it.size == 2 } ?: return null
        return runCatching {
            val resolver = context.contentResolver
            when (kind) {
                KIND_FILE -> {
                    val file = File(location)
                    val frames = RandomAccessFile(file, "rw").channel.use(WavRepair::repair)
                    MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("audio/wav"), null)
                    RecoveredTake(file.name, seconds(file, frames))
                }
                else -> {
                    val uri = location.toUri()
                    val frames = resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                        val input = java.io.FileInputStream(pfd.fileDescriptor).channel
                        val output = FileOutputStream(pfd.fileDescriptor).channel
                        WavRepair.repair(input, output)
                    }
                    if (kind == KIND_MEDIA && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                    }
                    RecoveredTake(displayName(uri) ?: "?", seconds(uri, frames))
                }
            }
        }.getOrNull()
    }

    /** Durée en secondes : on relit la fréquence dans l'en-tête réparé. */
    private fun seconds(file: File, frames: Long): Double =
        runCatching { RandomAccessFile(file, "r").channel.use { com.gabrielifrim.brut.audio.WavReader.readInfo(it).durationSeconds } }
            .getOrDefault(frames / 48_000.0)

    private fun seconds(uri: Uri, frames: Long): Double = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).channel.use { com.gabrielifrim.brut.audio.WavReader.readInfo(it).durationSeconds }
        }
    }.getOrDefault(frames / 48_000.0)

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    // --- Création -----------------------------------------------------------------

    private fun createInMediaStore(name: String): RecordingFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
            // Invisible des autres applications tant que la prise n'est pas terminée.
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri: Uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Impossible de créer le fichier dans Musique/$FOLDER")
        return openUri(uri, name, KIND_MEDIA) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        }
    }

    private fun createInTree(tree: Uri, name: String): RecordingFile {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = DocumentsContract.createDocument(resolver, parent, "audio/wav", name)
            ?: throw IllegalStateException("Impossible de créer le fichier dans le dossier choisi")
        return openUri(uri, name, KIND_DOCUMENT) {}
    }

    private fun openUri(uri: Uri, name: String, kind: String, publish: () -> Unit): RecordingFile {
        val resolver = context.contentResolver
        val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Impossible d'ouvrir le fichier en écriture")
        markInProgress(kind, uri.toString())
        return RecordingFile(
            displayName = name,
            channel = FileOutputStream(pfd.fileDescriptor).channel,
            onPublish = {
                runCatching { pfd.close() }
                publish()
                clearInProgress(uri.toString())
            },
            onDiscard = {
                runCatching { pfd.close() }
                runCatching {
                    if (kind == KIND_DOCUMENT) DocumentsContract.deleteDocument(resolver, uri) else resolver.delete(uri, null, null)
                }
                clearInProgress(uri.toString())
            },
        )
    }

    private fun createLegacy(name: String): RecordingFile {
        val file = File(legacyDir().apply { mkdirs() }, name)
        val raf = RandomAccessFile(file, "rw")
        markInProgress(KIND_FILE, file.path)
        return RecordingFile(
            displayName = name,
            channel = raf.channel,
            onPublish = {
                runCatching { raf.close() }
                MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("audio/wav"), null)
                clearInProgress(file.path)
            },
            onDiscard = {
                runCatching { raf.close() }
                file.delete()
                clearInProgress(file.path)
            },
        )
    }

    private fun legacyDir(): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, FOLDER)

    private fun defaultMusicLabel(): String = context.getString(com.gabrielifrim.brut.R.string.storage_music)

    // --- Dossier choisi -----------------------------------------------------------

    private fun isAccessible(tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /** « primary:Enregistrements/Concerts » → « Enregistrements/Concerts » ; carte SD → « Carte SD/… ». */
    private fun treeLabel(tree: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val root = if (volume == "primary") {
            context.getString(com.gabrielifrim.brut.R.string.storage_internal)
        } else {
            context.getString(com.gabrielifrim.brut.R.string.storage_sd)
        }
        return if (path.isBlank()) root else "$root/$path"
    }

    /**
     * Espace libre du volume qui porte le dossier choisi. Les racines du fournisseur de
     * documents sont réservées au système : on retrouve le volume par son identifiant
     * (« primary » ou l'UUID de la carte SD) et on interroge le système de fichiers.
     */
    private fun treeFreeBytes(tree: Uri): Long? = runCatching {
        val volumeId = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
        val dir: File? = if (volumeId == "primary") {
            Environment.getExternalStorageDirectory()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(android.os.storage.StorageManager::class.java)
                .storageVolumes.firstOrNull { it.uuid == volumeId }?.directory
        } else {
            // Avant Android 11 : le dossier propre à l'appli sur la carte se trouve sur le même volume.
            context.getExternalFilesDirs(null).firstOrNull { it?.path?.contains(volumeId) == true }
        }
        dir?.let { StatFs(it.path).availableBytes }
    }.getOrNull()

    companion object {
        const val FOLDER = "Brut"
        private val NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        private const val KEY_FOLDER = "dossier"
        private const val KEY_IN_PROGRESS = "prise_en_cours"
        private const val KIND_MEDIA = "media"
        private const val KIND_DOCUMENT = "document"
        private const val KIND_FILE = "fichier"
    }
}
