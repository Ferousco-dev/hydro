package com.ferousco.hydro.core.provider

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.time.Duration

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
        require(!connectTimeout.isNegative && !connectTimeout.isZero) {
            "connectTimeout must be positive."
        }
        require(!readTimeout.isNegative && !readTimeout.isZero) {
            "readTimeout must be positive."
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
 * Portable HTTP transport backed by [HttpURLConnection], available on both Android (all supported
 * API levels) and the JVM. Uses [Dispatchers.IO] with interruption support so cancellation does not
 * block the caller, enforces HTTPS, disables automatic redirects, and maps failures to structured
 * [EngineError] values.
 *
 * This replaces the previous `java.net.http.HttpClient` implementation, which is **not** part of
 * the Android platform class library and would fail at runtime with `NoClassDefFoundError`.
 */
class UrlConnectionHttpTransport : HttpTransport {

    override suspend fun get(request: HttpGetRequest, providerId: String): EngineResult<RawHttpResponse> =
        try {
            runInterruptible(Dispatchers.IO) { execute(request, providerId) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Throwable) {
            EngineResult.Failure(classifyTransportThrowable(ex, providerId))
        }

    private fun execute(request: HttpGetRequest, providerId: String): EngineResult<RawHttpResponse> {
        var connection: HttpURLConnection? = null
        try {
            connection = (request.uri.toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = request.connectTimeout.toMillis().coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                readTimeout = request.readTimeout.toMillis().coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                for ((name, value) in request.headers) {
                    setRequestProperty(name, value)
                }
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val headers = buildMap<String, List<String>> {
                connection.headerFields.forEach { (key, values) ->
                    if (key != null) put(key, values ?: emptyList())
                }
            }

            return EngineResult.Success(
                RawHttpResponse(
                    statusCode = statusCode,
                    body = body,
                    headers = headers
                )
            )
        } catch (ex: IOException) {
            return EngineResult.Failure(classifyTransportThrowable(ex, providerId))
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        fun classifyTransportThrowable(throwable: Throwable, providerId: String): EngineError = when (throwable) {
            is UnknownHostException,
            is ConnectException -> EngineError.NetworkUnavailable(
                message = "Network connection to '$providerId' is unavailable.",
                causeSummary = throwable::class.simpleName
            )
            is SocketTimeoutException -> EngineError.ProviderError(
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
