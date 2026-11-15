package com.testconnection.confidence_agent.data.remote

import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ExperienceFeedbackClient {
    suspend fun submit(payload: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL("${ServerEndpoint.current()}/api/v1/experience-feedback").openAuthorizedConnection().apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.checkedResponseCode()
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            when {
                status in 200..299 -> JSONObject(text)
                status == 401 -> error("云测授权已失效，请重新授权后返回重试，草稿已保留。")
                status == 422 -> throw InvalidFeedbackException("问卷内容需要检查，请重新确认必填项及选项。")
                status == 409 -> throw InvalidFeedbackException("这次提交已收到，请重新开始一份评价。")
                status == 429 -> error("请求较多或今日额度已用完，请稍后再试，草稿已保留。")
                else -> error("暂时未确认提交结果，请稍后重试，草稿已保留。")
            }
        } finally { connection.disconnect() }
    }
}

class InvalidFeedbackException(message: String) : IllegalStateException(message)
