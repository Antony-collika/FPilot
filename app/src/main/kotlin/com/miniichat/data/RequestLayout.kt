package com.miniichat.data

import kotlinx.serialization.Serializable

/** The kinds of card that can appear in the Request Control Panel. */
object CardRole {
    const val METADATA = "metadata"
    const val SYSTEM = "system"
    const val FREE = "free"
    const val DIRECTORY_TREE = "directory_tree"
    const val HISTORY = "history"
    const val LAST_TURN = "last_turn"

    /** Cards that always exist and can be neither deleted nor switched off. */
    val FIXED = setOf(METADATA, HISTORY, LAST_TURN)
}

/**
 * One card of the request. The position of a card in the list is its position in the request.
 *
 * - [roleLabel]: the role name sent to the model. Only used by free cards.
 * - [source]: "text" (typed in the app) or "file" (a file chosen on the phone).
 * - [wrapTag]: if set, the text is sent as [TAG] ... [/TAG].
 * - [treeDepth]: how deep the directory tree scan goes (Directory Tree card only).
 */
@Serializable
data class RequestCard(
    val id: String,
    val role: String,
    val title: String = "",
    val enabled: Boolean = true,
    val roleLabel: String = "system",
    val source: String = "text",
    val text: String = "",
    val fileUri: String = "",
    val fileName: String = "",
    val wrapTag: String = "",
    val treeDepth: Int = 2
)

@Serializable
data class RequestLayout(val cards: List<RequestCard> = emptyList())

/** The name shown to the user for a card (also used in error messages). */
fun RequestCard.displayName(): String {
    if (title.isNotBlank()) return title
    return when (role) {
        CardRole.METADATA -> "Metadata"
        CardRole.SYSTEM -> "System"
        CardRole.FREE -> "Free card"
        CardRole.DIRECTORY_TREE -> "Directory Tree"
        CardRole.HISTORY -> "History"
        CardRole.LAST_TURN -> "Last turn"
        else -> role
    }
}

/**
 * Returns a message describing the first problem with the card list, or null if it is valid.
 * Rules: Metadata is first; exactly one Metadata, History and Last turn card;
 * History must be above Last turn.
 */
fun RequestLayout.validationError(): String? {
    if (cards.isEmpty()) return "The request has no cards."
    if (cards.first().role != CardRole.METADATA) return "The Metadata card must be the first card."
    for (role in CardRole.FIXED) {
        val count = cards.count { it.role == role }
        if (count != 1) return "The request needs exactly one ${role.replace('_', ' ')} card, but has $count."
    }
    val historyIndex = cards.indexOfFirst { it.role == CardRole.HISTORY }
    val lastTurnIndex = cards.indexOfFirst { it.role == CardRole.LAST_TURN }
    if (historyIndex > lastTurnIndex) return "The History card must be above the Last turn card."
    return null
}

/** The layout used for a fresh start or after "Reset". */
fun defaultLayout(newId: () -> String): RequestLayout = RequestLayout(
    listOf(
        RequestCard(id = newId(), role = CardRole.METADATA),
        RequestCard(id = newId(), role = CardRole.SYSTEM, text = "You are a helpful assistant."),
        RequestCard(id = newId(), role = CardRole.HISTORY),
        RequestCard(id = newId(), role = CardRole.LAST_TURN)
    )
)

/**
 * Builds the layout that reproduces exactly what the app sent before the panel existed,
 * from the old Settings fields. Card ids are fixed on purpose, so they stay the same
 * every time this function runs.
 * Order: System, Directory Tree, Skills, History, Last turn.
 */
fun layoutFromSettings(s: AppSettings): RequestLayout {
    val cards = mutableListOf<RequestCard>()
    cards += RequestCard(id = "legacy-metadata", role = CardRole.METADATA)
    if (s.systemPromptSource == "file" || s.systemPrompt.isNotBlank()) {
        cards += RequestCard(
            id = "legacy-system",
            role = CardRole.SYSTEM,
            source = s.systemPromptSource,
            text = s.systemPrompt,
            fileUri = s.systemPromptFileUri,
            fileName = s.systemPromptFileName
        )
    }
    if (s.workspaceTreeUri.isNotBlank() && s.directoryTreeEnabled) {
        cards += RequestCard(
            id = "legacy-tree",
            role = CardRole.DIRECTORY_TREE,
            treeDepth = s.directoryTreeDepth.coerceIn(1, 8)
        )
    }
    if (s.skillsEnabled && (s.skillsSource == "file" || s.skillsText.isNotBlank())) {
        cards += RequestCard(
            id = "legacy-skills",
            role = CardRole.SYSTEM,
            title = "Skills",
            source = s.skillsSource,
            text = s.skillsText,
            fileUri = s.skillsFileUri,
            fileName = s.skillsFileName,
            wrapTag = "SKILLS"
        )
    }
    cards += RequestCard(id = "legacy-history", role = CardRole.HISTORY)
    cards += RequestCard(id = "legacy-last-turn", role = CardRole.LAST_TURN)
    return RequestLayout(cards)
}

/** Where a newly added card goes: system and free cards above History, a tree above Last turn. */
fun RequestLayout.insertionIndexFor(role: String): Int {
    val target = when (role) {
        CardRole.DIRECTORY_TREE -> CardRole.LAST_TURN
        else -> CardRole.HISTORY
    }
    val index = cards.indexOfFirst { it.role == target }
    return if (index >= 0) index else cards.size
}

/**
 * Moves a card up (delta = -1) or down (delta = +1).
 * Returns null when the move is not allowed (Metadata stays first, History stays above Last turn).
 */
fun RequestLayout.moved(cardId: String, delta: Int): RequestLayout? {
    val from = cards.indexOfFirst { it.id == cardId }
    if (from <= 0) return null
    val to = from + delta
    if (to < 1 || to > cards.lastIndex) return null
    val list = cards.toMutableList()
    val card = list.removeAt(from)
    list.add(to, card)
    val result = RequestLayout(list)
    return if (result.validationError() == null) result else null
}
