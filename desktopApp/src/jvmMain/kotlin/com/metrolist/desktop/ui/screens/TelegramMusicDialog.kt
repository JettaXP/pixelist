/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.metrolist.desktop.telegram.TelegramAuthState
import com.metrolist.desktop.telegram.TelegramChatInfo
import com.metrolist.desktop.telegram.TelegramMusicClient
import com.metrolist.desktop.telegram.TelegramPlayback
import com.metrolist.desktop.telegram.TelegramPlaybackState
import com.metrolist.desktop.telegram.TelegramTrack
import com.metrolist.shared.ui.components.Dialog
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.launch
import java.io.File
import java.util.Properties

private const val TELEGRAM_CREDENTIALS_FILE = "login.properties"

@Composable
fun TelegramMusicDialog(
    colorScheme: ColorScheme,
    onDismissRequest: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val authState by TelegramMusicClient.authState.collectAsState()
    val playbackState by TelegramPlayback.state.collectAsState()
    val credentials = remember { TelegramCredentials.load() }

    var apiId by remember { mutableStateOf(credentials.apiId) }
    var apiHash by remember { mutableStateOf(credentials.apiHash) }
    var phoneNumber by remember { mutableStateOf(credentials.phoneNumber) }
    var loginCode by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var chats by remember { mutableStateOf<List<TelegramChatInfo>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<TelegramTrack>>(emptyList()) }
    var selectedChat by remember { mutableStateOf<TelegramChatInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var inlineError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(authState) {
        val state = authState
        if (state is TelegramAuthState.Failed) {
            inlineError = state.message
        }
        if (state is TelegramAuthState.Ready && chats.isEmpty() && !loading) {
            loading = true
            runCatching { TelegramMusicClient.chats() }
                .onSuccess { loaded -> chats = loaded }
                .onFailure { failure -> inlineError = failure.message }
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier =
                Modifier
                    .padding(16.dp)
                    .width(720.dp)
                    .heightIn(max = 720.dp)
                    .fillMaxHeight(),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxSize(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Telegram Music",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (authState is TelegramAuthState.Ready) {
                        TextButton(
                            onClick = {
                                TelegramPlayback.stop()
                                TelegramMusicClient.logout()
                                selectedChat = null
                                chats = emptyList()
                                tracks = emptyList()
                            },
                        ) {
                            Text("Log out")
                        }
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text("Close")
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (authState) {
                    is TelegramAuthState.LoggedOut, is TelegramAuthState.Closing -> {
                        TelegramLoginForm(
                            apiId = apiId,
                            apiHash = apiHash,
                            phoneNumber = phoneNumber,
                            onApiIdChange = { value -> apiId = value.filter(Char::isDigit) },
                            onApiHashChange = { value -> apiHash = value.trim() },
                            onPhoneNumberChange = { value -> phoneNumber = value.trim() },
                            onLogin = {
                                val parsedApiId = apiId.toIntOrNull()
                                when {
                                    parsedApiId == null || apiHash.isBlank() -> {
                                        inlineError = "Enter your Telegram API ID and API hash (my.telegram.org)."
                                    }

                                    phoneNumber.isBlank() -> {
                                        inlineError = "Enter your phone number in international format."
                                    }

                                    else -> {
                                        inlineError = null
                                        TelegramCredentials(
                                            apiId = apiId,
                                            apiHash = apiHash,
                                            phoneNumber = phoneNumber,
                                        ).save()
                                        coroutineScope.launch {
                                            TelegramMusicClient
                                                .login(parsedApiId, apiHash, phoneNumber)
                                                .onFailure { failure -> inlineError = failure.message }
                                        }
                                    }
                                }
                            },
                        )
                    }

                    is TelegramAuthState.Failed -> {
                        TelegramLoginForm(
                            apiId = apiId,
                            apiHash = apiHash,
                            phoneNumber = phoneNumber,
                            onApiIdChange = { value -> apiId = value.filter(Char::isDigit) },
                            onApiHashChange = { value -> apiHash = value.trim() },
                            onPhoneNumberChange = { value -> phoneNumber = value.trim() },
                            onLogin = {
                                val parsedApiId = apiId.toIntOrNull()
                                if (parsedApiId != null && apiHash.isNotBlank() && phoneNumber.isNotBlank()) {
                                    inlineError = null
                                    coroutineScope.launch {
                                        TelegramMusicClient
                                            .login(parsedApiId, apiHash, phoneNumber)
                                            .onFailure { failure -> inlineError = failure.message }
                                    }
                                }
                            },
                        )
                    }

                    TelegramAuthState.Connecting -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Connecting to Telegram\u2026")
                        }
                    }

                    TelegramAuthState.WaitCode -> {
                        TelegramCodeForm(
                            code = loginCode,
                            onCodeChange = { value -> loginCode = value.filter(Char::isDigit).take(8) },
                            onSubmit = {
                                inlineError = null
                                TelegramMusicClient.submitCode(loginCode)
                            },
                        )
                    }

                    TelegramAuthState.WaitPassword -> {
                        TelegramPasswordForm(
                            password = loginPassword,
                            onPasswordChange = { value -> loginPassword = value },
                            onSubmit = {
                                inlineError = null
                                TelegramMusicClient.submitPassword(loginPassword)
                            },
                        )
                    }

                    TelegramAuthState.Ready -> {
                        val currentChat = selectedChat
                        if (currentChat == null) {
                            if (loading) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                TelegramChatList(
                                    chats = chats,
                                    onChatSelected = { chat ->
                                        selectedChat = chat
                                        loading = true
                                        coroutineScope.launch {
                                            runCatching { TelegramMusicClient.tracks(chat.id) }
                                                .onSuccess { loaded -> tracks = loaded }
                                                .onFailure { failure -> inlineError = failure.message }
                                            loading = false
                                        }
                                    },
                                )
                            }
                        } else {
                            TelegramTrackList(
                                chat = currentChat,
                                tracks = tracks,
                                playbackState = playbackState,
                                loading = loading,
                                onBack = {
                                    selectedChat = null
                                    tracks = emptyList()
                                },
                                onPlay = { track ->
                                    if (playbackState.track == track && !playbackState.isPreparing) {
                                        TelegramPlayback.togglePause()
                                    } else {
                                        TelegramPlayback.prepare(track)
                                        coroutineScope.launch {
                                            val path = runCatching { TelegramMusicClient.downloadTrack(track) }.getOrNull()
                                            if (path == null) {
                                                TelegramPlayback.reportError("Could not download this track. Try again.")
                                            } else {
                                                TelegramPlayback.playDownloadedFile(track, path)
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                inlineError?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TelegramLoginForm(
    apiId: String,
    apiHash: String,
    phoneNumber: String,
    onApiIdChange: (String) -> Unit,
    onApiHashChange: (String) -> Unit,
    onPhoneNumberChange: (String) -> Unit,
    onLogin: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Sign in with Telegram to play music from your chats.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = apiId,
            onValueChange = onApiIdChange,
            label = { Text("API ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiHash,
            onValueChange = onApiHashChange,
            label = { Text("API hash") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = phoneNumber,
            onValueChange = onPhoneNumberChange,
            label = { Text("Phone number (+14155550123)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onLogin, modifier = Modifier.align(Alignment.End)) {
            Text("Log in")
        }
    }
}

@Composable
private fun TelegramCodeForm(
    code: String,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Enter the verification code sent by Telegram.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("Verification code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onSubmit, enabled = code.length >= 3, modifier = Modifier.align(Alignment.End)) {
            Text("Verify")
        }
    }
}

@Composable
private fun TelegramPasswordForm(
    password: String,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Enter your two-step verification password.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onSubmit, enabled = password.isNotBlank(), modifier = Modifier.align(Alignment.End)) {
            Text("Verify")
        }
    }
}

@Composable
private fun TelegramChatList(
    chats: List<TelegramChatInfo>,
    onChatSelected: (TelegramChatInfo) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(chats, key = { it.id }) { chat ->
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onChatSelected(chat) },
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(text = chat.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TelegramTrackList(
    chat: TelegramChatInfo,
    tracks: List<TelegramTrack>,
    playbackState: TelegramPlaybackState,
    loading: Boolean,
    onBack: () -> Unit,
    onPlay: (TelegramTrack) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(MetrolistIcons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back")
            }
            Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(tracks, key = { "${it.chatId}-${it.messageId}" }) { track ->
                    val isActive = playbackState.track == track
                    Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPlay(track) },
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = track.title.ifBlank { track.fileName },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                )
                                if (track.artist.isNotBlank()) {
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (isActive && playbackState.isPreparing) {
                                CircularProgressIndicator()
                            } else {
                                Icon(
                                    if (isActive && playbackState.isPlaying) {
                                        MetrolistIcons.Filled.Pause
                                    } else {
                                        MetrolistIcons.Outlined.PlayArrow
                                    },
                                    contentDescription = "Play",
                                )
                            }
                        }
                    }
                }
            }
        }
        playbackState.error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private data class TelegramCredentials(
    val apiId: String = "",
    val apiHash: String = "",
    val phoneNumber: String = "",
) {
    fun save() {
        runCatching {
            val directory = TelegramMusicClient.telegramDataDirectory()
            val file = File(directory, TELEGRAM_CREDENTIALS_FILE)
            val properties = Properties()
            properties.setProperty("apiId", apiId)
            properties.setProperty("apiHash", apiHash)
            properties.setProperty("phoneNumber", phoneNumber)
            file.outputStream().use { properties.store(it, null) }
        }
    }

    companion object {
        fun load(): TelegramCredentials =
            runCatching {
                val file = File(TelegramMusicClient.telegramDataDirectory(), TELEGRAM_CREDENTIALS_FILE)
                if (!file.isFile) return@runCatching TelegramCredentials()
                val properties = Properties()
                file.inputStream().use { properties.load(it) }
                TelegramCredentials(
                    apiId = properties.getProperty("apiId").orEmpty(),
                    apiHash = properties.getProperty("apiHash").orEmpty(),
                    phoneNumber = properties.getProperty("phoneNumber").orEmpty(),
                )
            }.getOrDefault(TelegramCredentials())
    }
}
