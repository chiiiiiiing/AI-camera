package com.example.aicamera.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 本地认证仓库（MVP 阶段基于 SharedPreferences）。
 *
 * MVP 用本地存储模拟注册/登录流程。后续接入真实后端时替换此类即可。
 *
 * ## 存储结构
 * - `auth_username` → 用户名
 * - `auth_password_hash` → PBKDF2(密码, salt, 20k 迭代)
 * - `auth_password_salt` → 随机盐值
 * - `auth_phone` → 注册手机号（用于找回密码）
 * - `auth_session_token` → 登录会话 token
 *
 * ## 安全说明（2026-07-26 加固）
 * - 密码哈希从 SHA-256 单轮升级为 **PBKDF2-HmacSHA256 / 20,000 迭代**（抗暴破）；
 *   ⚠️ 算法变更使旧哈希失效，老用户需重新注册（MVP 本地账号，可接受）。
 * - 会话 token 改用 **SecureRandom**（旧版用普通 Random，可预测）。
 * - 日志不再打印手机号/用户名等 PII。
 * - ⚠️ 模拟验证码固定为 `123456`（MVP 联调用），**接入真实后端前必须移除**。
 */
class AuthRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── 公开状态查询 ──────────────────────────────────────────

    /** 是否已有注册用户 */
    val hasRegisteredUser: Boolean
        get() = prefs.getString(KEY_USERNAME, null) != null

    /** 是否已登录（有有效会话） */
    val isLoggedIn: Boolean
        get() = prefs.getString(KEY_SESSION_TOKEN, null) != null

    /** 当前登录用户名（未登录返回 null） */
    val currentUsername: String?
        get() = if (isLoggedIn) prefs.getString(KEY_USERNAME, null) else null

    /** 当前用户头像 URI（本地路径） */
    val currentAvatarUri: String?
        get() = prefs.getString(KEY_AVATAR_URI, null)

    val registeredPhone: String?
        get() = prefs.getString(KEY_PHONE, null)

    val registeredEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)

    // ── 注册 ─────────────────────────────────────────────────

    /**
     * 注册新用户。
     *
     * @return [RegisterResult] 包含成功/失败及原因
     */
    fun register(
        username: String,
        password: String,
        phone: String
    ): RegisterResult {
        // 校验
        if (username.isBlank()) return RegisterResult.Error("用户名不能为空")
        if (username.length < 2) return RegisterResult.Error("用户名至少 2 个字符")
        if (password.isBlank()) return RegisterResult.Error("密码不能为空")
        if (password.length < 6) return RegisterResult.Error("密码至少 6 位")
        if (phone.isBlank()) return RegisterResult.Error("手机号不能为空")
        if (phone.length < 11) return RegisterResult.Error("请输入正确的手机号")

        // 检查是否已注册（同一手机号只能注册一次）
        if (hasRegisteredUser && registeredPhone == phone) {
            return RegisterResult.Error("该手机号已注册，请直接登录")
        }

        try {
            val salt = generateSalt()
            prefs.edit()
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD_HASH, hashPassword(password, salt))
                .putString(KEY_PASSWORD_SALT, salt)
                .putString(KEY_PHONE, phone)
                .apply()

            Log.i(TAG, "注册成功")
            return RegisterResult.Success(username)
        } catch (e: Exception) {
            Log.e(TAG, "注册失败", e)
            return RegisterResult.Error("注册失败，请重试")
        }
    }

    // ── 登录 ─────────────────────────────────────────────────

    /**
     * 登录验证。
     *
     * 支持用户名或手机号登录。
     */
    fun login(account: String, password: String): LoginResult {
        if (account.isBlank()) return LoginResult.Error("请输入账号")
        if (password.isBlank()) return LoginResult.Error("请输入密码")

        if (!hasRegisteredUser) {
            return LoginResult.Error("账号不存在，请先注册")
        }

        val savedUsername = prefs.getString(KEY_USERNAME, null) ?: return LoginResult.Error("账号数据异常")

        // 允许用用户名或手机号登录
        val savedPhone = prefs.getString(KEY_PHONE, null)
        val isAccountMatch = account == savedUsername || account == savedPhone

        if (!isAccountMatch) {
            return LoginResult.Error("账号不存在，请先注册")
        }

        val savedHash = prefs.getString(KEY_PASSWORD_HASH, null)
        val savedSalt = prefs.getString(KEY_PASSWORD_SALT, null) ?: ""
        if (savedHash == null || savedHash != hashPassword(password, savedSalt)) {
            return LoginResult.Error("密码错误")
        }

        // 生成会话 token
        val token = generateToken()
        prefs.edit()
            .putString(KEY_SESSION_TOKEN, token)
            .apply()

        Log.i(TAG, "登录成功")
        return LoginResult.Success(savedUsername, token)
    }

    // ── 登出 ─────────────────────────────────────────────────

    fun logout() {
        prefs.edit()
            .remove(KEY_SESSION_TOKEN)
            .apply()
        Log.i(TAG, "已登出")
    }

    /** 清除所有数据（用于"注销账号"） */
    fun clearAll() {
        prefs.edit().clear().apply()
        Log.i(TAG, "所有认证数据已清除")
    }

    // ── 邮箱注册 ─────────────────────────────────────────────

    fun registerWithEmail(email: String, username: String, password: String): RegisterResult {
        if (email.isBlank()) return RegisterResult.Error("邮箱不能为空")
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches())
            return RegisterResult.Error("邮箱格式不正确")
        if (username.isBlank() || username.length < 2) return RegisterResult.Error("用户名至少 2 个字符")
        if (password.length < 6) return RegisterResult.Error("密码至少 6 位")
        if (hasRegisteredUser && registeredEmail == email) return RegisterResult.Error("该邮箱已注册")
        try {
            val salt = generateSalt()
            prefs.edit()
                .putString(KEY_EMAIL, email)
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD_HASH, hashPassword(password, salt))
                .putString(KEY_PASSWORD_SALT, salt)
                .apply()
            Log.i(TAG, "邮箱注册成功")
            return RegisterResult.Success(username)
        } catch (e: Exception) {
            return RegisterResult.Error("注册失败")
        }
    }

    // ── 个人资料 ─────────────────────────────────────────────

    fun saveAvatarUri(uri: String) {
        prefs.edit().putString(KEY_AVATAR_URI, uri).apply()
    }

    fun updateBio(bio: String) {
        prefs.edit().putString(KEY_BIO, bio).apply()
    }

    val currentBio: String?
        get() = prefs.getString(KEY_BIO, null)

    // ── 辅助 ─────────────────────────────────────────────────

    /** 固定验证码（MVP 阶段） */
    fun getVerificationCode(): String = MOCK_VERIFY_CODE

    /** 验证码是否匹配 */
    fun verifyCode(input: String): Boolean = input == MOCK_VERIFY_CODE

    // ── 内部工具 ─────────────────────────────────────────────

    private val secureRandom = SecureRandom()

    private fun generateSalt(): String {
        val saltBytes = ByteArray(16)
        secureRandom.nextBytes(saltBytes)
        return saltBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * PBKDF2-HmacSHA256，20,000 迭代，输出 256bit。
     * （替代旧的 SHA-256 单轮哈希，抗离线暴破）
     */
    private fun hashPassword(password: String, salt: String): String {
        val saltBytes = salt.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = PBEKeySpec(password.toCharArray(), saltBytes, 20_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return hash.joinToString("") { "%02x".format(it) }
    }

    /** 会话 token：SecureRandom（不可预测） */
    private fun generateToken(): String {
        val tokenBytes = ByteArray(24)
        secureRandom.nextBytes(tokenBytes)
        return tokenBytes.joinToString("") { "%02x".format(it) }
    }

    // ── 结果类型 ─────────────────────────────────────────────

    sealed class RegisterResult {
        data class Success(val username: String) : RegisterResult()
        data class Error(val message: String) : RegisterResult()
    }

    sealed class LoginResult {
        data class Success(val username: String, val token: String) : LoginResult()
        data class Error(val message: String) : LoginResult()
    }

    companion object {
        private const val TAG = "AuthRepo"
        private const val PREF_NAME = "aicamera_auth"
        private const val KEY_USERNAME = "auth_username"
        private const val KEY_PASSWORD_HASH = "auth_password_hash"
        private const val KEY_PASSWORD_SALT = "auth_password_salt"
        private const val KEY_PHONE = "auth_phone"
        private const val KEY_EMAIL = "auth_email"
        private const val KEY_AVATAR_URI = "auth_avatar_uri"
        private const val KEY_BIO = "auth_bio"
        private const val KEY_SESSION_TOKEN = "auth_session_token"
        private const val MOCK_VERIFY_CODE = "123456"

        @Volatile
        private var INSTANCE: AuthRepository? = null

        fun getInstance(context: Context): AuthRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
