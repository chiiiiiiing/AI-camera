package com.example.aicamera.data.api

import android.util.Log
import com.example.aicamera.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 认证后端 API 客户端（OkHttp + org.json，零代码生成）。
 *
 * 后端为项目内 `server/server.js`（Node 零依赖），接口契约见 server/README.md。
 *
 * ## 错误模型
 * - [ApiOutcome.Success]：HTTP 2xx 且 `ok=true`
 * - [ApiOutcome.ApiError]：服务器返回的业务错误（4xx/5xx），message 可直接展示给用户
 * - [ApiOutcome.Unreachable]：连不上服务器（超时/拒连/DNS）→ 调用方应回退本地模式
 *
 * 超时刻意收紧（连接 2.5s / 读 4s）：登录页不能因为服务器不在线而卡死。
 */
class AuthApi(
    private val baseUrl: String = BuildConfig.AUTH_BASE_URL
) {
    data class ApiUser(
        val id: String,
        val username: String,
        val phone: String,
        val email: String,
    )

    data class LoginPayload(
        val token: String,
        val user: ApiUser,
        val isNewUser: Boolean = false,
    )

    data class SendCodePayload(
        val devCode: String?,
        val expiresInSec: Int,
    )

    sealed class ApiOutcome<out T> {
        data class Success<T>(val data: T) : ApiOutcome<T>()
        data class ApiError(val message: String, val httpCode: Int = 0) : ApiOutcome<Nothing>()
        data class Unreachable(val reason: String) : ApiOutcome<Nothing>()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(2_500, TimeUnit.MILLISECONDS)
        .readTimeout(4_000, TimeUnit.MILLISECONDS)
        .writeTimeout(4_000, TimeUnit.MILLISECONDS)
        .build()

    // ── 验证码 ───────────────────────────────────────────────

    suspend fun sendCode(target: String, scene: String): ApiOutcome<SendCodePayload> =
        post("/api/auth/code/send", JSONObject().put("target", target).put("scene", scene)) { json ->
            SendCodePayload(
                devCode = json.optString("devCode").ifBlank { null },
                expiresInSec = json.optInt("expiresInSec", 300)
            )
        }

    suspend fun verifyCode(target: String, scene: String, code: String): ApiOutcome<Unit> =
        post("/api/auth/code/verify", JSONObject()
            .put("target", target).put("scene", scene).put("code", code)) { }

    // ── 注册 / 登录 ──────────────────────────────────────────

    suspend fun register(
        username: String,
        password: String,
        phone: String?,
        email: String?,
    ): ApiOutcome<ApiUser> =
        post("/api/auth/register", JSONObject()
            .put("username", username)
            .put("password", password)
            .apply { phone?.let { put("phone", it) } }
            .apply { email?.let { put("email", it) } }) { json ->
            parseUser(json.getJSONObject("user"))
        }

    suspend fun login(account: String, password: String): ApiOutcome<LoginPayload> =
        post("/api/auth/login", JSONObject()
            .put("account", account).put("password", password)) { json ->
            parseLogin(json)
        }

    /** 验证码免密登录（未注册手机号/邮箱由服务器自动开户） */
    suspend fun loginWithCode(target: String, code: String): ApiOutcome<LoginPayload> =
        post("/api/auth/login-code", JSONObject()
            .put("target", target).put("code", code)) { json ->
            parseLogin(json)
        }

    suspend fun resetPassword(target: String, code: String, newPassword: String): ApiOutcome<Unit> =
        post("/api/auth/reset-password", JSONObject()
            .put("target", target).put("code", code).put("newPassword", newPassword)) { }

    // ── 会话 ─────────────────────────────────────────────────

    suspend fun me(token: String): ApiOutcome<ApiUser> {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/user/me")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return execute(request) { json -> parseUser(json.getJSONObject("user")) }
    }

    suspend fun health(): ApiOutcome<Int> =
        execute(Request.Builder().url(baseUrl.trimEnd('/') + "/api/health").get().build()) { json ->
            json.optInt("users", 0)
        }

    // ── 内部 ─────────────────────────────────────────────────

    private suspend fun <T> post(
        path: String,
        body: JSONObject,
        parse: (JSONObject) -> T,
    ): ApiOutcome<T> {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        return execute(request, parse)
    }

    private suspend fun <T> execute(
        request: Request,
        parse: (JSONObject) -> T,
    ): ApiOutcome<T> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = text.takeIf { it.isNotBlank() }?.let {
                    runCatching { JSONObject(it) }.getOrNull()
                }
                if (resp.isSuccessful && json != null && json.optBoolean("ok", false)) {
                    ApiOutcome.Success(parse(json))
                } else {
                    ApiOutcome.ApiError(
                        message = json?.optString("error") ?: "服务器错误 (${resp.code})",
                        httpCode = resp.code
                    )
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "服务器不可达: ${e.javaClass.simpleName}")
            ApiOutcome.Unreachable(e.message ?: "network error")
        } catch (e: Exception) {
            Log.e(TAG, "API 调用异常", e)
            ApiOutcome.ApiError("请求失败，请重试")
        }
    }

    private fun parseUser(json: JSONObject) = ApiUser(
        id = json.optString("id"),
        username = json.optString("username"),
        phone = json.optString("phone"),
        email = json.optString("email"),
    )

    private fun parseLogin(json: JSONObject) = LoginPayload(
        token = json.getString("token"),
        user = parseUser(json.getJSONObject("user")),
        isNewUser = json.optBoolean("isNewUser", false),
    )

    companion object {
        private const val TAG = "AuthApi"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
