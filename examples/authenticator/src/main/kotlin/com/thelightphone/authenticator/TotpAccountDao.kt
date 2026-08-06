package com.thelightphone.authenticator

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
internal interface TotpAccountDao {
    @Query(
        "SELECT id FROM totp_accounts " +
            "WHERE issuer = :issuer COLLATE NOCASE AND label = :label COLLATE NOCASE LIMIT 1"
    )
    fun findAccountId(issuer: String, label: String): Long?

    @Insert
    fun insert(account: TotpAccountEntity): Long

    @Update
    fun update(account: TotpAccountEntity): Int

    @Query("SELECT id, issuer, label, digits, period, algorithm FROM totp_accounts WHERE id = :id")
    fun getAccount(id: Long): StoredAccount?

    @Query(
        "SELECT id, issuer, label, digits, period, algorithm FROM totp_accounts " +
            "ORDER BY issuer COLLATE NOCASE, label COLLATE NOCASE"
    )
    fun listAccounts(): List<StoredAccount>

    @Query("DELETE FROM totp_accounts WHERE id = :id")
    fun deleteAccount(id: Long): Int

    @Query("SELECT encrypted_secret FROM totp_accounts WHERE id = :id")
    fun getEncryptedSecret(id: Long): ByteArray?
}
