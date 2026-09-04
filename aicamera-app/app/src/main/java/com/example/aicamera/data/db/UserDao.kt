package com.example.aicamera.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * 用户 DAO — 全部 suspend，由 Room 自动调度到后台线程。
 */
@Dao
interface UserDao {

    /** 按账号查找（手机号或邮箱） */
    @Query("SELECT * FROM users WHERE account_key = :key")
    suspend fun getByKey(key: String): LocalUser?

    /** 按用户名查找 */
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun getByUsername(username: String): LocalUser?

    /** 插入或更新 */
    @Upsert
    suspend fun upsert(user: LocalUser)

    /** 删除指定用户 */
    @Query("DELETE FROM users WHERE account_key = :key")
    suspend fun delete(key: String)

    /** 清空全部用户 */
    @Query("DELETE FROM users")
    suspend fun deleteAll()

    /** 用户总数 */
    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int
}
