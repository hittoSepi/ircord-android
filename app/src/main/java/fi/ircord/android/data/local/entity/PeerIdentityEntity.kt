package fi.ircord.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peer_identities")
data class PeerIdentityEntity(
    @PrimaryKey @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "identity_pub") val identityPub: ByteArray,
    @ColumnInfo(name = "trust_status") val trustStatus: String = "unverified",
    @ColumnInfo(name = "safety_number") val safetyNumber: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerIdentityEntity) return false
        return userId == other.userId
    }

    override fun hashCode(): Int = userId.hashCode()
}
