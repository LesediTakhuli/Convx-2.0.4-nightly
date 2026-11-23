/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convx.music.ai.AiDjSetBuilder
import com.convx.music.ai.AiDjSetResult
import com.convx.music.ai.ConcertSetPreparer
import com.convx.music.ai.PreparedConcertAssets
import com.convx.music.db.MusicDatabase
import com.convx.music.utils.TeqVoiceCredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ConcertPreparationState(
    val isPreparing: Boolean = false,
    val isReady: Boolean = false,
    val progress: Int = 0,
    val phase: String = "Waiting to prepare",
    val detail: String = "",
    val failure: String? = null,
    val assets: PreparedConcertAssets? = null,
)

data class AiDjSetState(
    val isRunning: Boolean = false,
    val logs: List<String> = emptyList(),
    val result: AiDjSetResult? = null,
    /** True when generation finishes without a result. */
    val failed: Boolean = false,
    val isSaving: Boolean = false,
    /** Playlist id after the generated set is saved. */
    val savedPlaylistId: String? = null,
    val concertPreparation: ConcertPreparationState = ConcertPreparationState(),
    val teqVoiceConfigured: Boolean = false,
    val teqSettingsMessage: String? = null,
)

@HiltViewModel
class AiDjSetViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) : ViewModel() {

    private val _state = MutableStateFlow(AiDjSetState())
    val state: StateFlow<AiDjSetState> = _state.asStateFlow()

    private var job: Job? = null
    private var preparationJob: Job? = null

    init {
        _state.update { it.copy(teqVoiceConfigured = TeqVoiceCredentialStore.isConfigured(context)) }
    }

    fun saveTeqCredentials(apiKey: String, voiceId: String) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { TeqVoiceCredentialStore.save(context, apiKey, voiceId) }
            }
            _state.update {
                it.copy(
                    teqVoiceConfigured = result.isSuccess,
                    teqSettingsMessage = result.exceptionOrNull()?.message ?: "Teq voice settings saved securely on this device.",
                )
            }
        }
    }

    fun clearTeqCredentials() {
        viewModelScope.launch(Dispatchers.IO) {
            TeqVoiceCredentialStore.clear(context)
            _state.update { it.copy(teqVoiceConfigured = false, teqSettingsMessage = "Teq voice credentials removed.") }
        }
    }

    fun generate(
        prompt: String?,
        seedSongId: String?,
        songCount: Int,
        targetDurationSeconds: Long? = null,
        concertMode: Boolean = false,
    ) {
        if (_state.value.isRunning || _state.value.concertPreparation.isPreparing) return
        preparationJob?.cancel()
        _state.value = AiDjSetState(teqVoiceConfigured = TeqVoiceCredentialStore.isConfigured(context), isRunning = true)

        job = viewModelScope.launch {
            val result = AiDjSetBuilder.buildSet(
                context = context,
                database = database,
                prompt = prompt,
                seedSongId = seedSongId,
                songCount = songCount,
                targetDurationSeconds = targetDurationSeconds,
                concertMode = concertMode,
                // Keep a Logcat copy because the running UI clears logs with the result.
                onLog = { line -> Log.d("AiDjSet", line); _state.update { it.copy(logs = it.logs + line) } },
            )
            _state.update { it.copy(isRunning = false, result = result, failed = result == null) }
            if (result?.isConcert == true) prepareConcert()
        }
    }

    /** Starts the asset pipeline. Playback stays locked until every narration cue and the pack are ready. */
    fun prepareConcert() {
        val result = _state.value.result ?: return
        if (!result.isConcert || _state.value.concertPreparation.isPreparing || _state.value.concertPreparation.isReady) return
        preparationJob?.cancel()
        _state.update {
            it.copy(concertPreparation = ConcertPreparationState(isPreparing = true, phase = "Starting preparation", detail = "Checking Teq voice and audio access"))
        }
        preparationJob = viewModelScope.launch {
            val prepared = runCatching {
                ConcertSetPreparer.prepare(context, result) { progress ->
                    _state.update { current ->
                        current.copy(
                            concertPreparation = current.concertPreparation.copy(
                                progress = progress.percent.coerceIn(0, 100),
                                phase = progress.phase,
                                detail = progress.detail,
                            ),
                        )
                    }
                }
            }
            prepared.onSuccess { assets ->
                _state.update {
                    it.copy(
                        concertPreparation = it.concertPreparation.copy(
                            isPreparing = false,
                            isReady = true,
                            progress = 100,
                            phase = "Concert ready",
                            detail = "${assets.preparedStemCount} tracks have prepared stems · ${assets.unavailableStemCount} need fallback mixing",
                            assets = assets,
                            failure = null,
                        ),
                    )
                }
            }.onFailure { error ->
                Log.e("ConvxConcert", "Concert preparation failed", error)
                _state.update {
                    it.copy(
                        concertPreparation = it.concertPreparation.copy(
                            isPreparing = false,
                            isReady = false,
                            failure = error.message ?: "Concert preparation failed.",
                            phase = "Preparation stopped",
                        ),
                    )
                }
            }
        }
    }

    fun save() {
        val result = _state.value.result ?: return
        if (_state.value.isSaving || _state.value.savedPlaylistId != null) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val playlistId = AiDjSetBuilder.savePlaylist(database, result)
            _state.update { it.copy(isSaving = false, savedPlaylistId = playlistId) }
        }
    }

    override fun onCleared() {
        job?.cancel()
        preparationJob?.cancel()
    }

    fun reset() {
        job?.cancel()
        preparationJob?.cancel()
        _state.value = AiDjSetState(teqVoiceConfigured = TeqVoiceCredentialStore.isConfigured(context))
    }
}
