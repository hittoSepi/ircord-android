package fi.ircord.android.di

import android.content.ContentResolver
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fi.ircord.android.crypto.NativeStore
import fi.ircord.android.data.local.preferences.UserPreferences
import fi.ircord.android.data.remote.IrcordConnectionManager
import fi.ircord.android.data.remote.IrcordSocket
import fi.ircord.android.data.repository.ChannelMemberRepository
import fi.ircord.android.data.repository.KeyRepository
import fi.ircord.android.data.repository.MessageRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplicationContext(@ApplicationContext context: Context): Context = context

    @Provides
    @Singleton
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    @Provides
    @Singleton
    fun provideIrcordConnectionManager(
        socket: IrcordSocket,
        userPreferences: UserPreferences,
        messageRepository: MessageRepository,
        keyRepository: KeyRepository,
        channelMemberRepository: ChannelMemberRepository,
        nativeStore: NativeStore,
    ): IrcordConnectionManager =
        IrcordConnectionManager(socket, userPreferences, messageRepository, keyRepository, channelMemberRepository, nativeStore)
}
