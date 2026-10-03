package com.gstoreshift.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ItemState { SCANNED, QUEUED, UPLOADING, UPLOADED, VERIFIED, LOCAL_DELETED, RELOCATED, FAILED }

@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey val mediaStoreId: Long,
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateTakenMillis: Long,
    val year: Int,
    val month: Int,
    val md5Hex: String? = null,
    val sourceFolder: String,          // original device folder, for exclusion registry
    val state: ItemState = ItemState.SCANNED,
)

@Entity(tableName = "aux_accounts")
data class AuxAccountEntity(
    @PrimaryKey val email: String,
    val isHost: Boolean = false,
    val quotaTotalBytes: Long? = null,
    val quotaUsedBytes: Long? = null,
    val markedFull: Boolean = false,
    val addedAtMillis: Long = System.currentTimeMillis(),
)

/** One row per successfully uploaded file — the idempotency/dedup registry. */
@Entity(tableName = "upload_receipts")
data class UploadReceiptEntity(
    @PrimaryKey val md5Hex: String,   // content hash — the dedup key
    val mediaStoreId: Long,
    val auxEmail: String,
    val driveFileId: String,
    val drivePath: String,             // e.g. GStoreShift/2023/07/IMG_1234.jpg
    val sizeBytes: Long,
    val uploadedAtMillis: Long,
)

@Entity(tableName = "exclusions")
data class ExclusionEntryEntity(
    @PrimaryKey val folderPath: String,
    val reason: String,                // MIGRATED | RELOCATED | USER_EXCLUDED
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(tableName = "migration_batches")
data class MigrationBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val filesAttempted: Int = 0,
    val filesSucceeded: Int = 0,
    val bytesUploaded: Long = 0,
)
