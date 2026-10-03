package com.gstoreshift.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Query("SELECT * FROM media_items WHERE state = :state ORDER BY dateTakenMillis")
    suspend fun byState(state: ItemState): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE state IN (:states)")
    fun observeByState(states: List<ItemState>): Flow<List<MediaItemEntity>>

    @Query("UPDATE media_items SET state = :state WHERE mediaStoreId = :id")
    suspend fun setState(id: Long, state: ItemState)

    @Query("UPDATE media_items SET md5Hex = :md5 WHERE mediaStoreId = :id")
    suspend fun setHash(id: Long, md5: String)

    @Query(
        """SELECT year, month, COUNT(*) AS cnt, SUM(sizeBytes) AS bytes
             FROM media_items WHERE state = 'SCANNED' GROUP BY year, month ORDER BY year, month"""
    )
    fun monthRollup(): Flow<List<MonthRollup>>
}

data class MonthRollup(val year: Int, val month: Int, val cnt: Int, val bytes: Long)

@Dao
interface AuxAccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AuxAccountEntity)

    @Query("SELECT * FROM aux_accounts WHERE isHost = 0 AND markedFull = 0")
    suspend fun activeAux(): List<AuxAccountEntity>

    @Query("SELECT * FROM aux_accounts")
    fun observeAll(): Flow<List<AuxAccountEntity>>

    @Query("UPDATE aux_accounts SET quotaTotalBytes = :total, quotaUsedBytes = :used WHERE email = :email")
    suspend fun updateQuota(email: String, total: Long?, used: Long?)

    @Query("UPDATE aux_accounts SET markedFull = :full WHERE email = :email")
    suspend fun setFull(email: String, full: Boolean)

    @Query("DELETE FROM aux_accounts WHERE email = :email")
    suspend fun remove(email: String)
}

@Dao
interface ReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(receipt: UploadReceiptEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM upload_receipts WHERE md5Hex = :md5)")
    suspend fun existsForHash(md5: String): Boolean

    @Query("SELECT * FROM upload_receipts ORDER BY uploadedAtMillis DESC")
    fun observeAll(): Flow<List<UploadReceiptEntity>>
}

@Dao
interface ExclusionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(entry: ExclusionEntryEntity)

    @Query("SELECT * FROM exclusions")
    fun observeAll(): Flow<List<ExclusionEntryEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM exclusions WHERE folderPath = :path)")
    suspend fun isExcluded(path: String): Boolean
}
