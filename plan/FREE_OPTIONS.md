# Free AI options (alternatives to Gemini)

LibreLookAI calls two model classes. Free-alternative availability is night-and-day
between them, so they are tracked separately.

| App constant | Model | Used for |
|---|---|---|
| `CLASSIFY_MODEL` | `gemini-3-flash-preview` | tagging, outfit suggestions, trends, text gen |
| `BG_MODEL` | `gemini-3.1-flash-image-preview` | background removal, try-on, compositing |

## Gemini free tier itself
- **Text (`gemini-3-flash-preview`)** — free tier available (text Flash family). Limits cut
  hard in Dec 2025: ~10 RPM / ~500–1,500 RPD / 250k TPM, resetting midnight Pacific.
- **Image (`gemini-3.1-flash-image-preview`)** — **no free tier**. The `*-flash-image`
  generation models show "Not available" in the Free Tier column for all serving modes; a
  billing-enabled key is required. So a free BYOK key covers only the text features; every
  image feature (bg removal, try-on, compositing) fails on a free key.
- Both are `-preview` models — availability/eligibility can shift; AI Studio → Rate limits
  is the authoritative per-project answer.

## 1. Text + vision (tagging, outfit suggestions, trends)
Plenty of genuinely free options, mostly OpenAI-compatible (small client swap). Clothing
classification needs **vision** (image-in), so target the multimodal ones.

| Provider | Free limits (2026) | Notes |
|---|---|---|
| **Groq** | ~30 RPM, Llama 3.3 70B | Fastest; Llama Vision for image input |
| **Cerebras** | ~60k TPM, ~1,700 req/day | Highest daily throughput, very fast |
| **OpenRouter** | 20 RPM, 50/day (1,000/day w/ $10 balance) | One key → Llama, Mistral, Gemma, Qwen incl. vision |
| **Mistral** | all models, ~1B tokens/mo @ 2 RPM | Pixtral handles image input |
| **GitHub Models** | 100+ models, generous dev limits | Free for GitHub accounts |
| **Ollama (local)** | unlimited, on-device | Qwen2.5-VL / Llama-Vision on phone or server — zero cost, no quota |

Vision-capable picks: Groq/OpenRouter Llama-Vision, Mistral Pixtral, local Qwen-VL.

## 2. Image generation / editing (bg removal, try-on, compositing)
The hard part. Gemini's "nano-banana" instruction editing is genuinely strong; free *API*
options are scarce and lower-fidelity.

- **Cloudflare Workers AI** — best free tier (~10,000 "neurons"/day). Hosts **FLUX.2 [klein]**,
  which does both generation and instruction-based editing. Closest free analog.
- **Hugging Face Inference** — free, rate-limited; hosts FLUX.1 [schnell], SD 3.5,
  **Qwen-Image-Edit**, **FLUX Kontext** (instruction edits, e.g. garment swap).
- **Pollinations.ai** — free, no-key gen; weak for controlled edits.

### Virtual try-on specifically (no good free hosted API)
- **IDM-VTON / OOTDiffusion** — open source (Apache/research). Self-host (free, needs GPU) or
  run on **Replicate ~$0.025/run** (cheap, not free).
- **FLUX Kontext / Qwen-Image-Edit** as instruction editing (~$0.03–0.04/op) — cheaper than
  Gemini image, but paid.

## Practical takeaway for this app
- **Background removal doesn't need a paid generative model** — the app already ships an
  on-device segmenter (Magic Touch / `prefer-on-device-bg`). Default to it and the most
  expensive Gemini path largely disappears.
- **Realistic free-ish stack:** Cerebras/Groq/OpenRouter (free) for text+tagging →
  on-device segmenter (free) for bg → reserve a *paid* image key only for try-on/compositing,
  where no good free API exists.
- **Architecture cost:** `GeminiRepository`/`GeminiApiCalls` hard-code
  `generativelanguage.googleapis.com` request/response shapes. Each non-Gemini provider has a
  different wire format → per-provider adapter, not a one-line URL change. An OpenAI-compatible
  abstraction (Groq/Cerebras/OpenRouter/Cloudflare all speak it) would cover most with one adapter.
- **BYOK messaging caveat:** `about_byok_desc` claims a *free* key avoids all costs — now only
  half true (image features need a billing-enabled key). Reword or gate image features when the
  key has no billing.

## Sources
- [Best Free LLM APIs 2026 (Groq/Cerebras/OpenRouter ranked)](https://costbench.com/best/best-llm-api-with-free-tier/)
- [Every Free AI API 2026](https://awesomeagents.ai/tools/free-ai-inference-providers-2026/)
- [Cloudflare Workers AI models (FLUX.2 klein, free neurons)](https://developers.cloudflare.com/workers-ai/models/)
- [Top Free Image Generation APIs & Open-Source Models 2026 — Eden AI](https://www.edenai.co/post/top-free-image-generation-tools-apis-and-open-source-models)
- [Comparing Top Open-Source Virtual Try-On Models — fashn.ai](https://fashn.ai/blog/comparing-the-top-4-open-source-virtual-try-on-viton-models)
- [IDM-VTON on Replicate](https://www.toolify.ai/ai-model/cuuupid-idm-vton)
- [Gemini Developer API pricing](https://ai.google.dev/gemini-api/docs/pricing)
- [Gemini API rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)
