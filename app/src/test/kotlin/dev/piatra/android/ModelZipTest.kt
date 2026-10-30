package dev.piatra.android

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The unzip helpers behind ModelManager. Plain java.io, so they run on the computer's JVM.
class ModelZipTest {
    // A fresh temporary folder per test, deleted afterwards.
    private val tempDir: File = Files.createTempDirectory("model-zip-test").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    // Builds a zip in tempDir from (entry name -> text) pairs.
    private fun zipOf(vararg entries: Pair<String, String>): File {
        val zip = File(tempDir, "test.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            for ((name, text) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(text.toByteArray())
                output.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `a single top folder is flattened, as in Cactus zips`() {
        val zip = zipOf(
            "model-cq4/config.txt" to "config",
            "model-cq4/components/encoder/graph.cactus" to "graph",
        )
        val target = File(tempDir, "model")

        extractZip(zip, target)

        assertEquals("config", File(target, "config.txt").readText())
        assertEquals("graph", File(target, "components/encoder/graph.cactus").readText())
        assertFalse(File(target, "model-cq4").exists())
    }

    @Test
    fun `files at the top level are left where they are`() {
        val zip = zipOf("config.txt" to "config", "weights/a.weights" to "a")
        val target = File(tempDir, "model")

        extractZip(zip, target)

        assertEquals("config", File(target, "config.txt").readText())
        assertEquals("a", File(target, "weights/a.weights").readText())
    }

    @Test
    fun `an entry escaping the folder is refused (zip slip)`() {
        val zip = zipOf("model/config.txt" to "ok", "../evil.txt" to "gotcha")
        val target = File(tempDir, "model")

        assertFailsWith<IOException> { extractZip(zip, target) }
        assertFalse(File(tempDir, "evil.txt").exists())
    }

    @Test
    fun `a sibling folder with a longer name doesn't count as inside`() {
        // "model-evil" starts with the text "model", but it isn't inside "model".
        val zip = zipOf("../model-evil/x.txt" to "gotcha")
        val target = File(tempDir, "model")

        assertFailsWith<IOException> { extractZip(zip, target) }
        assertFalse(File(tempDir, "model-evil/x.txt").exists())
    }

    @Test
    fun `a damaged zip fails instead of unpacking garbage`() {
        val zip = zipOf("model/config.txt" to "config ".repeat(1000))
        // Overwrite bytes in the middle of the compressed data.
        val bytes = zip.readBytes()
        for (i in 60 until 80) bytes[i] = 0x55
        zip.writeBytes(bytes)

        assertFailsWith<IOException> { extractZip(zip, File(tempDir, "model")) }
    }

    @Test
    fun `flattening leaves a folder alone when it has more than one item`() {
        val dir = File(tempDir, "dir").apply { mkdirs() }
        File(dir, "a").mkdirs()
        File(dir, "b.txt").writeText("b")

        flattenSingleFolder(dir)

        assertTrue(File(dir, "a").isDirectory)
        assertTrue(File(dir, "b.txt").isFile)
    }
}
