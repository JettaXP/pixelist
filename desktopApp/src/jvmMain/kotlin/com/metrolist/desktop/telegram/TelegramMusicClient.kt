/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.telegram

import com.metrolist.shared.utils.XdgUtils
import it.tdlight.Init
import it.tdlight.client.APIToken
import it.tdlight.client.AuthenticationSupplier
import it.tdlight.client.ClientInteraction
import it.tdlight.client.InputParameter
import it.tdlight.client.ParameterInfo
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientFactory
import it.tdlight.client.TDLibSettings
import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object TelegramMusicClient {
    private var factory: SimpleTelegramClientFactory? = null
    private var client: SimpleTelegramClient? = null
    private val loginMutex = Mutex()
    private val pendingPrompts = ConcurrentHashMap<InputParameter, CompletableFuture<String>>()

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.LoggedOut)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    fun isLoggedIn(): Boolean = _authState.value is TelegramAuthState.Ready

    fun submitCode(code: String) {
        pendingPrompts.remove(InputParameter.ASK_CODE)?.complete(code)
    }

    fun submitPassword(password: String) {
        pendingPrompts.remove(InputParameter.ASK_PASSWORD)?.complete(password)
    }

    suspend fun login(
        apiId: Int,
        apiHash: String,
        phoneNumber: String,
    ): Result<Unit> =
        loginMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (client != null) return@runCatching
                    Init.init()
                    val token = APIToken(apiId, apiHash)
                    val settings = TDLibSettings.create(token)
                    val dataDirectory = telegramDataDirectory()
                    settings.databaseDirectoryPath = dataDirectory.resolve("data").toPath()
                    settings.downloadedFilesDirectoryPath = dataDirectory.resolve("downloads").toPath()
                    settings.isChatInfoDatabaseEnabled = true
                    settings.isFileDatabaseEnabled = true
                    settings.isMessageDatabaseEnabled = true
                    val clientFactory = factory ?: SimpleTelegramClientFactory().also { factory = it }
                    val builder = clientFactory.builder(settings)
                    builder.setClientInteraction(InteractiveLogin)
                    builder.addUpdateHandler(TdApi.UpdateAuthorizationState::class.java) { update ->
                        onAuthorizationState(update.authorizationState)
                    }
                    builder.addDefaultExceptionHandler { throwable ->
                        val message =
                            (throwable as? TelegramError)?.errorMessage
                                ?: throwable.message
                                ?: "Telegram error"
                        _authState.value = TelegramAuthState.Failed(message)
                    }
                    _authState.value = TelegramAuthState.Connecting
                    client = builder.build(AuthenticationSupplier.user(phoneNumber))
                }
            }
        }

    fun logout() {
        val current = client
        client = null
        _authState.value = TelegramAuthState.Closing
        if (current != null) {
            Thread {
                runCatching { current.closeAndWait() }
                _authState.value = TelegramAuthState.LoggedOut
            }.start()
        } else {
            _authState.value = TelegramAuthState.LoggedOut
        }
    }

    suspend fun chats(): List<TelegramChatInfo> =
        withClient { activeClient ->
            val request =
                TdApi.GetChats().apply {
                    chatList = TdApi.ChatListMain()
                    limit = 200
                }
            val chats = activeClient.send(request).await() as? TdApi.Chats
            val chatIds = chats?.chatIds ?: LongArray(0)
            coroutineScope {
                chatIds
                    .map { chatId ->
                        async {
                            val chat = activeClient.send(TdApi.GetChat(chatId)).await() as? TdApi.Chat
                            chat?.let { TelegramChatInfo(it.id, it.title) }
                        }
                    }.awaitAll()
            }.filterNotNull()
        }

    suspend fun tracks(chatId: Long): List<TelegramTrack> =
        withClient { activeClient ->
            val request =
                TdApi.GetChatHistory().apply {
                    this.chatId = chatId
                    fromMessageId = 0
                    offset = 0
                    limit = 100
                    onlyLocal = false
                }
            val messages = activeClient.send(request).await() as? TdApi.Messages
            messages?.messages.orEmpty().mapNotNull { message -> mapMessageToTrack(message) }
        }

    suspend fun downloadTrack(track: TelegramTrack): String? =
        withClient { activeClient ->
            val request =
                TdApi.DownloadFile().apply {
                    fileId = track.fileId
                    priority = 5
                    offset = 0
                    limit = 0
                    synchronous = true
                }
            val file = activeClient.send(request).await() as? TdApi.File
            file?.local?.path?.takeIf { it.isNotEmpty() }
        }

    fun telegramDataDirectory(): File = File(XdgUtils.getXdgDataHome(), "metrolist/telegram").apply { mkdirs() }

    private suspend fun <T> withClient(block: suspend (SimpleTelegramClient) -> T): T {
        val activeClient = client
        if (activeClient == null || _authState.value !is TelegramAuthState.Ready) {
            throw IllegalStateException("Telegram is not logged in")
        }
        return withContext(Dispatchers.IO) { block(activeClient) }
    }

    private fun mapMessageToTrack(message: TdApi.Message): TelegramTrack? =
        when (val content = message.content) {
            is TdApi.MessageAudio -> {
                val audio = content.audio
                TelegramTrack(
                    chatId = message.chatId,
                    messageId = message.id,
                    title = audio.title.ifBlank { audio.fileName },
                    artist = audio.performer,
                    durationSeconds = audio.duration,
                    fileId = audio.audio.id,
                    fileName = audio.fileName,
                    isVoiceNote = false,
                )
            }

            is TdApi.MessageVoiceNote -> {
                val voiceNote = content.voiceNote
                TelegramTrack(
                    chatId = message.chatId,
                    messageId = message.id,
                    title = "Voice message",
                    artist = "",
                    durationSeconds = voiceNote.duration,
                    fileId = voiceNote.voice.id,
                    fileName = "voice-${message.id}.ogg",
                    isVoiceNote = true,
                )
            }

            else -> {
                null
            }
        }

    private fun onAuthorizationState(authorizationState: TdApi.AuthorizationState) {
        _authState.value =
            when (authorizationState) {
                is TdApi.AuthorizationStateWaitPhoneNumber,
                is TdApi.AuthorizationStateWaitOtherDeviceConfirmation,
                is TdApi.AuthorizationStateWaitRegistration,
                -> TelegramAuthState.Connecting

                is TdApi.AuthorizationStateWaitCode -> TelegramAuthState.WaitCode

                is TdApi.AuthorizationStateWaitPassword -> TelegramAuthState.WaitPassword

                is TdApi.AuthorizationStateReady -> TelegramAuthState.Ready

                is TdApi.AuthorizationStateLoggingOut,
                is TdApi.AuthorizationStateClosing,
                -> TelegramAuthState.Closing

                is TdApi.AuthorizationStateClosed -> TelegramAuthState.LoggedOut

                else -> TelegramAuthState.Connecting
            }
    }

    private object InteractiveLogin : ClientInteraction {
        override fun onParameterRequest(
            parameter: InputParameter,
            parameterInfo: ParameterInfo,
        ): CompletableFuture<String> =
            when (parameter) {
                InputParameter.ASK_CODE -> newPrompt(parameter)
                InputParameter.ASK_PASSWORD -> newPrompt(parameter)
                InputParameter.ASK_FIRST_NAME -> completed("Metrolist")
                InputParameter.ASK_LAST_NAME -> completed("")
                else -> completed("")
            }

        private fun newPrompt(parameter: InputParameter): CompletableFuture<String> {
            val future = CompletableFuture<String>()
            pendingPrompts[parameter] = future
            future.whenComplete { _, _ -> pendingPrompts.remove(parameter) }
            return future
        }

        private fun completed(value: String): CompletableFuture<String> = CompletableFuture.completedFuture(value)
    }
}
