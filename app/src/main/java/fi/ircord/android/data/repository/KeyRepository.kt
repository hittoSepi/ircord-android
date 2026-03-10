package fi.ircord.android.data.repository

import fi.ircord.android.data.local.dao.PeerIdentityDao
import fi.ircord.android.data.local.entity.PeerIdentityEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyRepository @Inject constructor(
    private val peerIdentityDao: PeerIdentityDao,
) {
    suspend fun getPeerIdentity(userId: String): PeerIdentityEntity? =
        peerIdentityDao.getByUserId(userId)

    fun getAllPeers(): Flow<List<PeerIdentityEntity>> = peerIdentityDao.getAll()

    suspend fun insertPeer(identity: PeerIdentityEntity) = peerIdentityDao.insert(identity)

    suspend fun markVerified(userId: String) = peerIdentityDao.updateTrust(userId, "verified")
}
