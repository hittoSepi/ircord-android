package fi.ircord.android.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fi.ircord.android.data.local.EncryptedDatabaseFactory
import fi.ircord.android.data.local.IrcordDatabase
import fi.ircord.android.data.local.dao.ChannelDao
import fi.ircord.android.data.local.dao.ChannelMemberDao
import fi.ircord.android.data.local.dao.MessageDao
import fi.ircord.android.data.local.dao.PeerIdentityDao
import fi.ircord.android.crypto.NativeStore
import net.sqlcipher.database.SQLiteDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    init {
        // Initialize SQLCipher
        System.loadLibrary("sqlcipher")
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): IrcordDatabase {
        // Create encrypted database using SQLCipher
        return EncryptedDatabaseFactory.create(
            context = context,
            klass = IrcordDatabase::class.java,
            name = "ircord.db"
        )
    }

    @Provides
    fun provideMessageDao(db: IrcordDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideChannelDao(db: IrcordDatabase): ChannelDao = db.channelDao()

    @Provides
    fun providePeerIdentityDao(db: IrcordDatabase): PeerIdentityDao = db.peerIdentityDao()

    @Provides
    fun provideChannelMemberDao(db: IrcordDatabase): ChannelMemberDao = db.channelMemberDao()

    @Provides
    @Singleton
    fun provideNativeStore(
        @ApplicationContext context: Context,
        db: IrcordDatabase
    ): NativeStore = NativeStore(context, db)
}
