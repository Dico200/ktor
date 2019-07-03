/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.tls

import io.ktor.network.sockets.*
import kotlinx.coroutines.io.*
import kotlin.coroutines.*

internal expect suspend fun openTLSSession(
    socket: Socket,
    input: ByteReadChannel, output: ByteWriteChannel,
    config: TLSConfig,
    context: CoroutineContext
): Socket
