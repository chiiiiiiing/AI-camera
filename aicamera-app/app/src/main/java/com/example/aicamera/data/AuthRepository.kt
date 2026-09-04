package com.example.aicamera.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.aicamera.data.api.AuthApi
import com.example.aicamera.data.db.LocalUser
import com.example.aicamera.data.db.UserDao
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 认证仓库 — 服务器优先 + 本地兜底（2026-07-31 重构）。
 *
 * ## 架构
 * ```
 * 登录/注册/验证码请求
 *   ├── 服务器在线 → server/server.js（用户数据持久化在服务器 data.json，
 *   │                换手机/重装 App 后账号仍在，同局域网均可登录）
 *   │              → 成功结果同时写入本地缓存（离线时也能登录）
 *   └── 服务器不可达 → 本地多用户存储（PBKDF2 哈希），功能完整可用
 * ```
 *
 * ## 本地存储结构（SharedPreferences `aicamera_auth`）
 * - `users_json` → { phoneOrEmail: {u,h,s,p,e,avatar,bio} }（多用户）
 * - `session_token` / `session_account` → 当前会话
 * - `codes_json` → { "scene|target": {c,exp,att} }（本地兜底验证码，5 分钟有效）
 *
 * ## 安全说明
 * - 密码 PBKDF2-HmacSHA256 / 20,000 迭代（服务器侧为 scrypt）；
 * - 验证码：服务器随机 6 位 + 5 分钟过期 + 5 次尝试上限 + 60s 重发冷却，
 *   本地兜底同策略；**固定验证码 123456 已移除**；
 * - 日志不打印手机号/邮箱等 PII。
 */
class AuthRepository private constructor(
    context: Context,
    private val userDao: UserDao
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val api = AuthApi()

    init {
        // 一次性迁移在后台执行（不阻塞首次构造）
        val handler = CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "认证仓库初始化/迁移异常: ${e.message}", e)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + handler).launch {
            runCatching {
                migrateLegacyPrefsToRoom()
                migrateLegacyAccount()
            }.onFailure { e ->
                Log.w(TAG, "旧数据迁移失败: ${e.message}", e)
            }
        }
    }

    // ── 公开状态查询 ──────────────────────────────────────────

    /** 是否已有注册用户（Room 口径） */
    val hasRegisteredUser: Boolean
        get() {
            // 此属性被 UI 线程同步读取；由于 Room 禁止主线程 IO，
            // 我们用 SharedPreferences 的 _has_users 哨兵值作为快速判断。
            // 该哨兵在每次写用户时同步更新，保证与 Room 一致。
            return prefs.getBoolean(PREF_HAS_USERS, false)
        }

    /** 是否已登录（有有效会话） */
    val isLoggedIn: Boolean
        get() = prefs.getString(KEY_SESSION_TOKEN, null) != null

    /** 首次启动 — 返回 true 表示从未完成过登录/注册流程 */
    val isFirstLaunch: Boolean
        get() = !prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)

    /** 标记首次启动已完成（进入相机页即标记，不需要登录） */
    fun markFirstLaunchDone() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
    }

    /**
     * 创建游客会话 — 首次启动跳过登录后调用。
     * 写入 session token 使 [isLoggedIn] 在后续启动返回 true，
     * 避免二次启动被强制要求注册/登录。
     */
    fun createGuestSession() {
        val token = generateToken()
        prefs.edit()
            .putString(KEY_SESSION_TOKEN, token)
            .putString(KEY_SESSION_ACCOUNT, GUEST_ACCOUNT_KEY)
            .apply()
        // 恢复游客缓存：尝试从 Room 加载，不存在则为空
        val handler = CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "游客会话加载缓存异常: ${e.message}", e)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + handler).launch {
            runCatching {
                cachedActiveUser = userDao.getByKey(GUEST_ACCOUNT_KEY)
            }.onFailure { e ->
                Log.w(TAG, "读取游客缓存失败: ${e.message}", e)
            }
        }
        Log.i(TAG, "已创建游客会话（免注册体验）")
    }

    /** 当前登录用户名（未登录返回 null） */
    val currentUsername: String?
        get() = cachedActiveUser?.username

    /** 当前用户头像 URI（本地路径） */
    val currentAvatarUri: String?
        get() = cachedActiveUser?.avatarUri?.takeIf { it.isNotBlank() }

    val currentBio: String?
        get() = cachedActiveUser?.bio?.takeIf { it.isNotBlank() }

    /** 当前账号的手机号（兼容旧调用） */
    val registeredPhone: String?
        get() = cachedActiveUser?.phone?.takeIf { it.isNotBlank() }

    /** 当前账号的邮箱（兼容旧调用） */
    val registeredEmail: String?
        get() = cachedActiveUser?.email?.takeIf { it.isNotBlank() }

    /** ★ Room 活跃用户内存缓存：登录时写入，更新时同步，避免主线程读 Room */
    @Volatile
    private var cachedActiveUser: LocalUser? = null

    // ── 验证码 ───────────────────────────────────────────────

    sealed class CodeResult {
        /** 已发送。devCode 仅在开发模式（无短信通道）下发，UI 直接展示/预填 */
        data class Sent(val devCode: String?, val expiresInSec: Int) : CodeResult()
        data class Error(val message: String) : CodeResult()
    }

    /**
     * 请求验证码（服务器优先，不可达则本地生成同策略验证码）。
     *
     * @param target 手机号或邮箱
     * @param scene "register" | "login" | "reset"
     */
    suspend fun requestCode(target: String, scene: String): CodeResult {
        if (!isValidTarget(target)) return CodeResult.Error("请输入正确的手机号或邮箱")
        return when (val r = api.sendCode(target, scene)) {
            is AuthApi.ApiOutcome.Success -> CodeResult.Sent(r.data.devCode, r.data.expiresInSec)
            is AuthApi.ApiOutcome.ApiError -> CodeResult.Error(r.message)
            is AuthApi.ApiOutcome.Unreachable -> {
                // 本地兜底：随机 6 位 + 5 分钟过期 + 60s 冷却
                val key = "$scene|$target"
                val codes = codesObject()
                val existing = codes.optJSONObject(key)
                val now = System.currentTimeMillis()
                if (existing != null && now - existing.optLong("ts", 0) < RESEND_COOLDOWN_MS) {
                    val wait = (RESEND_COOLDOWN_MS - (now - existing.optLong("ts", 0))) / 1000
                    return CodeResult.Error("发送太频繁，请 ${wait}s 后再试")
                }
                val code = String.format("%06d", secureRandom.nextInt(1_000_000))
                codes.put(key, JSONObject()
                    .put("c", code)
                    .put("exp", now + CODE_TTL_MS)
                    .put("att", 0)
                    .put("ts", now))
                saveCodes(codes)
                Log.i(TAG, "本地验证码已生成（服务器离线兜底）")
                CodeResult.Sent(devCode = code, expiresInSec = (CODE_TTL_MS / 1000).toInt())
            }
        }
    }

    /**
     * 校验验证码（注册场景：验证通过后才允许提交注册）。
     * 服务器在线走服务器校验，离线走本地兜底校验。验证成功会消费该验证码。
     */
    suspend fun verifyCode(target: String, scene: String, code: String): Boolean {
        if (code.length != 6) return false
        return when (api.verifyCode(target, scene, code)) {
            is AuthApi.ApiOutcome.Success -> true
            is AuthApi.ApiOutcome.ApiError -> false
            is AuthApi.ApiOutcome.Unreachable -> verifyLocalCode(target, scene, code)
        }
    }

    /** 校验本地兜底验证码（服务器验证码由服务器在校验接口/登录接口内验证） */
    private fun verifyLocalCode(target: String, scene: String, input: String): Boolean {
        val key = "$scene|$target"
        val codes = codesObject()
        val entry = codes.optJSONObject(key) ?: return false
        val now = System.currentTimeMillis()
        if (now > entry.optLong("exp", 0)) return false
        val attempts = entry.optInt("att", 0)
        if (attempts >= CODE_MAX_ATTEMPTS) return false
        val ok = entry.optString("c") == input
        entry.put("att", attempts + 1)
        if (ok) codes.remove(key) else codes.put(key, entry)
        saveCodes(codes)
        return ok
    }

    // ── 注册 ─────────────────────────────────────────────────

    /**
     * 手机号注册（需先通过 [requestCode] + 验证场景码）。
     */
    suspend fun register(username: String, password: String, phone: String): RegisterResult {
        validateCredentials(username, password)?.let { return RegisterResult.Error(it) }
        if (!PHONE_REGEX.matches(phone)) return RegisterResult.Error("请输入正确的手机号")

        return when (val r = api.register(username, password, phone = phone, email = null)) {
            is AuthApi.ApiOutcome.Success -> {
                upsertLocalUser(phone, username, password, phone = phone, email = "")
                Log.i(TAG, "注册成功（服务器）")
                RegisterResult.Success(username)
            }
            is AuthApi.ApiOutcome.ApiError -> RegisterResult.Error(r.message)
            is AuthApi.ApiOutcome.Unreachable -> registerLocal(username, password, phone = phone, email = "")
        }
    }

    /** 邮箱注册 */
    suspend fun registerWithEmail(email: String, username: String, password: String): RegisterResult {
        validateCredentials(username, password)?.let { return RegisterResult.Error(it) }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches())
            return RegisterResult.Error("邮箱格式不正确")

        return when (val r = api.register(username, password, phone = null, email = email)) {
            is AuthApi.ApiOutcome.Success -> {
                upsertLocalUser(email, username, password, phone = "", email = email)
                Log.i(TAG, "邮箱注册成功（服务器）")
                RegisterResult.Success(username)
            }
            is AuthApi.ApiOutcome.ApiError -> RegisterResult.Error(r.message)
            is AuthApi.ApiOutcome.Unreachable -> registerLocal(username, password, phone = "", email = email)
        }
    }

    private suspend fun registerLocal(
        username: String, password: String, phone: String, email: String
    ): RegisterResult {
        val key = phone.ifBlank { email }
        if (userDao.getByKey(key) != null)
            return RegisterResult.Error("该${if (phone.isNotBlank()) "手机号" else "邮箱"}已注册，请直接登录")
        if (userDao.getByUsername(username) != null)
            return RegisterResult.Error("用户名已被占用")
        upsertLocalUser(key, username, password, phone, email)
        Log.i(TAG, "注册成功（本地）")
        return RegisterResult.Success(username)
    }

    // ── 登录 ─────────────────────────────────────────────────

    /** 密码登录：账号可以是用户名 / 手机号 / 邮箱 */
    suspend fun login(account: String, password: String): LoginResult {
        if (account.isBlank()) return LoginResult.Error("请输入账号")
        if (password.isBlank()) return LoginResult.Error("请输入密码")

        return when (val r = api.login(account, password)) {
            is AuthApi.ApiOutcome.Success -> {
                cacheServerLogin(r.data, password)
                Log.i(TAG, "登录成功（服务器）")
                LoginResult.Success(r.data.user.username, r.data.token)
            }
            is AuthApi.ApiOutcome.ApiError -> LoginResult.Error(r.message)
            is AuthApi.ApiOutcome.Unreachable -> loginLocal(account, password)
        }
    }

    /** 验证码免密登录（未注册账号自动开户） */
    suspend fun loginWithCode(target: String, code: String): LoginResult {
        if (!isValidTarget(target)) return LoginResult.Error("请输入正确的手机号或邮箱")
        if (code.length != 6) return LoginResult.Error("请输入 6 位验证码")

        return when (val r = api.loginWithCode(target, code)) {
            is AuthApi.ApiOutcome.Success -> {
                cacheServerLogin(r.data, password = null)
                Log.i(TAG, "验证码登录成功（服务器，新用户=${r.data.isNewUser}）")
                LoginResult.Success(r.data.user.username, r.data.token)
            }
            is AuthApi.ApiOutcome.ApiError -> LoginResult.Error(r.message)
            is AuthApi.ApiOutcome.Unreachable -> {
                if (!verifyLocalCode(target, "login", code)) {
                    return LoginResult.Error("验证码错误或已过期")
                }
                val key = target
                val existing = userDao.getByKey(key)
                if (existing == null) {
                    // 本地自动开户
                    val autoName = "用户" + target.takeLast(4)
                    upsertLocalUser(key, autoName, password = null,
                        phone = if (PHONE_REGEX.matches(target)) target else "",
                        email = if (PHONE_REGEX.matches(target)) "" else target)
                }
                val token = startSession(key)
                val username = userDao.getByKey(key)?.username ?: "用户" + target.takeLast(4)
                Log.i(TAG, "验证码登录成功（本地）")
                LoginResult.Success(username, token)
            }
        }
    }

    private suspend fun loginLocal(account: String, password: String): LoginResult {
        if (userDao.count() == 0) return LoginResult.Error("账号不存在，请先注册")

        // 按 username / phone / email 查找匹配用户
        val matched = userDao.getByUsername(account)
            ?: userDao.getByKey(account)  // account 本身就是 phone 或 email
        val key = matched?.accountKey ?: return LoginResult.Error("账号不存在，请先注册")
        val savedHash = matched.passwordHash
        if (savedHash.isNullOrBlank()) return LoginResult.Error("该账号使用验证码登录，未设置密码")
        if (savedHash != hashPassword(password, matched.passwordSalt ?: "")) {
            return LoginResult.Error("密码错误")
        }
        val token = startSession(key)
        cachedActiveUser = matched
        Log.i(TAG, "登录成功（本地）")
        return LoginResult.Success(matched.username, token)
    }

    // ── 找回密码 ─────────────────────────────────────────────

    suspend fun resetPassword(target: String, code: String, newPassword: String): ResetResult {
        if (!isValidTarget(target)) return ResetResult.Error("请输入正确的手机号或邮箱")
        if (newPassword.length < 6) return ResetResult.Error("密码至少 6 位")

        return when (val r = api.resetPassword(target, code, newPassword)) {
            is AuthApi.ApiOutcome.Success -> {
                // 同步更新本地缓存的哈希
                userDao.getByKey(target)?.let { u ->
                    val salt = generateSalt()
                    userDao.upsert(u.copy(
                        passwordHash = hashPassword(newPassword, salt),
                        passwordSalt = salt
                    ))
                }
                ResetResult.Success
            }
            is AuthApi.ApiOutcome.ApiError -> ResetResult.Error(r.message)
            is AuthApi.ApiOutcome.Unreachable -> {
                if (!verifyLocalCode(target, "reset", code)) {
                    return ResetResult.Error("验证码错误或已过期")
                }
                val u = userDao.getByKey(target)
                    ?: return ResetResult.Error("账号不存在，请先注册")
                val salt = generateSalt()
                userDao.upsert(u.copy(
                    passwordHash = hashPassword(newPassword, salt),
                    passwordSalt = salt
                ))
                ResetResult.Success
            }
        }
    }

    // ── 登出 / 资料 ──────────────────────────────────────────

    fun logout() {
        prefs.edit()
            .remove(KEY_SESSION_TOKEN)
            .remove(KEY_SESSION_ACCOUNT)
            .apply()
        Log.i(TAG, "已登出")
    }

    /** 清除所有数据（用于"注销账号"） */
    fun clearAll() {
        prefs.edit().clear().apply()
        Log.i(TAG, "所有认证数据已清除")
    }

    fun saveAvatarUri(uri: String) {
        runInScope { updateActiveUser { it.copy(avatarUri = uri) } }
    }

    fun updateBio(bio: String) {
        runInScope { updateActiveUser { it.copy(bio = bio) } }
    }

    fun updateUsername(username: String) {
        runInScope { updateActiveUser { it.copy(username = username) } }
    }

    /** 在 IO 线程安全执行 Room 写操作（供同步 UI 回调使用） */
    private fun runInScope(block: suspend () -> Unit) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { block() } catch (e: Exception) {
                Log.w(TAG, "Room 操作异常: ${e.message}")
            }
        }
    }

    // ── 内部：Room 用户存储（替代旧 SharedPreferences JSON） ──

    /**
     * 一次性迁移：旧 SharedPreferences `users_json` → Room。
     * 成功迁移后删除旧键，后续走 Room。
     */
    private suspend fun migrateLegacyPrefsToRoom() {
        val oldJson = prefs.getString(KEY_USERS, null) ?: return
        if (oldJson == "{}" || oldJson.isBlank()) return
        val already = prefs.getBoolean(MIGRATED_TO_ROOM, false)
        if (already) return

        runCatching { JSONObject(oldJson) }
            .onSuccess { users ->
                var count = 0
                for (key in users.keys()) {
                    val u = users.getJSONObject(key)
                    val user = LocalUser(
                        accountKey = key,
                        username = u.optString("u", ""),
                        passwordHash = u.optString("h", "").ifBlank { null },
                        passwordSalt = u.optString("s", "").ifBlank { null },
                        phone = u.optString("p", ""),
                        email = u.optString("e", ""),
                        avatarUri = u.optString("avatar", ""),
                        bio = u.optString("bio", ""),
                        createdAt = System.currentTimeMillis()
                    )
                    userDao.upsert(user)
                    count++
                }
                prefs.edit()
                    .putBoolean(MIGRATED_TO_ROOM, true)
                    .putBoolean(PREF_HAS_USERS, count > 0)
                    .apply()
                Log.i(TAG, "已迁移 $count 个用户到 Room")
            }
            .onFailure { e ->
                Log.w(TAG, "旧用户数据迁移失败: ${e.message}")
            }
    }

    private fun setHasUsersFlag(hasUsers: Boolean = true) {
        prefs.edit().putBoolean(PREF_HAS_USERS, hasUsers).apply()
    }

    private fun activeUser(): LocalUser? = cachedActiveUser

    private fun codesObject(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_CODES, "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun saveCodes(codes: JSONObject) {
        prefs.edit().putString(KEY_CODES, codes.toString()).apply()
    }

    private suspend fun updateActiveUser(mutate: (LocalUser) -> LocalUser) {
        val account = prefs.getString(KEY_SESSION_ACCOUNT, null) ?: return
        val existing = userDao.getByKey(account) ?: return
        val updated = mutate(existing)
        userDao.upsert(updated)
        cachedActiveUser = updated
    }

    /** 写入/更新本地用户记录（密码非空时刷新哈希） */
    private suspend fun upsertLocalUser(
        key: String, username: String, password: String?, phone: String, email: String
    ) {
        val existing = userDao.getByKey(key)
        val (hash, salt) = if (password != null) {
            val s = generateSalt()
            hashPassword(password, s) to s
        } else {
            (existing?.passwordHash to existing?.passwordSalt)
        }
        userDao.upsert(
            LocalUser(
                accountKey = key,
                username = username,
                passwordHash = hash,
                passwordSalt = salt,
                phone = phone,
                email = email,
                avatarUri = existing?.avatarUri ?: "",
                bio = existing?.bio ?: "",
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )
        )
        setHasUsersFlag(true)
    }

    /** 服务器登录成功 → 缓存到本地（含密码哈希，供离线回退登录） */
    private suspend fun cacheServerLogin(data: AuthApi.LoginPayload, password: String?) {
        val key = data.user.phone.ifBlank { data.user.email }
        if (key.isNotBlank()) {
            upsertLocalUser(key, data.user.username, password, data.user.phone, data.user.email)
        }
        prefs.edit()
            .putString(KEY_SESSION_TOKEN, data.token)
            .putString(KEY_SESSION_ACCOUNT, key)
            .apply()
    }

    private suspend fun startSession(accountKey: String): String {
        val token = generateToken()
        prefs.edit()
            .putString(KEY_SESSION_TOKEN, token)
            .putString(KEY_SESSION_ACCOUNT, accountKey)
            .apply()
        // 加载用户到内存缓存
        cachedActiveUser = userDao.getByKey(accountKey)
        return token
    }

    private fun validateCredentials(username: String, password: String): String? = when {
        username.isBlank() -> "用户名不能为空"
        username.length < 2 -> "用户名至少 2 个字符"
        username.length > 20 -> "用户名最多 20 个字符"
        password.isBlank() -> "密码不能为空"
        password.length < 6 -> "密码至少 6 位"
        else -> null
    }

    private fun isValidTarget(target: String): Boolean =
        PHONE_REGEX.matches(target) || android.util.Patterns.EMAIL_ADDRESS.matcher(target).matches()

    // ── 旧单账号存储迁移（一次性） ───────────────────────────

    private suspend fun migrateLegacyAccount() {
        val legacyName = prefs.getString(LEGACY_USERNAME, null) ?: return
        val legacyHash = prefs.getString(LEGACY_PASSWORD_HASH, null) ?: return
        val legacySalt = prefs.getString(LEGACY_PASSWORD_SALT, null) ?: ""
        val legacyPhone = prefs.getString(LEGACY_PHONE, null) ?: ""
        val legacyEmail = prefs.getString(LEGACY_EMAIL, null) ?: ""
        val key = legacyPhone.ifBlank { legacyEmail }.ifBlank { return }
        val existing = userDao.getByKey(key)
        if (existing == null) {
            userDao.upsert(
                LocalUser(
                    accountKey = key,
                    username = legacyName,
                    passwordHash = legacyHash,
                    passwordSalt = legacySalt,
                    phone = legacyPhone,
                    email = legacyEmail,
                    avatarUri = prefs.getString(LEGACY_AVATAR, "") ?: "",
                    bio = prefs.getString(LEGACY_BIO, "") ?: ""
                )
            )
            setHasUsersFlag(true)
        }
        prefs.edit()
            .remove(LEGACY_USERNAME).remove(LEGACY_PASSWORD_HASH).remove(LEGACY_PASSWORD_SALT)
            .remove(LEGACY_PHONE).remove(LEGACY_EMAIL)
            .remove(LEGACY_AVATAR).remove(LEGACY_BIO)
            .apply()
        Log.i(TAG, "旧版单账号数据已迁移到 Room")
    }

    // ── 内部：加密工具 ───────────────────────────────────────

    private val secureRandom = SecureRandom()

    private fun generateSalt(): String {
        val saltBytes = ByteArray(16)
        secureRandom.nextBytes(saltBytes)
        return saltBytes.joinToString("") { "%02x".format(it) }
    }

    /** PBKDF2-HmacSHA256，20,000 迭代，输出 256bit */
    private fun hashPassword(password: String, salt: String): String {
        val saltBytes = salt.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = PBEKeySpec(password.toCharArray(), saltBytes, 20_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return hash.joinToString("") { "%02x".format(it) }
    }

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

    sealed class ResetResult {
        data object Success : ResetResult()
        data class Error(val message: String) : ResetResult()
    }

    companion object {
        private const val TAG = "AuthRepo"
        private const val PREF_NAME = "aicamera_auth"
        private const val KEY_USERS = "users_json"          // 旧 JSON 存储（迁移后删除）
        private const val KEY_CODES = "codes_json"
        private const val KEY_SESSION_TOKEN = "auth_session_token"
        private const val KEY_SESSION_ACCOUNT = "auth_session_account"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
        private const val PREF_HAS_USERS = "_has_users"     // 哨兵：Room 中有用户
        private const val MIGRATED_TO_ROOM = "_migrated_to_room"

        // 旧版单账号键（迁移用）
        private const val LEGACY_USERNAME = "auth_username"
        private const val LEGACY_PASSWORD_HASH = "auth_password_hash"
        private const val LEGACY_PASSWORD_SALT = "auth_password_salt"
        private const val LEGACY_PHONE = "auth_phone"
        private const val LEGACY_EMAIL = "auth_email"
        private const val LEGACY_AVATAR = "auth_avatar_uri"
        private const val LEGACY_BIO = "auth_bio"

        private const val GUEST_ACCOUNT_KEY = "__guest__"
        private val PHONE_REGEX = Regex("^1\\d{10}$")
        private const val CODE_TTL_MS = 5 * 60 * 1000L
        private const val RESEND_COOLDOWN_MS = 60 * 1000L
        private const val CODE_MAX_ATTEMPTS = 5

        @Volatile
        private var INSTANCE: AuthRepository? = null

        /**
         * ★ 通过 Hilt EntryPointAccessors 自动解析 [UserDao]，
         * 无需调用方传入数据库依赖。
         */
        @EntryPoint
        @InstallIn(SingletonComponent::class)
        interface AuthRepoEntryPoint {
            fun userDao(): UserDao
        }

        fun getInstance(context: Context): AuthRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val app = context.applicationContext
                    val userDao = EntryPoints.get(app, AuthRepoEntryPoint::class.java).userDao()
                    AuthRepository(app, userDao).also { INSTANCE = it }
                }
            }
        }
    }
}
