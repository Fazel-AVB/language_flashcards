package com.fazelavb.flashcards.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.fazelavb.flashcards.core.ReviewLog
import com.fazelavb.flashcards.core.ReviewSession
import com.fazelavb.flashcards.core.Workbook
import com.fazelavb.flashcards.core.WorkbookData
import com.fazelavb.flashcards.core.WriteResult
import com.fazelavb.flashcards.core.Writeback
import com.fazelavb.flashcards.core.CellWrite
import com.fazelavb.flashcards.core.RowKey
import com.fazelavb.flashcards.core.UserException
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The workbook on the phone, through Android's file picker (the Storage Access
 * Framework). No storage permission is needed: picking the file grants this
 * app access to that one file, kept across restarts. It can live on the phone,
 * or in Google Drive / OneDrive through their apps.
 *
 * App-private files (nothing else on the phone can read them):
 *   backups/      a copy of the workbook before every save (the newest 50 are kept)
 *   reviews.csv   one line per saved grade, for tuning FSRS to your memory later
 */

data class LoadedWorkbook(val data: WorkbookData, val name: String, val uri: Uri)

data class SaveResult(val write: WriteResult, val backup: File, val logWarning: String?)

class WorkbookRepository(private val context: Context) {
    private val resolver get() = context.contentResolver
    val backupDir: File get() = File(context.filesDir, "backups")
    private val reviewLog: File get() = File(context.filesDir, "reviews.csv")

    /** Keep access to a picked file across restarts. */
    fun remember(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some providers grant only read: still usable, Save will then explain.
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    fun forget(uri: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    fun readBytes(uri: Uri): ByteArray = try {
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw UserException("The workbook could not be opened.")
    } catch (e: FileNotFoundException) {
        throw UserException("The workbook is gone or no longer accessible. Choose it again.")
    } catch (e: SecurityException) {
        throw UserException("This app no longer has access to the workbook. Choose it again.")
    }

    fun displayName(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "workbook.xlsx"

    fun load(uri: Uri): LoadedWorkbook = LoadedWorkbook(Workbook.load(readBytes(uri)), displayName(uri), uri)

    /**
     * Write the session's cells into the workbook: re-read it, refuse if it
     * changed since the session started (the hash), back it up, write it.
     */
    fun save(loaded: LoadedWorkbook, updates: Map<RowKey, Map<String, CellWrite>>, session: ReviewSession): SaveResult {
        val current = readBytes(loaded.uri)
        if (Workbook.sha256(current) != loaded.data.hash) {
            throw UserException("The workbook changed since this session started (edited on another device?). " +
                "Your grades are kept: discard them, or note them down, then review again on the current file.")
        }
        val result = Writeback.apply(current, updates)
        val backup = backup(loaded.name, current)
        try {
            // "wt": write and truncate. Plain "w" is not a fallback: on some providers it leaves the
            // old file's tail behind a shorter new one, which corrupts the zip.
            val out = try {
                resolver.openOutputStream(loaded.uri, "wt")
            } catch (e: IllegalArgumentException) {
                throw SecurityException(e.message)
            }
            out?.use { it.write(result.bytes) } ?: throw UserException("The workbook could not be opened for writing.")
        } catch (e: SecurityException) {
            throw UserException("This storage doesn't let the app change the file. Copy the workbook to the phone " +
                "(e.g. Downloads or Documents) and choose that copy.")
        } catch (e: FileNotFoundException) {
            throw UserException("The workbook could not be written: ${e.message}")
        }
        val warning = try {
            appendLog(loaded.name, session)
            null
        } catch (e: Exception) {
            "The review log could not be written (${e.message})."
        }
        return SaveResult(result, backup, warning)
    }

    private fun backup(name: String, bytes: ByteArray): File {
        backupDir.mkdirs()
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "xlsx")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val file = File(backupDir, "$stem.$stamp.$ext")
        file.writeBytes(bytes)
        // A phone has less room than a laptop: keep the newest 50.
        backupDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(50)?.forEach { it.delete() }
        return file
    }

    private fun appendLog(name: String, session: ReviewSession) {
        val newFile = !reviewLog.exists()
        val lines = buildString {
            if (newFile) appendLine(ReviewLog.HEADER.joinToString(","))
            for (line in ReviewLog.lines(name, session)) appendLine(line)
        }
        reviewLog.appendText(lines)
    }

    fun backupCount(): Int = backupDir.listFiles()?.size ?: 0
}
