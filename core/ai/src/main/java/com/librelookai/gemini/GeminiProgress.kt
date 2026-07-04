package com.librelookai.gemini

import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/**
 * Single-slot progress bus for the in-flight Gemini HTTP call. The
 * [com.librelookai.util.AiProcessingOverlay] collects [state] (via the app's
 * `LocalGeminiProgress` CompositionLocal) to show real upload-byte progress
 * and, once the request body is sent, a time-based "waiting for AI" estimate
 * (Gemini typically takes 20–40s to answer).
 *
 * Driven by [listener] (wired onto [GeminiRepository.http]) for the call
 * lifecycle plus [CountingRequestBody] for incremental upload bytes. Only ever
 * reflects the most recent call; an overlapping bulk call (which shows no
 * overlay) may overwrite the slot harmlessly.
 *
 * An injected `@Singleton` (refactor § 3 slice 5 — formerly a static `object`
 * slot): [GeminiRepository] owns the write side, the UI receives the same
 * instance through `MainActivity`.
 */
@javax.inject.Singleton
class GeminiProgress @javax.inject.Inject constructor() {
    data class State(
        /** Monotonic call-start time ([SystemClock.elapsedRealtime]). */
        val startedAtMillis: Long,
        /** True while the request body is still being written to the socket. */
        val uploading: Boolean,
        val bytesSent: Long,
        val bytesTotal: Long,
        /** Monotonic time the upload finished, or 0 while still uploading. */
        val uploadDoneAtMillis: Long,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private fun start() {
        _state.value = State(
            startedAtMillis = SystemClock.elapsedRealtime(),
            uploading = true,
            bytesSent = 0,
            bytesTotal = 0,
            uploadDoneAtMillis = 0,
        )
    }

    private fun onUpload(sent: Long, total: Long) {
        val s = _state.value ?: return
        _state.value = s.copy(bytesSent = sent, bytesTotal = total)
    }

    private fun uploadDone() {
        val s = _state.value ?: return
        if (!s.uploading) return
        _state.value = s.copy(uploading = false, uploadDoneAtMillis = SystemClock.elapsedRealtime())
    }

    private fun finish() {
        _state.value = null
    }

    /** OkHttp lifecycle hook: opens the slot on call start, clears it on completion/failure. */
    internal val listener: EventListener = object : EventListener() {
        override fun callStart(call: Call) = start()
        override fun requestBodyEnd(call: Call, byteCount: Long) = uploadDone()
        override fun callEnd(call: Call) = finish()
        override fun callFailed(call: Call, ioe: IOException) = finish()
        override fun canceled(call: Call) = finish()
    }

    /** Wraps a request body to report bytes as they are written to the socket. */
    internal inner class CountingRequestBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            val counting = object : ForwardingSink(sink) {
                private var written = 0L
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    onUpload(written, total)
                }
            }
            val buffered = counting.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }
}
