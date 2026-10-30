package dev.piatra.core.obsidian

// Files in the vault folder the user picked. The app uses AndroidObsidianVault (android/), tests
// a fake. `folder` is the saved key to that folder (on Android, a content:// tree URI as text);
// only the implementation reads it. File names are relative to the folder and may start with
// one subfolder ("Piatra Inbox/2026-10-22 1405.md"), which is created when missing.
interface ObsidianVault {
    // False once the folder is gone or Piatra's access to it was taken away.
    suspend fun hasAccess(folder: String): Boolean

    // Names of the .md files directly inside subfolder ("" = the vault folder itself).
    suspend fun listMarkdownFiles(folder: String, subfolder: String): List<String>

    // The file's text, or null if there's no such file.
    suspend fun readFile(folder: String, name: String): String?

    // Creates or replaces the file. False if it couldn't.
    suspend fun writeFile(folder: String, name: String, content: String): Boolean
}
