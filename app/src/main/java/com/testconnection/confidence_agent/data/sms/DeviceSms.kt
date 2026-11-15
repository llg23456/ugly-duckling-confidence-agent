package com.testconnection.confidence_agent.data.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.testconnection.confidence_agent.BuildConfig
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import com.testconnection.confidence_agent.data.remote.WellbeingApiClient
import com.testconnection.confidence_agent.data.remote.WellbeingApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Foreground-only local-demo transport; no permission, token or enrollment is implicit. */
object DeviceSms {
    const val CONSENT = "device-sms-context-adult-v2"
    private const val STORE = "device_sms"
    private val lock = Any()
    private val pollMutex = Mutex()
    private fun prefs(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun hasPermission(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun readiness(context: Context): String? = when {
        BuildConfig.CLOUD_TEST -> "请运行 localDebug 本地版"
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) -> "这台设备不支持手机短信"
        !hasPermission(context) -> "请先允许短信权限"
        else -> runCatching {
            if (SubscriptionManager.getDefaultSmsSubscriptionId() < 0) "请在系统 SIM 卡设置中选择默认短信卡，不能设为每次询问" else null
        }.getOrDefault("无法读取默认短信卡，请检查系统 SIM 卡设置")
    }

    fun activate(context: Context, token: String, contactId: Long, phone: String) = synchronized(lock) {
        val p = prefs(context)
        val device = DeviceIdStore(context).get()
        val sameAccount = p.getString("endpoint", "") == ServerEndpoint.current() && p.getString("device", "") == device
        val registrations = JSONObject(if (sameAccount) p.getString("registrations", "{}") ?: "{}" else "{}")
        registrations.put(contactId.toString(), JSONObject().put("token", token).put("phone", phone).put("contact_id", contactId))
        check(p.edit().putString("registrations", registrations.toString()).putString("endpoint", ServerEndpoint.current())
            .putString("device", device).putBoolean("enabled", true).remove("token").remove("phone").remove("contact_id")
            .remove("error").commit()) { "无法保存本机短信授权" }
    }

    fun removeContact(context: Context, contactId: Long?, expectedToken: String? = null) {
        synchronized(lock) {
            if (contactId == null) return
            val p = prefs(context)
            val registrations = JSONObject(p.getString("registrations", "{}") ?: "{}")
            val currentToken = registrations.optJSONObject(contactId.toString())?.optString("token") ?: p.getString("token", null)
            if (expectedToken != null && currentToken != expectedToken) return
            registrations.remove(contactId.toString())
            val edit = p.edit().putString("registrations", registrations.toString())
            if (p.getLong("contact_id", -1) == contactId) edit.remove("token").remove("phone").remove("contact_id")
            edit.commit()
        }
    }

    fun stop(context: Context) { prefs(context).edit().putBoolean("enabled", false).commit() }
    fun configured(context: Context) = prefs(context).getBoolean("enabled", false) &&
        prefs(context).getString("endpoint", "") == ServerEndpoint.current()
    fun lastError(context: Context): String? = prefs(context).getString("error", null)

    private fun updateError(context: Context, error: String?) {
        prefs(context).edit().putString("error", error).commit()
    }

    private fun journal(context: Context, key: String, update: (JSONObject) -> Unit) = synchronized(lock) {
        val p = prefs(context)
        val row = JSONObject(p.getString(key, "{}") ?: "{}")
        update(row)
        check(p.edit().putString(key, row.toString()).commit()) { "无法保存短信发送记录" }
    }

    private fun receiptIntent(context: Context, key: String, index: Int, type: String): PendingIntent {
        val intent = Intent(context, DeviceSmsReceiptReceiver::class.java)
            .setAction("${context.packageName}.SMS_$type")
            .setData(Uri.parse("yaligo-sms://receipt/$key/$index/$type"))
            .putExtra("key", key).putExtra("part", index).putExtra("type", type)
        // Only the explicit delivery callback needs a mutable Intent for the OS
        // status-report PDU. The component is private; no phone/content is attached.
        val mutability = if (type == "delivered" && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE
                         else if (type == "delivered") 0 else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, mutability or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    internal fun receive(context: Context, intent: Intent, result: Int) {
        val key = intent.getStringExtra("key") ?: return
        if (!key.startsWith("job_") || !prefs(context).contains(key)) return
        val index = intent.getIntExtra("part", -1)
        journal(context, key) { row ->
            val total = row.optInt("parts")
            if (index !in 0 until total) return@journal
            if (intent.getStringExtra("type") == "sent") {
                row.put("sent_$index", result == Activity.RESULT_OK)
                if (result != Activity.RESULT_OK) row.put("error", "系统短信发送失败（代码 $result），请检查余额、信号和短信权限")
            } else if (intent.getStringExtra("type") == "delivered" && result == Activity.RESULT_OK) {
                val report = intent.getByteArrayExtra("pdu")?.let { pdu ->
                    runCatching { SmsMessage.createFromPdu(pdu, intent.getStringExtra("format") ?: "3gpp") }.getOrNull()
                }
                // A delivery report may also describe failure/delay. Only a
                // completed status establishes delivery; missing PDU stays unknown.
                if (report?.isStatusReportMessage == true && report.status == 0) row.put("delivered_$index", true)
            }
            val sent = (0 until total).filter { row.has("sent_$it") }.associateWith { row.optBoolean("sent_$it") }
            val delivered = (0 until total).filter { row.optBoolean("delivered_$it") }.toSet()
            row.put("status", SmsReceiptState.status(total, sent, delivered))
            row.put("dirty", true)
        }
    }

    private suspend fun uploadReceipts(context: Context, api: WellbeingApiClient, device: String, endpoint: String) {
        // Keep receipts after disabling; this only records a previous send, never resends it.
        val entries = prefs(context).all.filterKeys { it.startsWith("job_") }
        for ((key, raw) in entries) {
            var row = JSONObject(raw as? String ?: continue)
            if (row.optString("endpoint") != endpoint || row.optString("device") != device) continue
            if (row.optString("status") == "phone_sending" && System.currentTimeMillis() - row.optLong("started") > 120_000) {
                journal(context, key) { it.put("status", "phone_unknown").put("dirty", true) }
                row = JSONObject(prefs(context).getString(key, "{}")!!)
            }
            val status = row.optString("status")
            if (!row.optBoolean("dirty") || status == "phone_sending") continue
            try {
                api.request("device-sms/${row.getLong("id")}/receipt", device, "POST",
                    JSONObject().put("claim_token", row.getString("claim_token")).put("status", status), endpoint)
            } catch (e: WellbeingApiException) {
                if (e.statusCode in listOf(403, 404)) {
                    prefs(context).edit().remove(key).commit() // source/account deleted or server reset
                    continue
                }
                throw e
            }
            journal(context, key) { current ->
                if (current.optString("status") == status) current.put("dirty", false)
            }
        }
    }

    suspend fun poll(context: Context) = pollMutex.withLock {
        if (BuildConfig.CLOUD_TEST) return@withLock
        withContext(Dispatchers.IO) {
            val api = WellbeingApiClient()
            val endpoint = ServerEndpoint.current()
            val device = DeviceIdStore(context).get()
            try { uploadReceipts(context, api, device, endpoint) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* retry receipts next foreground poll */ }
            val p = prefs(context)
            if (!configured(context) || p.getString("device", "") != device) return@withContext
            val problem = readiness(context)
            if (problem != null) { updateError(context, problem); return@withContext }
            val registrations = JSONObject(p.getString("registrations", "{}") ?: "{}")
            val entries = registrations.keys().asSequence().map { registrations.getJSONObject(it) }.toList()
            // An old authorization can only receive the old non-contextual template
            // until the user edits a contact and accepts the new disclosure notice.
            val legacy = p.getString("token", null)?.let {
                JSONObject().put("token", it).put("phone", p.getString("phone", "")).put("contact_id", p.getLong("contact_id", -1))
            }
            for (entry in entries.ifEmpty { listOfNotNull(legacy) }) {
                pollContact(context, api, endpoint, device, entry)
            }
        }
    }

    private fun stillAuthorized(context: Context, entry: JSONObject): Boolean {
        val p = prefs(context)
        val saved = JSONObject(p.getString("registrations", "{}") ?: "{}").optJSONObject(entry.optLong("contact_id").toString())
        return configured(context) && p.getString("device", "") == DeviceIdStore(context).get() &&
            (saved?.optString("token") ?: p.getString("token", "")) == entry.getString("token")
    }

    private suspend fun pollContact(context: Context, api: WellbeingApiClient, endpoint: String, device: String, entry: JSONObject) {
            try {
                if (!stillAuthorized(context, entry)) return
                val token = entry.getString("token")
                val response = api.request("device-sms/claim", device, "POST", JSONObject().put("enrollment_token", token), endpoint)
                val job = response.optJSONObject("job") ?: run { updateError(context, null); return }
                check(job.getString("phone") == entry.getString("phone")) { "接收号码已变化，请编辑联系人重新保存" }
                val key = "job_" + UUID.randomUUID().toString()
                // Persist BEFORE authorization and platform I/O. A claimed job is never reissued,
                // even if the process dies or the HTTP response is lost.
                journal(context, key) {
                    it.put("id", job.getLong("id")).put("claim_token", job.getString("claim_token"))
                        .put("device", device).put("endpoint", endpoint).put("started", System.currentTimeMillis())
                        .put("status", "phone_sending").put("dirty", false)
                }
                if (!stillAuthorized(context, entry) || Instant.parse(job.getString("expires_at")).isBefore(Instant.now())) return
                api.request("device-sms/${job.getLong("id")}/authorize", device, "POST",
                    JSONObject().put("enrollment_token", token).put("claim_token", job.getString("claim_token")), endpoint)
                try {
                    check(stillAuthorized(context, entry)) { "已关闭本机发送" }
                    check(Instant.parse(job.getString("expires_at")).isAfter(Instant.now())) { "通知已过期" }
                    readiness(context)?.let { error(it) }
                    val subscription = SubscriptionManager.getDefaultSmsSubscriptionId()
                    @Suppress("DEPRECATION")
                    val manager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscription)
                                  else SmsManager.getSmsManagerForSubscriptionId(subscription)
                    val parts = manager.divideMessage(job.getString("text"))
                    journal(context, key) { it.put("parts", parts.size) }
                    manager.sendMultipartTextMessage(job.getString("phone"), null, parts,
                        ArrayList(parts.indices.map { receiptIntent(context, key, it, "sent") }),
                        ArrayList(parts.indices.map { receiptIntent(context, key, it, "delivered") }))
                    updateError(context, null)
                } catch (e: Exception) {
                    journal(context, key) { it.put("status", "phone_failed").put("dirty", true) }
                    updateError(context, e.message ?: "短信未发送，请查看系统短信权限和 SIM 卡")
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                  if (e is WellbeingApiException && e.statusCode == 403) removeContact(context, entry.optLong("contact_id"), entry.optString("token"))
                  if (e is WellbeingApiException && e.statusCode == 409 && e.message == "自动短信已关闭") stop(context)
                  updateError(context, e.message ?: "无法连接本地短信任务服务")
              }
    }
}

class DeviceSmsReceiptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.CLOUD_TEST) runCatching { DeviceSms.receive(context, intent, resultCode) }
    }
}

@Composable
fun DeviceSmsDispatcher() {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        if (!BuildConfig.CLOUD_TEST) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                DeviceSms.poll(context)
                delay(4_000)
            }
        }
    }
}
