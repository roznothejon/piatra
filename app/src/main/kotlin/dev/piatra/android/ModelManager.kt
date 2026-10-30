package dev.piatra.android

import android.util.Log
import dev.piatra.core.ModelInfo
import dev.piatra.core.ModelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.ZipInputStream

// Downloads one model and unpacks it into modelsDir/<name>/. One instance per model.
// The download runs while the app is alive. If it's interrupted (Cancel, no network, app
// closed), the partial file stays and the next download() continues from where it stopped.
// Files in modelsDir:
//   <name>-<version>.zip.part  the zip, while downloading
//   <name>.unpacking/          the zip being unpacked
//   <name>/                    the finished model; its .cactus_version file marks it complete
class ModelManager(
    val model: ModelInfo,
    private val modelsDir: File,
    // Should use Dispatchers.IO: the work here is waiting on the network and the disk.
    private val scope: CoroutineScope,
) {
    private val modelDir = File(modelsDir, model.name)
    private val versionFile = File(modelDir, VERSION_FILE)
    private val partFile = File(modelsDir, "${model.name}-${model.version}.zip.part")
    private val unpackDir = File(modelsDir, "${model.name}.unpacking")

    private val _state = MutableStateFlow(stateOnDisk())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private var job: Job? = null

    // The folder to give the engine (cactusInit), or null if the model isn't ready yet.
    fun modelPathOrNull(): String? = if (isReady()) modelDir.absolutePath else null

    fun download() {
        if (job?.isActive == true || isReady()) return
        job = scope.launch {
            try {
                downloadZip()
                _state.value = ModelState.Unpacking
                unpack()
                _state.value = ModelState.Ready
            } catch (e: CancellationException) {
                _state.value = stateOnDisk() // e.g. "paused at 43%"
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download of ${model.name} failed", e)
                _state.value = ModelState.Failed(failureMessage(e))
            }
        }
    }

    // Stops the download, keeping what's downloaded so far.
    fun cancel() {
        job?.cancel()
    }

    fun delete() {
        val running = job
        job = scope.launch {
            running?.cancelAndJoin() // wait until it has stopped writing files
            modelDir.deleteRecursively()
            unpackDir.deleteRecursively()
            partFile.delete()
            _state.value = ModelState.NotDownloaded()
        }
    }

    // A failed name lookup usually means no network for this app. Some Android builds
    // (GrapheneOS, for one) have a per-app "Network" permission: when it's off, the app is told it has INTERNET but
    // sees no network at all, so the first thing to fail is looking up the server's name.
    private fun failureMessage(e: Exception): String = when (e) {
        is UnknownHostException ->
            "Can't reach the download server. Check the internet connection, and that " +
                "Piatra's Network permission is on (Settings → Apps → Piatra → Permissions)."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun isReady(): Boolean = versionFile.exists() && versionFile.readText().trim() == model.version

    // length() is 0 when the file doesn't exist.
    private fun stateOnDisk(): ModelState =
        if (isReady()) ModelState.Ready else ModelState.NotDownloaded(partFile.length())

    // Downloads the zip into partFile, continuing a partial one with an HTTP Range request
    // ("send me the bytes from N onwards"). Hugging Face supports this.
    private suspend fun downloadZip() {
        modelsDir.mkdirs()
        var downloaded = partFile.length()
        if (downloaded >= model.sizeBytes) return // already complete

        val connection = URL(model.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        if (downloaded > 0) connection.setRequestProperty("Range", "bytes=$downloaded-")
        // When the coroutine is cancelled, close the connection, so a read that's waiting
        // for the network ends now instead of after the 60 s timeout.
        val onCancel = currentCoroutineContext().job.invokeOnCompletion { connection.disconnect() }
        try {
            // Hugging Face redirects to its file server; HttpURLConnection follows that itself.
            val append = when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true // 206: the rest of the file
                HttpURLConnection.HTTP_OK -> { downloaded = 0; false } // 200: the whole file
                else -> throw IOException("Server answered HTTP $code")
            }
            val remaining = connection.contentLengthLong // -1 if the server didn't say
            val total = if (remaining >= 0) downloaded + remaining else model.sizeBytes
            _state.value = ModelState.Downloading(downloaded, total)

            connection.inputStream.use { input ->
                FileOutputStream(partFile, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive() // stop here if cancelled
                        val count = input.read(buffer)
                        if (count == -1) break // end of the file
                        output.write(buffer, 0, count)
                        downloaded += count
                        _state.value = ModelState.Downloading(downloaded, total)
                    }
                }
            }
            if (downloaded < total) throw IOException("Download ended early")
        } catch (e: IOException) {
            // A cancel closes the connection, which shows up here as an IOException.
            // Report it as the cancellation it really is.
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            onCancel.dispose()
            connection.disconnect()
        }
    }

    // Unpacks into a temporary folder and only then moves it into place, so a half-unpacked
    // model (app killed midway) is never mistaken for a finished one.
    // Takes a few seconds and can't be cancelled halfway.
    private fun unpack() {
        unpackDir.deleteRecursively()
        try {
            extractZip(partFile, unpackDir)
        } catch (e: IOException) {
            // Each file in a zip carries a checksum that's verified while unpacking, so a
            // damaged download fails here. Throw it away so the next try starts fresh.
            unpackDir.deleteRecursively()
            partFile.delete()
            throw IOException("The download was damaged (${e.message}). Try again.", e)
        }
        File(unpackDir, VERSION_FILE).writeText(model.version)
        modelDir.deleteRecursively() // an older version, if any
        if (!unpackDir.renameTo(modelDir)) throw IOException("Couldn't move the model into place")
        partFile.delete()
    }

    companion object {
        private const val TAG = "ModelManager"
        private const val VERSION_FILE = ".cactus_version"
    }
}

// Unpacks every file of a zip into targetDir.
// internal: visible in this module (and its tests) only.
internal fun extractZip(zip: File, targetDir: File) {
    targetDir.mkdirs()
    val root = targetDir.canonicalFile.toPath()
    ZipInputStream(zip.inputStream().buffered()).use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            val outputFile = File(targetDir, entry.name)
            // "Zip slip": a malicious zip can name an entry "../../something" to write outside
            // targetDir. canonicalFile resolves the ".."s; refuse anything that ends up outside.
            if (!outputFile.canonicalFile.toPath().startsWith(root)) {
                throw IOException("Zip entry outside the target folder: ${entry.name}")
            }
            if (entry.isDirectory) {
                outputFile.mkdirs()
            } else {
                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { output -> input.copyTo(output) }
            }
        }
    }
    flattenSingleFolder(targetDir)
}

// Cactus zips hold one top folder (parakeet-tdt-0.6b-v2-cq4/...). If dir contains nothing but
// one folder, move that folder's contents up into dir, so the model files sit directly in it.
// (Upstream: promoteSingleRootDir.)
internal fun flattenSingleFolder(dir: File) {
    val only = dir.listFiles()?.singleOrNull() ?: return
    if (!only.isDirectory) return
    only.listFiles()?.forEach { child ->
        if (!child.renameTo(File(dir, child.name))) throw IOException("Couldn't move ${child.name}")
    }
    only.delete()
}
