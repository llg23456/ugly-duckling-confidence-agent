package com.testconnection.confidence_agent.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val serverId: Long,
    val role: String,
    val modality: String,
    val content: String,
    val mediaRef: String?,
    val localImagePath: String?,
    val createdAt: String,
    val isMock: Boolean,
)

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY serverId ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY serverId ASC")
    suspend fun all(): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages")
    suspend fun clear()
}

@Database(entities = [ChatMessageEntity::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun messages(): ChatMessageDao

    companion object {
        @Volatile private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                "duck_chat.db",
            ).build().also { instance = it }
        }
    }
}
