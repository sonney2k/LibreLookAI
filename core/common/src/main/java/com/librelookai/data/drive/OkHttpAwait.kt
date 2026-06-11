package com.librelookai.data.drive

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp coroutine bridge, used by every HTTP-calling repository (Drive, Gemini, weather, auth).
 * Lives in `:core:common` (it was the *only* `data.drive` symbol the gemini/auth/weather packages
 * imported — moving it removes those wrong-direction edges); kept in the
 * `com.librelookai.data.drive` package so existing import sites stay untouched.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
    cont.invokeOnCancellation { cancel() }
}
