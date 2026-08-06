package com.thelightphone.authenticator

class TotpAccountRepository private constructor(
    database: TotpDatabase,
    private val cipher: TotpSecretCipher,
) {
    private val dao = database.accountDao()

    fun addAccount(account: Account): StoredAccount {
        val encryptedSecret = cipher.encrypt(account.secret)
        val existingId = dao.findAccountId(account.issuer, account.label)
        val id = if (existingId != null) {
            val updated = dao.update(
                TotpAccountEntity(
                    id = existingId,
                    issuer = account.issuer,
                    label = account.label,
                    digits = account.digits,
                    period = account.period,
                    algorithm = account.algorithm,
                    encryptedSecret = encryptedSecret,
                ),
            )
            if (updated == 0) error("Failed to update TOTP account")
            existingId
        } else {
            val inserted = dao.insert(
                TotpAccountEntity(
                    issuer = account.issuer,
                    label = account.label,
                    digits = account.digits,
                    period = account.period,
                    algorithm = account.algorithm,
                    encryptedSecret = encryptedSecret,
                ),
            )
            if (inserted == -1L) error("Failed to insert TOTP account")
            inserted
        }
        return StoredAccount(
            id = id,
            issuer = account.issuer,
            label = account.label,
            digits = account.digits,
            period = account.period,
            algorithm = account.algorithm,
        )
    }

    fun getAccount(id: Long): StoredAccount? = dao.getAccount(id)

    fun listAccounts(): List<StoredAccount> = dao.listAccounts()

    fun deleteAccount(id: Long): Boolean = dao.deleteAccount(id) > 0

    fun decryptSecret(id: Long): String? {
        val encrypted = dao.getEncryptedSecret(id) ?: return null
        return cipher.decrypt(encrypted)
    }

    companion object {
        const val DATABASE_NAME = "totp_accounts.db"

        @Volatile
        private var instance: TotpAccountRepository? = null

        fun getInstance(databaseProvider: () -> TotpDatabase): TotpAccountRepository {
            return instance ?: synchronized(this) {
                instance ?: TotpAccountRepository(
                    database = databaseProvider(),
                    cipher = TotpSecretCipher(),
                ).also { instance = it }
            }
        }
    }
}
