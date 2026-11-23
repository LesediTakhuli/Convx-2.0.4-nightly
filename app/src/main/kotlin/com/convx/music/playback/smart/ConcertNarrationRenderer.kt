/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.playback.smart

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Teq is Convx's narrator profile. The actual natural voice is supplied by the user's chosen hosted voice. */
object TeqVoiceClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()

    /** Creates MP3 speech with ElevenLabs; never falls back to Android's built-in TTS. */
    fun generateSpeech(apiKey: String, voiceId: String, text: String, destination: File) {
        require(apiKey.isNotBlank()) { "Teq is not configured. Add an ElevenLabs API key in Advanced Concert Settings." }
        require(voiceId.isNotBlank()) { "Teq needs an ElevenLabs voice ID. Add it in Advanced Concert Settings." }
        val body = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_v4")
            put("voice_settings", JSONObject().apply {
                put("stability", 0.48)
                put("similarity_boost", 0.82)
            })
        }.toString().toRequestBody("application/json".toMediaType())
        val url = "https://api.elevenlabs.io/v1/text-to-speech/${voiceId.trim()}?output_format=mp3_44100_128"
        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val reason = response.body?.string()?.take(300).orEmpty()
                error("Teq voice generation failed (HTTP ${response.code}). ${reason.ifBlank { "Check the API key, voice ID and ElevenLabs account." }}")
            }
            val bytes = response.body?.bytes() ?: error("Teq returned an empty audio file.")
            require(bytes.size > 1024) { "Teq returned an unexpectedly small audio file." }
            destination.parentFile?.mkdirs()
            destination.writeBytes(bytes)
        }
    }
}

/** Renders a narration cue over an instrumental-only bed. It never samples the upcoming song. */
object ConcertNarrationRenderer {
    private const val TAG = "ConvxConcertNarration"
    private const val OUTPUT_RATE = 44_100.0
    private const val BED_GAIN = 0.24f
    private const val VOICE_GAIN = 0.98f

    fun render(
        context: Context,
        speechFile: File,
        previousInstrumentalStem: File?,
        destination: File,
        narratorGain: Float = VOICE_GAIN,
        instrumentalGain: Float = BED_GAIN,
        nextInstrumentalStem: File? = null,
        nextTrackStartSeconds: Double = 0.0,
        transitionSeconds: Double = 6.0,
    ): Long {
        val speechSource = UriMediaDataSource.open(context, Uri.fromFile(speechFile))
            ?: error("Could not open Teq narration audio.")
        val speech = speechSource.use { AudioDecoder.decodeRegionStereo(it, 0.0, 180.0) }
            ?: error("Could not decode Teq narration audio.")
        val voiceLeft = MelSpectrogram.resample(speech.first.left, speech.first.sampleRate, OUTPUT_RATE)
            ?: error("Could not resample Teq narration audio.")
        val voiceRight = MelSpectrogram.resample(speech.first.right, speech.first.sampleRate, OUTPUT_RATE)
            ?: error("Could not resample Teq narration audio.")
        require(voiceLeft.isNotEmpty() && voiceRight.isNotEmpty()) { "Teq narration audio is empty." }

        val bed = previousInstrumentalStem?.takeIf { it.exists() && it.length() > 1024 }
            ?.let { decodeBed(context, it, 0.0, 90.0) }
        val bedLeft = bed?.first ?: FloatArray(0)
        val bedRight = bed?.second ?: FloatArray(0)
        val generated = bedLeft.isEmpty() || bedRight.isEmpty()
        val incoming = nextInstrumentalStem?.takeIf { it.exists() && it.length() > 1024 }
            ?.let { decodeBed(context, it, nextTrackStartSeconds, transitionSeconds) }
        val incomingLeft = incoming?.first ?: FloatArray(0)
        val incomingRight = incoming?.second ?: FloatArray(0)
        val voiceFrames = max(voiceLeft.size, voiceRight.size)
        val transitionFrames = if (incomingLeft.isNotEmpty() && incomingRight.isNotEmpty())
            (OUTPUT_RATE * transitionSeconds.coerceIn(2.0, 10.0)).toInt() else 0
        val tailFrames = if (transitionFrames > 0) 0 else (OUTPUT_RATE * 0.35).toInt()
        val frames = voiceFrames + transitionFrames + tailFrames
        val mixLeft = FloatArray(frames)
        val mixRight = FloatArray(frames)
        val bedLevel = instrumentalGain.coerceIn(0f, 1f)
        val voiceLevel = narratorGain.coerceIn(0f, 1f)

        for (index in 0 until frames) {
            val sourceL: Float
            val sourceR: Float
            val bedLevelNow: Float
            val voiceL: Float
            val voiceR: Float
            when {
                index < voiceFrames -> {
                    // During the spoken part, only the previous instrumental or original synth pad
                    // is audible behind Teq. No upcoming-song audio is read here.
                    val generatedL = if (generated) syntheticBed(index) else 0f
                    val generatedR = if (generated) syntheticBed(index + 173) else 0f
                    sourceL = if (bedLeft.isNotEmpty()) bedLeft[index % bedLeft.size] else generatedL
                    sourceR = if (bedRight.isNotEmpty()) bedRight[index % bedRight.size] else generatedR
                    val fadeIn = (index / (OUTPUT_RATE * 0.45)).toFloat().coerceIn(0f, 1f)
                    bedLevelNow = bedLevel * fadeIn
                    voiceL = voiceLeft.getOrElse(index) { 0f }
                    voiceR = voiceRight.getOrElse(index) { 0f }
                }
                transitionFrames > 0 -> {
                    // Narration has ended. Only now do we blend the previous instrumental into the
                    // incoming track's instrumental highlight. The incoming vocal stem is never used.
                    val transitionIndex = index - voiceFrames
                    val amount = (transitionIndex.toDouble() / transitionFrames.coerceAtLeast(1)).coerceIn(0.0, 1.0)
                    val outgoingGain = kotlin.math.cos(amount * PI / 2.0).toFloat()
                    val incomingGain = kotlin.math.sin(amount * PI / 2.0).toFloat()
                    val previousL = if (bedLeft.isNotEmpty()) bedLeft[index % bedLeft.size] else syntheticBed(index)
                    val previousR = if (bedRight.isNotEmpty()) bedRight[index % bedRight.size] else syntheticBed(index + 173)
                    val nextL = incomingLeft[transitionIndex % incomingLeft.size]
                    val nextR = incomingRight[transitionIndex % incomingRight.size]
                    sourceL = previousL * outgoingGain + nextL * incomingGain
                    sourceR = previousR * outgoingGain + nextR * incomingGain
                    bedLevelNow = bedLevel
                    voiceL = 0f
                    voiceR = 0f
                }
                else -> {
                    // If the incoming instrumental is unavailable, fade the previous bed away
                    // rather than leaving an abrupt cut at the end of the cue.
                    val generatedL = if (generated) syntheticBed(index) else 0f
                    val generatedR = if (generated) syntheticBed(index + 173) else 0f
                    sourceL = if (bedLeft.isNotEmpty()) bedLeft[index % bedLeft.size] else generatedL
                    sourceR = if (bedRight.isNotEmpty()) bedRight[index % bedRight.size] else generatedR
                    val remaining = 1f - ((index - voiceFrames) / tailFrames.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                    bedLevelNow = bedLevel * remaining
                    voiceL = 0f
                    voiceR = 0f
                }
            }
            mixLeft[index] = (sourceL * bedLevelNow + voiceL * voiceLevel).coerceIn(-0.98f, 0.98f)
            mixRight[index] = (sourceR * bedLevelNow + voiceR * voiceLevel).coerceIn(-0.98f, 0.98f)
        }

        destination.parentFile?.mkdirs()
        val writer = AacFileWriter(destination, OUTPUT_RATE.toInt(), bitrate = 160_000)
        try {
            var offset = 0
            val chunk = 16_384
            while (offset < frames) {
                val count = minOf(chunk, frames - offset)
                writer.write(mixLeft, mixRight, offset, count)
                offset += count
            }
            writer.finish()
        } catch (error: Throwable) {
            writer.abort()
            destination.delete()
            throw error
        }
        Log.d(TAG, "Rendered narration cue ${destination.name}; bed=${if (generated) "original synth" else "previous track instrumental"}; transition=${transitionFrames > 0}")
        return if (transitionFrames > 0) (transitionFrames * 1_000.0 / OUTPUT_RATE).toLong() else 0L
    }

    private fun decodeBed(context: Context, file: File, startSeconds: Double, durationSeconds: Double): Pair<FloatArray, FloatArray>? = runCatching {
        val source = UriMediaDataSource.open(context, Uri.fromFile(file)) ?: return null
        val decoded = source.use { AudioDecoder.decodeRegionStereo(it, startSeconds, startSeconds + durationSeconds) } ?: return null
        val left = MelSpectrogram.resample(decoded.first.left, decoded.first.sampleRate, OUTPUT_RATE) ?: return null
        val right = MelSpectrogram.resample(decoded.first.right, decoded.first.sampleRate, OUTPUT_RATE) ?: return null
        left to right
    }.onFailure { Log.w(TAG, "Could not decode previous instrumental stem for the narration bed", it) }.getOrNull()

    /** Builds a usable instrumental master from Demucs' drums, bass and other stems. */
    fun createInstrumentalComposite(context: Context, stems: List<File>, destination: File): Boolean {
        val decoded = stems.filter { it.exists() && it.length() > 1_024L }
            .mapNotNull { decodeBed(context, it, 0.0, 900.0) }
        if (decoded.isEmpty()) return false
        val frames = decoded.maxOf { max(it.first.size, it.second.size) }
        if (frames <= 0) return false
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        decoded.forEach { (stemLeft, stemRight) ->
            for (i in 0 until frames) {
                left[i] += stemLeft.getOrElse(i) { 0f }
                right[i] += stemRight.getOrElse(i) { 0f }
            }
        }
        for (i in 0 until frames) {
            left[i] = left[i].coerceIn(-0.96f, 0.96f)
            right[i] = right[i].coerceIn(-0.96f, 0.96f)
        }
        destination.parentFile?.mkdirs()
        val writer = AacFileWriter(destination, OUTPUT_RATE.toInt(), bitrate = 160_000)
        return try {
            var offset = 0
            while (offset < frames) {
                val count = minOf(16_384, frames - offset)
                writer.write(left, right, offset, count)
                offset += count
            }
            writer.finish()
            true
        } catch (error: Throwable) {
            writer.abort()
            destination.delete()
            Log.w(TAG, "Could not combine Demucs stems into an instrumental master", error)
            false
        }
    }

    /** Original pad tone used for the opening cue or when a previous instrumental stem is unavailable. */
    private fun syntheticBed(sampleIndex: Int): Float {
        val t = sampleIndex / OUTPUT_RATE
        val chord = when ((t.toInt() / 5) % 4) {
            0 -> doubleArrayOf(55.0, 82.41, 110.0, 164.81)
            1 -> doubleArrayOf(65.41, 98.0, 130.81, 196.0)
            2 -> doubleArrayOf(73.42, 110.0, 146.83, 220.0)
            else -> doubleArrayOf(61.74, 92.5, 123.47, 185.0)
        }
        var value = 0.0
        chord.forEachIndexed { index, frequency ->
            val slowEnvelope = 0.55 + 0.45 * sin(2.0 * PI * t / (8.0 + index) + index).coerceAtLeast(0.0)
            value += sin(2.0 * PI * frequency * t) * slowEnvelope * (0.035 / (index + 1))
        }
        val beat = t % 2.0
        val pulse = if (beat < 0.32) sin(2.0 * PI * 48.0 * beat) * (1.0 - beat / 0.32) * 0.055 else 0.0
        return (value + pulse).toFloat()
    }
}
