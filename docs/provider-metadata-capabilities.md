# Provider metadata and capability references

FPilot separates metadata configuration from capability guidance. The built-in capability map is advisory: an API endpoint may accept a field while a particular model, model version, route, or API protocol rejects it or ignores it. Users can enter custom metadata at the global, provider, or exact-model level. The request builder preserves JSON value types and expands dotted paths (for example, `reasoning.effort`) into nested JSON objects.

## Precedence

The final request body resolves metadata in this order, from lowest to highest priority:

1. Provider Extra Body.
2. Global metadata.
3. Provider metadata.
4. Exact model metadata.
5. Active Request Control Panel controls.

A missing key inherits from the lower layer. `@omit` and `@disabled` explicitly remove an inherited field; JSON `null`, `false`, zero, empty strings, arrays, and objects remain values and are not omission markers. Core transport keys (`model`, `messages`, `input`, `stream`, and `tools`) cannot be overridden by metadata.

## Provider documentation

| Provider | Official documentation |
| --- | --- |
| OpenAI Chat Completions | https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create |
| OpenAI Responses | https://developers.openai.com/api/reference/resources/responses/methods/create |
| OpenRouter | https://openrouter.ai/docs/api-reference/parameters |
| DeepSeek Chat Completions | https://api-docs.deepseek.com/api/create-chat-completion |
| DeepSeek Responses | https://api-docs.deepseek.com/api/create-response/ and https://api-docs.deepseek.com/guides/responses_api/ |
| Groq | https://console.groq.com/docs/api-reference and https://console.groq.com/docs/reasoning |
| Mistral | https://docs.mistral.ai/api/endpoint/chat |
| Together AI | https://docs.together.ai/reference/chat-completions |
| Gemini OpenAI-compatible endpoint | https://ai.google.dev/gemini-api/docs/openai |
| SiliconFlow | https://docs.siliconflow.cn/docs/api/chat-completions-post |
| Ollama | https://github.com/ollama/ollama/blob/main/docs/api/openai-compatibility.mdx |
| LM Studio Chat Completions | https://lmstudio.ai/docs/developer/openai-compat |
| LM Studio Responses | https://lmstudio.ai/docs/developer/openai-compat/responses |

## Interpretation

- `Documented` means the field appears in the provider's endpoint documentation for that protocol.
- `Model-dependent` means model support, model routing, or model settings can change acceptance or behavior.
- `Unverified` means FPilot has no provider/protocol-specific rule; the user can still send the field through advanced metadata.
- `Not applicable to this protocol` means the catalog field is associated with the other API protocol; it does not prevent sending a provider-specific field manually.

The registry uses the documentation for the selected protocol where separate endpoint references exist. It is not a live capability probe. Recheck linked documentation and test the exact model before relying on an optional parameter in production.
