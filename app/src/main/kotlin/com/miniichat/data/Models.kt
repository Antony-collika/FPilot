package com.miniichat.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject

enum class Role { user, assistant, system }

@Serializable
data class Attachment(
    val type: String,        // "image" | "file"
    val uri: String,         // content:// or file path
    val mimeType: String,
    val name: String,
    val sizeBytes: Long = 0
)

@Serializable
data class Message(
    val id: String,
    val role: String,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    @SerialName("extra_content") val extraContent: JsonObject? = null
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
