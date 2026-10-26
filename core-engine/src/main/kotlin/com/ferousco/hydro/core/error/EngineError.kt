package com.ferousco.hydro.core.error

import java.time.Duration
import java.time.Instant

/**
 * Structured error taxonomy for Hydro's environmental intelligence engine.
 *
 * Failures are represented explicitly rather than converted into default or fabricated weather values.
 */
sealed interface EngineError {
    val message: String

    data class NetworkUnavailable(
        override val message: String,
        val causeSummary: String? = null
    ) : EngineError

    data class ProviderError(
        val providerId: String,
        val statusCode: Int?,
        val providerReason: String?,
        val isTransient: Boolean,
        val retryAfterSeconds: Long? = null,
        override val message: String
    ) : EngineError

    data class InvalidResponse(
        val providerId: String,
        val fieldOrStage: String,
        override val message: String
    ) : EngineError

    data class MissingRequiredInputs(
        val operation: String,
        val missingFields: List<String>,
        override val message: String
    ) : EngineError

    data class StaleData(
        val retrievedAt: Instant,
        val evaluatedAt: Instant,
        val maxAllowedAge: Duration,
        override val message: String
    ) : EngineError

    data class UnsupportedCalculation(
        val methodId: String,
        val reason: String,
        override val message: String
    ) : EngineError

    data class PersistenceFailure(
        val operation: String,
        override val message: String,
        val causeSummary: String? = null
    ) : EngineError

    data class Cancelled(
        val operation: String,
        override val message: String = "Operation '$operation' was cancelled."
    ) : EngineError

    data class InvalidInput(
        val field: String,
        val rejectedValueSummary: String,
        override val message: String
    ) : EngineError

    data class PolicyDenied(
        val capability: String,
        override val message: String
    ) : EngineError
}

sealed interface EngineResult<out T> {
    data class Success<T>(val value: T) : EngineResult<T>
    data class Failure(val error: EngineError) : EngineResult<Nothing>

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun errorOrNull(): EngineError? = when (this) {
        is Success -> null
        is Failure -> error
    }
}

inline fun <T, R> EngineResult<T>.map(transform: (T) -> R): EngineResult<R> = when (this) {
    is EngineResult.Success -> EngineResult.Success(transform(value))
    is EngineResult.Failure -> this
}

inline fun <T, R> EngineResult<T>.flatMap(transform: (T) -> EngineResult<R>): EngineResult<R> = when (this) {
    is EngineResult.Success -> transform(value)
    is EngineResult.Failure -> this
}

inline fun <T, R> EngineResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (EngineError) -> R
): R = when (this) {
    is EngineResult.Success -> onSuccess(value)
    is EngineResult.Failure -> onFailure(error)
}
