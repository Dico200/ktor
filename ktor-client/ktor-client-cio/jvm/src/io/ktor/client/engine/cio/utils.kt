/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.cio

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.http.cio.websocket.*
import io.ktor.http.content.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

internal suspend fun HttpRequestData.write(
    output: ByteWriteChannel, callContext: CoroutineContext,
    overProxy: Boolean,
    allowHalfClose: Boolean
) {
    val builder = RequestResponseBuilder()

    val contentLength = headers[HttpHeaders.ContentLength] ?: body.contentLength?.toString()
    val contentEncoding = headers[HttpHeaders.TransferEncoding]
    val responseEncoding = body.headers[HttpHeaders.TransferEncoding]
    val chunked = contentLength == null || responseEncoding == "chunked" || contentEncoding == "chunked"

    try {
        val urlString = if (overProxy) {
            url.toString()
        } else {
            url.fullPath
        }

        builder.requestLine(method, urlString, HttpProtocolVersion.HTTP_1_1.toString())
        // this will only add the port to the host header if the port is non-standard for the protocol
        builder.headerLine("Host", if (url.protocol.defaultPort == url.port) url.host else url.hostWithPort)

        mergeHeaders(headers, body) { key, value ->
            builder.headerLine(key, value)
        }

        if (chunked && contentEncoding == null && responseEncoding == null && body !is OutgoingContent.NoContent) {
            builder.headerLine(HttpHeaders.TransferEncoding, "chunked")
        }

        builder.emptyLine()
        output.writePacket(builder.build())
        output.flush()
    } finally {
        builder.release()
    }

    val content = body
    if (content is OutgoingContent.NoContent)
        return

    val chunkedJob: EncoderJob? = if (chunked) encodeChunked(output, callContext) else null
    val channel = chunkedJob?.channel ?: output

    try {
        when (content) {
            is OutgoingContent.NoContent -> return
            is OutgoingContent.ByteArrayContent -> content.writeTo(channel, allowHalfClose)
            is OutgoingContent.ReadChannelContent -> content.writeTo(channel, allowHalfClose)
            is OutgoingContent.WriteChannelContent -> content.writeTo(callContext, channel, allowHalfClose)
            is OutgoingContent.ProtocolUpgrade -> throw UnsupportedContentTypeException(content)
        }
    } catch (cause: Throwable) {
        channel.close(cause)
    } finally {
        channel.flush()
        if (allowHalfClose) {
            chunkedJob?.channel?.close()
            chunkedJob?.join()
        }
    }
}

/**
 * Utils function that writes content into specified [channel] and closes it if half close is allowed.
 */
private suspend fun OutgoingContent.ByteArrayContent.writeTo(channel: ByteWriteChannel, allowHalfClose: Boolean) {
    channel.writeFully(bytes())
    if (allowHalfClose) {
        channel.close()
    }
}

/**
 * Utils function that writes content into specified [channel] and closes it if half close is allowed.
 */
private suspend fun OutgoingContent.ReadChannelContent.writeTo(channel: ByteWriteChannel, allowHalfClose: Boolean) {
    if (allowHalfClose) {
        readFrom().copyAndClose(channel)
    } else {
        readFrom().copyTo(channel)
    }
}

/**
 * Utils function that writes content into specified [channel] and closes it if half close is allowed.
 */
private suspend fun OutgoingContent.WriteChannelContent.writeTo(
    coroutineContext: CoroutineContext,
    channel: ByteWriteChannel,
    allowHalfClose: Boolean
) {
    if (allowHalfClose) {
        writeTo(channel)
    } else {
        val proxyChannel = GlobalScope.reader(coroutineContext) {
            this.channel.copyTo(channel, Long.MAX_VALUE)
        }.channel

        writeTo(proxyChannel)
    }
}

internal suspend fun readResponse(
    requestTime: GMTDate,
    request: HttpRequestData,
    input: ByteReadChannel,
    output: ByteWriteChannel,
    callContext: CoroutineContext
): HttpResponseData {
    val rawResponse = parseResponse(input)
        ?: throw EOFException("Failed to parse HTTP response: unexpected EOF")

    val status = HttpStatusCode(rawResponse.status, rawResponse.statusText.toString())
    val contentLength = rawResponse.headers[HttpHeaders.ContentLength]?.toString()?.toLong() ?: -1L
    val transferEncoding = rawResponse.headers[HttpHeaders.TransferEncoding]
    val connectionType = ConnectionOptions.parse(rawResponse.headers[HttpHeaders.Connection])

    val headers = buildHeaders {
        appendAll(CIOHeaders(rawResponse.headers))
        rawResponse.headers.release()
    }

    val version = HttpProtocolVersion.parse(rawResponse.version)

    if (status == HttpStatusCode.SwitchingProtocols) {
        val session = RawWebSocket(input, output, masking = true, coroutineContext = callContext)
        return HttpResponseData(status, requestTime, headers, version, session, callContext)
    }

    val body = when {
        request.method == HttpMethod.Head ||
            status in listOf(HttpStatusCode.NotModified, HttpStatusCode.NoContent) ||
            status.isInformational() -> {
            ByteReadChannel.Empty
        }
        else -> {
            val httpBodyParser = GlobalScope.writer(callContext, autoFlush = true) {
                parseHttpBody(contentLength, transferEncoding, connectionType, input, channel)
            }

            httpBodyParser.channel
        }
    }

    return HttpResponseData(status, requestTime, headers, version, body, callContext)
}

internal fun HttpStatusCode.isInformational(): Boolean = (value / 100) == 1
