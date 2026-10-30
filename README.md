# FPilot

FPilot is an Android AI chat app built with Kotlin and Jetpack Compose. It connects to OpenAI-compatible language-model APIs and adds an experimental agent workflow for exploring and editing Markdown files in a folder you choose on your device.

The app runs locally on Android and does not require a FPilot backend. You bring your own provider endpoint and API key.

## Highlights

- **OpenAI-compatible providers** — configure a base URL, API key, and model for compatible hosted or local endpoints.
- **Streaming chat** — ordinary conversations stream responses to the chat UI.
- **Agentic Markdown workspace (experimental)** — choose a folder using Android's system folder picker. The agent can list files, search file names and Markdown contents, inspect file information, and read relevant files as needed.
- **Opt-in file edits** — file creation and exact-match edits are disabled by default. Enable **Allow file modifications** in Settings to expose those tools to the agent. The app does not currently provide a delete-file tool.
- **Bounded tool loop** — the model can request tools, inspect their results, and make further tool calls within a configured round limit. Tool calling depends on the selected model/provider supporting the OpenAI-compatible tools format.
- **Scoped storage access** — workspace operations use Android's Storage Access Framework and operate relative to the selected folder.
- **Markdown-friendly workflow** — intended for folders of notes and documents, including projects that use Markdown links and instruction files. The agent should read only the relevant context instead of sending the entire folder to the model.
- **Local app preferences and conversations** — settings and chat data are stored on-device.

## Agent workspace setup

1. Open **Settings → Agent workspace**.
2. Choose the folder containing your Markdown notes or documents.
3. Start a chat and ask the model to find, read, or summarize relevant files.
4. To permit file creation or edits, explicitly enable **Allow file modifications**. It is off by default.
5. Review important changes in your normal Markdown editor. The agent's edit tool uses an exact-match target and attempts to read back the result for verification.

**Current limitations:** this feature is experimental. Tool-call behavior varies by model and provider; the agent workflow is not streamed round-by-round; there is no delete tool or per-edit approval dialog yet. Always review important changes. Avoid enabling writes for folders where the agent should only read.

## Provider configuration

1. Open **Settings → Providers**.
2. Add a provider, choose a preset or configure a custom endpoint, and enter your API key.
3. Select the API protocol supported by the endpoint: **Chat Completions** or **Responses**.
4. Fetch available models or enter a model ID manually.
5. Select the model from the chat screen.

Use **Responses** for GPT-5.6 Sol and other endpoints that require the Responses API. Existing provider configurations default to Chat Completions so older OpenAI-compatible services keep working. In Responses mode, conversation history replays the original response items, including encrypted reasoning data and function-call outputs. The app does not send the Chat Completions temperature setting in Responses mode; configure a JSON `reasoning` object or another supported parameter in Advanced → Extra body when the selected model/provider requires it.

Examples of compatible base URLs:

| Provider | Base URL | Example model |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| OpenRouter | `https://openrouter.ai/api/v1` | Provider-specific model ID |
| DeepSeek | `https://api.deepseek.com/v1` | Provider-specific model ID |
| Ollama | `http://<host>:11434/v1` | A model installed locally |
| LM Studio | `http://<host>:1234/v1` | The currently loaded model |

For Ollama or LM Studio on a physical Android device, use your computer's LAN IP address rather than the emulator-only address.

## Build

The repository includes a GitHub Actions workflow intended to run unit tests and build debug and release APKs on pushes to `main` and `agent-fs`, and on pull requests targeting `main`.

Requirements for a local build: JDK 17 and Android SDK.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Debug and release APKs are uploaded as workflow artifacts when the workflow completes successfully. A successful build has not been verified for every commit on the experimental `agent-fs` branch; check the Actions run for the exact commit before installing or distributing an APK.

## Project structure

```
app/src/main/kotlin/com/miniichat/
├── api/        # OpenAI-compatible API client and tool-call protocol
├── agent/      # Agent loop and tool registry
├── files/      # SAF workspace access and Markdown file tools
├── data/       # Settings and persisted chat data
└── ui/         # Compose chat, provider, and settings screens
```

## License

See [LICENSE](LICENSE).
