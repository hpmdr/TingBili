package cn.debubu.tingbili.core.data

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val msg: String, val throwable: Throwable? = null) : Result<Nothing>()
}
