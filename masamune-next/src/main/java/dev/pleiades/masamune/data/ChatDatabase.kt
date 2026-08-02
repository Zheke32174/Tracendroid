package dev.pleiades.masamune.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Chat persistence: Room + KSP only.
 *
 * The donor tree's chat schema was already androidx.room (its ObjectBox usage was elsewhere,
 * in the memory/vector layer, which is not carried over). So this survives the kapt ban
 * untouched and needs no annotation processor beyond `ksp(room-compiler)`.
 */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** "openai:gpt-4o-mini" — recorded so an old chat shows what actually answered it. */
    @ColumnInfo(name = "provider_model") val providerModel: String,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("chat_id")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    /** PromptTurnKind name. */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Set when the turn ended in a refusal or an upstream error, so the UI can mark it. */
    @ColumnInfo(name = "error") val error: String? = null,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Insert
    suspend fun insert(chat: ChatEntity): Long

    @Query("UPDATE chats SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, title: String, updatedAt: Long)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun byId(id: Long): ChatEntity?
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY id ASC")
    fun observeForChat(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY id ASC")
    suspend fun forChat(chatId: Long): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("UPDATE messages SET content = :content, error = :error WHERE id = :id")
    suspend fun update(id: Long, content: String, error: String?)

    @Query("DELETE FROM messages WHERE chat_id = :chatId")
    suspend fun deleteForChat(chatId: Long)
}

@Database(
    entities = [ChatEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "masamune-chat.db",
                ).build().also { instance = it }
            }
    }
}
