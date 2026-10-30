package dev.piatra.core

// Thrown by a job when trying again later might work (e.g. the model isn't downloaded yet).
// Any other exception means retrying won't help, so the job fails. Same rule as upstream
// (util/.../queue/RecoverableTaskException.kt).
// countsAsAttempt = false: the job couldn't even start (nothing to run it with yet), so it
// shouldn't use up one of its attempts. Otherwise a recording made before the model download
// would fail after 3 minutes. (Not in upstream.)
class RecoverableTaskException(
    message: String,
    cause: Throwable? = null,
    val countsAsAttempt: Boolean = true,
) : Exception(message, cause)
