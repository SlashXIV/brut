package com.gabrielifrim.brut.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
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

/**
 * Range les prises dans `Musique/Brut`, visibles des autres applications (lecteurs,
 * explorateurs, ordinateur en USB). Avant Android 10, le dossier public exige une
 * permission : on utilise alors le dossier Musique propre à l'application.
 */
class RecordingStorage(private val context: Context) {

    fun create(): RecordingFile {
        val name = "Brut_" + LocalDateTime.now().format(NAME_FORMAT) + ".wav"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) createInMediaStore(name) else createLegacy(name)
    }

    /** Octets libres sur le volume où sont rangées les prises. */
    fun freeBytes(): Long {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Environment.getExternalStorageDirectory()
        } else {
            legacyDir()
        }
        return runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)
    }

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
        val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Impossible d'ouvrir le fichier en écriture")
        val stream = FileOutputStream(pfd.fileDescriptor)
        return RecordingFile(
            displayName = name,
            channel = stream.channel,
            onPublish = {
                runCatching { pfd.close() }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            },
            onDiscard = {
                runCatching { pfd.close() }
                resolver.delete(uri, null, null)
            },
        )
    }

    private fun createLegacy(name: String): RecordingFile {
        val file = File(legacyDir().apply { mkdirs() }, name)
        val raf = RandomAccessFile(file, "rw")
        return RecordingFile(
            displayName = name,
            channel = raf.channel,
            onPublish = {
                runCatching { raf.close() }
                MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("audio/wav"), null)
            },
            onDiscard = {
                runCatching { raf.close() }
                file.delete()
            },
        )
    }

    private fun legacyDir(): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, FOLDER)

    companion object {
        const val FOLDER = "Brut"
        private val NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}
