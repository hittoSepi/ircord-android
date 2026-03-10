package fi.ircord.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fi.ircord.android.data.local.entity.PeerIdentityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerIdentityDao {

    @Query("SELECT * FROM peer_identities WHERE user_id = :userId")
    suspend fun getByUserId(userId: String): PeerIdentityEntity?

    @Query("SELECT * FROM peer_identities")
    fun getAll(): Flow<List<PeerIdentityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identity: PeerIdentityEntity)

    @Query("UPDATE peer_identities SET trust_status = :status WHERE user_id = :userId")
    suspend fun updateTrust(userId: String, status: String)
}
