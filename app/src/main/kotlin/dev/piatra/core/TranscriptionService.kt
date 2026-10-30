package dev.piatra.core

// Turns speech into text. The app uses CactusTranscriptionService (on-device model, set in
// AppModule); tests use scripted fakes.
// Upstream's version (util/.../transcription/TranscriptionService.kt) streams partial results;
// ours returns the finished text: Parakeet handles a whole clip at once, and voice notes are short.
interface TranscriptionService {
    // pcm: a whole recording in PcmFormat. Throws RecoverableTaskException if trying
    // again later might work (e.g. the model isn't downloaded yet).
    suspend fun transcribe(pcm: ByteArray): String
}
