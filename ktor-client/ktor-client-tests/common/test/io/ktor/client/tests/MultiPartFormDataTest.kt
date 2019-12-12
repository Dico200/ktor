/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.tests

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import kotlinx.io.*
import kotlinx.serialization.internal.*
import kotlin.test.*

/**
 * Tests client request with multi-part form data.
 */
class MultiPartFormDataTest : ClientLoader() {

    @Test
    fun testMultiPartFormData() = clientTests {
        test { client ->
            val result = client.post<HttpStatement>("$TEST_SERVER/multipart") {
                body = MultiPartFormDataContent(formData {
                    append(
                        "file",
                        ByteArray(1024 * 1024),
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, """form-data; name="file"; filename="test.png"""")
                        }
                    )
                })
            }.execute()

            check(result.status == HttpStatusCode.OK)
        }
    }
}
