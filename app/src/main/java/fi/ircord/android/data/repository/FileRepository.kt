package fi.ircord.android.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import fi.ircord.android.domain.model.*
import ircord.Ircord.*
import fi.ircord.android.data.remote.IrcordSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for file transfer operations.
 */
@Singleton
class FileRepository @Inject constructor(
    private val context: Context,
    private val ircordSocket: IrcordSocket,
    private val contentResolver: ContentResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Active transfers
    private val activeTransfers = ConcurrentHashMap<String, FileTransfer>()
    private val _transferUpdates = MutableSharedFlow<FileTransfer>(extraBufferCapacity = 64)
    val transferUpdates: SharedFlow<FileTransfer> = _transferUpdates.asSharedFlow()
    
    // Transfer limits
    private val maxConcurrentUploads = FileTransferLimits.MAX_CONCURRENT_UPLOADS
    private val maxConcurrentDownloads = FileTransferLimits.MAX_CONCURRENT_DOWNLOADS
    
    private val _pendingUploads = MutableStateFlow(0)
    private val _pendingDownloads = MutableStateFlow(0)
    
    /**
     * Upload a file to the server.
     * @param uri The local file URI
     * @param recipientId Target user (null for channel upload)
     * @param channelId Target channel (null for DM)
     * @return FileTransfer object tracking the upload
     */
    suspend fun uploadFile(
        uri: Uri,
        recipientId: String? = null,
        channelId: String? = null
    ): Result<FileTransfer> = withContext(Dispatchers.IO) {
        try {
            // Get file info
            val fileInfo = getFileInfo(uri)
            val (filename, mimeType, fileSize) = fileInfo
            
            // Validate file size
            if (fileSize > FileTransferLimits.MAX_FILE_SIZE) {
                return@withContext Result.failure(
                    IllegalArgumentException("File too large (max 100 MB)")
                )
            }
            
            // Generate file ID
            val fileId = UUID.randomUUID().toString()
            
            // Create transfer object
            val transfer = FileTransfer(
                fileId = fileId,
                filename = filename,
                mimeType = mimeType,
                fileSize = fileSize,
                direction = TransferDirection.UPLOAD,
                status = TransferStatus.PENDING,
                localUri = uri.toString(),
                recipientId = recipientId,
                channelId = channelId
            )
            
            activeTransfers[fileId] = transfer
            
            // Start upload
            uploadFileInternal(transfer, uri)
            
            Result.success(transfer)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start file upload")
            Result.failure(e)
        }
    }
    
    /**
     * Download a file from the server.
     * @param fileId Server file ID
     * @param metadata File metadata (optional, will be fetched if null)
     * @return FileTransfer object tracking the download
     */
    suspend fun downloadFile(
        fileId: String,
        metadata: fi.ircord.android.domain.model.FileMetadata? = null
    ): Result<FileTransfer> = withContext(Dispatchers.IO) {
        try {
            // Use provided metadata or fetch from server
            val meta = metadata ?: fetchFileMetadata(fileId)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("File not found")
                )
            
            // Create transfer object
            val transfer = FileTransfer(
                fileId = fileId,
                filename = meta.filename,
                mimeType = meta.mimeType,
                fileSize = meta.fileSize,
                direction = TransferDirection.DOWNLOAD,
                status = TransferStatus.PENDING,
                recipientId = meta.recipientId,
                channelId = meta.channelId
            )
            
            activeTransfers[fileId] = transfer
            
            // Start download
            downloadFileInternal(transfer)
            
            Result.success(transfer)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start file download")
            Result.failure(e)
        }
    }
    
    /**
     * Cancel an active transfer.
     */
    fun cancelTransfer(fileId: String) {
        activeTransfers[fileId]?.let { transfer ->
            updateTransfer(transfer.copy(status = TransferStatus.CANCELLED))
        }
    }
    
    /**
     * Get the local file for a completed download.
     */
    fun getLocalFile(fileId: String): File? {
        val transfer = activeTransfers[fileId]
        if (transfer?.status != TransferStatus.COMPLETED) return null
        
        return transfer.localUri?.let { uri ->
            File(Uri.parse(uri).path ?: return@let null)
        }
    }
    
    /**
     * Get transfer by ID.
     */
    fun getTransfer(fileId: String): FileTransfer? = activeTransfers[fileId]
    
    /**
     * Get all active transfers.
     */
    fun getActiveTransfers(): List<FileTransfer> {
        return activeTransfers.values
            .filter { it.isActive }
            .sortedByDescending { it.timestamp }
    }
    
    /**
     * Clear completed/failed transfers from memory.
     */
    fun clearCompletedTransfers() {
        activeTransfers.entries.removeIf { (_, transfer) ->
            transfer.isComplete || transfer.isFailed || transfer.status == TransferStatus.CANCELLED
        }
    }
    
    // ============================================================================
    // Private Implementation
    // ============================================================================
    
    private suspend fun uploadFileInternal(transfer: FileTransfer, uri: Uri) {
        try {
            updateTransfer(transfer.copy(status = TransferStatus.UPLOADING))
            
            // TODO: Implement file upload using correct protobuf classes
            // File upload is currently disabled pending protobuf message fixes
            Timber.w("File upload not yet implemented")
            updateTransfer(transfer.copy(status = TransferStatus.FAILED, errorMessage = "Not implemented"))
            return
            
            /* Original implementation - commented out due to missing protobuf classes:
            val request = FileUploadRequest.newBuilder()
                .setFileId(transfer.fileId)
                .setFilename(transfer.filename)
                .setFileSize(transfer.fileSize)
                .setMimeType(transfer.mimeType)
                .setChunkSize(FileTransferLimits.CHUNK_SIZE)
                .apply {
                    transfer.recipientId?.let { setRecipientId(it) }
                    transfer.channelId?.let { setChannelId(it) }
                }
                .build()
            
            ircordSocket.send(request.toByteArray())
            
            // Read and send file in chunks
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(FileTransferLimits.CHUNK_SIZE)
                var bytesRead: Int
                var totalSent = 0L
                var chunkIndex = 0
                
                while (input.read(buffer).also { bytesRead = it } > 0) {
                    // Check if cancelled
                    if (activeTransfers[transfer.fileId]?.status == TransferStatus.CANCELLED) {
                        Timber.d("Upload cancelled for ${transfer.fileId}")
                        return
                    }
                    
                    val chunk = FileUploadChunk.newBuilder()
                        .setFileId(transfer.fileId)
                        .setChunkIndex(chunkIndex++)
                        .setData(com.google.protobuf.ByteString.copyFrom(buffer, 0, bytesRead))
                        .setIsLast(bytesRead < FileTransferLimits.CHUNK_SIZE)
                        .build()
                    
                    ircordSocket.send(MT_FILE_UPLOAD, chunk)
                    
                    totalSent += bytesRead
                    val progress = totalSent.toFloat() / transfer.fileSize
                    updateTransfer(transfer.copy(
                        bytesTransferred = totalSent,
                        progress = progress
                    ))
                }
            } ?: throw IllegalStateException("Could not open input stream")
            
            // Wait for server completion confirmation
            // (handled via server response in message handler)
            */
            
        } catch (e: Exception) {
            Timber.e(e, "Upload failed for ${transfer.fileId}")
            updateTransfer(transfer.copy(
                status = TransferStatus.FAILED,
                errorMessage = e.message
            ))
        }
    }
    
    private suspend fun downloadFileInternal(transfer: FileTransfer) {
        try {
            updateTransfer(transfer.copy(status = TransferStatus.DOWNLOADING))
            
            // TODO: Implement file download using correct protobuf classes
            // File download is currently disabled pending protobuf message fixes
            Timber.w("File download not yet implemented")
            updateTransfer(transfer.copy(status = TransferStatus.FAILED, errorMessage = "Not implemented"))
            return
            
            /* Original implementation - commented out due to missing protobuf classes:
            val downloadsDir = context.getExternalFilesDir("downloads")
                ?: context.filesDir
            val localFile = File(downloadsDir, transfer.fileId + "_" + transfer.filename)
            
            val request = FileDownloadRequest.newBuilder()
                .setFileId(transfer.fileId)
                .setChunkIndex(0)
                .build()
            
            ircordSocket.send(request.toByteArray())
            
            // Download is handled asynchronously via server responses
            // Update local URI for when download completes
            updateTransfer(transfer.copy(
                localUri = Uri.fromFile(localFile).toString()
            ))
            */
            
        } catch (e: Exception) {
            Timber.e(e, "Download failed for ${transfer.fileId}")
            updateTransfer(transfer.copy(
                status = TransferStatus.FAILED,
                errorMessage = e.message
            ))
        }
    }
    
    /**
     * Handle incoming file chunk from server.
     * Called by socket message handler.
     */
    fun handleFileChunk(chunk: ircord.Ircord.FileChunk) {
        // TODO: Implement file chunk handling
        Timber.w("File chunk handling not yet implemented")
    }
    
    /**
     * Handle file progress update from server.
     */
    fun handleFileProgress(progress: ircord.Ircord.FileProgress) {
        // TODO: Implement file progress handling
        Timber.w("File progress handling not yet implemented")
    }
    
    /**
     * Handle file completion from server.
     */
    fun handleFileComplete(complete: ircord.Ircord.FileComplete) {
        // TODO: Implement file complete handling
        Timber.w("File complete handling not yet implemented")
    }
    
    /**
     * Handle file error from server.
     */
    fun handleFileError(error: ircord.Ircord.FileError) {
        // TODO: Implement file error handling
        Timber.w("File error handling not yet implemented")
    }
    
    private fun updateTransfer(transfer: FileTransfer) {
        activeTransfers[transfer.fileId] = transfer
        _transferUpdates.tryEmit(transfer)
    }
    
    private fun getFileInfo(uri: Uri): Triple<String, String, Long> {
        var filename = "unknown"
        var mimeType = "application/octet-stream"
        var size = 0L
        
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                
                if (nameIndex >= 0) filename = cursor.getString(nameIndex)
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
            }
        }
        
        contentResolver.getType(uri)?.let { mimeType = it }
        
        return Triple(filename, mimeType, size)
    }
    
    private suspend fun fetchFileMetadata(fileId: String): fi.ircord.android.domain.model.FileMetadata? {
        // This would typically fetch metadata from server or local cache
        // For now, return null - metadata should be provided
        return null
    }
    
    fun cleanup() {
        scope.cancel()
    }
}
