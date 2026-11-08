package com.bpmapp.audio.audio

import com.bpmapp.audio.data.Track

/**
 * Builds M3U playlist file content from a list of tracks.
 *
 * The produced format is the extended M3U format:
 * ```
 * #EXTM3U
 * #EXTINF:<durationSec>,<artist> - <title>
 * #EXTBPM:<bpm>               (only with includeMetadata and when the track has a BPM)
 * #EXTRB:NFR=<0|1>;FAV=<0|1>  (only with includeMetadata; RunBeat flags)
 * <track path or URI>
 * ```
 */
object M3uExporter {

    private const val HEADER = "#EXTM3U"

    /**
     * Build the M3U file content for a playlist.
     *
     * @param playlistName name of the playlist (used for display only, not written to the file)
     * @param tracks tracks in playlist order (already sorted by position by the caller)
     * @param includeMetadata when true, per-track metadata lines are written:
     *        #EXTBPM:<bpm> (when the track has a BPM) and #EXTRB:<NFR=..;FAV=..> for the RunBeat flags
     */
    fun buildM3uContent(
        playlistName: String,
        tracks: List<Track>,
        includeMetadata: Boolean = false
    ): String {
        val builder = StringBuilder()
        builder.append(HEADER).append('\n')
        for (track in tracks) {
            val durationSec = (track.durationMs ?: 0L) / 1000
            val artist = escapeNewlines(track.metadataArtist ?: track.fileName)
            val title = escapeNewlines(track.metadataTitle ?: track.fileName)
            builder.append("#EXTINF:")
                .append(durationSec)
                .append(',')
                .append(artist)
                .append(" - ")
                .append(title)
                .append('\n')
            if (includeMetadata) {
                track.bpm?.let { bpm ->
                    builder.append("#EXTBPM:").append(MetadataEditor.formatBpmValue(bpm)).append('\n')
                }
                builder.append("#EXTRB:")
                    .append(MetadataEditor.formatFlags(track.notForRunning, track.isFavorite))
                    .append('\n')
            }
            builder.append(escapeNewlines(track.id)).append('\n')
        }
        return builder.toString()
    }

    /**
     * M3U is a line-based format, so raw newlines inside metadata would corrupt
     * the file structure. Replace them with spaces.
     */
    private fun escapeNewlines(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ')
}
