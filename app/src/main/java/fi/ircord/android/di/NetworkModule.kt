package fi.ircord.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fi.ircord.android.data.remote.FrameCodec
import fi.ircord.android.data.remote.IrcordSocket
import fi.ircord.android.data.remote.ReconnectPolicy
import fi.ircord.android.security.pinning.PinRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideFrameCodec(): FrameCodec = FrameCodec()

    @Provides
    @Singleton
    fun provideReconnectPolicy(): ReconnectPolicy = ReconnectPolicy()

    @Provides
    @Singleton
    fun provideIrcordSocket(
        frameCodec: FrameCodec,
        reconnectPolicy: ReconnectPolicy,
        pinRepository: PinRepository,
    ): IrcordSocket = IrcordSocket(frameCodec, reconnectPolicy, pinRepository)
}
