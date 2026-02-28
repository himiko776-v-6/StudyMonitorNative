// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
// Modified for StudyMonitor 2026
// Licensed under the Apache License, Version 2.0

package com.example.studymonitor.studymonitor.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 学习会话记录
 */
@Entity(tableName = "study_sessions")
data class StudySession(
    @PrimaryKey val id: String,
    val startTime: Long,
    val endTime: Long,
    val distractionCount: Int,
    val breakCount: Int,
    val totalMinutes: Int,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 走神记录
 */
@Entity(tableName = "distractions")
data class DistractionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timestamp: Long,
    val confidence: Float,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 休息记录
 */
@Entity(tableName = "breaks")
data class BreakRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timestamp: Long,
    val type: String = "eye_rest",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 每日统计汇总
 */
@Entity(tableName = "daily_stats", primaryKeys = ["date", "userId"])
data class DailyStats(
    val date: String, // yyyy-MM-dd
    val userId: String = "default",
    val totalStudyMinutes: Int,
    val totalDistractionCount: Int,
    val totalBreakCount: Int,
    val sessionCount: Int,
    val goalMinutes: Int = 120,
    val updatedAt: Long = System.currentTimeMillis()
)

// ========== DAO ==========

@Dao
interface StudySessionDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: StudySession)
    
    @Query("SELECT * FROM study_sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: String): StudySession?
    
    @Query("SELECT * FROM study_sessions WHERE startTime BETWEEN :startTime AND :endTime ORDER BY startTime DESC")
    suspend fun getByTimeRange(startTime: Long, endTime: Long): List<StudySession>
    
    @Query("SELECT * FROM study_sessions ORDER BY startTime DESC LIMIT :limit")
    fun getRecent(limit: Int = 10): Flow<List<StudySession>>
    
    @Query("DELETE FROM study_sessions WHERE createdAt < :beforeTime")
    suspend fun deleteOldSessions(beforeTime: Long): Int
}

@Dao
interface DistractionDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DistractionRecord)
    
    @Query("SELECT COUNT(*) FROM distractions WHERE sessionId = :sessionId")
    suspend fun getCountBySession(sessionId: String): Int
    
    @Query("SELECT COUNT(*) FROM distractions WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun getCountByTimeRange(startTime: Long, endTime: Long): Int
    
    @Query("SELECT * FROM distractions WHERE sessionId = :sessionId ORDER BY timestamp")
    suspend fun getBySession(sessionId: String): List<DistractionRecord>
    
    @Query("DELETE FROM distractions WHERE createdAt < :beforeTime")
    suspend fun deleteOldRecords(beforeTime: Long): Int
}

@Dao
interface BreakDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: BreakRecord)
    
    @Query("SELECT COUNT(*) FROM breaks WHERE sessionId = :sessionId")
    suspend fun getCountBySession(sessionId: String): Int
    
    @Query("SELECT * FROM breaks WHERE sessionId = :sessionId ORDER BY timestamp")
    suspend fun getBySession(sessionId: String): List<BreakRecord>
}

@Dao
interface DailyStatsDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stats: DailyStats)
    
    @Query("SELECT * FROM daily_stats WHERE date = :date AND userId = :userId")
    suspend fun getByDate(date: String, userId: String = "default"): DailyStats?
    
    @Query("SELECT * FROM daily_stats WHERE date BETWEEN :startDate AND :endDate ORDER BY date")
    suspend fun getByDateRange(startDate: String, endDate: String): List<DailyStats>
    
    @Query("SELECT * FROM daily_stats ORDER BY date DESC LIMIT :limit")
    fun getRecent(limit: Int = 30): Flow<List<DailyStats>>
}

// ========== Database ==========

@Database(
    entities = [
        StudySession::class,
        DistractionRecord::class,
        BreakRecord::class,
        DailyStats::class
    ],
    version = 1,
    exportSchema = false
)
abstract class StudyDatabase : RoomDatabase() {
    abstract fun sessionDao(): StudySessionDao
    abstract fun distractionDao(): DistractionDao
    abstract fun breakDao(): BreakDao
    abstract fun dailyStatsDao(): DailyStatsDao
    
    companion object {
        @Volatile
        private var instance: StudyDatabase? = null
        
        fun getInstance(context: Context): StudyDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    StudyDatabase::class.java,
                    "study_monitor_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}