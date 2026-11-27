/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.telegram

data class TelegramChatInfo(
    val id: Long,
    val title: String,
)

data class TelegramTrack(
    val chatId: Long,
    val messageId: Long,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val fileId: Int,
    val fileName: String,
    val isVoiceNote: Boolean,
)

sealed interface TelegramAuthState {
    data object LoggedOut : TelegramAuthState
    data object Connecting : TelegramAuthState
    data object WaitCode : TelegramAuthState
    data object WaitPassword : TelegramAuthState
    data object Ready : TelegramAuthState
    data object Closing : TelegramAuthState

    data class Failed(
        val message: String,
    ) : TelegramAuthState
}
