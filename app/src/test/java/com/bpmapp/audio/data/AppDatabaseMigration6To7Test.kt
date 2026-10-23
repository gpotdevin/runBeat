// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.bpmapp.audio.di.AppModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit test for the Room migration 6 -> 7 ([AppModule.MIGRATION_6_7]), which
 * adds the `notForRunning` column to the tracks table.
 *
 * A version-6 database with the pre-migration schema is created by hand, then
 * opened with Room at version 7 applying the migration. Room validates the
 * migrated schema on open, so this also proves the migration result matches
 * the current entity schema.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigration6To7Test {

    private lateinit var context: Context

    companion object {
        private const val DB_NAME = "migration_6_7_test.db"

        // Version-6 schema: the current schema minus the notForRunning column.
        private const val CREATE_TRACKS_V6 =
            "CREATE TABLE IF NOT EXISTS `tracks` (`id` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `fileName` TEXT NOT NULL, `bpm` REAL, `fileSizeBytes` INTEGER, `durationMs` INTEGER, `lastUpdated` INTEGER NOT NULL, `source` TEXT NOT NULL, `metadataTitle` TEXT, `metadataArtist` TEXT, `metadataAlbum` TEXT, `metadataGenre` TEXT, `metadataTrackNumber` TEXT, `isFavorite` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
        private const val CREATE_PLAYLISTS_V6 =
            "CREATE TABLE IF NOT EXISTS `playlists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))"
        private const val CREATE_PLAYLIST_TRACKS_V6 =
            "CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlistId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `trackId`))"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(DB_NAME).delete()
    }

    @After
    fun tearDown() {
        context.getDatabasePath(DB_NAME).delete()
    }

    @Test
    fun migration6To7_addsNotForRunningColumn() = runBlocking {
        createV6Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(AppModule.MIGRATION_6_7)
            .build()

        try {
            val trackDao = db.trackDao()

            // The pre-existing row is readable and defaults to notForRunning = false
            val legacy = trackDao.getTrackById("legacy").first()
            assertEquals("legacy.mp3", legacy?.fileName)
            assertFalse("Existing rows must default to notForRunning = false", legacy!!.notForRunning)

            // The new column is writable through the DAO
            trackDao.updateNotForRunning("legacy", true)
            assertTrue(trackDao.getTrackById("legacy").first()!!.notForRunning)
            assertEquals(
                listOf("legacy"),
                trackDao.getNotForRunningTracks().first().map { it.id }
            )

            // The exclusion query only returns unflagged tracks
            trackDao.insertOrReplace(
                Track(
                    id = "normal",
                    relativePath = ".",
                    fileName = "normal.mp3",
                    bpm = 120.0f,
                    fileSizeBytes = 1000L,
                    durationMs = 180000L
                )
            )
            assertEquals(
                listOf("normal"),
                trackDao.getTracksExcludingNotForRunning().first().map { it.id }
            )
        } finally {
            db.close()
        }
    }

    /**
     * Create a database with the version-6 schema (no notForRunning column)
     * and one pre-existing track row.
     */
    private fun createV6Database() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_TRACKS_V6)
                        db.execSQL(CREATE_PLAYLISTS_V6)
                        db.execSQL(CREATE_PLAYLIST_TRACKS_V6)
                        db.execSQL(
                            "INSERT INTO tracks (id, relativePath, fileName, bpm, lastUpdated, source, isFavorite) " +
                                "VALUES ('legacy', '.', 'legacy.mp3', 128.0, 0, 'APP', 1)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }
}
