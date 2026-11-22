package com.bpmapp.audio.audio

import com.bpmapp.audio.data.Track

/**
 * Builds CSV file content from the library track list.
 *
 * The produced format is a header line followed by one line per track:
 * ```
 * relativePath,fileName,title,artist,album,genre,bpm,notForRunning,isFavorite,filePath,durationMs
 * GoGo_Penguin/A_Humdrum_Star,1-2-Raven-320.mp3,Song Title,Artist Name,Album Name,Genre,152,0,1,/path/to/file.mp3,245000
 * ```
 *
 * Fields containing a comma, a double quote, or a line break are wrapped in
 * double quotes (RFC 4180); embedded double quotes are doubled.
 */
object CsvExporter {

    private val HEADER = listOf(
        "relativePath", "fileName", "title", "artist", "album", "genre", "bpm",
        "notForRunning", "isFavorite", "filePath", "durationMs"
    )

    /**
     * Build the CSV file content for the whole library.
     *
     * @param tracks all tracks of the library (any order; the caller decides sorting)
     */
    fun buildCsvContent(tracks: List<Track>): String {
        val builder = StringBuilder()
        builder.append(HEADER.joinToString(",") { escapeCsvField(it) }).append('\n')
        for (track in tracks) {
            val fields = listOf(
                track.relativePath,
                track.fileName,
                track.metadataTitle ?: track.fileName,
                track.metadataArtist ?: "",
                track.metadataAlbum ?: "",
                track.metadataGenre ?: "",
                track.bpm?.let { MetadataEditor.formatBpmValue(it) } ?: "",
                if (track.notForRunning) "1" else "0",
                if (track.isFavorite) "1" else "0",
                track.id,
                track.durationMs?.toString() ?: ""
            )
            builder.append(fields.joinToString(",") { escapeCsvField(it) }).append('\n')
        }
        return builder.toString()
    }

    /**
     * Escape a single CSV field per RFC 4180: quote the field when it contains
     * a comma, a double quote, or a line break, and double any embedded quote.
     */
    private fun escapeCsvField(value: String): String {
        return if (value.contains(',') || value.contains('"') ||
            value.contains('\n') || value.contains('\r')
        ) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
