package com.bpmapp.audio.di

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bpmapp.audio.audio.CadenceMatcher
import com.bpmapp.audio.audio.MetadataEditor
import com.bpmapp.audio.audio.ModeBeepPlayer
import com.bpmapp.audio.audio.PlayerRepository
import com.bpmapp.audio.audio.SoundTouchManager
import com.bpmapp.audio.audio.TempoStretcher
import com.bpmapp.audio.audio.TrackRepository
import com.bpmapp.audio.data.AppDatabase
import com.bpmapp.audio.data.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt Module for Application-level dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideExoPlayer(@ApplicationContext context: Context): ExoPlayer {
        return ExoPlayer.Builder(context).build()
    }
    
    @Provides
    @Singleton
    fun providePlayerRepository(
        @ApplicationContext context: Context, 
        exoPlayer: ExoPlayer,
        tempoStretcher: TempoStretcher,
        modeBeepPlayer: ModeBeepPlayer
    ): PlayerRepository {
        return PlayerRepository(context, exoPlayer, tempoStretcher, modeBeepPlayer)
    }
    
    // TempoStretcher with SoundTouch integration
    @Provides
    @Singleton
    fun provideSoundTouchManager(): SoundTouchManager {
        return SoundTouchManager()
    }
    
    @Provides
    @Singleton
    fun provideTempoStretcher(exoPlayer: ExoPlayer): TempoStretcher {
        return TempoStretcher(exoPlayer, useSoundTouch = false)
    }
    
    @Provides
    @Singleton
    fun provideCadenceMatcher(): CadenceMatcher {
        return CadenceMatcher()
    }
    
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN metadataTrackNumber TEXT")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
        .addMigrations(MIGRATION_5_6)
        .fallbackToDestructiveMigration()
        .build()
    }
    
    @Provides
    @Singleton
    fun provideTrackDao(appDatabase: AppDatabase): TrackDao {
        return appDatabase.trackDao()
    }
    
    @Provides
    @Singleton
    fun provideTrackRepository(
        trackDao: TrackDao,
        @ApplicationContext context: Context
    ): TrackRepository {
        return TrackRepository(trackDao, context)
    }

    @Provides
    fun provideMetadataEditor(@ApplicationContext context: Context): MetadataEditor {
        return MetadataEditor(context)
    }
    
    // Add more provides methods as needed
}
