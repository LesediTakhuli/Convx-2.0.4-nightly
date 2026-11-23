/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.ai

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.convx.music.constants.StemQualityKey
import com.convx.music.constants.ConcertNarratorVolumeKey
import com.convx.music.constants.ConcertInstrumentalBedVolumeKey
import com.convx.music.extensions.toMediaItem
import com.convx.music.playback.AudioCache
import com.convx.music.playback.smart.ConcertNarrationRenderer
import com.convx.music.playback.smart.StemQuality
import com.convx.music.playback.smart.StemSeparator
import com.convx.music.playback.smart.StemStore
import com.convx.music.playback.smart.TeqVoiceClient
import com.convx.music.utils.TeqVoiceCredentialStore
import com.convx.music.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Output files required to play and archive a prepared Convx Live session. */
data class PreparedConcertAssets(
    val narrationCuePaths: List<String>,
    val packageUri: String,
    val preparedStemCount: Int,
    val unavailableStemCount: Int,
    val playbackStartOffsetsMs: Map<String, Long> = emptyMap(),
)

/**
 * Builds the offline-ready performance assets. A cue's background is always the previous song's
 * instrumental stem, or an original Convx synth bed for the opening/fallback. The next song is
 * never used as the narration backing track.
 */
object ConcertSetPreparer {
    private val stemNames = listOf("vocals", "instrumental", "drums", "bass", "other")

    data class Progress(val percent: Int, val phase: String, val detail: String)

    @OptIn(UnstableApi::class)
    suspend fun prepare(
        context: Context,
        result: AiDjSetResult,
        onProgress: suspend (Progress) -> Unit,
    ): PreparedConcertAssets = withContext(Dispatchers.IO) {
        require(result.isConcert) { "Only a Convx Live set can be prepared as a concert." }
        val credentials = TeqVoiceCredentialStore.read(context)
            ?: error("Teq is not configured. Add your ElevenLabs API key and voice ID in Advanced Concert Settings.")
        val ids = result.orderedIds.distinct()
        require(ids.isNotEmpty()) { "The concert has no tracks." }

        val sessionDir = File(context.filesDir, "concerts/${safeName(result.playlistName)}_${System.currentTimeMillis()}")
        check(sessionDir.mkdirs() || sessionDir.isDirectory) { "Could not create the concert working folder." }
        val stemsDir = File(sessionDir, "stems").apply { mkdirs() }
        val narrationDir = File(sessionDir, "narration").apply { mkdirs() }
        var preparedStems = 0
        var unavailableStems = 0
        val playbackStartOffsetsMs = mutableMapOf<String, Long>()

        // Stage 1: create/cache stems where the original track is locally accessible or fully cached.
        val settings = context.dataStore.data.first()
        val qualityName = settings[StemQualityKey]
        val quality = StemQuality.entries.firstOrNull { it.name.equals(qualityName, true) } ?: StemQuality.FAST
        val narratorGain = (settings[ConcertNarratorVolumeKey] ?: 0.98f).coerceIn(0f, 1f)
        val instrumentalGain = (settings[ConcertInstrumentalBedVolumeKey] ?: 0.24f).coerceIn(0f, 1f)
        ids.forEachIndexed { index, id ->
            val song = result.songsById[id]
            val duration = song?.duration ?: result.seedSong?.takeIf { it.song.id == id }?.song?.duration ?: 0
            val uri = sourceUri(id)
            val instrumental = StemStore.instrumental(context, id)
            var available = instrumental.exists() && instrumental.length() > 0
            if (!available && duration in 1..StemSeparator.MAX_SECONDS && canReadSource(uri)) {
                val separated = runCatching {
                    StemSeparator.separate(context, id, uri, duration, quality) { true }
                }.getOrDefault(false)
                if (separated && (!instrumental.exists() || instrumental.length() == 0L)) {
                    // Demucs creates drums/bass/other/vocals. Build the instrumental master from
                    // its three non-vocal stems so Teq still never talks over vocals.
                    ConcertNarrationRenderer.createInstrumentalComposite(
                        context,
                        listOf("drums", "bass", "other").map { StemStore.stem(context, id, it) },
                        instrumental,
                    )
                }
                available = instrumental.exists() && instrumental.length() > 0
            }
            if (available) preparedStems++ else unavailableStems++
            // Export every stem that exists, even if the instrumental master could not be rebuilt.
            stemNames.forEach { name ->
                val source = StemStore.stem(context, id, name)
                if (source.exists() && source.length() > 0) {
                    source.copyTo(
                        File(stemsDir, "${index + 1}_${safeName(song?.title ?: result.seedSong?.takeIf { it.song.id == id }?.song?.title ?: id)}_$name.m4a"),
                        overwrite = true,
                    )
                }
            }
            onProgress(
                Progress(
                    percent = ((index + 1) * 45 / ids.size).coerceIn(1, 45),
                    phase = "Preparing song stems",
                    detail = "${index + 1}/${ids.size} · ${song?.title ?: id}",
                )
            )
        }

        // Stage 2: generate a small number of strategically placed introductions, not chatter between every song.
        val cueIndexes = cueIndexes(ids.size)
        val cuePaths = mutableListOf<String>()
        val firstTrackId = ids.first()
        val headliner = result.songsById[firstTrackId]?.artists?.firstOrNull()?.name
            ?: result.seedSong?.takeIf { it.song.id == firstTrackId }?.artists?.firstOrNull()?.name
            ?: "tonight's featured artist"
        cueIndexes.forEachIndexed { cueNumber, beforeIndex ->
            val script = narrationScript(cueNumber, cueIndexes.size, result.playlistName, headliner)
            val speechFile = File(narrationDir, "teq_${cueNumber + 1}.mp3")
            val mixedFile = File(narrationDir, "teq_${cueNumber + 1}_with_instrumental.m4a")
            onProgress(
                Progress(
                    percent = 45 + (((cueNumber + 1) * 45) / cueIndexes.size),
                    phase = "Generating Teq narration",
                    detail = "${cueNumber + 1}/${cueIndexes.size} · ${listOf("Opening", "Build-up", "Peak", "Finale").getOrElse(cueNumber) { "Introduction" }}",
                )
            )
            TeqVoiceClient.generateSpeech(credentials.apiKey, credentials.voiceId, script, speechFile)
            val previousId = ids.getOrNull(beforeIndex - 1)
            val incomingId = ids.getOrNull(beforeIndex)
            val previousInstrumental = previousId?.let { StemStore.instrumental(context, it) }
            val incomingInstrumental = incomingId?.let { StemStore.instrumental(context, it) }
            val incomingWindow = incomingId?.let { result.playbackWindows[it] }
            val transitionMs = ConcertNarrationRenderer.render(
                context = context,
                speechFile = speechFile,
                previousInstrumentalStem = previousInstrumental,
                destination = mixedFile,
                narratorGain = narratorGain,
                instrumentalGain = instrumentalGain,
                nextInstrumentalStem = incomingInstrumental,
                nextTrackStartSeconds = (incomingWindow?.startMs ?: 0L) / 1_000.0,
                transitionSeconds = 6.0,
            )
            if (transitionMs > 0L && incomingId != null) {
                playbackStartOffsetsMs[incomingId] = (incomingWindow?.startMs ?: 0L) + transitionMs
            }
            speechFile.delete() // Keep only the final mixed cue in the concert pack.
            cuePaths += mixedFile.absolutePath
        }

        // Stage 3: write manifest and package all generated narration plus every available stem.
        val manifest = JSONObject().apply {
            put("format", "convx-live-pack-v1")
            put("concertName", result.playlistName)
            put("headliner", headliner)
            put("requestedDurationSeconds", result.targetDurationSeconds ?: 0L)
            put("generatedAtEpochMs", System.currentTimeMillis())
            put("narrator", "Teq (Voice V1.0)")
            put("ttsProvider", "ElevenLabs")
            put("stemQuality", quality.name)
            put("preparedStemCount", preparedStems)
            put("unavailableStemCount", unavailableStems)
            put("tracks", JSONArray().apply {
                ids.forEachIndexed { index, id ->
                    val song = result.songsById[id]
                    put(JSONObject().apply {
                        put("position", index + 1)
                        put("id", id)
                        put("title", song?.title ?: result.seedSong?.song?.title ?: id)
                        put("artists", JSONArray().apply {
                            song?.artists?.forEach { put(it.name) }
                                ?: result.seedSong?.takeIf { it.song.id == id }?.artists?.forEach { put(it.name) }
                        })
                        put("durationSeconds", song?.duration ?: result.seedSong?.takeIf { it.song.id == id }?.song?.duration ?: 0)
                        put("stemAvailable", StemStore.instrumental(context, id).exists())
                        result.playbackWindows[id]?.let { window ->
                            put("highlightStartMs", window.startMs)
                            put("highlightEndMs", window.endMs)
                            put("highlightSelectionMethod", if (window.usedEnergyAnalysis) "cached-energy-profile" else "mid-track-fallback-or-full-track")
                        }
                        playbackStartOffsetsMs[id]?.let { put("transitionStartMs", it) }
                    })
                }
            })
            put("narrationCueCount", cuePaths.size)
            put("narrationBedPolicy", "during narration: previous track instrumental only or original Convx synth pad; after narration: optional instrumental-only crossfade into incoming track; incoming vocals never play under narration")
            put("transitionOffsetsMs", JSONObject().apply { playbackStartOffsetsMs.forEach { (id, offset) -> put(id, offset) } })
            put("audienceAudio", false)
        }
        val manifestFile = File(sessionDir, "concert.json").apply { writeText(manifest.toString(2)) }
        val zipFile = File(sessionDir, "${safeName(result.playlistName)}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            addToZip(zip, manifestFile, "concert.json")
            cuePaths.forEachIndexed { index, path -> addToZip(zip, File(path), "narration/teq_${index + 1}.m4a") }
            stemsDir.listFiles()?.forEach { file -> addToZip(zip, file, "stems/${file.name}") }
        }
        onProgress(Progress(98, "Saving concert package", zipFile.name))
        val publishedUri = publishToDownloads(context, zipFile, result.playlistName)
        onProgress(Progress(100, "Ready", "Concert package saved to Downloads/Convx Concerts"))

        PreparedConcertAssets(
            narrationCuePaths = cuePaths,
            packageUri = publishedUri.toString(),
            preparedStemCount = preparedStems,
            unavailableStemCount = unavailableStems,
            playbackStartOffsetsMs = playbackStartOffsetsMs.toMap(),
        )
    }

    internal fun cueIndexes(songCount: Int): List<Int> = ConcertDurationPlanner.cueIndexes(songCount)

    private fun narrationScript(index: Int, count: Int, concertName: String, headliner: String): String = when (index) {
        0 -> "Tonight, Convx presents $concertName. At the center of this performance, $headliner. The music is ready. Let's begin."
        1 -> "The foundation is set, and the energy is moving higher. Stay with this performance. The next chapter starts now."
        2 -> "This is the moment the whole set has been building toward. Turn it up, and let the music take over."
        else -> "Every great set deserves a final moment. This is the closing chapter of $concertName. Thank you for being here."
    }.take(1_200)

    private fun sourceUri(id: String): Uri = when {
        id.startsWith("content://") || id.startsWith("file://") -> Uri.parse(id)
        else -> Uri.parse("https://music.youtube.com/watch?v=$id")
    }

    @OptIn(UnstableApi::class)
    private fun canReadSource(uri: Uri): Boolean = when (uri.scheme) {
        "content", "file" -> true
        else -> AudioCache.renditionsOf(uri).any { it.isComplete }
    }

    private fun safeName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9 _-]"), "")
        .trim()
        .replace(Regex("\\s+"), "_")
        .take(64)
        .ifBlank { "Convx_Live" }

    private fun addToZip(zip: ZipOutputStream, file: File, entryName: String) {
        if (!file.exists() || !file.isFile) return
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun publishToDownloads(context: Context, archive: File, concertName: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${safeName(concertName)}.zip")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Convx Concerts")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Android could not create the concert download.")
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    FileInputStream(archive).use { it.copyTo(output) }
                } ?: error("Android could not write the concert download.")
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                return uri
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        }
        val downloads = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Convx Concerts")
        check(downloads.mkdirs() || downloads.isDirectory) { "Could not create the concert download folder." }
        val destination = File(downloads, "${safeName(concertName)}.zip")
        archive.copyTo(destination, overwrite = true)
        return Uri.fromFile(destination)
    }
}

/** Keeps normal AI DJ queue behavior untouched; Convx Live inserts prepared narrator cues. */
fun AiDjSetResult.toConcertMediaItems(assets: PreparedConcertAssets): List<MediaItem> {
    val cueIndexes = ConcertSetPreparer.cueIndexes(orderedIds.size)
    val cueMap = cueIndexes.zip(assets.narrationCuePaths).toMap()
    val result = mutableListOf<MediaItem>()
    orderedIds.forEachIndexed { index, id ->
        cueMap[index]?.let { path ->
            val cueUri = Uri.fromFile(File(path))
            result += MediaItem.Builder()
                .setMediaId(cueUri.toString())
                .setUri(cueUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Teq · Concert introduction")
                        .setArtist("Teq (Voice V1.0)")
                        .build(),
                )
                .build()
        }
        val sourceItem = songsById[id]?.toMediaItem()
            ?: seedSong?.takeIf { it.song.id == id }?.toMediaItem()
        if (sourceItem != null) {
            val window = playbackWindows[id]
            val transitionOffset = assets.playbackStartOffsetsMs[id]
            val startMs = maxOf(window?.startMs ?: 0L, transitionOffset ?: 0L)
            val plannedMusicMs = durationPlan?.averageMusicSecondsPerSong?.times(1_000L)
            // If a cue could not use an incoming instrumental stem, trim away the six seconds
            // reserved for the transition. When it exists, start after the baked transition and
            // retain the full planned music window.
            val endMs = window?.let {
                if (transitionOffset == null && plannedMusicMs != null && it.durationMs > plannedMusicMs) {
                    minOf(it.endMs, it.startMs + plannedMusicMs)
                } else it.endMs
            }
            val songItem = if (startMs > 0L || endMs != null) {
                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .apply { endMs?.let { setEndPositionMs(it) } }
                    .build()
                sourceItem.buildUpon().setClippingConfiguration(clipping).build()
            } else sourceItem
            result += songItem
        }
    }
    return result
}
