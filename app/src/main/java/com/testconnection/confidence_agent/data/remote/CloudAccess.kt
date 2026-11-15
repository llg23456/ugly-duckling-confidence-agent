package com.testconnection.confidence_agent.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.testconnection.confidence_agent.BuildConfig
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

object CloudAccess {
    private const val ALIAS = "duckling_cloud_access_v1"
    private lateinit var preferences: SharedPreferences
    private lateinit var appContext: Context
    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAt: Long = 0
    private val mutableAuthorized = MutableStateFlow(false)
    val authorized = mutableAuthorized.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        preferences = appContext.getSharedPreferences("cloud_access", Context.MODE_PRIVATE)
        if (!BuildConfig.CLOUD_TEST) return
        runCatching {
            val encrypted = preferences.getString("encrypted", null) ?: return@runCatching
            val iv = Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
            require(json.getString("server") == BuildConfig.API_BASE_URL.trimEnd('/'))
            require(json.getString("device_id") == DeviceIdStore(appContext).get())
            expiresAt = json.getLong("expires_at")
            require(expiresAt > System.currentTimeMillis() / 1000)
            accessToken = json.getString("access_token")
            mutableAuthorized.value = true
        }.onFailure { invalidate() }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun save(json: JSONObject) {
        json.put("server", BuildConfig.API_BASE_URL.trimEnd('/'))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit()
            .putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
        expiresAt = json.getLong("expires_at")
        accessToken = json.getString("access_token")
        mutableAuthorized.value = true
    }

    fun invalidate() {
        accessToken = null
        expiresAt = 0
        mutableAuthorized.value = false
        if (::preferences.isInitialized) preferences.edit().clear().apply()
        if (::appContext.isInitialized) com.testconnection.confidence_agent.data.repository.CommunityImageCache.clearAll(appContext)
    }

    fun tokenFor(url: URL): String? {
        if (!BuildConfig.CLOUD_TEST || !CloudUrlPolicy.isApiUrl(url, BuildConfig.API_BASE_URL)) return null
        if (expiresAt <= System.currentTimeMillis() / 1000) {
            invalidate()
            return null
        }
        return accessToken
    }

    suspend fun activate(code: String) = withContext(Dispatchers.IO) {
        val url = URL("${BuildConfig.API_BASE_URL.trimEnd('/')}/api/v1/cloud-auth/activate")
        require(url.protocol == "https") { "云测需要安全连接" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        var phase = "request"
        try {
            connection.outputStream.use { it.write(JSONObject().put("invite_code", code.trim())
                .put("device_id", DeviceIdStore(appContext).get()).toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                error(detail?.takeIf { it.isNotBlank() && !it.startsWith("[") } ?: "云测入口暂时不可用，请稍后重试")
            }
            phase = "credential storage"
            save(JSONObject(text))
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("CloudAccess", "Activation failed during $phase", error)
            throw error
        } finally {
            connection.disconnect()
        }
    }
}

fun URL.openAuthorizedConnection(): HttpURLConnection = (openConnection() as HttpURLConnection).apply {
    CloudAccess.tokenFor(this@openAuthorizedConnection)?.let {
        instanceFollowRedirects = false
        setRequestProperty("Authorization", "Bearer $it")
    }
}

fun HttpURLConnection.checkedResponseCode(): Int = responseCode.also {
    if (it == 401 && BuildConfig.CLOUD_TEST &&
        CloudUrlPolicy.isApiUrl(url, BuildConfig.API_BASE_URL)) CloudAccess.invalidate()
}
