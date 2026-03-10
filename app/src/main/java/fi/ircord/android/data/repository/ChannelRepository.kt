package fi.ircord.android.data.repository

import fi.ircord.android.data.local.dao.ChannelDao
import fi.ircord.android.data.local.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepository @Inject constructor(
    private val channelDao: ChannelDao,
) {
    fun getAllChannels(): Flow<List<ChannelEntity>> = channelDao.getAllChannels()

    suspend fun insert(channel: ChannelEntity) = channelDao.insert(channel)

    suspend fun updateLastRead(channelId: String, ts: Long) =
        channelDao.updateLastRead(channelId, ts)
}
