package dev.piatra.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dev.piatra.core.AudioPlayer
import dev.piatra.core.PcmFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream

// Plays raw PCM through the speaker with AudioTrack: AudioRecord in reverse.
class AndroidAudioPlayer(context: Context) : AudioPlayer {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    override suspend fun play(input: InputStream) {
        // AudioTrack.write() blocks, so run on the IO thread pool, never the main thread.
        withContext(Dispatchers.IO) {
            val minBufferSize = AudioTrack.getMinBufferSize(
                PcmFormat.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            // "Media" sound: the media volume slider controls it.
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            // Audio focus: asks other apps (e.g. a music player) to pause or turn down while we
            // play, and to resume afterwards. TRANSIENT means "only briefly". Same as upstream.
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                // Must match how the file was recorded, or it plays at the wrong speed or as noise.
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PcmFormat.SAMPLE_RATE_HZ)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize)
                // Stream mode: feed audio chunk by chunk while it plays, so any length works.
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                // Denied e.g. during a phone call: don't play over it.
                check(audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    "Couldn't get audio focus (is a call in progress?)"
                }
                track.play()
                val buffer = ByteArray(minBufferSize)
                var framesWritten = 0L // one frame = one sample per channel
                // isActive turns false when the coroutine is cancelled (the user pressed Stop).
                while (isActive) {
                    val size = input.read(buffer)
                    if (size == -1) break // end of the file
                    // Waits while the track's buffer is full, which paces this loop to playback speed.
                    val written = track.write(buffer, 0, size)
                    check(written >= 0) { "Audio output failed (error $written)" }
                    framesWritten += written / (PcmFormat.BYTES_PER_SAMPLE * PcmFormat.CHANNELS)
                }
                // write() returns once audio is in the track's buffer, not once it's been heard.
                // Wait for the speaker to catch up, or the last fraction of a second gets cut off.
                // delay() also stops early if the coroutine is cancelled.
                while (track.playbackHeadPosition < framesWritten) delay(20)
            } finally {
                // Stops playback immediately and frees the audio output.
                track.release()
                // Lets the other apps resume.
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        }
    }
}
