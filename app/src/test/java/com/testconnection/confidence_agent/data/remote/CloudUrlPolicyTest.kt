package com.testconnection.confidence_agent.data.remote

import java.net.URL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudUrlPolicyTest {
    private val base = "https://api.yaligo-growth.top"
    @Test fun limitsCredentialToHttpsApiOrigin() {
        assertTrue(CloudUrlPolicy.isApiUrl(URL("$base/api/v1/chat"), base))
        assertTrue(CloudUrlPolicy.isApiUrl(URL("$base:443/api/v1/chat"), base))
        listOf("http://api.yaligo-growth.top/api/v1/chat", "$base:8443/api/v1/chat",
            "https://evil.example/api/v1/chat", "https://api.yaligo-growth.top.evil.example/api/v1/chat",
            "$base/health", "$base/api/v10/chat", "https://user@api.yaligo-growth.top/api/v1/chat")
            .forEach { assertFalse(it, CloudUrlPolicy.isApiUrl(URL(it), base)) }
    }
}
