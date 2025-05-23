package com.google.ai.edge.aicore.demo.kotlin

import com.google.ai.edge.aicore.GenerativeModel // Ensure this import is correct
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.UserMessage
// Required for Response class
import dev.langchain4j.model.output.Response
// Required for the interface
import dev.langchain4j.model.chat.ChatLanguageModel

class EdgeAiCoreChatModel(
    private val generativeModel: GenerativeModel,
    private val systemPrompt: String? = null
) : ChatLanguageModel { // Ensure this is the correct interface name

    override fun generate(messages: List<ChatMessage>): Response<AiMessage> {
        val promptBuilder = StringBuilder()

        systemPrompt?.takeIf { it.isNotBlank() }?.let {
            promptBuilder.append(it)
            promptBuilder.append("\n\n")
        }

        messages.forEach { message ->
            when (message) {
                is UserMessage -> promptBuilder.append("User: ").append(message.text()).append("\n")
                is AiMessage -> promptBuilder.append("AI: ").append(message.text()).append("\n")
                // SystemMessage in list is ignored, constructor one is used.
                else -> {} // Ignore other types
            }
        }

        val fullPrompt = promptBuilder.toString().trimEnd()

        return try {
            // Assuming generativeModel.generateContent expects a String and returns an object
            // with a nullable 'text' property.
            val sdkResponse = generativeModel.generateContent(fullPrompt)
            val responseText = sdkResponse.text ?: "" 
            Response(AiMessage(responseText))
        } catch (e: Exception) {
            // Consider logging the exception e
            // You might want to wrap e in a more specific Langchain4j exception if available.
            // For now, returning an AiMessage with the error.
            // Alternatively, you could throw a RuntimeException:
            // throw RuntimeException("Failed to generate content due to: ${e.message}", e)
            Response(AiMessage("Error: " + e.message)) 
        }
    }
}
