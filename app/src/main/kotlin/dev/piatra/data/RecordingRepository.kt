package dev.piatra.data

import dev.piatra.android.RecordingStorage
import dev.piatra.core.RecordingQueueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

// A recording is a database row plus an audio file. Everything else (the ViewModel, the
// processing queue, the board sync) goes through here, so both stay in step.
// It's also the queue's storage (RecordingQueueStore).
class RecordingRepository(
    private val dao: RecordingDao,
    private val storage: RecordingStorage,
) : RecordingQueueStore {
    fun observeAll(): Flow<List<RecordingEntity>> = dao.observeAll()

    fun observe(id: Long): Flow<RecordingEntity?> = dao.observe(id)

    // Records an audio file that has already been written. Returns the new row's id.
    // boardKey: only for recordings from the board (see RecordingEntity).
    suspend fun add(fileId: String, createdAt: Long, durationMillis: Long, boardKey: String? = null): Long =
        dao.insert(RecordingEntity(fileId = fileId, createdAt = createdAt, durationMillis = durationMillis, boardKey = boardKey))

    suspend fun hasBoardRecording(key: String): Boolean = dao.hasBoardKey(key)

    suspend fun delete(recording: RecordingEntity) {
        dao.delete(recording.id)
        storage.delete(recording.fileId)
    }

    override suspend fun get(id: Long): RecordingEntity? = dao.get(id)

    override suspend fun idsWithStatus(status: RecordingStatus): List<Long> = dao.idsWithStatus(status)

    override suspend fun update(recording: RecordingEntity) = dao.update(recording)

    // The whole recording as PCM: a minute of audio is about 1.9 MB.
    override suspend fun readAudio(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        storage.readPcm(fileId)
    }
}
