package fi.ircord.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import fi.ircord.android.data.local.dao.ChannelDao
import fi.ircord.android.data.local.dao.MessageDao
import fi.ircord.android.data.local.dao.PeerIdentityDao
import fi.ircord.android.data.local.entity.ChannelEntity
import fi.ircord.android.data.local.entity.MessageEntity
import fi.ircord.android.data.local.entity.PeerIdentityEntity

@Database(
    entities = [
        MessageEntity::class,
        ChannelEntity::class,
        PeerIdentityEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class IrcordDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun channelDao(): ChannelDao
    abstract fun peerIdentityDao(): PeerIdentityDao
}
