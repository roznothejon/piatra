package dev.piatra.core

// A Cactus model that's downloaded once from Hugging Face, as a zip of its weight files.
data class ModelInfo(
    val name: String,
    // The weights release. When upstream bumps it, the model is downloaded again.
    val version: String,
    val label: String,
    val sizeBytes: Long,
) {
    // cq4: Cactus's 4-bit compressed weights, the format upstream uses.
    val url: String
        get() = "https://huggingface.co/Cactus-Compute/$name/resolve/$version/${name.lowercase()}-cq4.zip"
}

// The models Piatra uses (names from upstream util/build.gradle.kts).
object Models {
    // The two speech models; Settings picks one (SpeechModelChoice). Upstream offers the same
    // pair: "Higher accuracy for English" and "Widest language support".
    val SPEECH_ENGLISH = ModelInfo(
        name = "parakeet-tdt-0.6b-v2",
        version = "v2.2.2",
        label = "English speech model",
        sizeBytes = 367_108_112,
    )

    // 25 European languages; the language is detected by itself.
    val SPEECH_MULTILINGUAL = ModelInfo(
        name = "parakeet-tdt-0.6b-v3",
        version = "v2.2.2",
        label = "Multilingual speech model",
        sizeBytes = 384_126_776,
    )

    // Turns a transcript into tool calls. A small "router" model (encoder-decoder, 36 MB unpacked)
    // fine-tuned by Core Devices. Upstream's build names weights v2.1.0, which doesn't exist on
    // Hugging Face; upstream ships the model inside its app instead. The v2.0.1 tag is
    // byte-for-byte that bundled file (same SHA-256).
    val AGENT = ModelInfo(
        name = "needle-pebble-ft",
        version = "v2.0.1",
        label = "Agent model",
        sizeBytes = 13_732_623,
    )
}

// Where a model is on its way to the phone.
sealed interface ModelState {
    // downloadedBytes > 0: a download was interrupted and will continue from there.
    data class NotDownloaded(val downloadedBytes: Long = 0) : ModelState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelState
    data object Unpacking : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}
