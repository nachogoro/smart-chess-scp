package scp.protocol

sealed class Result<out E, out T> {
    data class Success<T>(val value: T) : Result<Nothing, T>()
    data class Failure<E>(val error: E) : Result<E, Nothing>()
}

inline fun <E, T, R> Result<E, T>.map(transform: (T) -> R): Result<E, R> = when (this) {
    is Result.Success -> Result.Success(transform(value))
    is Result.Failure -> this
}

inline fun <E, T, R> Result<E, T>.flatMap(transform: (T) -> Result<E, R>): Result<E, R> = when (this) {
    is Result.Success -> transform(value)
    is Result.Failure -> this
}

fun <E, T> Result<E, T>.getOrNull(): T? = when (this) {
    is Result.Success -> value
    is Result.Failure -> null
}

fun <E, T> Result<E, T>.errorOrNull(): E? = when (this) {
    is Result.Success -> null
    is Result.Failure -> error
}

inline fun <E, T> Result<E, T>.onSuccess(action: (T) -> Unit): Result<E, T> {
    if (this is Result.Success) action(value)
    return this
}

inline fun <E, T> Result<E, T>.onFailure(action: (E) -> Unit): Result<E, T> {
    if (this is Result.Failure) action(error)
    return this
}
