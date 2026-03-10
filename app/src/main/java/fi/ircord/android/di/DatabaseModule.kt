package fi.ircord.android.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fi.ircord.android.data.local.IrcordDatabase
import fi.ircord.android.data.local.dao.ChannelDao
import fi.ircord.android.data.local.dao.MessageDao
import fi.ircord.android.data.local.dao.PeerIdentityDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): IrcordDatabase {
        return Room.databaseBuilder(
            context,
            IrcordDatabase::class.java,
            "ircord.db"
        ).build()
    }

    @Provides
    fun provideMessageDao(db: IrcordDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideChannelDao(db: IrcordDatabase): ChannelDao = db.channelDao()

    @Provides
    fun providePeerIdentityDao(db: IrcordDatabase): PeerIdentityDao = db.peerIdentityDao()
}
