package dev.piatra.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dev.piatra.core.obsidian.ObsidianVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The Obsidian vault folder through the Storage Access Framework (SAF). Android doesn't let apps
// write anywhere they like: the user picks a folder in the system picker, and Piatra gets a
// "tree URI", a key to that folder and nothing else. takeAccess() keeps the key working after
// restarts and reboots, so notes can be written from the background.
// DocumentFile wraps the URI so it works like folders and files. Ported from upstream's
// AndroidObsidianVault; its picker moved into the screen (rememberLauncherForActivityResult).
class AndroidObsidianVault(private val context: Context) : ObsidianVault {

    // Keeps access to a folder the picker returned. Returns its name, or null if Android
    // wouldn't let Piatra keep it.
    fun takeAccess(folder: Uri): String? = try {
        context.contentResolver.takePersistableUriPermission(folder, READ_WRITE)
        DocumentFile.fromTreeUri(context, folder)?.name ?: "Obsidian vault"
    } catch (e: SecurityException) {
        Log.w(TAG, "Couldn't keep access to $folder", e)
        null
    }

    // Gives the key back, when the user stops sending notes to Obsidian.
    fun releaseAccess(folder: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(folder), READ_WRITE) }
    }

    override suspend fun hasAccess(folder: String): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse(folder)
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        held && DocumentFile.fromTreeUri(context, uri)?.canWrite() == true
    }

    override suspend fun listMarkdownFiles(folder: String, subfolder: String): List<String> = withContext(Dispatchers.IO) {
        val dir = findDir(folder, subfolder, create = false) ?: return@withContext emptyList()
        dir.listFiles().filter { it.isFile }.mapNotNull { it.name }.filter { it.endsWith(".md", ignoreCase = true) }
    }

    override suspend fun readFile(folder: String, name: String): String? = withContext(Dispatchers.IO) {
        val file = findFile(folder, name, create = false) ?: return@withContext null
        runCatching { context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
    }

    override suspend fun writeFile(folder: String, name: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val file = findFile(folder, name, create = true) ?: return@withContext false
        try {
            // "wt": write, truncating first, so the old text is replaced rather than written over.
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(content.encodeToByteArray()) } != null
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't write $name", e)
            false
        }
    }

    // "Piatra Inbox" under the vault folder, made if create is true.
    private fun findDir(folder: String, subfolder: String, create: Boolean): DocumentFile? {
        var dir = DocumentFile.fromTreeUri(context, Uri.parse(folder)) ?: return null
        for (part in subfolder.split('/').filter { it.isNotBlank() }) {
            val existing = dir.findFile(part)
            dir = when {
                existing != null && existing.isDirectory -> existing
                create -> dir.createDirectory(part) ?: return null
                else -> return null
            }
        }
        return dir
    }

    // "Piatra Inbox/2026-10-22 1405.md" under the vault folder, made if create is true.
    private fun findFile(folder: String, name: String, create: Boolean): DocumentFile? {
        val dir = findDir(folder, name.substringBeforeLast('/', ""), create) ?: return null
        val fileName = name.substringAfterLast('/')
        return dir.findFile(fileName) ?: if (create) dir.createFile("text/markdown", fileName) else null
    }

    private companion object {
        const val TAG = "AndroidObsidianVault"
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
