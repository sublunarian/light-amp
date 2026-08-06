package com.thelightphone.sdk

import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * SDK PATCH (additive, upstreamable): added `fallbackToDestructiveMigration`.
 *
 * A tool can only reach Room through this helper (android.content.Context is a
 * blocked import in tool code), so it has no way to register migrations or a
 * fallback of its own. Without one, shipping any schema change crashes every
 * existing install on launch with "Migration didn't properly handle...".
 * Recreating the tables is the right default here because a tool's Room database
 * is a rebuildable cache of server state, not the system of record.
 */
fun <T : RoomDatabase> SealedLightContext.buildDatabase(dbClass: Class<T>, dbName: String?): T {
    return Room.databaseBuilder(androidContext.applicationContext, dbClass, dbName)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}