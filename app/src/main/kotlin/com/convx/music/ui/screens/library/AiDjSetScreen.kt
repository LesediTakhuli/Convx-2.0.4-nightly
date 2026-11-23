/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.ui.screens.library

import com.convx.music.ui.component.sf
import com.convx.music.ui.component.SymbolIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.convx.music.LocalDatabase
import com.convx.music.LocalPlayerConnection
import com.convx.music.R
import com.convx.music.constants.ConcertNarratorVolumeKey
import com.convx.music.constants.ConcertInstrumentalBedVolumeKey
import com.convx.music.ai.toMediaItems
import com.convx.music.ai.toConcertMediaItems
import com.convx.music.ai.ConcertDurationPlanner
import com.convx.music.playback.queues.ListQueue
import com.convx.music.ui.ios.compat.AppTheme
import com.convx.music.ui.ios.compat.Button
import com.convx.music.ui.ios.compat.CircularProgressIndicator
import com.convx.music.ui.ios.compat.Icon
import com.convx.music.ui.ios.compat.IconButton
import com.convx.music.ui.ios.compat.OutlinedTextField
import com.convx.music.ui.ios.compat.Switch
import com.convx.music.ui.ios.compat.Text
import com.convx.music.ui.ios.compat.TextButton
import com.convx.music.ui.screens.settings.SettingsPage
import com.convx.music.ui.utils.localPlaylistRoute
import com.convx.music.viewmodels.AiDjSetViewModel
import com.convx.music.utils.rememberPreference

private const val MIN_SONG_COUNT = 4
private const val MAX_SONG_COUNT = 30
private const val DEFAULT_SONG_COUNT = 12

private const val MIN_DURATION_MINUTES = 15
private const val MAX_DURATION_MINUTES = 240
private const val DEFAULT_DURATION_MINUTES = 60
private const val DURATION_STEP_MINUTES = 15

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDjSetScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    seedSongId: String?,
    startInConcertMode: Boolean = false,
    viewModel: AiDjSetViewModel = hiltViewModel(),
) {
    val database = LocalDatabase.current.get()
    val playerConnection = LocalPlayerConnection.current ?: return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result = state.result

    var prompt by rememberSaveable { mutableStateOf("") }
    var songCount by rememberSaveable { mutableIntStateOf(DEFAULT_SONG_COUNT) }
    var byDuration by rememberSaveable { mutableStateOf(false) }
    var durationMinutes by rememberSaveable { mutableIntStateOf(DEFAULT_DURATION_MINUTES) }
    var concertMode by rememberSaveable { mutableStateOf(startInConcertMode) }
    var showAdvancedConcertSettings by rememberSaveable { mutableStateOf(false) }
    var teqApiKey by remember { mutableStateOf("") }
    var teqVoiceId by rememberSaveable { mutableStateOf("") }
    var narratorVolume by rememberPreference(ConcertNarratorVolumeKey, 0.98f)
    var instrumentalBedVolume by rememberPreference(ConcertInstrumentalBedVolumeKey, 0.24f)

    val seedSong = if (!seedSongId.isNullOrBlank()) {
        database.song(seedSongId).collectAsStateWithLifecycle(initialValue = null).value
    } else {
        null
    }

    SettingsPage(
        navController = navController,
        title = stringResource(R.string.ai_dj_mix),
        pageColor = AppTheme.colorScheme.background,
        horizontalPadding = 16.dp,
    ) {
        when {
            result != null -> {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    com.convx.music.ui.component.PlaylistIdentity(
                        title = result.playlistName,
                        artwork = seedSong?.thumbnailUrl,
                        subtitle = stringResource(R.string.ai_dj_mix_song_count, result.orderedTitles.size),
                    )

                    if (result.isConcert) {
                        val prep = state.concertPreparation
                        result.durationPlan?.let { plan ->
                            Text(
                                text = "${result.orderedTitles.size} songs · about ${ConcertDurationPlanner.formatDuration(plan.averageMusicSecondsPerSong)} per song · ${ConcertDurationPlanner.DEFAULT_TRANSITION_SECONDS}s per transition (${ConcertDurationPlanner.formatDuration(plan.transitionSeconds)} total) · ${ConcertDurationPlanner.formatDuration(plan.narrationSeconds)} Teq narration · approx. ${ConcertDurationPlanner.formatDuration(plan.estimatedTotalSeconds)} total. Duration mode selects a high-energy segment when Convx analysis is available.",
                                style = AppTheme.typography.bodySmall,
                                color = AppTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = prep.phase,
                                    style = AppTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${prep.progress}%",
                                    style = AppTheme.typography.labelMedium,
                                    color = AppTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            LinearProgressIndicator(
                                progress = prep.progress / 100f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = prep.failure ?: prep.detail,
                                style = AppTheme.typography.bodySmall,
                                color = if (prep.failure != null) AppTheme.colorScheme.error else AppTheme.colorScheme.onSurfaceVariant,
                            )
                            if (prep.failure != null) {
                                Button(onClick = { viewModel.prepareConcert() }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Retry concert preparation")
                                }
                            }
                            if (prep.isReady) {
                                Text(
                                    text = "${prep.assets?.preparedStemCount ?: 0} tracks with stems · ${prep.assets?.unavailableStemCount ?: 0} unavailable for stem separation. Narration uses the previous track's instrumental, or an original Convx synth bed.",
                                    style = AppTheme.typography.bodySmall,
                                    color = AppTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "Concert pack saved to Downloads / Convx Concerts",
                                    style = AppTheme.typography.bodySmall,
                                    color = AppTheme.colorScheme.primary,
                                )
                            }
                        }
                        Button(
                            onClick = {
                                val assets = state.concertPreparation.assets
                                if (assets != null && state.concertPreparation.isReady) {
                                    playerConnection.playQueue(
                                        ListQueue(title = result.playlistName, items = result.toConcertMediaItems(assets))
                                    )
                                }
                            },
                            enabled = prep.isReady && prep.assets != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (prep.isReady) "PLAY CONCERT" else "PREPARE BEFORE PLAYING")
                        }
                    } else {
                        com.convx.music.ui.component.PlaylistPlaybackActions(
                            enabled = result.orderedTitles.isNotEmpty(),
                            playing = false,
                            onPlay = { playerConnection.playQueue(ListQueue(title = result.playlistName, items = result.toMediaItems())) },
                            onShuffle = { playerConnection.playQueue(ListQueue(title = result.playlistName, items = result.toMediaItems().shuffled())) },
                        )
                    }

                    if (result.unmatchedTitles.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.ai_dj_mix_unmatched_count,
                                result.unmatchedTitles.size,
                                result.unmatchedTitles.joinToString(", "),
                            ),
                            style = AppTheme.typography.bodySmall,
                            color = AppTheme.colorScheme.error,
                        )
                    }

                    HorizontalDivider()

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(result.orderedTitles.indices.toList()) { index ->
                            val id = result.orderedIds.getOrNull(index)
                            val window = id?.let { result.playbackWindows[it] }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text(
                                    text = result.orderedTitles[index],
                                    style = AppTheme.typography.bodyMedium,
                                    color = AppTheme.colorScheme.onSurface,
                                )
                                if (window != null) {
                                    val method = when {
                                        window.usedEnergyAnalysis -> "Energy-selected highlight"
                                        window.startMs == 0L -> "Full track"
                                        else -> "Mid-track fallback highlight"
                                    }
                                    Text(
                                        text = "$method · ${ConcertDurationPlanner.formatDuration(window.startMs / 1_000L)}–${ConcertDurationPlanner.formatDuration(window.endMs / 1_000L)}",
                                        style = AppTheme.typography.labelSmall,
                                        color = AppTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.reset() }) {
                            Text(stringResource(R.string.ai_dj_mix_start_over))
                        }
                        if (!result.isConcert) {
                            val savedPlaylistId = state.savedPlaylistId
                            if (savedPlaylistId != null) {
                                Button(onClick = { navController.navigate(localPlaylistRoute(savedPlaylistId, null)) }) {
                                    Text(stringResource(R.string.ai_dj_mix_open_playlist))
                                }
                            } else {
                                TextButton(
                                    onClick = {
                                        playerConnection.playQueue(
                                            ListQueue(title = result.playlistName, items = result.toMediaItems())
                                        )
                                    },
                                ) {
                                    Text(stringResource(R.string.ai_dj_mix_play_now))
                                }
                                Button(onClick = { viewModel.save() }, enabled = !state.isSaving) {
                                    Text(
                                        if (state.isSaving) stringResource(R.string.ai_dj_mix_saving)
                                        else stringResource(R.string.ai_dj_mix_save_as_playlist)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            state.isRunning -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    CircularProgressIndicator(color = AppTheme.colorScheme.primary)
                    val latestLog = state.logs.lastOrNull() ?: stringResource(R.string.ai_dj_mix_starting)
                    Text(
                        text = latestLog,
                        style = AppTheme.typography.bodyMedium,
                        color = AppTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (state.logs.size > 1) {
                        val scrollState = rememberScrollState()
                        Text(
                            text = state.logs.dropLast(1).asReversed().joinToString("\n"),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(scrollState),
                            style = AppTheme.typography.bodySmall,
                            color = AppTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.failed) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = state.logs.lastOrNull() ?: stringResource(R.string.ai_dj_mix_failed),
                                style = AppTheme.typography.bodyMedium,
                                color = AppTheme.colorScheme.error,
                            )
                            // Last line is a generic outcome; the real reason is usually an earlier line.
                            if (state.logs.size > 1) {
                                Text(
                                    text = state.logs.dropLast(1).joinToString("\n"),
                                    style = AppTheme.typography.bodySmall,
                                    color = AppTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (seedSong != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(painterResource(R.drawable.sparks), contentDescription = null)
                            Text(
                                text = stringResource(R.string.ai_dj_mix_based_on, seedSong.song.title),
                                style = AppTheme.typography.bodyMedium,
                                color = AppTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text(stringResource(R.string.ai_dj_mix_prompt_label)) },
                        placeholder = {
                            Text(
                                when {
                                    concertMode -> stringResource(R.string.ai_dj_mix_prompt_hint_concert)
                                    seedSong != null -> stringResource(R.string.ai_dj_mix_prompt_hint_seeded)
                                    else -> stringResource(R.string.ai_dj_mix_prompt_hint)
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 2,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ai_dj_mix_concert_mode_label),
                                style = AppTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.ai_dj_mix_concert_mode_hint),
                                style = AppTheme.typography.bodySmall,
                                color = AppTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = concertMode, onCheckedChange = { concertMode = it })
                    }

                    if (concertMode) {
                        TextButton(onClick = { showAdvancedConcertSettings = !showAdvancedConcertSettings }) {
                            Text(if (showAdvancedConcertSettings) "Hide advanced concert settings" else "Advanced concert settings")
                        }
                        if (showAdvancedConcertSettings) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "Teq · Voice V1.0",
                                    style = AppTheme.typography.titleSmall,
                                )
                                Text(
                                    text = "Teq uses a hosted AI voice, not Android's built-in text-to-speech. Add your ElevenLabs API key and voice ID. The key is encrypted with Android Keystore and is not included in the concert download.",
                                    style = AppTheme.typography.bodySmall,
                                    color = AppTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    value = teqApiKey,
                                    onValueChange = { teqApiKey = it },
                                    label = { Text("ElevenLabs API key") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                )
                                OutlinedTextField(
                                    value = teqVoiceId,
                                    onValueChange = { teqVoiceId = it },
                                    label = { Text("ElevenLabs voice ID") },
                                    placeholder = { Text("Paste the ID of the voice you want Teq to use") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                )
                                Text("Teq narrator volume · ${(narratorVolume * 100).toInt()}%", style = AppTheme.typography.bodySmall)
                                Slider(
                                    value = narratorVolume,
                                    onValueChange = { narratorVolume = it },
                                    valueRange = 0f..1f,
                                )
                                Text("Instrumental bed volume · ${(instrumentalBedVolume * 100).toInt()}%", style = AppTheme.typography.bodySmall)
                                Slider(
                                    value = instrumentalBedVolume,
                                    onValueChange = { instrumentalBedVolume = it },
                                    valueRange = 0f..0.8f,
                                )
                                Text(
                                    text = "These two levels are mixed into the prepared narration clips. Change them before creating a set; a new preparation is required to apply changes.",
                                    style = AppTheme.typography.bodySmall,
                                    color = AppTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(
                                    onClick = {
                                        viewModel.saveTeqCredentials(teqApiKey, teqVoiceId)
                                        teqApiKey = ""
                                    },
                                    enabled = teqApiKey.isNotBlank() && teqVoiceId.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Save Teq voice settings")
                                }
                                if (state.teqVoiceConfigured) {
                                    Text("Teq voice is configured on this device.", color = AppTheme.colorScheme.primary, style = AppTheme.typography.bodySmall)
                                    TextButton(onClick = { viewModel.clearTeqCredentials() }) {
                                        Text("Remove saved Teq credentials")
                                    }
                                }
                                state.teqSettingsMessage?.let {
                                    Text(it, style = AppTheme.typography.bodySmall, color = AppTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    text = "Narration plays over the previous track's instrumental stem, or an original Convx synth bed. Only after Teq finishes can the incoming instrumental blend in; its vocals never play under narration. Duration mode selects high-energy windows when Convx analysis is available; song-count mode keeps full tracks. Available stems and narration are packaged automatically.",
                                    style = AppTheme.typography.bodySmall,
                                    color = AppTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (byDuration) {
                            TextButton(onClick = { byDuration = false }) {
                                Text(stringResource(R.string.ai_dj_mix_mode_songs))
                            }
                            Button(onClick = { }) {
                                Text(stringResource(R.string.ai_dj_mix_mode_duration))
                            }
                        } else {
                            Button(onClick = { }) {
                                Text(stringResource(R.string.ai_dj_mix_mode_songs))
                            }
                            TextButton(onClick = { byDuration = true }) {
                                Text(stringResource(R.string.ai_dj_mix_mode_duration))
                            }
                        }
                    }

                    if (byDuration) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.ai_dj_mix_duration_label),
                                style = AppTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    durationMinutes = (durationMinutes - DURATION_STEP_MINUTES)
                                        .coerceAtLeast(MIN_DURATION_MINUTES)
                                },
                            ) {
                                SymbolIcon(symbol = sf("minus"), contentDescription = null)
                            }
                            Text(
                                text = stringResource(R.string.ai_dj_mix_duration_minutes, durationMinutes),
                                style = AppTheme.typography.bodyLarge,
                            )
                            IconButton(
                                onClick = {
                                    durationMinutes = (durationMinutes + DURATION_STEP_MINUTES)
                                        .coerceAtMost(MAX_DURATION_MINUTES)
                                },
                            ) {
                                SymbolIcon(symbol = sf("plus"), contentDescription = null)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.ai_dj_mix_song_count_label),
                                style = AppTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { songCount = (songCount - 1).coerceAtLeast(MIN_SONG_COUNT) },
                            ) {
                                SymbolIcon(symbol = sf("minus"), contentDescription = null)
                            }
                            Text(text = "$songCount", style = AppTheme.typography.bodyLarge)
                            IconButton(
                                onClick = { songCount = (songCount + 1).coerceAtMost(MAX_SONG_COUNT) },
                            ) {
                                SymbolIcon(symbol = sf("plus"), contentDescription = null)
                            }
                        }
                    }

                    if (concertMode && byDuration) {
                        val estimatedSongs = ConcertDurationPlanner.estimateSongCount(durationMinutes * 60L)
                        val estimate = ConcertDurationPlanner.create(durationMinutes * 60L, estimatedSongs)
                        Text(
                            text = "Estimated plan: $estimatedSongs songs · ${ConcertDurationPlanner.formatDuration(estimate.averageMusicSecondsPerSong)} music per song · ${ConcertDurationPlanner.formatDuration(estimate.transitionSeconds)} transitions · ${ConcertDurationPlanner.formatDuration(estimate.narrationSeconds)} narration",
                            style = AppTheme.typography.bodySmall,
                            color = AppTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val canDescribeSet = prompt.isNotBlank() || seedSong != null
                    val canGenerate = canDescribeSet && (!concertMode || state.teqVoiceConfigured)
                    if (!canDescribeSet) {
                        Text(
                            text = stringResource(R.string.ai_dj_mix_need_prompt_or_seed),
                            style = AppTheme.typography.bodySmall,
                            color = AppTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (concertMode && !state.teqVoiceConfigured) {
                        Text(
                            text = "Open Advanced concert settings and save your Teq AI voice key and voice ID before generating the concert.",
                            style = AppTheme.typography.bodySmall,
                            color = AppTheme.colorScheme.error,
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.generate(
                                prompt = prompt.takeIf { it.isNotBlank() },
                                seedSongId = seedSongId,
                                songCount = songCount,
                                targetDurationSeconds = if (byDuration) durationMinutes * 60L else null,
                                concertMode = concertMode,
                            )
                        },
                        enabled = canGenerate,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.ai_dj_mix_generate))
                    }
                }
            }
        }
    }
}
