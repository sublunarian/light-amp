package com.thelightphone.authenticator

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "totp_accounts")
internal data class TotpAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val issuer: String,
    val label: String,
    val digits: Int,
    val period: Int,
    val algorithm: String,
    @ColumnInfo(name = "encrypted_secret") val encryptedSecret: ByteArray,
)
