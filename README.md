# FPilot

FPilot is a native Android AI workspace built with Kotlin and Jetpack Compose. It combines streaming chat with a folder-scoped agent that can inspect and, with explicit permission, edit Markdown files on the device.

FPilot runs locally on Android and does not require a FPilot backend. You configure the model endpoint and provide your own API key.

## What it does

- **OpenAI-compatible providers** — configure hosted or local endpoints, model IDs, headers, and provider-specific request parameters.
- **Streaming chat and bounded agent loop** — assistant text and tool progress are displayed separately; the tool loop is limited to a configured number of rounds.
- **Workspace access through Android SAF** — the agent operates relative to a folder you select. It does not receive arbitrary filesystem access.
- **Search and inspect** — list files, search filenames and Markdown content, read bounded file sections, and inspect file metadata.
- **Controlled Markdown writes** — create and exact-match edit are disabled until enabled in Settings. Every write requires a fresh confirmation, even when read-only tools are set to “always allow”. Writes are scoped to the configured subfolder, bounded by size limits, checked against the original content, and read back for verification.
- **Glassbox request control** — inspect the compiled messages, the card each message came from, source locations, SHA-256 digests, and effective tool schemas before sending.
- **Editable Markdown history** — file-backed sessions use `history.md` for visible conversation text and `history.meta.json` for message IDs, attachments, provider metadata, and tool rounds. Direct edits are detected by content digest and marked in the UI.
- **Local data** — provider settings and non-file-backed conversations stay on-device.

## Set up a workspace

1. Open **Settings → Agent workspace** and choose a folder.
2. Open **Request Control Panel** and choose **Initialize Glassbox workspace documents**. Initialization is explicit and non-destructive: existing files are preserved.
3. Review and edit the generated `.fpilot/` Markdown files in your preferred editor.
4. In **Settings → Agent workspace**, enable file creation/editing only for folders where writes are appropriate.
5. Start a chat. Use **Preview the next request** in the Request Control Panel to inspect the actual assembled messages and tool schemas.

The initializer creates this structure:

```text
<selected-folder>/
  .fpilot/
    config.md
    system.md
    request-layout.md
    memory.md
    tools/
      workspace.md
    skills/
    sessions/
    requests/
```

The current `config.md` switches are:

| Setting | Default | Effect |
| --- | --- | --- |
| `include_system` | `true` | Include `.fpilot/system.md` as workspace instructions. |
| `include_memory` | `false` | Include `.fpilot/memory.md` as durable context. |
| `include_skills` | `true` | Show the names of skills in `.fpilot/skills/`; skill content is read on demand. |
| `include_workspace_tools` | `true` | Load editable tool names/descriptions from `.fpilot/tools/workspace.md`. Tool parameter schemas remain defined by the application. |
| `save_request_snapshots` | `false` | Save the compiled request and effective tool schemas under `.fpilot/requests/`. Enable only when you want a persistent copy of potentially sensitive prompts. |

The existing **Request Control Panel** remains the authoritative ordering interface. `.fpilot/request-layout.md` documents the default order; it is not yet parsed as an executable layout file.

### Safety and limits

Workspace files are untrusted content, not trusted system instructions. Missing or malformed documents should fail safely. API keys and authorization headers are not written to workspace files or request snapshots.

Memory updates returned by the assistant are shown for approval before writing. A memory file that changed after the proposal was prepared is not overwritten. Editing a transcript changes conversation text only; it does not replay previously recorded tool calls.

There is no file-delete tool. SAF providers do not expose a portable compare-and-swap operation, so stale content is checked immediately before writing, but edits made outside FPilot at exactly the same instant cannot be protected by a filesystem-wide transaction.

## Configure a model provider

1. Open **Settings → Providers**.
2. Add a preset or configure a custom OpenAI-compatible endpoint.
3. Enter the API key and fetch models, or enter a model ID manually.
4. Select the model from the chat screen.

Examples:

| Provider | Base URL | Example model |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| OpenRouter | `https://openrouter.ai/api/v1` | Provider-specific model ID |
| DeepSeek | `https://api.deepseek.com/v1` | Provider-specific model ID |
| Ollama | `http://<host>:11434/v1` | Locally installed model |
| LM Studio | `http://<host>:1234/v1` | Currently loaded model |

The current generation adapter implements the OpenAI-compatible protocol, including its tool-call format. Native Anthropic and native Gemini adapters are not part of this milestone; those endpoints need an OpenAI-compatible shim to work here. Provider capabilities are exposed through an application-owned runtime interface so additional adapters can be added without changing the agent loop.

## Build

Requirements: JDK 17 and Android SDK.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The GitHub Actions workflow runs unit tests and builds a release APK for the configured push and pull-request events. CI creates a temporary signing key for its artifact. Such an APK is not signed with your permanent release key and should not be treated as a continuity key for future app updates.

For locally signed releases, provide all four values through Gradle properties or environment variables:

- `RELEASE_STORE_FILE` — path relative to the `app/` module.
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

When these are absent, the local release variant falls back to debug signing. Never commit a keystore or passwords to this repository.

## Implementation and licensing notes

FPilot's runtime contract and workspace features are implemented in this repository. APilot was reviewed as an architectural reference, but its GPL-3.0 code has not been copied into this MIT-licensed project. Any future code-level port requires an explicit license decision and attribution review.

## License

See [LICENSE](LICENSE).
