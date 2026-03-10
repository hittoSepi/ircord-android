package fi.ircord.android.data.repository

import fi.ircord.android.data.local.dao.MessageDao
import fi.ircord.android.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
) {
    fun getMessages(channelId: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesForChannel(channelId)

    suspend fun insert(message: MessageEntity): Long =
        messageDao.insert(message)

    suspend fun updateSendStatus(id: Long, status: String) =
        messageDao.updateSendStatus(id, status)
}
