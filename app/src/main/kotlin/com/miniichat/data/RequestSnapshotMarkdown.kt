package com.miniichat.data

import com.miniichat.api.ToolDefinition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Human-readable export of a compiled model request. Credentials and binary image payloads are excluded. */
object RequestSnapshotMarkdown {
    fun render(
        snapshot: RequestSnapshot,
        tools: List<ToolDefinition>,
        createdAt: Long = System.currentTimeMillis()
    ): String = buildString {
        appendLine("---")
        appendLine("format: fpilot-request-snapshot-v1")
        appendLine("created_at: \"${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).format(Date(createdAt))}\"")
        appendLine("fingerprint: ${snapshot.fingerprint}")
        appendLine("messages: ${snapshot.messages.size}")
        appendLine("tools: ${tools.size}")
        appendLine("---")
        appendLine()
        appendLine("# Effective request snapshot")
        appendLine()
        appendLine("This is the exact ordered text projection compiled for the model. Provider credentials, HTTP authorization headers, and binary attachment data are intentionally excluded.")
        appendLine()
        snapshot.parts.forEachIndexed { index, part ->
            appendLine("## Message ${index + 1}")
            appendLine()
            appendLine("- Role: ${part.role}")
            appendLine("- Card: ${part.cardName} (${part.cardId})")
            appendLine("- Source: ${part.sourceKind}")
            part.sourceLocation?.let { appendLine("- Source location: $it") }
            part.messageId?.let { appendLine("- Message ID: $it") }
            appendLine("- SHA-256: ${part.contentSha256}")
            appendLine()
            appendLine("### Content")
            appendLine()
            appendLine(part.contentPreview)
            appendLine()
        }
        appendLine("## Effective tools")
        appendLine()
        if (tools.isEmpty()) {
            appendLine("No tools were exposed to the model.")
        } else {
            tools.forEach { tool ->
                appendLine("### ${tool.name}")
                appendLine()
                appendLine(tool.description)
                appendLine()
                appendLine("JSON schema:")
                appendLine()
                appendLine("~~~json")
                appendLine(tool.parameters.toString())
                appendLine("~~~")
                appendLine()
            }
        }
    }
}
