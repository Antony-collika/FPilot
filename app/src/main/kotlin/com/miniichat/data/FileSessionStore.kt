package com.miniichat.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Creates a private session directory under a user-selected Storage tree using Android's
 * Storage Access Framework. Existing memory.md files are never rewritten, so hand edits persist.
 */
class FileSessionStore(
    private val resolver: ContentResolver
) : SessionFilesRepository {

    private data class Child(val id: String, val name: String, val mimeType: String) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private val BRANCH_FILE = Regex("^history_branch_([0-9]{2,})\\.md$")

    private fun branchIdFromFile(fileName: String): String {
        val number = BRANCH_FILE.matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("Invalid history branch file name: $fileName")
        return "branch_" + number.toString().padStart(2, '0')
    }

    override suspend fun ensureSession(
        storageTreeUri: String,
        sessionId: String,
        useSidecarForNewSession: Boolean
    ): SessionFiles = withContext(Dispatchers.IO) {
        require(storageTreeUri.isNotBlank()) { "Choose a Storage folder first." }
        require(SESSION_ID.matches(sessionId)) { "The session ID is invalid." }

        val treeUri = Uri.parse(storageTreeUri)
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            ?: throw IOException("Could not open the selected Storage folder.")
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val sessions = findOrCreateDirectory(treeUri, root, "sessions")
        val existing = listChildren(treeUri, sessions)
            .firstOrNull { it.isDirectory && it.name.endsWith("_$sessionId") }
        val folder = if (existing != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.id)
        } else {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            findOrCreateDirectory(treeUri, sessions, "${date}_$sessionId")
        }
        val folderName = queryDisplayName(folder) ?: "session_$sessionId"
        val memory = findOrCreateFile(treeUri, folder, "memory.md", MemoryDoc.template())
        var children = listChildren(treeUri, folder)
        var metaChild = children.firstOrNull { !it.isDirectory && it.name == "history.meta.json" }
        var metadataText = metaChild?.let {
            readText(DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id).toString())
        }
        var metadata = metadataText?.let { runCatching { HistoryMeta.parse(it, sessionId) }.getOrNull() }
        if (metadata == null && metaChild != null && !metadataText.isNullOrBlank()) {
            // Preserve corrupt JSON before the application rebuilds it from Markdown.
            runCatching {
                backupFile(DocumentsContract.buildDocumentUriUsingTree(treeUri, metaChild!!.id).toString())
            }
        }

        // Migrate the former history.md format into the first named branch. Keep the source
        // file as a recoverable backup; all future reads and writes use history_branch_NN.md.
        var branchChildren = children.filter { !it.isDirectory && BRANCH_FILE.matches(it.name) }
        if (branchChildren.isEmpty()) {
            val legacy = children.firstOrNull { !it.isDirectory && it.name == "history.md" }
            val branchUri = findOrCreateFile(treeUri, folder, "history_branch_01.md", "")
            val legacyText = legacy?.let {
                readText(DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id).toString())
            }.orEmpty()
            val parsedLegacy = if (legacyText.isBlank()) {
                HistoryMd.empty(sessionId, "New chat")
            } else {
                runCatching {
                    HistoryMd.parse(legacyText, sessionId, "New chat", metadataText, "branch_01")
                }.getOrElse {
                    // If metadata is corrupt, parse the Markdown on its own. This supports both
                    // legacy inline json-meta and the ID-linked sidecar format.
                    runCatching {
                        HistoryMd.parse(legacyText, sessionId, "New chat", null, "branch_01")
                    }.getOrNull()
                }
            }
            val recovered = (parsedLegacy ?: HistoryMd.empty(sessionId, "New chat")).copy(branchId = "branch_01")
            if (parsedLegacy != null || legacyText.isBlank()) {
                writeText(branchUri.toString(), HistoryMd.render(recovered, sidecarFormat = true))
            } else {
                // Preserve malformed Markdown verbatim in the new active branch so Repair can
                // salvage it. The original history.md is retained as an additional backup.
                writeText(branchUri.toString(), legacyText)
            }
            val branch = HistoryBranchMetadata(
                id = "branch_01",
                file = "history_branch_01.md",
                parentBranchId = null,
                createdAt = recovered.createdAt
            )
            if (metaChild == null) {
                metaChild = findOrCreateFile(
                    treeUri, folder, "history.meta.json", HistoryMeta.empty(sessionId), "application/json"
                )
            }
            val builtMeta = runCatching {
                HistoryMeta.renderForBranch(
                    metadataText.orEmpty(), recovered, "branch_01", listOf(branch), 2
                )
            }.getOrElse {
                HistoryMeta.renderForBranch("", recovered, "branch_01", listOf(branch), 2)
            }
            val metaUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, metaChild!!.id)
            writeText(metaUri.toString(), builtMeta)
            metadataText = builtMeta
            metadata = HistoryMeta.parse(builtMeta, sessionId)
            children = listChildren(treeUri, folder)
            branchChildren = children.filter { !it.isDirectory && BRANCH_FILE.matches(it.name) }
        } else if (metadata == null) {
            // If JSON was removed or corrupted, recover branch identities and message IDs from
            // Markdown. Cache-backed fields are merged by ChatViewModel before the next write.
            val ordered = branchChildren.sortedBy {
                BRANCH_FILE.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
            }
            val activeChild = ordered.lastOrNull() ?: throw IOException("No history branch Markdown file was found.")
            val activeUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, activeChild.id)
            val activeId = branchIdFromFile(activeChild.name)
            val recovered = runCatching {
                HistoryMd.parse(readText(activeUri.toString()), sessionId, "New chat", null, activeId)
            }.getOrElse { HistoryMd.empty(sessionId, "New chat").copy(branchId = activeId) }
            val branches = ordered.map { child ->
                val id = branchIdFromFile(child.name)
                HistoryBranchMetadata(id, child.name, null, System.currentTimeMillis())
            }
            val next = (branches.mapNotNull { it.id.removePrefix("branch_").toIntOrNull() }.maxOrNull() ?: 1) + 1
            val rebuilt = HistoryMeta.renderForBranch("", recovered, activeId, branches, next)
            if (metaChild == null) {
                val uri = findOrCreateFile(treeUri, folder, "history.meta.json", rebuilt, "application/json")
                metaChild = Child(DocumentsContract.getDocumentId(uri), "history.meta.json", "application/json")
            } else {
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, metaChild!!.id)
                writeText(uri.toString(), rebuilt)
            }
            metadataText = rebuilt
            metadata = HistoryMeta.parse(rebuilt, sessionId)
        }

        children = listChildren(treeUri, folder)
        branchChildren = children.filter { !it.isDirectory && BRANCH_FILE.matches(it.name) }
        if (branchChildren.isEmpty()) {
            throw IOException("No history_branch_NN.md files are available.")
        }
        // Avoid leaving a misleading history.md next to the real branch files. Once a
        // branch transcript and metadata exist, archive the former canonical file.
        val legacyHistory = children.firstOrNull { !it.isDirectory && it.name == "history.md" }
        if (legacyHistory != null) {
            val legacyUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, legacyHistory.id)
            val renamed = runCatching {
                DocumentsContract.renameDocument(resolver, legacyUri, "history_legacy_backup.md")
            }.getOrNull()
            if (renamed == null) {
                val backedUp = runCatching { backupFile(legacyUri.toString()) }.isSuccess
                if (backedUp) runCatching { DocumentsContract.deleteDocument(resolver, legacyUri) }
            }
            children = listChildren(treeUri, folder)
        }
        val currentMeta = metadata ?: run {
            val latestText = metaChild?.let {
                readText(DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id).toString())
            } ?: throw IOException("Could not create history.meta.json.")
            HistoryMeta.parse(latestText, sessionId)
        }
        val existingById = currentMeta.branches.associateBy { it.id }
        val diskById = branchChildren.associateBy { branchIdFromFile(it.name) }
        val reconciledBranches = diskById.map { (id, child) ->
            val existingBranch = existingById[id]
            existingBranch?.copy(
                file = child.name,
                parentBranchId = existingBranch.parentBranchId?.takeIf { it in diskById.keys }
            ) ?: HistoryBranchMetadata(
                id = id,
                file = child.name,
                parentBranchId = currentMeta.activeBranchId.takeIf { it != id && it in diskById.keys },
                createdAt = System.currentTimeMillis()
            )
        }.sortedBy { it.id.removePrefix("branch_").toIntOrNull() ?: Int.MAX_VALUE }
        val activeId = currentMeta.activeBranchId.takeIf { it in diskById }
            ?: reconciledBranches.lastOrNull()?.id
            ?: throw IOException("No valid history branch is available.")
        val maxBranchNumber = reconciledBranches.mapNotNull {
            it.id.removePrefix("branch_").toIntOrNull()
        }.maxOrNull() ?: 1
        val normalized = currentMeta.copy(
            version = 2,
            activeBranchId = activeId,
            branches = reconciledBranches,
            nextBranchNumber = maxOf(currentMeta.nextBranchNumber, maxBranchNumber + 1, 2)
        )
        val metaUri = metaChild?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id) }
            ?: findOrCreateFile(treeUri, folder, "history.meta.json", HistoryMeta.empty(sessionId), "application/json")
        val activeChild = diskById[activeId] ?: throw IOException("The active history branch file is missing.")
        val activeUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, activeChild.id)
        // Re-read every branch, not just the selected one. This makes edits to any
        // history_branch_NN.md visible in the sidecar as soon as the app resumes.
        var synchronizedMetadata = metadataText.orEmpty()
        for (child in branchChildren.sortedBy {
            branchIdFromFile(it.name).removePrefix("branch_").toIntOrNull() ?: Int.MAX_VALUE
        }) {
            val branchId = branchIdFromFile(child.name)
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
            val parsed = runCatching {
                HistoryMd.parse(
                    readText(uri.toString()), sessionId, "New chat", synchronizedMetadata, branchId
                ).copy(branchId = branchId)
            }.getOrNull()
            synchronizedMetadata = if (parsed != null) {
                HistoryMeta.syncBranchDocument(
                    synchronizedMetadata, sessionId, parsed, branchId,
                    reconciledBranches, normalized.nextBranchNumber
                )
            } else {
                // Keep prior branch membership when the Markdown is malformed; Repair must
                // distinguish a damaged file from a deliberately emptied branch.
                HistoryMeta.reconcileBranches(
                    synchronizedMetadata, sessionId, activeId, reconciledBranches, normalized.nextBranchNumber
                )
            }
        }
        val finalMeta = HistoryMeta.parse(synchronizedMetadata, sessionId)
        if (metadataText != synchronizedMetadata || currentMeta.version != 2 ||
            currentMeta.activeBranchId != activeId || currentMeta.branches != reconciledBranches ||
            finalMeta.activeBranchId != activeId) {
            writeText(metaUri.toString(), synchronizedMetadata)
        }

        SessionFiles(
            sessionId = sessionId,
            directoryName = folderName,
            historyUri = activeUri.toString(),
            memoryUri = memory.toString(),
            historyMetaUri = metaUri.toString(),
            directoryUri = folder.toString(),
            storageTreeUri = storageTreeUri,
            activeBranchId = finalMeta.activeBranchId,
            branches = finalMeta.branches,
            nextBranchNumber = finalMeta.nextBranchNumber
        )
    }

    /** Read a session Markdown file using the persisted Storage Access Framework URI. */
    override suspend fun readText(uriString: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IOException("Could not read the session file.")
    }

    /** Replace a session Markdown file. Callers must re-read it immediately before writing. */
    override suspend fun writeText(uriString: String, text: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val output = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Could not write the session file.")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }
    override suspend fun readHistory(files: SessionFiles, fallbackTitle: String): HistoryDocument {
        val markdown = readText(files.historyUri)
        val metadataText = files.historyMetaUri?.let { readText(it) }
        val document = HistoryMd.parse(
            markdown, files.sessionId, fallbackTitle, metadataText, files.activeBranchId
        ).copy(branchId = files.activeBranchId)
        // Any new message IDs written directly in Markdown are adopted into the sidecar.
        // Body edits never require touching JSON or manually reproducing metadata.
        syncHistoryMetadata(files, document)
        return document
    }

    override suspend fun syncHistoryMetadata(files: SessionFiles, document: HistoryDocument) {
        val metadataUri = files.historyMetaUri ?: return
        val previousText = readText(metadataUri)
        val rendered = HistoryMeta.syncBranchDocument(
            previousText,
            files.sessionId,
            document.copy(branchId = files.activeBranchId),
            files.activeBranchId,
            files.branches,
            files.nextBranchNumber
        )
        HistoryMeta.parse(rendered, files.sessionId)
        if (rendered != previousText) {
            try {
                writeText(metadataUri, rendered)
            } catch (failure: Exception) {
                runCatching { writeText(metadataUri, previousText) }
                    .exceptionOrNull()?.let { failure.addSuppressed(it) }
                throw failure
            }
        }
    }

    override suspend fun switchHistoryBranch(files: SessionFiles, branchId: String): SessionFiles {
        val metadataUri = files.historyMetaUri ?: throw IOException("This session has no branch metadata.")
        val metadataText = readText(metadataUri)
        val metadata = HistoryMeta.parse(metadataText, files.sessionId)
        val branch = metadata.branches.firstOrNull { it.id == branchId }
            ?: throw IOException("The requested history branch does not exist.")
        val treeUri = Uri.parse(files.storageTreeUri)
        val folder = Uri.parse(files.directoryUri)
        val child = listChildren(treeUri, folder).firstOrNull { !it.isDirectory && it.name == branch.file }
            ?: throw IOException("The Markdown file for $branchId is missing.")
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
        val updated = HistoryMeta.setActiveBranch(metadataText, files.sessionId, branchId)
        try {
            writeText(metadataUri, updated)
        } catch (failure: Exception) {
            runCatching { writeText(metadataUri, metadataText) }
                .exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
        return files.copy(
            historyUri = uri.toString(),
            activeBranchId = branchId,
            branches = metadata.branches,
            nextBranchNumber = metadata.nextBranchNumber
        )
    }

    override suspend fun createHistoryBranch(
        files: SessionFiles,
        document: HistoryDocument,
        parentBranchId: String?
    ): SessionFiles {
        val metadataUri = files.historyMetaUri ?: throw IOException("This session has no branch metadata.")
        val metadataText = readText(metadataUri)
        val metadata = HistoryMeta.parse(metadataText, files.sessionId)
        val treeUri = Uri.parse(files.storageTreeUri)
        val folder = Uri.parse(files.directoryUri)
        val children = listChildren(treeUri, folder)
        var number = metadata.nextBranchNumber
        var branchId: String
        var fileName: String
        do {
            branchId = "branch_" + number.toString().padStart(2, '0')
            fileName = "history_${branchId}.md"
            number++
        } while (children.any { !it.isDirectory && it.name == fileName })
        val branch = HistoryBranchMetadata(
            id = branchId,
            file = fileName,
            parentBranchId = parentBranchId?.takeIf { it in metadata.branches.map(HistoryBranchMetadata::id) },
            createdAt = System.currentTimeMillis()
        )
        val branches = (metadata.branches + branch).sortedBy {
            it.id.removePrefix("branch_").toIntOrNull() ?: Int.MAX_VALUE
        }
        val nextNumber = number
        val branchDocument = document.copy(branchId = branchId)
        val markdown = HistoryMd.render(branchDocument, sidecarFormat = true)
        val updatedMetadata = HistoryMeta.renderForBranch(
            metadataText, branchDocument, branchId, branches, nextNumber
        )
        HistoryMd.parse(markdown, files.sessionId, branchDocument.title, updatedMetadata, branchId)
        val branchUri = DocumentsContract.createDocument(resolver, folder, "text/markdown", fileName)
            ?: throw IOException("Could not create $fileName.")
        try {
            writeText(branchUri.toString(), markdown)
            writeText(metadataUri, updatedMetadata)
        } catch (failure: Exception) {
            runCatching { writeText(metadataUri, metadataText) }
                .exceptionOrNull()?.let { failure.addSuppressed(it) }
            runCatching { DocumentsContract.deleteDocument(resolver, branchUri) }
                .exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
        return files.copy(
            historyUri = branchUri.toString(),
            activeBranchId = branchId,
            branches = branches,
            nextBranchNumber = nextNumber
        )
    }


    /** Create a sibling backup before any repair operation. */
    override suspend fun backupFile(uriString: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val originalName = queryDisplayName(uri) ?: throw IOException("Could not determine the file name for backup.")
        val treeUri = uri
        val documentId = DocumentsContract.getDocumentId(uri)
        val parentId = documentId.substringBeforeLast('/', "")
        if (parentId.isBlank()) throw IOException("Could not determine the parent folder for backup.")
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val base = originalName.removeSuffix(".md")
        val backupName = "$base.broken-$stamp.md"
        val backup = DocumentsContract.createDocument(resolver, parent, "text/markdown", backupName)
            ?: throw IOException("Could not create the backup file.")
        val original = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not read the original file for backup.")
        resolver.openOutputStream(backup, "wt")?.use { it.write(original) }
            ?: throw IOException("Could not write the backup file.")
        backup.toString()
    }

    /** Repairs a Memory file while retaining content under recognized section headings. */
    override suspend fun repairMemory(uriString: String): String {
        val original = readText(uriString)
        val backup = backupFile(uriString)
        val repaired = runCatching { MemoryDoc.parse(original) }.getOrElse {
            val sections = MemoryDoc.HEADINGS.associateWith { mutableListOf<String>() }
            val preamble = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            var current: String? = null
            for (line in original.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
                if (line.startsWith("## ")) {
                    val name = line.removePrefix("## ").trim()
                    if (name in sections) current = name
                    else {
                        skipped += name
                        current = null
                    }
                } else if (current != null) {
                    if (line.isNotBlank()) sections.getValue(current!!).add(line.trimEnd())
                } else if (line.isNotBlank()) {
                    preamble += line
                }
            }
            preamble += skipped.map { name ->
                "<!-- Repair note: skipped unknown Memory heading: " + name.replace("--", "—") + " -->"
            }
            MemoryDoc(preamble.joinToString("\n"), sections.mapValues { it.value.toList() })
        }
        writeText(uriString, repaired.render())
        return backup
    }

    /** Repairs history only after successfully backing up the original bytes. */
    override suspend fun repairHistory(uriString: String, sessionId: String, title: String): String {
        val original = readText(uriString)
        val repaired = HistoryMd.repair(original, sessionId, title)
        val backup = backupFile(uriString)
        writeText(uriString, HistoryMd.render(repaired))
        return backup
    }

    override suspend fun createHistoryTemplate(uriString: String, sessionId: String, title: String) {
        writeText(uriString, HistoryMd.render(HistoryMd.empty(sessionId, title)))
    }

    private fun findOrCreateDirectory(treeUri: Uri, parent: Uri, name: String): Uri {
        val found = listChildren(treeUri, parent).firstOrNull { it.name == name }
        if (found != null) {
            if (!found.isDirectory) throw IOException("\"$name\" exists but is not a folder.")
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, found.id)
        }
        return DocumentsContract.createDocument(
            resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: throw IOException("Could not create the folder \"$name\".")
    }

    private fun findOrCreateFile(treeUri: Uri, parent: Uri, name: String, initialText: String, mimeType: String = "text/markdown"): Uri {
        val found = listChildren(treeUri, parent).firstOrNull { it.name == name }
        if (found != null) {
            if (found.isDirectory) throw IOException("\"$name\" exists but is a folder.")
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, found.id)
        }
        val uri = DocumentsContract.createDocument(resolver, parent, mimeType, name)
            ?: throw IOException("Could not create the file \"$name\".")
        val output = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Could not write the file \"$name\".")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(initialText) }
        return uri
    }

    private fun listChildren(treeUri: Uri, parent: Uri): List<Child> {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val result = mutableListOf<Child>()
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                result += Child(
                    id = cursor.getString(idIndex),
                    name = cursor.getString(nameIndex).orEmpty(),
                    mimeType = cursor.getString(mimeIndex).orEmpty()
                )
            }
        } ?: throw IOException("Could not list the selected Storage folder.")
        return result
    }

    private fun queryDisplayName(uri: Uri): String? =
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    companion object {
        private val SESSION_ID = Regex("[0-9a-f]{8}")

        /** Eight lowercase hex characters, generated independently of chat message IDs. */
        fun newSessionId(): String =
            UUID.randomUUID().toString().replace("-", "").take(8).lowercase(Locale.ROOT)
    }
}
