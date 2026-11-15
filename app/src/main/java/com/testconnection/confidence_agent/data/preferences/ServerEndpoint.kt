package com.testconnection.confidence_agent.data.preferences

import android.content.Context
import android.net.Uri
import com.testconnection.confidence_agent.BuildConfig

object ServerEndpoint {
    private const val STORE_NAME = "server_endpoint"
    private const val KEY_BASE_URL = "base_url"

    @Volatile
    private var value: String = BuildConfig.API_BASE_URL.trimEnd('/')

    fun initialize(context: Context) {
        if (BuildConfig.CLOUD_TEST) {
            value = BuildConfig.API_BASE_URL.trimEnd('/')
            return
        }
        value = context.applicationContext
            .getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, BuildConfig.API_BASE_URL)
            .orEmpty()
            .ifBlank { BuildConfig.API_BASE_URL }
            .trimEnd('/')
    }

    fun current(): String = value

    fun normalize(input: String): String {
        val candidate = input.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
        }.trimEnd('/')
        val uri = Uri.parse(candidate)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "请输入有效地址，例如 http://192.168.1.10:8000"
        }
        return candidate
    }

    fun save(context: Context, input: String): String {
        val normalized = normalize(input)
        require(!BuildConfig.CLOUD_TEST || normalized == BuildConfig.API_BASE_URL.trimEnd('/')) {
            "云测版使用项目指定服务，无需修改地址"
        }
        context.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_BASE_URL, normalized).apply()
        value = normalized
        return normalized
    }
}
