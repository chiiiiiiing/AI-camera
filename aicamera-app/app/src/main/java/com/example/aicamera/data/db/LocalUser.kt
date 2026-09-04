package com.example.aicamera.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 用户实体 — 替代旧 SharedPreferences 手拼 JSON 的本地用户存储。
 *
 * 每个用户由 [accountKey]（手机号或邮箱）唯一标识。
 * 密码字段仅在本机离线模式或服务器缓存场景下存在；纯服务器用户可能无本地哈希。
 */
@Entity(tableName = "users")
data class LocalUser(
    /** 账号标识：手机号（"13800138000"）或邮箱（"a@b.com"） */
    @PrimaryKey
    @ColumnInfo(name = "account_key")
    val accountKey: String,

    @ColumnInfo(name = "username")
    val username: String,

    /** PBKDF2-HmacSHA256 哈希（hex）；验证码登录用户此字段为 null */
    @ColumnInfo(name = "password_hash")
    val passwordHash: String?,

    /** 密码盐（hex） */
    @ColumnInfo(name = "password_salt")
    val passwordSalt: String?,

    @ColumnInfo(name = "phone")
    val phone: String,

    @ColumnInfo(name = "email")
    val email: String,

    /** 头像 URI（本地路径或空字符串） */
    @ColumnInfo(name = "avatar_uri")
    val avatarUri: String,

    /** 个人简介 */
    @ColumnInfo(name = "bio")
    val bio: String,

    /** 创建时间（epoch millis） */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
