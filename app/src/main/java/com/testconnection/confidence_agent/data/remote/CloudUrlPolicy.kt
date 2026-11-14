package com.testconnection.confidence_agent.data.remote

import java.net.URL

object CloudUrlPolicy {
    fun isApiUrl(url: URL, baseUrl: String): Boolean = runCatching {
        val base = URL(baseUrl)
        url.protocol == "https" && base.protocol == "https" &&
            url.host.equals(base.host, ignoreCase = true) &&
            (if (url.port == -1) 443 else url.port) == (if (base.port == -1) 443 else base.port) &&
            url.userInfo == null && url.path.startsWith("/api/v1/")
    }.getOrDefault(false)
}
