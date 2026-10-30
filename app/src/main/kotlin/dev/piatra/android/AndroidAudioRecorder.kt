package dev.piatra.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.piatra.core.AudioRecorder
import dev.piatra.core.PcmFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

// Reads raw PCM from the phone's microphone with AudioRecord.
// Settings follow upstream's AudioRecorder.android.kt.
class AndroidAudioRecorder : AudioRecorder {

    // The UI checks RECORD_AUDIO before starting; without it AudioRecord fails to initialise.
    @SuppressLint("MissingPermission")
    override suspend fun record(onChunk: (chunk: ByteArray, size: Int) -> Unit) {
        // AudioRecord.read() blocks, so run on the IO thread pool, never the main thread.
        withContext(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                PcmFormat.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, // tuned for speech, little processing
                PcmFormat.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2, // some slack so audio isn't lost if we're briefly slow
            )
            try {
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    "Couldn't open the microphone"
                }
                audioRecord.startRecording()
                val buffer = ByteArray(minBufferSize)
                // isActive turns false when the coroutine is cancelled (the user released the button).
                while (isActive) {
                    val size = audioRecord.read(buffer, 0, buffer.size) // waits for the next chunk
                    check(size >= 0) { "Microphone read failed (error $size)" }
                    onChunk(buffer, size)
                }
            } finally {
                // Always give the mic back, or the next recording (or another app) can't use it.
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
                audioRecord.release()
            }
        }
    }
}
