/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.telegram

import com.metrolist.shared.playback.ffmpeg.AudioPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TelegramPlaybackState(
    val track: TelegramTrack? = null,
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val error: String? = null,
)

object TelegramPlayback {
    private var player: AudioPlayer? = null

    private val _state = MutableStateFlow(TelegramPlaybackState())
    val state: StateFlow<TelegramPlaybackState> = _state.asStateFlow()

    fun prepare(track: TelegramTrack) {
        _state.value = _state.value.copy(track = track, isPreparing = true, error = null)
    }

    fun playDownloadedFile(
        track: TelegramTrack,
        path: String,
    ): Boolean {
        val audioPlayer = player ?: createPlayer()
        val started = runCatching { audioPlayer.loadFile(path) }.getOrDefault(false)
        _state.value =
            if (started) {
                _state.value.copy(track = track, isPlaying = true, isPreparing = false, error = null)
            } else {
                _state.value.copy(
                    track = track,
                    isPlaying = false,
                    isPreparing = false,
                    error = "Unable to play the downloaded Telegram file",
                )
            }
        return started
    }

    fun togglePause() {
        val current = player ?: return
        val wasPlaying = _state.value.isPlaying
        runCatching {
            if (wasPlaying) {
                current.pause()
            } else {
                current.resume()
            }
        }
        _state.value = _state.value.copy(isPlaying = !wasPlaying)
    }

    fun stop() {
        player?.let { runCatching { it.pause() } }
        player = null
        _state.value = TelegramPlaybackState()
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(isPreparing = false, isPlaying = false, error = message)
    }

    private fun createPlayer(): AudioPlayer {
        val audioPlayer =
            AudioPlayer(
                onPlaybackCompleted = {
                    _state.value = _state.value.copy(isPlaying = false)
                },
                onPlaybackFailed = { _, failure ->
                    _state.value =
                        _state.value.copy(
                            isPlaying = false,
                            error = failure.message ?: "Playback failed",
                        )
                },
            )
        player = audioPlayer
        return audioPlayer
    }
}
