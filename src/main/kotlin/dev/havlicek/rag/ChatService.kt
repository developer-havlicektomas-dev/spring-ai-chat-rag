package dev.havlicek.rag

import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

@Service
class ChatService(private val chatClient: ChatClient) {
    fun ask(question: String): String =
        chatClient.prompt().user(question).call().content() ?: ""
}
