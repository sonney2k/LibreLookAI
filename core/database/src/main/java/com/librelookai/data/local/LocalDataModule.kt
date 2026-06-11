package com.librelookai.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** v2: outfits cache tables (`styles_cache_*.json` successor). */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `outfits` " +
                "(`id` TEXT NOT NULL, `folderId` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_outfits_folderId` ON `outfits` (`folderId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `outfit_folders` " +
                "(`folderId` TEXT NOT NULL, PRIMARY KEY(`folderId`))",
        )
    }
}

/** v3: outfit-events cache tables (`outfit_events_cache_*.json` successor). */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `outfit_events` " +
                "(`id` TEXT NOT NULL, `folderId` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_outfit_events_folderId` ON `outfit_events` (`folderId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `outfit_event_folders` " +
                "(`folderId` TEXT NOT NULL, PRIMARY KEY(`folderId`))",
        )
    }
}

/** v4: trips cache tables (`trips_cache.json` successor). */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trips` " +
                "(`id` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trip_markers` " +
                "(`key` TEXT NOT NULL, PRIMARY KEY(`key`))",
        )
    }
}

/** v5: try-ons cache tables (`tryons_cache.json` successor). */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tryons` " +
                "(`id` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tryon_markers` " +
                "(`key` TEXT NOT NULL, PRIMARY KEY(`key`))",
        )
    }
}

/** v6: pending-mutation queue (refactor § 2 — Drive-bound writes drained by the SyncEngine). */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_mutations` " +
                "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                "`targetId` TEXT NOT NULL, `folderId` TEXT, `payload` TEXT NOT NULL, " +
                "`rollback` TEXT, `attempts` INTEGER NOT NULL, `lastError` TEXT, " +
                "`createdMs` INTEGER NOT NULL)",
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LocalDatabaseModule {

    @Provides
    @Singleton
    fun provideLocalDatabase(@ApplicationContext context: Context): LocalDatabase =
        Room.databaseBuilder(context, LocalDatabase::class.java, "librelookai.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()

    @Provides
    fun provideWardrobeItemDao(db: LocalDatabase): WardrobeItemDao = db.wardrobeItemDao()

    @Provides
    fun provideOutfitDao(db: LocalDatabase): OutfitDao = db.outfitDao()

    @Provides
    fun provideOutfitEventDao(db: LocalDatabase): OutfitEventDao = db.outfitEventDao()

    @Provides
    fun provideTripDao(db: LocalDatabase): TripDao = db.tripDao()

    @Provides
    fun provideTryOnDao(db: LocalDatabase): TryOnDao = db.tryOnDao()

    @Provides
    fun providePendingMutationDao(db: LocalDatabase): PendingMutationDao = db.pendingMutationDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalStoreModule {

    @Binds
    abstract fun bindWardrobeItemStore(impl: RoomWardrobeItemStore): WardrobeItemStore

    @Binds
    abstract fun bindOutfitStore(impl: RoomOutfitStore): OutfitStore

    @Binds
    abstract fun bindOutfitEventStore(impl: RoomOutfitEventStore): OutfitEventStore

    @Binds
    abstract fun bindTripStore(impl: RoomTripStore): TripStore

    @Binds
    abstract fun bindTryOnStore(impl: RoomTryOnStore): TryOnStore

    @Binds
    abstract fun bindPendingMutationStore(impl: RoomPendingMutationStore): PendingMutationStore
}
