package com.ferousco.hydro.core.provider

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpGetRequest(
    val uri: URI,
    val headers: Map<String, String> = emptyMap(),
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(15)
) {
    init {
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Hydro HTTP transport requires HTTPS scheme, got '${uri.scheme}'."
        }
    }
}

data class RawHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap()
) {
    fun firstHeader(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * Minimal, testable HTTP GET abstraction for weather provider adapters.
 */
interface HttpTransport {
    suspend fun get(request: HttpGetRequest, providerId: String): EngineResult<RawHttpResponse>
}

/**
 * Production JVM/Android-API-compatible HTTP transport backed by [java.net.http.HttpClient]
 * with strict timeouts, HTTPS enforcement, coroutine cancellation, and structured error mapping.
 */
class JavaNetHttpTransport(
    connectTimeout: Duration = Duration.ofSeconds(10)
) : HttpTransport {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override suspend fun get(request: HttpGetRequest, providerId: String): EngineResult<RawHttpResponse> {
        val jdkRequestBuilder = HttpRequest.newBuilder()
            .uri(request.uri)
            .timeout(request.readTimeout)
            .GET()

        for ((k, v) in request.headers) {
            jdkRequestBuilder.header(k, v)
        }

        return try {
            val response = client.sendCancellable(jdkRequestBuilder.build())
            EngineResult.Success(
                RawHttpResponse(
                    statusCode = response.statusCode(),
                    body = response.body(),
                    headers = response.headers().map()
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Throwable) {
            val root = unwrapCompletionException(ex)
            EngineResult.Failure(classifyTransportThrowable(root, providerId))
        }
    }

    private suspend fun HttpClient.sendCancellable(
        request: HttpRequest
    ): HttpResponse<String> = suspendCancellableCoroutine { cont ->
        val future = sendAsync(request, HttpResponse.BodyHandlers.ofString())
        cont.invokeOnCancellation {
            future.cancel(true)
        }
        future.whenComplete { response, throwable ->
            if (!cont.isActive) return@whenComplete
            if (throwable != null) {
                val unwrapped = unwrapCompletionException(throwable)
                if (unwrapped is java.util.concurrent.CancellationException) {
                    cont.resumeWithException(CancellationException("HTTP request cancelled."))
                } else {
                    cont.resumeWithException(unwrapped)
                }
            } else {
                cont.resume(response)
            }
        }
    }

    companion object {
        private fun unwrapCompletionException(throwable: Throwable): Throwable =
            if (throwable is CompletionException && throwable.cause != null) {
                unwrapCompletionException(throwable.cause!!)
            } else {
                throwable
            }

        fun classifyTransportThrowable(throwable: Throwable, providerId: String): EngineError = when (throwable) {
            is UnknownHostException,
            is ConnectException -> EngineError.NetworkUnavailable(
                message = "Network connection to '$providerId' is unavailable.",
                causeSummary = throwable::class.simpleName
            )
            is HttpConnectTimeoutException,
            is HttpTimeoutException,
            is java.net.SocketTimeoutException -> EngineError.ProviderError(
                providerId = providerId,
                statusCode = null,
                providerReason = "request_timeout",
                isTransient = true,
                message = "Request to '$providerId' timed out."
            )
            is IOException -> EngineError.NetworkUnavailable(
                message = "I/O failure while communicating with '$providerId'.",
                causeSummary = throwable::class.simpleName
            )
            else -> EngineError.ProviderError(
                providerId = providerId,
                statusCode = null,
                providerReason = throwable::class.simpleName,
                isTransient = false,
                message = "Unexpected transport error from '$providerId': ${throwable::class.simpleName}."
            )
        }
    }
}
