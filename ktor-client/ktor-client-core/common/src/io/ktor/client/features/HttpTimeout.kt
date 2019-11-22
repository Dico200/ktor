/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.features

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.util.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.*
import kotlin.native.concurrent.*

/**
 * Client HTTP timeout feature. There are no default values, so default timeouts will be taken from engine configuration
 * or considered as infinite time if engine doesn't provide them.
 */
class HttpTimeout(
    private val requestTimeoutMillis: Long?,
    private val connectTimeoutMillis: Long?,
    private val socketTimeoutMillis: Long?
) {
    /**
     * [HttpTimeout] extension configuration that is used during installation.
     */
    class HttpTimeoutExtension(
        requestTimeoutMillis: Long? = null,
        connectTimeoutMillis: Long? = null,
        socketTimeoutMillis: Long? = null
    ) {

        var requestTimeoutMillis: Long? = requestTimeoutMillis
            set(value) {
                field = checkTimeoutValue(value)
            }

        var connectTimeoutMillis: Long? = connectTimeoutMillis
            set(value) {
                field = checkTimeoutValue(value)
            }

        var socketTimeoutMillis: Long? = socketTimeoutMillis
            set(value) {
                field = checkTimeoutValue(value)
            }

        internal fun build(): HttpTimeout = HttpTimeout(requestTimeoutMillis, connectTimeoutMillis, socketTimeoutMillis)

        private fun checkTimeoutValue(value: Long?): Long? {
            check(value == null || value > 0) {
                "Only positive timeout values are allowed, for infinite timeout use INFINITE_TIMEOUT_MS"
            }
            return value
        }

        companion object {
            @SharedImmutable
            val key = AttributeKey<HttpTimeoutExtension>("TimeoutConfiguration")

            @SharedImmutable
            const val INFINITE_TIMEOUT_MS = Long.MAX_VALUE
        }
    }

    /**
     * Utils method that return true if at least one timeout is configured (has not null value).
     */
    private fun hasNotNullTimeouts() =
        requestTimeoutMillis != null || connectTimeoutMillis != null || socketTimeoutMillis != null

    /**
     * Companion object for feature installation.
     */
    companion object Feature : HttpClientFeature<HttpTimeoutExtension, HttpTimeout> {

        override val key: AttributeKey<HttpTimeout> = AttributeKey("TimeoutFeature")

        override fun prepare(block: HttpTimeoutExtension.() -> Unit): HttpTimeout =
            HttpTimeoutExtension().apply(block).build()

        override fun install(feature: HttpTimeout, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.Before) {
                var configuration = context.getExtensionOrNull(HttpTimeoutExtension.key)
                if (configuration == null && feature.hasNotNullTimeouts()) {
                    configuration = HttpTimeoutExtension()
                    context.setExtension(HttpTimeoutExtension.key, configuration)
                }

                configuration?.apply {
                    connectTimeoutMillis = connectTimeoutMillis ?: feature.connectTimeoutMillis
                    socketTimeoutMillis = socketTimeoutMillis ?: feature.socketTimeoutMillis
                    requestTimeoutMillis = requestTimeoutMillis ?: feature.requestTimeoutMillis

                    val requestTimeout = requestTimeoutMillis ?: feature.requestTimeoutMillis
                    if (requestTimeout == null || requestTimeout == 0L) return@apply

                    val executionContext = context.executionContext
                    val killer = GlobalScope.launch {
                        delay(requestTimeout)
                        executionContext.cancel(HttpRequestTimeoutException())
                    }

                    context.executionContext.invokeOnCompletion {
                        killer.cancel()
                    }
                }
            }
        }
    }
}

/**
 * This exception is thrown in case request timeout exceeded.
 */
class HttpRequestTimeoutException : CancellationException("Request timeout has been expired")

/**
 * This exception is thrown in case connect timeout exceeded.
 */
expect class HttpConnectTimeoutException : IOException

/**
 * This exception is thrown in case socket timeout exceeded.
 */
expect class HttpSocketTimeoutException : IOException
