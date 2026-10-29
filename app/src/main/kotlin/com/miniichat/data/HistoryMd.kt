package com.miniichat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HistoryFormatException(
    val lineNumber: Int,
    val sourceLine: String,
    reason: String
) : Exception("history.md:" + lineNumber + ": " + reason + " Line: \"" + sourceLine.take(160) + "\"")

data class HistoryDocument(
    val sessionId: String,
    val title: String,
    val createdAt: Long,
    val nextTurn: Int,
    val messages: List<Message>,
    val repairNotes: List<String> = emptyList()
)

private data class HistoryBlock(
    val turn: Int,
    val kind: String,
    val round: Int,
    val startLine: Int,
    val lines: List<String>
)

/** Markdown codec for file-backed sessions. Message bodies remain human-editable. */
object HistoryMd {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val compactJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val heading = Regex("^## T([0-9]+) (user|assistant|round ([0-9]+))$")
    private val sessionPattern = Regex("[0-9a-f]{8}")

    @Serializable
    private data class MessageMeta(
        val id: String,
        val attachments: List<Attachment> = emptyList(),
        val createdAt: Long,
        @SerialName("extra_content") val extraContent: JsonObject? = null
    )

    fun empty(sessionId: String, title: String = "New chat", createdAt: Long = System.currentTimeMillis()) =
        HistoryDocument(sessionId, title, createdAt, 1, emptyList())

    fun parse(text: String, fallbackSessionId: String, fallbackTitle: String = "New chat", metadataText: String? = null): HistoryDocument {
        if (text.isBlank()) {
            if (metadataText != null) {
                val metadata = try {
                    HistoryMeta.parse(metadataText, fallbackSessionId)
                } catch (error: Exception) {
                    throw HistoryFormatException(1, "history.meta.json", "Invalid sidecar metadata: " + (error.message ?: "unknown error"))
                }
                if (metadata.messages.isNotEmpty()) {
                    throw HistoryFormatException(1, "", "history.md is empty but history.meta.json still contains message metadata.")
                }
            }
            return empty(fallbackSessionId, fallbackTitle)
        }
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        if (lines.firstOrNull() != "---") {
            throw HistoryFormatException(1, lines.firstOrNull().orEmpty(), "Expected front matter beginning with ---.")
        }
        val headerEnd = (1 until lines.size).firstOrNull { lines[it] == "---" }
            ?: throw HistoryFormatException(lines.size, lines.lastOrNull().orEmpty(), "Front matter has no closing ---.")

        val fields = linkedMapOf<String, String>()
        for (i in 1 until headerEnd) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) throw HistoryFormatException(i + 1, line, "Expected a front-matter key: value pair.")
            val key = line.substring(0, colon).trim()
            if (key !in setOf("session", "title", "created", "next_turn")) continue
            if (fields.containsKey(key)) throw HistoryFormatException(i + 1, line, "Duplicate front-matter field '" + key + "'.")
            fields[key] = line.substring(colon + 1).trim()
        }
        val session = fields["session"].orEmpty()
        if (!sessionPattern.matches(session)) {
            throw HistoryFormatException(2, "session: " + session, "Session ID must be eight lowercase hexadecimal characters.")
        }
        val title = runCatching { json.decodeFromString<String>(fields["title"].orEmpty()) }
            .getOrElse { fields["title"]?.removeSurrounding("\"") ?: fallbackTitle }
        val createdAt = parseDate(fields["created"])
        val declaredNext = fields["next_turn"]?.toIntOrNull()
            ?: throw HistoryFormatException(2, "next_turn: " + fields["next_turn"], "next_turn must be a positive integer.")
        if (declaredNext < 1) throw HistoryFormatException(2, "next_turn: " + declaredNext, "next_turn must be positive.")

        val blocks = mutableListOf<HistoryBlock>()
        var currentTurn = -1
        var currentKind = ""
        var currentRound = 0
        var currentStart = 0
        var currentLines = mutableListOf<String>()
        fun finishBlock() {
            if (currentKind.isNotEmpty()) blocks += HistoryBlock(currentTurn, currentKind, currentRound, currentStart, currentLines.toList())
        }
        for (i in headerEnd + 1 until lines.size) {
            val line = lines[i]
            val match = heading.matchEntire(line)
            if (match != null) {
                finishBlock()
                currentTurn = match.groupValues[1].toIntOrNull()
                    ?: throw HistoryFormatException(i + 1, line, "Turn number is invalid.")
                val type = match.groupValues[2]
                currentKind = if (type.startsWith("round ")) "round" else type
                currentRound = match.groupValues[3].toIntOrNull() ?: 0
                currentStart = i + 1
                currentLines = mutableListOf()
            } else {
                if (line.startsWith("## T")) {
                    throw HistoryFormatException(i + 1, line, "Malformed turn heading. Expected ## T<number> user, round <n>, or assistant.")
                }
                if (currentKind.isNotEmpty()) currentLines += line
                else if (line.isNotBlank() && !line.trim().startsWith("<!--")) throw HistoryFormatException(i + 1, line, "Content appears outside a turn block.")
            }
        }
        finishBlock()

        if (metadataText != null) {
            return parseSidecarFormat(session, title, createdAt, declaredNext, blocks, metadataText)
        }
        val messageBlocks = blocks.filter { it.kind == "user" || it.kind == "assistant" }
        val idLineFound = messageBlocks.firstOrNull { block ->
            runCatching { compactJson.decodeFromString<MessageId>(block.lines.firstOrNull().orEmpty()).id.isNotBlank() }.getOrDefault(false)
        }
        val allBlocksHaveIdLines = messageBlocks.isNotEmpty() && messageBlocks.all { block ->
            runCatching { compactJson.decodeFromString<MessageId>(block.lines.firstOrNull().orEmpty()).id.isNotBlank() }.getOrDefault(false)
        }
        if (idLineFound != null && allBlocksHaveIdLines && blocks.none { it.lines.any { line -> line.trim() == "```json-meta" } }) {
            throw HistoryFormatException(idLineFound.startLine + 1, idLineFound.lines.firstOrNull().orEmpty(), "This history uses the sidecar format, but history.meta.json is missing.")
        }

        val turns = linkedMapOf<Int, MutableList<HistoryBlock>>()
        for (block in blocks) turns.getOrPut(block.turn) { mutableListOf() }.add(block)
        val messages = mutableListOf<Message>()
        var maxTurn = 0
        for ((turn, turnBlocks) in turns) {
            if (turn <= 0) throw HistoryFormatException(turnBlocks.first().startLine, "## T" + turn, "Turn number must be positive.")
            maxTurn = maxOf(maxTurn, turn)
            val users = turnBlocks.filter { it.kind == "user" }
            val assistants = turnBlocks.filter { it.kind == "assistant" }
            if (users.size != 1 || assistants.size != 1) {
                val block = turnBlocks.first()
                throw HistoryFormatException(block.startLine, "## T" + turn, "Each turn must contain exactly one user block and one assistant block.")
            }
            val rounds = turnBlocks.filter { it.kind == "round" }.sortedBy { it.round }.map { parseRoundBlock(it) }
            messages += parseMessageBlock(users.single(), "user")
            messages += parseMessageBlock(assistants.single(), "assistant", rounds)
        }
        return HistoryDocument(session, title, createdAt, maxOf(declaredNext, maxTurn + 1), messages)
    }

    @Serializable
    private data class MessageId(val id: String)

    private fun parseSidecarFormat(
        session: String,
        title: String,
        createdAt: Long,
        declaredNext: Int,
        blocks: List<HistoryBlock>,
        metadataText: String
    ): HistoryDocument {
        val metadata = try {
            HistoryMeta.parse(metadataText, session)
        } catch (error: Exception) {
            throw HistoryFormatException(1, "history.meta.json", "Invalid sidecar metadata: " + (error.message ?: "unknown error"))
        }
        val seenIds = mutableSetOf<String>()
        val messages = mutableListOf<Message>()
        var maxTurn = 0
        for (block in blocks) {
            if (block.turn <= 0) {
                throw HistoryFormatException(block.startLine, "## T" + block.turn, "Turn number must be positive.")
            }
            if (block.kind != "user" && block.kind != "assistant") {
                throw HistoryFormatException(block.startLine, "## T" + block.turn, "Tool rounds must be stored in history.meta.json for this format.")
            }
            maxTurn = maxOf(maxTurn, block.turn)
            val idLine = block.lines.firstOrNull().orEmpty()
            val id = runCatching { json.decodeFromString<MessageId>(idLine).id }.getOrElse {
                throw HistoryFormatException(block.startLine + 1, idLine, "Expected a JSON message ID line immediately after the heading.")
            }
            if (id.isBlank()) throw HistoryFormatException(block.startLine + 1, idLine, "Message ID must not be blank.")
            if (!seenIds.add(id)) throw HistoryFormatException(block.startLine + 1, idLine, "Duplicate message ID '" + id + "'.")
            val meta = metadata.messages[id]
                ?: throw HistoryFormatException(block.startLine + 1, idLine, "Message ID '" + id + "' is missing from history.meta.json.")
            var bodyLines = block.lines.drop(1)
            while (bodyLines.lastOrNull().isNullOrEmpty()) bodyLines = bodyLines.dropLast(1)
            messages += Message(
                id = id,
                role = block.kind,
                content = unescape(bodyLines).joinToString("\n"),
                attachments = meta.attachments,
                createdAt = meta.createdAt,
                extraContent = meta.extraContent,
                turnNo = if (block.kind == "user") block.turn else null,
                toolRounds = if (block.kind == "assistant") meta.toolRounds else emptyList()
            )
        }
        return HistoryDocument(
            sessionId = session,
            title = title,
            createdAt = createdAt,
            nextTurn = maxOf(declaredNext, maxTurn + 1),
            messages = messages
        )
    }

    private fun parseMessageBlock(block: HistoryBlock, role: String, rounds: List<ToolRound> = emptyList()): Message {
        val metadataIndex = block.lines.indexOfFirst { it.trim() == "```json-meta" }
        var bodyLines = if (metadataIndex >= 0) block.lines.take(metadataIndex) else block.lines
        while (bodyLines.lastOrNull().isNullOrEmpty()) bodyLines = bodyLines.dropLast(1)
        val body = unescape(bodyLines).joinToString("\n")
        var meta: MessageMeta? = null
        if (metadataIndex >= 0) {
            val close = (metadataIndex + 1 until block.lines.size).firstOrNull { block.lines[it].trim() == "```" }
                ?: throw HistoryFormatException(block.startLine + metadataIndex, block.lines[metadataIndex], "json-meta block has no closing fence.")
            val raw = block.lines.subList(metadataIndex + 1, close).joinToString("\n")
            meta = runCatching { json.decodeFromString<MessageMeta>(raw) }.getOrElse {
                throw HistoryFormatException(block.startLine + metadataIndex + 1, raw.lineSequence().firstOrNull().orEmpty(), "Invalid json-meta block: " + it.message)
            }
        }
        return Message(
            id = meta?.id ?: "history-t" + block.turn + "-" + role,
            role = role,
            content = body,
            attachments = meta?.attachments.orEmpty(),
            createdAt = meta?.createdAt ?: System.currentTimeMillis(),
            extraContent = meta?.extraContent,
            turnNo = if (role == "user") block.turn else null,
            toolRounds = if (role == "assistant") rounds else emptyList()
        )
    }

    private fun parseRoundBlock(block: HistoryBlock): ToolRound {
        val marker = block.lines.indexOfFirst { it.trim() == "```json-round" }
        if (marker < 0) throw HistoryFormatException(block.startLine, block.lines.firstOrNull().orEmpty(), "Round block needs a json-round fenced payload.")
        val close = (marker + 1 until block.lines.size).firstOrNull { block.lines[it].trim() == "```" }
            ?: throw HistoryFormatException(block.startLine + marker, block.lines[marker], "json-round block has no closing fence.")
        return runCatching { json.decodeFromString<ToolRound>(block.lines.subList(marker + 1, close).joinToString("\n")) }
            .getOrElse { throw HistoryFormatException(block.startLine + marker + 1, block.lines.getOrNull(marker + 1).orEmpty(), "Invalid tool round JSON: " + it.message) }
    }

    /** Remove an uncommitted last turn from a legacy DataStore snapshot before seeding a file. */
    fun completedMessages(messages: List<Message>): List<Message> {
        val lastUser = messages.indexOfLast { it.role == "user" }
        if (lastUser >= 0 && messages.drop(lastUser + 1).none { it.role == "assistant" && it.content.isNotEmpty() }) {
            return messages.take(lastUser).filterNot { it.role == "assistant" && it.content.isEmpty() }
        }
        return messages.filterNot { it.role == "assistant" && it.content.isEmpty() }
    }

    fun render(document: HistoryDocument, sidecarFormat: Boolean = false): String {
        require(sessionPattern.matches(document.sessionId)) { "Invalid session ID." }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(document.createdAt))
        val out = StringBuilder()
        out.append("---\n")
        out.append("session: ").append(document.sessionId).append("\n")
        out.append("title: ").append(json.encodeToString(document.title)).append("\n")
        out.append("created: ").append(created).append("\n")
        out.append("next_turn: ").append(document.nextTurn.coerceAtLeast(1)).append("\n")
        out.append("---\n\n")
        document.repairNotes.forEach { note ->
            out.append("<!-- Repair note: ").append(note.replace("--", "—").replace("-->", "—>")).append(" -->\n")
        }
        if (document.repairNotes.isNotEmpty()) out.append("\n")
        if (sidecarFormat) {
            var currentTurn = 1
            var nextTurn = 1
            document.messages.forEach { message ->
                val turn = if (message.role == "user") {
                    (message.turnNo ?: nextTurn).also {
                        currentTurn = it
                        nextTurn = maxOf(nextTurn, it + 1)
                    }
                } else {
                    currentTurn
                }
                if (message.role != "user" && message.role != "assistant") return@forEach
                out.append("## T").append(turn).append(" ").append(message.role).append("\n")
                out.append(compactJson.encodeToString(MessageId(message.id))).append("\n")
                out.append(escape(message.content)).append("\n\n")
            }
            return out.toString()
        }
        var index = 0
        var fallbackTurn = 1
        while (index < document.messages.size) {
            val user = document.messages[index]
            if (user.role != "user") { index++; continue }
            val turn = user.turnNo ?: fallbackTurn
            fallbackTurn = maxOf(fallbackTurn + 1, turn + 1)
            val assistant = document.messages.drop(index + 1).firstOrNull { it.role == "assistant" }
            out.append("## T").append(turn).append(" user\n")
            out.append(escape(user.content)).append("\n")
            appendMeta(out, user)
            out.append("\n")
            if (assistant != null) {
                assistant.toolRounds.forEachIndexed { roundIndex, round ->
                    out.append("## T").append(turn).append(" round ").append(roundIndex + 1).append("\n")
                    round.toolCalls.forEach { call ->
                        out.append("### call ").append(call.id).append(" ").append(call.name).append("\n")
                        out.append("```json\n").append(call.arguments).append("\n```\n")
                        val result = round.results.firstOrNull { it.toolCallId == call.id }
                        out.append("### result ").append(call.id).append("\n")
                        out.append(escape(result?.compact ?: result?.content.orEmpty())).append("\n\n")
                    }
                    // Structured payload keeps extra_content and IDs intact while the sections above stay readable.
                    out.append("```json-round\n")
                    out.append(json.encodeToString(round.copy(results = round.results.map { result -> result.copy(content = result.compact ?: result.content) }))).append("\n")
                    out.append("```\n\n")
                }
                out.append("## T").append(turn).append(" assistant\n")
                out.append(escape(assistant.content)).append("\n")
                appendMeta(out, assistant)
                out.append("\n")
                index = document.messages.indexOf(assistant) + 1
            } else {
                index++
            }
        }
        return out.toString()
    }

    private fun appendMeta(out: StringBuilder, message: Message) {
        val meta = MessageMeta(message.id, message.attachments, message.createdAt, message.extraContent)
        out.append("```json-meta\n")
        out.append(json.encodeToString(meta)).append("\n")
        out.append("```\n")
    }

    /** Best-effort repair retaining valid turns; callers must back up the original before writing. */
    fun repair(
        text: String,
        fallbackSessionId: String,
        fallbackTitle: String = "New chat",
        metadataText: String? = null
    ): HistoryDocument {
        if (metadataText != null) {
            try { return parse(text, fallbackSessionId, fallbackTitle, metadataText) } catch (_: Exception) {
                // Salvage sidecar-format message blocks independently. Never send this format
                // through the legacy turn-pair salvage routine, which does not understand IDs.
            }
            val metadata = HistoryMeta.parse(metadataText, fallbackSessionId)
            val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
            val notes = mutableListOf<String>()
            val headerEnd = if (lines.firstOrNull() == "---") {
                (1 until lines.size).firstOrNull { lines[it] == "---" }
            } else null
            val base = if (headerEnd != null) {
                val headerOnly = lines.take(headerEnd + 1).joinToString("\n") + "\n"
                runCatching { parse(headerOnly, fallbackSessionId, fallbackTitle, metadataText) }.getOrElse {
                    notes += "Rebuilt invalid front matter using the fallback session details."
                    empty(fallbackSessionId, fallbackTitle)
                }
            } else {
                notes += "Rebuilt missing or invalid front matter using the fallback session details."
                empty(fallbackSessionId, fallbackTitle)
            }
            val searchStart = headerEnd?.plus(1) ?: 0
            val boundaries = (searchStart until lines.size).filter { lines[it].startsWith("## T") }
            val starts = boundaries.filter { heading.matches(lines[it]) }
            boundaries.filterNot { heading.matches(lines[it]) }.forEach {
                notes += "Skipped malformed heading at original line " + (it + 1) + ": " + lines[it]
            }
            val valid = mutableListOf<Message>()
            val seenIds = mutableSetOf<String>()
            var maxTurn = 0
            starts.forEachIndexed { index, start ->
                val match = heading.matchEntire(lines[start]) ?: return@forEachIndexed
                val turn = match.groupValues[1].toIntOrNull() ?: return@forEachIndexed
                val kind = match.groupValues[2]
                if (kind != "user" && kind != "assistant") {
                    notes += "Skipped unsupported block at original line " + (start + 1) + "."
                    return@forEachIndexed
                }
                val end = boundaries.firstOrNull { it > start } ?: lines.size
                val block = HistoryBlock(turn, kind, 0, start + 1, lines.subList(start + 1, end))
                val id = runCatching {
                    compactJson.decodeFromString<MessageId>(block.lines.firstOrNull().orEmpty()).id
                }.getOrNull()
                if (id.isNullOrBlank()) {
                    notes += "Skipped message block without a valid ID at original line " + (start + 1) + "."
                    return@forEachIndexed
                }
                if (!metadata.messages.containsKey(id)) {
                    notes += "Skipped message ID '$id' because its metadata is missing."
                    return@forEachIndexed
                }
                if (!seenIds.add(id)) {
                    notes += "Skipped duplicate message ID '$id'."
                    return@forEachIndexed
                }
                val parsed = runCatching {
                    parseSidecarFormat(base.sessionId, base.title, base.createdAt, base.nextTurn, listOf(block), metadataText)
                }.getOrElse {
                    notes += "Skipped invalid message block at original line " + (start + 1) + "."
                    return@forEachIndexed
                }
                valid += parsed.messages
                maxTurn = maxOf(maxTurn, turn)
            }
            if (notes.isEmpty()) notes += "Rebuilt the history while retaining valid ID-linked message blocks."
            return base.copy(
                messages = valid,
                nextTurn = maxOf(base.nextTurn, maxTurn + 1),
                repairNotes = notes
            )
        }
        try { return parse(text, fallbackSessionId, fallbackTitle) } catch (_: Exception) { /* salvage valid turns below */ }
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val valid = mutableListOf<Message>()
        val notes = mutableListOf<String>()
        val boundaries = lines.indices.filter { lines[it].startsWith("## T") }
        val starts = boundaries.filter { heading.matches(lines[it]) }
        boundaries.filterNot { heading.matches(lines[it]) }.forEach { notes += "Skipped malformed heading at original line " + (it + 1) + ": " + lines[it] }
        for (start in starts) {
            val turnMatch = heading.matchEntire(lines[start]) ?: continue
            val turn = turnMatch.groupValues[1]
            val kind = turnMatch.groupValues[2]
            if (kind != "user") continue
            val assistantStart = starts.firstOrNull {
                it > start &&
                    heading.matchEntire(lines[it])?.groupValues?.get(1) == turn &&
                    heading.matchEntire(lines[it])?.groupValues?.get(2) == "assistant"
            }
            if (assistantStart == null) {
                notes += "Skipped incomplete turn T" + turn + " because no assistant block was found."
                continue
            }
            val assistantEnd = boundaries.firstOrNull { it > assistantStart } ?: lines.size
            val pairText = "---\nsession: " + fallbackSessionId + "\ntitle: " + json.encodeToString(fallbackTitle) + "\ncreated: 1970-01-01T00:00:00Z\nnext_turn: 1\n---\n\n" +
                lines.subList(start, assistantEnd).joinToString("\n")
            val parsed = runCatching { parse(pairText, fallbackSessionId, fallbackTitle) }.getOrNull()
            if (parsed != null) valid += parsed.messages
            else notes += "Skipped invalid turn T" + turn + " after parser validation."
        }
        if (notes.isEmpty()) notes += "Rebuilt a valid front matter header and retained turns that passed validation."
        val base = empty(fallbackSessionId, fallbackTitle)
        return base.copy(
            messages = valid.distinctBy { it.id },
            nextTurn = (valid.mapNotNull { it.turnNo }.maxOrNull() ?: 0) + 1,
            repairNotes = notes
        )
    }

    private fun escape(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n")
        .split("\n").joinToString("\n") { line ->
            if (heading.matches(line) || line.trim() == "```json-meta" || line.trim() == "```json-round") "\\$line" else line
        }

    private fun unescape(lines: List<String>): List<String> = lines.map { line ->
        if ((line.startsWith("\\## T") && heading.matches(line.drop(1))) ||
            line.trim() == "\\```json-meta" || line.trim() == "\\```json-round"
        ) line.replaceFirst("\\", "") else line
    }

    private fun parseDate(value: String?): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(value ?: "")?.time ?: System.currentTimeMillis()
    }.getOrDefault(System.currentTimeMillis())
}
