// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Worker class for parallel processing of metadata operations.
 * Uses Kotlin coroutines to process multiple files concurrently.
 */
class MetadataWorker(
    private val context: Context,
    private val metadataEditor: MetadataEditor,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    
    companion object {
        private const val TAG = "MetadataWorker"
        
        // Default number of concurrent workers
        const val DEFAULT_CONCURRENCY = 4
        
        // Maximum number of retries for failed operations
        const val MAX_RETRIES = 2
    }
    
    /**
     * Process multiple tracks in parallel to save BPM to their files
     * 
     * @param tracks List of tracks to process
     * @param concurrency Number of concurrent operations (default: DEFAULT_CONCURRENCY)
     * @param onProgress Progress callback
     * @param onComplete Completion callback with results
     */
    suspend fun processTracksInParallel(
        tracks: List<com.bpmapp.audio.data.Track>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        onProgress: (MetadataProgress) -> Unit = {},
        onComplete: (MetadataResult) -> Unit = {}
    ) = withContext(dispatcher) {
        val validTracks = tracks.filter { track ->
            track.bpm != null && track.bpm > 0 && 
            metadataEditor.canHandleFile(Uri.parse(track.id)) &&
            metadataEditor.isFileWritable(Uri.parse(track.id))
        }
        
        val total = validTracks.size
        val successCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        
        Log.d(TAG, "Starting parallel processing of $total tracks with concurrency $concurrency")
        
        if (total == 0) {
            onComplete(MetadataResult(0, 0, 0, emptyList()))
            return@withContext
        }
        
        // Split tracks into batches
        val batchSize = (total + concurrency - 1) / concurrency
        val batches = validTracks.chunked(batchSize)
        
        Log.d(TAG, "Processing ${batches.size} batches with $batchSize tracks each")
        
        // Process batches in parallel
        val batchResults = mutableListOf<BatchResult>()
        
        batches.forEachIndexed { batchIndex, batch ->
            val batchSuccess = AtomicInteger(0)
            val batchFailed = AtomicInteger(0)
            
            // Process each track in the batch
            val trackJobs = batch.map { track ->
                async {
                    processSingleTrack(track, batchIndex, batchSuccess, batchFailed)
                }
            }
            
            // Wait for all tracks in this batch to complete
            trackJobs.awaitAll()
            
            batchResults.add(BatchResult(batchIndex, batchSuccess.get(), batchFailed.get()))
            
            // Update progress
            val currentCompleted = completedCount.addAndGet(batch.size)
            val currentSuccess = successCount.addAndGet(batchSuccess.get())
            val currentFailed = failedCount.addAndGet(batchFailed.get())
            
            onProgress(MetadataProgress(currentCompleted, total, currentSuccess, currentFailed))
        }
        
        // Calculate final results
        val finalSuccess = successCount.get()
        val finalFailed = failedCount.get()
        
        Log.d(TAG, "Parallel processing complete: $finalSuccess success, $finalFailed failed")
        
        onComplete(MetadataResult(total, finalSuccess, finalFailed, batchResults))
    }
    
    /**
     * Process a single track
     */
    private suspend fun processSingleTrack(
        track: com.bpmapp.audio.data.Track,
        batchIndex: Int,
        batchSuccess: AtomicInteger,
        batchFailed: AtomicInteger
    ) = withContext(dispatcher) {
        var retries = 0
        var success = false
        
        while (retries <= MAX_RETRIES && !success) {
            try {
                val uri = Uri.parse(track.id)
                val bpm = track.bpm ?: return@withContext
                
                val result = metadataEditor.saveBpmToFile(uri, bpm)
                
                if (result) {
                    success = true
                    batchSuccess.incrementAndGet()
                    Log.d(TAG, "Successfully processed track: ${track.fileName} (BPM: $bpm)")
                } else {
                    retries++
                    Log.w(TAG, "Failed to process track: ${track.fileName} (attempt $retries)")
                }
                
            } catch (e: Exception) {
                retries++
                Log.e(TAG, "Error processing track: ${track.fileName} (attempt $retries)", e)
            }
        }
        
        if (!success) {
            batchFailed.incrementAndGet()
            Log.e(TAG, "Failed to process track after $MAX_RETRIES attempts: ${track.fileName}")
        }
    }
    
    /**
     * Data class for tracking progress
     */
    data class MetadataProgress(
        val completed: Int,
        val total: Int,
        val successCount: Int,
        val failedCount: Int
    )
    
    /**
     * Data class for batch processing results
     */
    data class BatchResult(
        val batchIndex: Int,
        val successCount: Int,
        val failedCount: Int
    )
    
    /**
     * Data class for final processing results
     */
    data class MetadataResult(
        val total: Int,
        val successCount: Int,
        val failedCount: Int,
        val batchResults: List<BatchResult>
    ) {
        val successRate: Float get() = if (total > 0) successCount.toFloat() / total.toFloat() else 0f
        val isComplete: Boolean get() = successCount + failedCount >= total
    }
}