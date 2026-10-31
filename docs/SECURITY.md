# FPilot security model

## Trust boundaries

- The Android Storage Access Framework grant defines the only workspace root available to the file repository.
- Workspace documents, project files, tool results, skill files, and imported conversation text are untrusted data. They do not override the user's current request or application permission checks.
- The selected model provider receives the compiled prompt and any attachments the user sends. Provider credentials are used only for the network request and are excluded from workspace request snapshots.
- The user is the authority for state-changing operations. A model tool call is a proposal, not permission.

## Workspace access

All workspace paths are relative to the persisted SAF tree grant. Absolute paths, URI-like values, empty path segments, dot segments, and parent traversal are rejected. The repository resolves each path segment through the granted tree rather than converting workspace paths into arbitrary device filesystem paths.

Reads are bounded. Oversized file reads require a separate user decision before chunked content is exposed to the model. Directory scans and search results have configured depth, result, and output limits.

## Write policy

Agent file creation and editing have independent settings gates and path scope restrictions. Each mutation requires a new confirmation dialog, even when non-mutating reads are configured as always allowed. The dialog displays the target path and a bounded content or replacement preview.

Create-only writes refuse to open an existing target for truncation. Exact-match edits re-read the file before applying a change and reject stale content. Writes are read back for verification. A failed edit never restores old content over a different, concurrently changed version.

SAF does not offer a portable cross-provider compare-and-swap transaction. The immediate re-read reduces stale writes, but a separate editor or process can still race between the check and the provider write. The app must not claim filesystem-wide atomicity.

There is no delete-file tool and no shell execution.

## Prompt and transcript integrity

The effective request preview shows ordered messages, card identity, source location, and content digests. Saved Markdown snapshots are opt-in and disabled by default. They intentionally omit authorization headers and binary attachment data.

File-backed conversation text is represented in Markdown, while sidecar metadata preserves message identity, attachments, and tool-call records. Message edits are recorded with a digest of the original text. Editing a transcript never schedules or replays its historical tool calls.

Memory changes proposed by the assistant require a dedicated approval. FPilot re-reads the Memory file before the write and rejects the update if it changed since the preview.

## Provider credentials

Provider credentials remain in local application settings and are not copied into workspace Markdown or request snapshots. Native Anthropic uses the `x-api-key` header. Native Gemini currently uses the provider's API-key query parameter; network logging must not include request URLs or headers. The request logger records JSON bodies only.

Never put API keys, signing credentials, or other secrets in a workspace document, a test fixture, or a Git commit.

## Residual risks and operational guidance

- Read and review untrusted project content before enabling agent writes.
- Review tool approvals carefully; an injected prompt can ask the model to request a legitimate tool.
- Use snapshots only when the workspace is appropriate for retaining copies of prompts.
- Verify the SAF provider's behavior on the target device; document providers differ in rename, write, and permission semantics.
- If a signing keystore was ever committed to a public repository, removing the file from the current branch does not erase public Git history. Discontinue and rotate that signing key.
- Before adding shell, MCP, subagents, network retrieval, scheduled tasks, or RAG, define a separate threat model and explicit permission model for each capability.
