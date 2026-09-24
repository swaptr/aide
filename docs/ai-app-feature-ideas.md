# AI App Feature Ideas — a backlog to mine for AIDE

> **What this is.** A durable, skimmable catalogue of cool / cutting-edge features that modern AI
> products and AI keyboards shipped in **2024–2026**, captured to mine for ideas. AIDE's standout
> surface is an **AI-integrated custom keyboard (IME)** — the AI is woven into the typing surface
> (rewrite/transform selected text, ad-hoc instructions, voice, tools/MCP) — plus a chat sheet, a
> voice overlay, local + remote model providers, and MCP connectors. Every feature below carries a
> one-line **AIDE angle**: where it could land on *our* surfaces.
>
> _Compiled 2026-06-30. Sources are linked inline. Jump to the [Top 10 shortlist](#top-10-highest-leverage-for-an-ai-keyboard) or the [cautionary signals](#cautionary-signals--what-didnt-stick)._

**How to read each entry:** **Feature** — *App* · what it is · why users love it · **AIDE angle** · `Source`.

**Categories**
- [0. AI keyboards — direct competitors & patterns](#0-ai-keyboards--direct-competitors--the-patterns-they-ship)  ← most relevant
- [1. Writing & text transforms](#1-writing--text-transforms)
- [2. Reply & conversation assistance](#2-reply--conversation-assistance)
- [3. Memory & personalization](#3-memory--personalization)
- [4. Voice & multimodal](#4-voice--multimodal)
- [5. Agentic & automation](#5-agentic--automation)
- [6. Knowledge, research & answers](#6-knowledge-research--answers)
- [7. Health & life](#7-health--life)
- [8. Privacy & on-device](#8-privacy--on-device)
- [9. Delight & social](#9-delight--social)
- [10. Platform & extensibility (tools / MCP / apps)](#10-platform--extensibility-tools--mcp--apps)
- [Top 10 highest-leverage for an AI keyboard](#top-10-highest-leverage-for-an-ai-keyboard)
- [Cautionary signals — what didn't stick](#cautionary-signals--what-didnt-stick)

---

## 0. AI keyboards — direct competitors & the patterns they ship

These are the products AIDE competes with most directly. The recurring pattern: **rewrite/tone on
selected text**, **compose-from-instruction**, **reply suggestions from conversation context**,
**inline image/sticker gen**, and **translate-as-you-type** — increasingly **on-device**.

| Keyboard | Standout AI features | On-device? |
|---|---|---|
| **Gboard** (Google) | Proofread (whole-message grammar/spell/punctuation), Smart Compose, AI rewrite to *formal / concise / expressive*, translate-as-you-type (400+ langs), scan-to-translate camera | Yes — Gemini Nano for proofread/rewrite |
| **SwiftKey + Copilot** (Microsoft) | Tone rewrite (Professional/Casual/Polite/Social-post), Compose (describe → draft w/ length+tone+format), Bing/DALL·E image creator inline, AI stickers, camera lenses, grammar "Editor", Bing visual search | No — cloud (GPT-4 Turbo) |
| **Apple keyboard** (Writing Tools) | System-wide Rewrite/Proofread/Summarize/Compose + "Describe your change", Genmoji from text | Yes — on-device + Private Cloud Compute |
| **Rizz / RizzPlus** & dating keyboards | Paste or screenshot a chat → AI explains "what they really mean" + 3 tailored replies (playful/direct/balanced) each with rationale; opener generators | No |
| **ReplyCraft / AI Tone / LeapMe** | Reply assistant that works in **any** app (WhatsApp/IG/Telegram/email); analyzes incoming-message intent, drafts replies, adjusts tone/length/emoji, multiple candidates | No |
| **Samsung Keyboard** (Galaxy AI) | Composer (generate w/ tone+format), Writing assist (grammar/tone + translate incoming), Live Translate for messages & calls | Partly on-device |
| **CleverType** (Fleksy-based) | 30+ AI assistants, large tone palette (incl. flirty/gen-z/poetic), user-defined custom assistants, "Clever Reply", 40+ langs | Cloud |
| **Typewise** | One-tap translate, proofread, tonality, shorten, extend, inclusive-language; next-sentence prediction; tone detection; learns your style; hexagonal layout | Yes — local AI, privacy-first |
| **Fleksy** | Inline real-time Google Translate, voice dictation, gesture typing, emoji/GIF search — plus a **GenAI Keyboard SDK** that powers many third-party AI / "rizz" keyboards | Translate is cloud |

**Gboard — Proofread + AI writing tools** — *Google Gboard.* A "Proofread" button fixes spelling,
grammar, and punctuation across the whole message in one tap, and AI rewrite re-tones text to formal,
concise, or expressive — **all on-device via Gemini Nano**, so text never leaves the phone. Loved for
being one tap, in every text field, and private. **AIDE angle:** this is AIDE's home turf — match the
one-tap whole-field Proofread *and* the tone-rewrite chips, but back them with our local *or* remote
providers and beat Gboard on device coverage. `Source:` [blog.google](https://blog.google/products-and-platforms/platforms/android/new-android-features-september-2025/) · [Android Police](https://www.androidpolice.com/gboard-proofread-ai-beta/)

**SwiftKey — Copilot "Tone" + "Compose"** — *Microsoft SwiftKey.* Tap the Copilot icon → **Tone**
rewrites your drafted text (Professional/Casual/Polite/Social-post) into an editable field you Accept
or Copy; **Compose** turns a short description into a polished draft with chosen length, tone, and
format. Plus inline Bing/DALL·E **image creation**, AI stickers, and a grammar "Editor". Loved as a
full mini-Copilot living in the keyboard. **AIDE angle:** mirror the "describe → draft" Compose box as
an ad-hoc instruction bar above AIDE's keys; we already have the model plumbing to one-up the
single-provider Copilot. `Source:` [Microsoft Support](https://support.microsoft.com/en-us/topic/how-to-use-tone-in-microsoft-swiftkey-keyboard-5acbf805-e28c-41f6-8028-be405758f34a) · [TechCrunch](https://techcrunch.com/2023/09/22/microsofts-mobile-keyboard-app-swiftkey-gains-new-ai-powered-features/)

**Ask-AI-anything inside the keyboard** — *SwiftKey (Copilot Chat), CleverType, Fleksy-based GPT
keyboards.* A button summons a full LLM chat *in the keyboard* — ask a question, look something up,
brainstorm, or rewrite — and the answer pastes straight into the field, in any app with no
context-switch. Loved as "ChatGPT everywhere you type," including apps with no native AI. **AIDE
angle:** AIDE already opens a chat sheet from the keyboard — make "ask AIDE" a first-class key so the
answer drops inline; this is the natural home for our MCP tools and local models. `Source:` [Windows Central](https://www.windowscentral.com/software-apps/microsoft-swiftkey-ditches-bing-for-copilot) · [CleverType](https://www.clevertype.co/) · [Fleksy GenAI SDK](https://www.fleksy.com/solutions/generative-ai/)

**Rizz / dating reply keyboards** — *Rizz, RizzPlus, SmoothRizz.* Paste a message or **screenshot the
whole conversation**; the AI decodes "what they really mean" and returns **3 tailored replies**
(playful / direct / balanced), each with a one-line rationale and "where it goes next". Explicitly
sold as the cure for "blank-screen anxiety." The genre has matured into selectable **personas**
(Charmer / Comedian / Savage / Sweetheart), a flirtiness **"spice level"** dial, and a **custom
voice-clone** trained on your own texting style (RizzMode "Memory Mode") — plus opener/icebreaker
generation from a match's profile photo, dating-bio writing, and dead-chat revival (Plug AI,
YourMove). **AIDE angle:** a "reply for me" mode where the keyboard reads the visible thread (clipboard
/ accessibility) and offers 2–3 reply chips with rationale — works in *any* app, not just dating; the
user-trained persona/voice-clone idea maps to AIDE's per-context memory. `Source:` [SmoothRizz](https://www.smoothrizz.com/) · [InsideHook](https://www.insidehook.com/sex-and-dating/rizz-app-ai-dating-wingman) · [RizzMode (App Store)](https://apps.apple.com/cd/app/rizzmode-ai-keyboard-for-rizz/id6755379236) · [Plug AI](https://apps.apple.com/us/app/plug-ai-texting-assistant/id6449750416)

**ReplyCraft / AI Tone — reply in any app** — *ReplyCraft, AI Tone, LeapMe.* Keyboard-level reply
assistants that work across WhatsApp, Messenger, Telegram, Instagram, email — analyze the incoming
message's intent, draft a contextual reply, then let you tweak tone, length, and emoji and pick among
candidates. **AIDE angle:** generalize Rizz's idea into a polite "suggest a reply" affordance for
every messaging app; gate emoji/length as quick toggles. `Source:` [ReplyCraft (Play)](https://play.google.com/store/apps/details?id=in.jenefa.replycraft&hl=en) · [AI Tone (App Store)](https://apps.apple.com/us/app/ai-reply-reply-assistant/id6738033894)

**Typewise — local AI text actions** — *Typewise.* One-tap **translate, proofread, adjust tonality,
shorten, extend, inclusive-language**, with a self-learning predictive engine and strong on-device
privacy claims. Loved by privacy-minded users who want AI rewrites without the cloud. **AIDE angle:**
"shorten / expand / make-inclusive" are cheap, high-use verbs that pair perfectly with AIDE's local
models. `Source:` [Typewise (Play)](https://play.google.com/store/apps/details?id=ch.icoaching.typewise&hl=en) · [Wikipedia](https://en.wikipedia.org/wiki/Typewise)

---

## 1. Writing & text transforms

**Apple Writing Tools** — *Apple Intelligence.* System-wide text actions: **Rewrite** in Friendly /
Professional / Concise tones (or a custom "Describe your change" like *"make this a poem"*),
**Proofread** (grammar/spelling while preserving voice), and **Summarize** into a paragraph, **key
points**, a **list**, or a **table**. Loved because it works identically in every app at the OS level.
**AIDE angle:** the "Describe your change" free-text instruction is the single most copy-worthy idea —
an open instruction box beats a fixed menu of tones. `Source:` [Apple Support](https://support.apple.com/en-us/121582) · [MacRumors guide](https://www.macrumors.com/guide/apple-intelligence-writing-tools/)

**Grammarly tone detector + generative assist (GrammarlyGO)** — *Grammarly.* Detects the emotional
tone of your draft (formal/friendly/confident) and suggests adjustments; on-demand generative AI to
**compose, rewrite, ideate, and reply**, adapting to your personal voice, plus a "humanize" pass.
Loved for catching tone mismatches *before* you send. **AIDE angle:** add a passive **tone read-out**
("this reads as: blunt") above the suggestion bar — a differentiator no on-device keyboard ships well.
`Source:` [Grammarly Support](https://support.grammarly.com/hc/en-us/articles/14528857014285-Introducing-generative-AI-assistance) · [Grammarly AI writing](https://www.grammarly.com/ai-writing-assistant)

**Magic Compose — style rewrites** — *Google Messages.* Rewrites a drafted message into playful
preset styles — **Remix, Shakespeare, Chill, Lyrical, Excited, Formal** — powered on-device by Gemini
Nano. Loved because the novelty styles (Shakespeare!) are genuinely fun and shareable. **AIDE angle:**
ship a couple of "delight" styles alongside the serious tones; they drive word-of-mouth. `Source:` [Android.com](https://www.android.com/articles/how-to-use-magic-compose/) · [Google Messages help](https://support.google.com/messages/answer/13632636?hl=en)

**ChatGPT Canvas — writing/coding workspace** — *ChatGPT.* A side-by-side editor where you and the
model co-edit a doc or code: highlight a span for targeted edits, plus shortcuts to adjust **length,
reading level, final polish, add emoji**, or (for code) review/add-logs/fix-bugs/port-languages, with a
**Run** button and version restore. Loved as "a real editor, not chat scrollback." **AIDE angle:** for
longer drafts, AIDE's chat sheet could open a lightweight canvas where keyboard-selected text becomes
an editable artifact. `Source:` [OpenAI](https://openai.com/index/introducing-canvas/) · [OpenAI Help](https://help.openai.com/en/articles/9930697-what-is-the-canvas-feature-in-chatgpt-and-how-do-i-use-it)

**Notion AI — autofill, formulas, Q&A** — *Notion.* AI blocks that draft and rewrite, **Autofill** to
populate database properties from content, AI-written formulas, and workspace-wide Q&A. Loved for
turning unstructured notes into structured data automatically. **AIDE angle:** "extract structured
fields from this text" is a strong keyboard verb for forms and notes. `Source:` [Notion AI](https://www.notion.com/product/ai) · [Notion 2.51 release](https://www.notion.com/releases/2025-05-13)

---

## 2. Reply & conversation assistance

**Smart Reply** — *Apple Mail/Messages, Gmail, Android notifications.* Context-aware one-tap reply
chips generated from the incoming message ("Yes, I'll be there" / "Can we reschedule?"). Loved for
near-zero-effort replies to routine messages. **AIDE angle:** AIDE's keyboard can surface 3 reply
chips the instant a text field is focused in a messaging app — the lowest-friction AI on the surface.
`Source:` [Mailmeteor (Gmail AI)](https://mailmeteor.com/blog/how-to-use-ai-in-gmail) · [Apple Intelligence](https://www.apple.com/newsroom/2024/06/introducing-apple-intelligence-for-iphone-ipad-and-mac/)

**Gboard Smart Reply + custom-instruction replies (on-device)** — *Gboard.* On-device Gemini Nano
generates context-aware replies that go beyond "OK"/"Sounds good"; Google is testing letting it **read
the chat context / a screenshot** and accept natural-language tweaks ("make this less robotic," "turn
this into a joke"). Loved for smarter replies that stay private. **AIDE angle:** combine
reply-from-context with a free-text "adjust this reply" box — the on-device, any-app version of the
Rizz move, which AIDE's local models can run. `Source:` [9to5Google](https://9to5google.com/2024/01/29/gboard-smart-reply-gemini-nano-apps/) · [Android Headlines](https://www.androidheadlines.com/2026/05/gboard-could-soon-use-chat-context-screenshots-to-generate-better-replies.html)

**Conversation screenshot → reply** — *Rizz, ReplyCraft (see §0).* Feed the AI a screenshot/paste of
the whole thread and it replies *in context* rather than to a single line. **AIDE angle:** "read the
visible conversation, then draft" is the keyboard-native version of agentic context — a flagship AIDE
move. `Source:` [SmoothRizz](https://www.smoothrizz.com/)

**ChatGPT Group Chats** — *ChatGPT.* Up to 20 people share one thread with ChatGPT; it follows the
social flow, decides when to chime in vs. stay quiet, and answers when @-mentioned — personal memory
never leaks into the group. Loved for collaborative planning with an AI participant. **AIDE angle:**
an "@AIDE" mention model inside group messaging (via the keyboard) that only speaks when summoned.
`Source:` [OpenAI](https://openai.com/index/group-chats-in-chatgpt/) · [TechCrunch](https://techcrunch.com/2025/11/20/chatgpt-launches-group-chats-globally/)

---

## 3. Memory & personalization

**ChatGPT Memory (saved + reference chat history + "Dreaming")** — *ChatGPT.* Three layers:
explicitly **saved memories** (name, diet, projects), automatic **reference to all past chats**, and a
background **"Dreaming"** process that consolidates years of chats into a rich evolving profile while
you're away (even self-updating facts: "going to Singapore" → "went to Singapore"). Loved because you
stop re-explaining yourself. **AIDE angle:** a keyboard that remembers your tone, signature phrasings,
and recurring contacts/projects across apps is a durable moat; keep it user-inspectable and wipeable.
`Source:` [OpenAI Memory](https://openai.com/index/memory-and-new-controls-for-chatgpt/) · [Dreaming](https://openai.com/index/chatgpt-memory-dreaming/)

**Claude Memory + auto-memory + Projects** — *Claude.* Chat memory that summarizes past sessions, a
file-system `/memory` folder for agentic use, an **auto-memory** mode where Claude decides what to
store, and **Projects** that bundle a knowledge base + custom instructions. Loved for accumulating
project knowledge without manual docs. **AIDE angle:** per-context "projects" (work vs. personal) that
swap the keyboard's tone + knowledge. `Source:` [Claude features](https://suprmind.ai/hub/claude/features/) · [Memory tool docs](https://platform.claude.com/docs/en/agents-and-tools/tool-use/memory-tool)

**ChatGPT Pulse — proactive daily briefings** — *ChatGPT (mobile).* Overnight, ChatGPT researches on
your behalf from your chats, memory, and connected apps (Gmail/Calendar) and delivers 5–10 scannable
"cards" each morning. Loved for shifting AI from reactive to **proactive**. **AIDE angle:** a morning
"keyboard brief" — drafts/reminders/replies queued for the day's likely conversations. `Source:` [OpenAI Pulse](https://openai.com/index/introducing-chatgpt-pulse/)

**Pixel Screenshots — recall your screenshots** — *Google Pixel.* On-device app that analyzes every
screenshot, makes them searchable, and answers natural-language questions ("what was that wifi
password?") entirely on the phone. Loved as a private, no-effort memory of things you saw. **AIDE
angle:** let the keyboard pull answers from your own screenshot/clipboard history when composing.
`Source:` [Google blog](https://blog.google/products/pixel/google-pixel-10-ai-features-updates/) · [Android Authority](https://www.androidauthority.com/pixel-recorder-google-pixel-9-3473920/)

**Life-logging memory: Limitless / Rewind / Microsoft Recall** — *Limitless Pendant, Rewind, MS
Recall.* Always-on capture of what you saw/heard/said (screen for Rewind/Recall; conversations for the
$99 Limitless Pendant) with perfect, searchable recall and speaker attribution. Loved (and feared) for
"never forget a meeting again." **AIDE angle:** opt-in, on-device conversation memory the keyboard can
cite — but heed the privacy backlash (see cautions). `Source:` [Limitless](https://www.limitless.ai/new) · [The Verge/Birchtree on Recall](https://birchtree.me/blog/limitless-just-got-sherlocked-by-microsoft/)

**Companion memory + proactive check-ins** — *Replika, Character.AI.* Companions that remember what
matters over months, **follow up and check in at the right moments**, and reach you by text, voice
call, or video. Loved for the feeling of being known. **AIDE angle:** a (tasteful) proactive nudge —
"want me to reply to Mom? she's waited 2 days" — is companion energy applied to messaging. `Source:` [Replika (App Store)](https://apps.apple.com/us/app/replika-ai-companion-chat/id1158555867) · [Character.AI review](https://www.startuphub.ai/ai-news/reviews/2026/character-ai-review-2026)

---

## 4. Voice & multimodal

**ChatGPT Advanced Voice + Vision** — *ChatGPT (mobile).* Natural, low-latency spoken conversation
with interruption handling, emotional expressiveness, and live translation — plus **vision**: point the
camera at the real world or **share your screen** and it responds in real time. Loved as "FaceTime
with an AI." **AIDE angle:** AIDE already has a voice overlay (VoiceInteractionSession) — add camera/
screen context so "what does this say / fix this" works hands-free. `Source:` [voice-with-video](https://chatgpt.com/features/voice-with-video/) · [TechCrunch](https://techcrunch.com/2024/12/12/chatgpt-now-understands-real-time-video-seven-months-after-openai-first-demoed-it/)

**Gemini Live — camera + screen share** — *Google Gemini.* Real-time voice with the model seeing your
**camera feed or your screen, system-wide across any app**, free on Android. Loved for "ask about
whatever I'm looking at." **AIDE angle:** the system-wide screen-aware assistant is exactly the overlay
AIDE can own on Android. `Source:` [blog.google](https://blog.google/products/gemini/gemini-live-android-tips/) · [Android Central](https://www.androidcentral.com/apps-software/android-os/gemini-live-real-time-screen-sharing-now-available-to-all-android-users)

**NotebookLM Audio Overviews** — *Google NotebookLM.* Turns your sources (PDFs, docs, URLs) into a
podcast-style discussion between two AI hosts that you can **interrupt and ask questions of by voice**;
80+ languages; 100M+ plays. Loved for turning reading into listening. **AIDE angle:** "read me a
summary of this thread/article" as a voice output mode. `Source:` [blog.google](https://blog.google/technology/ai/notebooklm-audio-overviews/) · [NotebookLM Help](https://support.google.com/notebooklm/answer/16212820?hl=en)

**Apple Live Translation** — *Apple Intelligence (AirPods/Phone/Messages).* Hands-free, **on-device**
real-time spoken translation between people who don't share a language; also translates Messages/calls.
Loved for private, in-person translation with no app juggling. **AIDE angle:** translate-as-you-speak
and translate-as-you-type are natural keyboard/voice features for travelers. `Source:` [Apple Support](https://support.apple.com/en-us/123185) · [Apple Newsroom](https://www.apple.com/newsroom/2025/09/new-apple-intelligence-features-are-available-today/)

**Translate-as-you-type** — *Gboard, SwiftKey, Fleksy.* Type in your language and the keyboard shows
the live translation in the field (Gboard: 400+ languages, plus scan-to-translate via camera;
SwiftKey's Translator does two-way translation of outgoing text *and* copied incoming messages across
60+ languages). Loved for seamless cross-language chat without leaving the app. **AIDE angle:** inline
two-way translate is table-stakes AIDE should match, and it pairs with on-device models for privacy.
`Source:` [Gboard Help](https://support.google.com/gboard/answer/7421372?hl=en&co=GENIE.Platform%3DAndroid) · [SwiftKey Translator](https://support.microsoft.com/en-us/topic/how-to-use-microsoft-translator-with-your-microsoft-swiftkey-keyboard-0034520a-ded5-4d24-9493-4200474793b5) · [Android Central](https://www.androidcentral.com/apps-software/how-to-use-gboard-to-automatically-translate-as-you-type)

**Voice dictation → clean message ("Rambler")** — *Gboard.* Speak in a stream of consciousness with
"ums," pauses, and self-corrections; Gemini extracts the intent and outputs a polished, send-ready
written message (and can switch languages mid-message); audio isn't stored. Loved for "dictate messy,
send clean." **AIDE angle:** a killer keyboard-native feature — pair AIDE's speech input with an
AI cleanup pass so voice notes become tidy text in any field. `Source:` [ChromeUnboxed](https://chromeunboxed.com/googles-new-rambler-feature-for-gboard-uses-gemini-to-fix-your-messy-voice-dictation/)

**Camera OCR → insert ("Scan Text")** — *Gboard.* A camera viewfinder inside the keyboard OCRs
printed/handwritten text and inserts it into the field; the image is discarded afterward. Loved for
pulling real-world text into any input without app-switching to Lens. **AIDE angle:** "scan and paste"
is a cheap, high-utility keyboard verb that pairs naturally with inline translate. `Source:` [9to5Google](https://9to5google.com/2024/02/21/gboard-scan-text-ocr-tool/)

**Circle to Search / Visual Intelligence** — *Android / Apple.* Long-press and **circle, scribble, or
tap anything on screen** (or point the camera) to search/identify/act — translate text, identify a
plant, add an event, ask ChatGPT about it. Loved for "search without leaving the app." **AIDE angle:**
a "select-and-ask" gesture over any on-screen text/image, launched from the keyboard or overlay.
`Source:` [blog.google](https://blog.google/products/search/google-circle-to-search-android/) · [Apple Visual Intelligence](https://www.macrumors.com/guide/visual-intelligence/)

**Inline image & emoji generation** — *GPT-4o, Image Playground, Genmoji, SwiftKey.* Generate images
in chat (GPT-4o renders legible text in images), build a **Genmoji** by typing a description, or make
AI stickers/images right from the keyboard (SwiftKey + DALL·E). Loved for expressive, personal visuals
inline. **AIDE angle:** a keyboard "make a sticker/emoji of…" action is pure delight and shareable.
`Source:` [Apple Newsroom (Genmoji/Playground)](https://www.apple.com/newsroom/2024/12/apple-intelligence-now-features-image-playground-genmoji-and-more/) · [SwiftKey image creator](https://www.androidpolice.com/swiftkey-beta-ai-microsoft-image-generator/)

---

## 5. Agentic & automation

**ChatGPT Agent (Operator + Deep Research, unified)** — *ChatGPT.* Autonomously completes multi-step
web tasks on its own **virtual computer** — visual browser (clicks, fills forms), text browser,
terminal, API — to book, order, or research-to-action, asking permission before consequential steps.
Loved for delegating real chores. **AIDE angle:** keyboard-initiated micro-agents ("fill this form,"
"find and paste the tracking number") scoped to the current app. `Source:` [OpenAI Agent](https://openai.com/index/introducing-chatgpt-agent/) · [Operator](https://openai.com/index/introducing-operator/)

**Agentic browsers — ChatGPT Atlas / Perplexity Comet** — *OpenAI / Perplexity.* AI-native browsers
with a built-in assistant that summarizes any page, answers follow-ups, and **drives the browser** to
book hotels, comparison-shop, or fill forms in your logged-in context. Loved for "ask it to do the
browsing." **AIDE angle:** out of scope for a keyboard, but the *summarize-and-act on what's on screen*
pattern maps to AIDE's overlay. `Source:` [Atlas](https://openai.com/index/introducing-chatgpt-atlas/) · [Comet (IBM Think)](https://www.ibm.com/think/news/comet-perplexity-take-agentic-browser)

**Claude Computer Use** — *Claude.* Claude takes screenshots, reasons, and clicks/types in a desktop
loop to operate apps. Loved as a glimpse of true desktop automation. **AIDE angle:** the screenshot→
reason→act loop is the model for "do this in the app I'm typing in." `Source:` [Computer use docs](https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool)

**ChatGPT Scheduled Tasks** — *ChatGPT.* Schedule one-off or recurring prompts — reminders, daily
briefings, monitoring — delivered via push/email without keeping a chat open ("brief me on AI news
each afternoon," "remind me of Mom's birthday"). Loved for proactive automation. **AIDE angle:** AIDE
already ships reminders; let the keyboard *create* a scheduled draft/reminder from selected text.
`Source:` [OpenAI Help](https://help.openai.com/en/articles/10291617-tasks-in-chatgpt) · [TechCrunch](https://techcrunch.com/2025/01/14/chatgpt-now-lets-you-schedule-reminders-and-recurring-tasks/)

**Perplexity Email Assistant** — *Perplexity (Max).* Connects Gmail/Outlook to auto-draft replies,
organize with smart labels, and schedule meetings straight from your inbox. Loved for turning the
inbox into a delegated queue. **AIDE angle:** with MCP mail connectors, AIDE's keyboard could draft
inbox replies in place. `Source:` [Perplexity](https://www.perplexity.ai/hub/blog/a-personal-assistant-for-your-inbox)

**Cluely — real-time invisible assist** — *Cluely.* A desktop overlay that watches your screen + audio
during meetings/interviews and feeds you real-time suggested answers, invisibly during screen-share
(launched as "cheat on everything," now a meeting assistant). Loved/controversial for live whisper-
coaching. **AIDE angle:** a discreet "suggested next line" overlay during live chats — the consensual,
ethical version. `Source:` [Cluely](https://cluely.com/) · [Wikipedia](https://en.wikipedia.org/wiki/Cluely)

**Granola — jot-and-enhance meeting notes** — *Granola.* You type rough notes during a call; Granola
transcribes device audio locally (no bot joins) and **enhances your notes** against the full
transcript into structured summaries — your text in black, AI's in gray — plus a pre-meeting **Brief**
and "ask the meeting" Q&A. Loved because it augments *your* notes instead of replacing them. **AIDE
angle:** the "you write the skeleton, AI fills the muscle" interaction is a brilliant trust model for
keyboard rewrites — show what's yours vs. AI's. `Source:` [Granola](https://www.granola.ai/) · [Wonder Tools](https://wondertools.substack.com/p/granolaguide)

---

## 6. Knowledge, research & answers

**Perplexity — answer engine + Spaces + Deep Research** — *Perplexity.* Cited answers with follow-ups;
**Spaces** (collaborative collections with custom instructions + files); **Deep Research** that now
emits structured outputs (slides, spreadsheets, dashboards, websites). Loved for trustworthy,
source-linked answers. **AIDE angle:** a keyboard "answer + cite" mode that drops a sourced answer into
the field beats hallucinated autocomplete. `Source:` [Perplexity 2026 update](https://beginnersinai.org/whats-new-perplexity-2026/) · [Comet](https://www.perplexity.ai/comet)

**ChatGPT Deep Research** — *ChatGPT.* Autonomous agent that reads hundreds of sources into a fully
**cited, analyst-grade report** over minutes. Loved for replacing hours of manual research with
verifiable output. **AIDE angle:** "research this and paste a sourced summary" as a long-running
keyboard action with a notification when done. `Source:` [OpenAI](https://openai.com/index/introducing-deep-research/)

**Claude Artifacts — build shareable AI apps** — *Claude.* A side panel that renders code/docs/games
live, now upgraded so artifacts can **call Claude themselves** and be **shared as interactive apps** (no
API key for users), with persistent storage and MCP connections. Loved for "describe an idea → instant
shareable app." **AIDE angle:** generate a tiny interactive widget (calculator, tracker) from a typed
request. `Source:` [Claude build-artifacts](https://claude.com/blog/build-artifacts) · [Help Center](https://support.claude.com/en/articles/9487310-what-are-artifacts-and-how-do-i-use-them)

**Notion AI — enterprise search + meeting notes w/ citations** — *Notion.* Q&A across Notion + Slack/
Google Drive/GitHub/Jira/Microsoft, and AI meeting notes where **each takeaway links to the exact
transcript moment**. Loved for answers (not links) grounded in your own corpus with citations. **AIDE
angle:** clickable provenance on every AI claim builds trust; do it for keyboard rewrites/answers.
`Source:` [Notion AI Meeting Notes](https://www.notion.com/help/ai-meeting-notes) · [Notion release](https://www.notion.com/releases/2025-05-13)

**Claude Files / Analysis tool — generate real Office files** — *Claude.* A private server-side sandbox
where Claude writes and runs code to analyze data and produce actual downloadable **.xlsx (with live
formulas), .pptx, .docx, and .pdf**, charts included. Loved for collapsing "programming + stats +
hours" into a chat that returns ready-to-use deliverables. **AIDE angle:** "turn this into a
spreadsheet/table file" as an output type for selected text. `Source:` [Claude create-files](https://claude.com/blog/create-files)

**Perplexity Pages + Discover** — *Perplexity.* **Pages** turns a research conversation into a
structured, shareable, Google-indexable article (auto sections + images, tuned to a chosen audience);
**Discover** is a personalized feed of trending topics with tappable AI summaries you can ask
follow-ups on. Loved for research-to-publishable-article in one step and effortless, interactive news.
**AIDE angle:** "expand my notes into a shareable page" plus a lightweight personalized brief. `Source:` [Pages](https://www.perplexity.ai/hub/blog/perplexity-pages) · [Discover](https://www.perplexity.ai/hub/blog/getting-started-with-perplexity)

---

## 7. Health & life

**ChatGPT Health + HealthBench** — *ChatGPT Health.* A dedicated, encrypted space where you connect
medical records and wellness apps (Apple Health, MyFitnessPal) to ask grounded questions — explain lab
results, prep appointment questions, build routines — built with 260+ physicians and evaluated on the
physician-authored **HealthBench**. Loved for plain-language explanations of confusing medical data.
**AIDE angle:** a careful "explain this / draft questions for my doctor" action — *with* the
escalation safeguards below. `Source:` [OpenAI Health](https://openai.com/index/improving-health-intelligence-in-chatgpt/) · [HealthBench](https://openai.com/index/healthbench/)
> **Caution:** a 2026 *Nature Medicine* triage stress-test found undertriage of **52% of gold-standard
> emergencies** — never position health output as a substitute for care; always escalate. `Source:` [Nature Medicine](https://www.nature.com/articles/s41591-026-04297-7)

**Apple priority notifications + Reduce Interruptions Focus** — *Apple Intelligence.* AI ranks
notifications and floats the important ones to the top; **Reduce Interruptions** Focus reads
notification content and only surfaces what needs attention (e.g., "early daycare pickup") while
silencing noise. Loved for protecting attention without missing what matters. **AIDE angle:** AIDE
could prioritize *which* messages deserve an AI-drafted reply first. `Source:` [Apple Support](https://support.apple.com/guide/iphone/summarize-notifications-reduce-interruptions-iph1fbe7d2b9/ios)

**Pixel Call Notes — record + summarize calls** — *Google Pixel.* Records a phone call and produces an
**on-device** transcript + AI summary so you can recall what was agreed. Loved for never missing call
details, privately. **AIDE angle:** summarize-a-conversation (call or chat) into action items the
keyboard can paste. `Source:` [Tom's Guide](https://www.tomsguide.com/phones/google-pixel-phones/how-to-use-call-notes-to-record-and-summarize-phone-calls-on-pixel-9-devices)

---

## 8. Privacy & on-device

**Apple Foundation Models framework** — *Apple (iOS 26).* Direct Swift access to Apple's **~3B on-device
LLM** — free inference, offline, with **tool calling** and **guided (structured) generation** — so any
app can build private AI features; iOS 26 opens it to additional local/server models too. Loved by devs
for free, private, offline intelligence. **AIDE angle:** validates AIDE's local-model bet; the
guided-generation + tool-calling contract mirrors AIDE's provider-agnostic tools design. `Source:` [Apple Newsroom](https://www.apple.com/newsroom/2025/09/apples-foundation-models-framework-unlocks-new-intelligent-app-experiences/) · [Apple Developer docs](https://developer.apple.com/documentation/FoundationModels)

**On-device proofread/rewrite via Gemini Nano** — *Gboard, Magic Compose, Call Notes, Pixel
Screenshots.* A whole class of Google features run entirely on-device — text never leaves the phone.
Loved as the privacy default. **AIDE angle:** "your text never leaves the device" is a headline AIDE
can own across the whole keyboard, not just one feature. `Source:` [MakeUseOf](https://www.makeuseof.com/these-pixel-ai-features-run-completely-on-phone-and-dont-use-cloud/)

**PocketPal AI / Private LLM — run models fully offline** — *PocketPal, Private LLM.* Download a GGUF
model once and chat with **no cloud, no account, no internet** on Android/iOS; open-source. Loved by
privacy- and offline-first users. **AIDE angle:** AIDE already supports local models — match PocketPal's
"search Hugging Face → download → run offline" model library UX. `Source:` [PocketPal (GitHub)](https://github.com/a-ghorbani/pocketpal-ai) · [PocketPal (Play)](https://play.google.com/store/apps/details?id=com.pocketpalai&hl=en_US)

---

## 9. Delight & social

**Genmoji & Image Playground** — *Apple Intelligence.* Type a description to mint a custom emoji, or
combine up to seven concepts (and people from your photos) into an image in Animation/Illustration/
Sketch styles, inline in Messages. Loved for personal, expressive, *shareable* visuals. **AIDE angle:**
"make a Genmoji of…" from the keyboard is high-delight, low-risk. `Source:` [Apple Newsroom](https://www.apple.com/newsroom/2024/12/apple-intelligence-now-features-image-playground-genmoji-and-more/)

**Gboard Emoji Kitchen + "Emogen"** — *Gboard.* Mash two emoji into one custom sticker (Emoji Kitchen),
or **generate emoji stickers from a text prompt** (Emogen), with on-device contextual emoji/sticker/GIF
suggestions surfaced as you type. Loved for expressive, local, zero-effort reactions. **AIDE angle:**
generated stickers + contextual emoji suggestions are easy, sticky delight on the keyboard surface.
`Source:` [Android Authority](https://www.androidauthority.com/gboard-beta-generative-ai-proofreading-emojis-3354114/)

**"Your Year with ChatGPT" recap** — *ChatGPT.* A Spotify-Wrapped-style annual recap — your themes,
chat stats, a generated poem, a pixel painting, an "award," and 2026 predictions. Loved as a fun,
shareable, self-reflective moment that drives engagement. **AIDE angle:** a "your typing year" recap
(top phrases, tones, most-rewritten messages) is a viral growth loop. `Source:` [OpenAI Help](https://help.openai.com/en/articles/20001042-your-year-with-chatgpt-faqs)

**Sora 2 — cameos** — *Sora app.* Text-to-video with synced audio in a TikTok-style feed, with
**Cameos** that insert a verified likeness of you/friends into any generated scene. The self-insertion
was the viral hook. *(Note: the Sora app was reportedly discontinued in 2026 — the cameo *idea* is the
keeper, not the app.)* **AIDE angle:** personalization-as-delight — let users put themselves into
generated stickers/images. `Source:` [OpenAI Sora 2](https://openai.com/index/sora-2/)

**Magic Compose "Shakespeare" mode** — *Google Messages.* See §1 — novelty tone presets are a
disproportionate share of why people *try* AI rewrite. **AIDE angle:** keep one or two playful styles
in the rotation. `Source:` [Android.com](https://www.android.com/articles/how-to-use-magic-compose/)

---

## 10. Platform & extensibility (tools / MCP / apps)

**Model Context Protocol (MCP)** — *Anthropic (open standard), adopted by ChatGPT.* "USB-C for AI" — a
standard way for an AI app to connect to external tools, data, and workflows (Drive, Slack, GitHub,
Calendar…). Loved because one integration standard unlocks hundreds of tools. **AIDE angle:** AIDE
already speaks MCP — a keyboard that can *call your connected tools* mid-sentence ("paste my next
meeting," "create a Linear issue") is a genuinely novel surface. `Source:` [Anthropic](https://www.anthropic.com/news/model-context-protocol) · [modelcontextprotocol.io](https://modelcontextprotocol.io/docs/getting-started/intro)

**Apps in ChatGPT (Apps SDK over MCP)** — *ChatGPT.* Third-party apps run *inside* ChatGPT with
interactive UI, built on MCP; "connectors" let it search and act on your data sources. Loved for
turning the assistant into a platform. **AIDE angle:** an "AIDE apps" surface where MCP tools render
mini-UIs above the keys. `Source:` [OpenAI Apps](https://openai.com/index/introducing-apps-in-chatgpt/)

**Custom GPTs + GPT Store** — *ChatGPT.* No-code builder to make a task-specific assistant
(instructions + knowledge + actions) and publish it to a searchable store. Loved for shareable,
specialized assistants. **AIDE angle:** user-defined "keyboard personas/skills" (e.g., a "polite email"
persona, a "standup update" skill) selectable per app. `Source:` [OpenAI GPT Store](https://openai.com/index/introducing-the-gpt-store/)

**Claude Skills** — *Claude.* Loadable expertise packages (incl. executable scripts) that Claude pulls
in automatically when relevant — e.g., Excel/PowerPoint/PDF workflows. Loved as "permanent, reusable
know-how." **AIDE angle:** ship installable keyboard "skills" that add verbs (cite-in-APA, format-as-
SQL) on demand. `Source:` [Claude Skills](https://techtiff.substack.com/p/claude-skills-your-permanent-ai-memory)

**ChatGPT Codex — parallel agent work** — *ChatGPT.* A cloud SWE agent that runs many tasks in parallel
in sandboxed containers and opens PRs. Loved for async delegation. **AIDE angle:** the *parallel,
async, notify-when-done* pattern fits long keyboard actions (research/translate-a-doc). `Source:` [OpenAI Codex](https://openai.com/index/introducing-codex/)

---

## Top 10 highest-leverage for an AI keyboard

Ranked for AIDE specifically — bias toward features that are **keyboard-native, high-frequency, and
hard for single-provider incumbents to match** given AIDE's local+remote models and MCP.

| # | Feature | Why it's high-leverage for AIDE | Inspired by |
|---|---|---|---|
| 1 | **Open "describe your change" instruction bar** (free-text rewrite, not a fixed tone menu) | Most flexible transform; AIDE's multi-model backend can do far more than a tone dropdown | [Apple Writing Tools](https://support.apple.com/en-us/121582), [SwiftKey Compose](https://support.microsoft.com/en-us/topic/how-to-use-tone-in-microsoft-swiftkey-keyboard-5acbf805-e28c-41f6-8028-be405758f34a) |
| 2 | **One-tap whole-field Proofread + tone chips** | Highest-frequency keyboard verb; on-device = privacy headline | [Gboard Proofread](https://blog.google/products-and-platforms/platforms/android/new-android-features-september-2025/) |
| 3 | **Reply-from-conversation-context** (read the visible thread → 2–3 reply chips w/ rationale) | The flagship "AI woven into typing" moment; works in *any* app | [Rizz](https://www.smoothrizz.com/), [Smart Reply](https://www.apple.com/newsroom/2024/06/introducing-apple-intelligence-for-iphone-ipad-and-mac/) |
| 4 | **MCP tools mid-sentence** ("paste my next meeting", "create issue") | AIDE already speaks MCP — almost nobody else has tools *in the keyboard* | [MCP](https://www.anthropic.com/news/model-context-protocol) |
| 5 | **Translate-as-you-type + translate-as-you-speak** | Table-stakes for global users; pairs with on-device models | [Gboard](https://support.google.com/gboard/answer/7421372?hl=en&co=GENIE.Platform%3DAndroid), [Apple Live Translation](https://support.apple.com/en-us/123185) |
| 6 | **Voice + screen/camera context in the overlay** | AIDE has a VoiceInteractionSession overlay — add "see what I see / read my screen" | [Gemini Live](https://blog.google/products/gemini/gemini-live-android-tips/), [ChatGPT Vision](https://chatgpt.com/features/voice-with-video/) |
| 7 | **"Jot-and-enhance" trust model** (show user text vs. AI text; let user keep/delete) | Builds trust in rewrites; cheap to implement; differentiator | [Granola](https://www.granola.ai/) |
| 8 | **On-device-by-default + visible model picker** | "Your text never leaves the phone" is a brand-defining promise AIDE can own | [PocketPal](https://github.com/a-ghorbani/pocketpal-ai), [Gemini Nano](https://www.makeuseof.com/these-pixel-ai-features-run-completely-on-phone-and-dont-use-cloud/) |
| 9 | **Cross-app personal memory** (your phrasings, contacts, projects — inspectable & wipeable) | Compounding moat; makes every suggestion feel personal | [ChatGPT Memory](https://openai.com/index/memory-and-new-controls-for-chatgpt/) |
| 10 | **Inline image/sticker/Genmoji generation** + one playful tone preset | Disproportionate delight & word-of-mouth for low effort | [Genmoji](https://www.apple.com/newsroom/2024/12/apple-intelligence-now-features-image-playground-genmoji-and-more/), [Magic Compose](https://www.android.com/articles/how-to-use-magic-compose/) |

**Honorable mentions:** voice-dictation → clean message (Gboard "Rambler"), camera-OCR → insert
(Gboard "Scan Text"), ask-AI-anything-in-keyboard (SwiftKey Copilot Chat / CleverType),
answer-with-citations mode (Perplexity), select-and-ask over any on-screen content (Circle to Search),
scheduled drafts/reminders from selected text (ChatGPT Tasks), summarize-a-conversation into action
items (Pixel Call Notes / Granola), installable "keyboard skills/personas" (Claude Skills / Custom
GPTs), generate real Office files from selected text (Claude Files tool).

---

## Cautionary signals — what didn't stick

Useful "don't repeat these" lessons from 2024–2026 launches:

- **Don't over-automate consequential actions.** OpenAI's **Instant Checkout** was pulled back to
  merchant-controlled flows within months — AI is trusted for discovery/intent, less for finishing the
  transaction. `Source:` [Buy it in ChatGPT](https://openai.com/index/buy-it-in-chatgpt/)
- **Agentic actions in someone else's logged-in service invite legal pushback.** Amazon won a
  preliminary injunction (Judge Chesney, Mar 10 2026, under the CFAA) blocking Perplexity's **Comet**
  from agentically shopping logged-in Amazon accounts — "with the user's permission, but without
  Amazon's authorization." (Stayed pending appeal.) `Source:` [GeekWire](https://www.geekwire.com/2026/judge-blocks-perplexitys-ai-bot-from-shopping-on-amazon-in-early-test-of-agentic-commerce/) · [Search Engine Journal](https://www.searchenginejournal.com/amazon-wins-preliminary-injunction-against-perplexitys-comet/569256/)
- **Keep manual control over automatic routing.** **GPT-5's** auto model-router drew enough backlash
  that OpenAI re-exposed manual Fast/Thinking toggles — users want a visible override (AIDE already
  exposes a model picker; keep it). `Source:` [Fortune](https://fortune.com/2025/08/12/openai-gpt-5-model-router-backlash-ai-future/)
- **Always-on memory needs opt-in + on-device + per-app exclusions.** **Microsoft Recall** was delayed
  over privacy alarm and relaunched opt-in. `Source:` [Birchtree](https://birchtree.me/blog/limitless-just-got-sherlocked-by-microsoft/)
- **Don't overpromise on health.** ChatGPT Health undertriaged **52%** of emergency vignettes in a
  *Nature Medicine* test — frame as guidance, escalate to care. `Source:` [Nature Medicine](https://www.nature.com/articles/s41591-026-04297-7)
- **Proactive features can over-reach.** **ChatGPT Pulse** is being folded back into Scheduled Tasks —
  proactive briefings are valuable but easy to over-build. `Source:` [Pulse](https://openai.com/index/introducing-chatgpt-pulse/)

---

### Cross-cutting themes (2024–2026)
1. **Proactive, not just reactive** (Pulse, Tasks, Dreaming memory, companion check-ins).
2. **Agents that act**, not just answer (ChatGPT Agent, Atlas/Comet, Computer Use).
3. **AI as a platform** via open tool standards (MCP, Apps SDK, Skills, Custom GPTs).
4. **On-device + privacy** as a default and a brand promise (Gemini Nano, Apple Foundation Models, PocketPal).
5. **Personalization/memory** as the connective tissue under all of it.

**Keyboard-genre specifics worth internalizing:**
- **Screenshot-as-context** is the breakout interaction of the "rizz"/reply-keyboard genre (feed the AI
  an *image* of the conversation, not plumbed message history) — and Gboard is now copying it.
- **Free-text rewrite instructions** ("make this less robotic," "turn this into a joke") are overtaking
  fixed tone-preset menus — an open instruction box is the more powerful primitive.
- **Personas / voice-clone** (RizzMode "Memory Mode," CleverType custom assistants) point to
  user-trained keyboard personalities — a memory/personalization play AIDE is well placed to own.
- **Build-an-AI-keyboard SDKs** (Fleksy GenAI) are *why* the genre exploded — basic rewrite/reply is
  commoditizing, so AIDE's moat must be **tools (MCP) + local models + cross-app memory**, not rewrite
  alone.

_For AIDE, the wedge is the intersection of #2/#3/#4 **at the keyboard surface** — tools and agency,
running locally, woven into typing — which no incumbent keyboard currently owns._
