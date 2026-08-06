package com.thelightphone.authenticator

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [TotpAccountEntity::class], version = 1, exportSchema = false)
abstract class TotpDatabase : RoomDatabase() {
    internal abstract fun accountDao(): TotpAccountDao
}
