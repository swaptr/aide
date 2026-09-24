# What users prefer in a mobile keyboard

Research-backed reference for designing AIDE's on-screen keyboard (IME). Scope: Android + iOS, 2023–2026,
weighted toward Gboard / SwiftKey behaviour, community sentiment, and HCI studies. Each item lists the
**preference → why → source → priority** for a *new* keyboard.

> Companion doc: [`keyboard-touch.md`](keyboard-touch.md) describes our touch engine (an AOSP LatinIME port).
> That engine already does the one thing that makes borderless safe: **hit-boxes are grown by each key's
> margin so gaps belong to a key — there are no dead zones.** Read the two together.

**Priority legend** (for a brand-new keyboard): **High** = ship in v1 / correctness or top switch-reason ·
**Med** = strong "nice", schedule soon · **Low** = optional / power-user / later.

## TL;DR — the borderless question

- Gboard's **default is borderless** (no key borders). "Key borders" is an *opt-in* toggle under Theme.
  Borderless is the modern default specifically because it looks clean and **keeps keys looking full-size**.
  ([Yahoo/Tech](https://tech.yahoo.com/apps/articles/gboards-rounded-keys-almost-youre-171742939.html),
  [Greenbot](https://www.greenbot.com/article/3095909/5-awesome-google-keyboard-features-you-probably-dont-know-about.html))
- The border is a **pure visual** (a background drawable). **The touch target is the whole cell either way** —
  turning borders off does *not* create dead zones. Google confirmed this when its over-rounded-keys
  experiment kept "touch-sensitive areas unchanged" yet still got reverted.
  ([ChromeUnboxed](https://chromeunboxed.com/gboard-tested-very-rounded-keys-we-all-hated-it-and-its-been-reverted/))
- **Critical nuance:** users hate it when the *visual* key shrinks inside its cell (heavy rounding / fat gaps),
  even when the touch area is identical — "felt like my targets were so much smaller."
  ([ChromeUnboxed](https://chromeunboxed.com/gboard-tested-very-rounded-keys-we-all-hated-it-and-its-been-reverted/),
  [Android Authority](https://www.androidauthority.com/gboard-rounded-keys-beta-3532723/))
  So **full-bleed borderless (key fills the cell) is the *good* end of this axis; rounded/inset keys are the
  hated end.** Borderless-by-default is the right call — *as long as the key colour visually fills the cell.*

---

## 1. Key borders vs borderless — **High**

**Preference:** Borderless **by default**, with an optional **"Key borders" toggle** (Gboard's exact model).

**Why:**
- Gboard ships borderless and hides borders behind a Theme toggle; Google made the clean look the default and
  added the toggle so people can revert. The bordered style reads as a "more traditional keyboard vibe" that
  some find "nicer to type on."
  ([Greenbot](https://www.greenbot.com/article/3095909/5-awesome-google-keyboard-features-you-probably-dont-know-about.html),
  [Gametabletz](https://www.gametabletz.com/2016/08/how-to-add-border-on-each-key-of-google.html),
  [Yahoo/Tech](https://tech.yahoo.com/apps/articles/gboards-rounded-keys-almost-youre-171742939.html))
- **Touch target is unaffected by the border.** When Google tested heavily rounded keys, the
  "touch-sensitive areas remained unchanged" — the border/shape is drawing only. The *visual* change alone
  tanked perceived accuracy and was reverted. Lesson: it is safe (and standard) to hide the border, but you
  must not let the drawn key shrink inside its cell.
  ([ChromeUnboxed](https://chromeunboxed.com/gboard-tested-very-rounded-keys-we-all-hated-it-and-its-been-reverted/),
  [Android Authority](https://www.androidauthority.com/gboard-rounded-keys-beta-3532723/))
- When Gboard later shipped optional rounded keys it kept them **optional** with a first-run banner to revert —
  reinforcing "borderless/flat default + toggle for the rest."
  ([Phandroid](https://phandroid.com/2025/05/01/gboards-material-you-update-is-coming-with-optional-rounded-keys/),
  [SahmCapital](https://www.sahmcapital.com/news/content/google-is-testing-circular-and-pill-shaped-gboard-keys-but-do-you-actually-want-them-2025-03-07))

**When borders DO help:** legibility on small screens / portrait, low-vision and accessibility users, and
larger keys where the outline aids targeting — some users report borderless makes it harder to hit the right
key. Keep the toggle for them.
([Gametabletz](https://www.gametabletz.com/2016/08/how-to-add-border-on-each-key-of-google.html))

---

## 2. Key spacing / gaps / dead zones — **High**

**Preference:** A small **visual** gap is fine and even helps the eye separate keys, **but every pixel must
still belong to a key.** Never ship a *touch* gap (a dead zone).

**Why:**
- On physical keyboards, accuracy was **better with a 5 mm gap than a 1 mm gap**, and a 17 mm key pitch beat
  16 mm — gaps and pitch genuinely aid accuracy *when the keys themselves are discrete physical targets*.
  ([Pereira et al., "Mind the Gap," Part 3 / Human Factors](https://journals.sagepub.com/doi/abs/10.1177/0018720812465005),
  [ResearchGate](https://www.researchgate.net/publication/277078471_Mind_the_Gap_The_Effect_of_Keyboard_Key_Gap_and_Pitch_on_Typing_Speed_Accuracy_and_Usability_Part_3))
- On a **touchscreen**, a gap that is also an inactive region is harmful: dead zones "cause key strokes to be
  lost" and break the keyboard's touch-recognition/autocorrect model, especially for off-centre and
  lateral-drift taps that are natural to fast typing.
  ([USPTO proximity-touch patent](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/8790025))
- This is exactly the bug our own engine fixed: every key carried a 3 dp margin → a 6 dp dead strip between
  keys → fast taps silently lost. The fix was to grow hit-boxes so the gap belongs to a key.
  ([keyboard-touch.md](keyboard-touch.md))

**Takeaway:** decouple **visual gap** from **touch gap**. Draw whatever gap looks good (or none, for
full-bleed); keep the touch grid gapless via nearest-key snapping.

---

## 3. Keyboard height & key size — **High** (size/height) · **Med** (adjustable) · **Low–Med** (one-handed/float/split)

**Preference:** Comfortable default height; user-adjustable height; effective key ≥ ~9–10 mm.

**Why:**
- **Key size:** thumb-target studies find **≥ 9.2 mm (discrete) / 9.6 mm (serial)** is enough with no accuracy
  penalty above ~7.7 mm; a **15 mm key is comfortably accurate**, while a cramped **13 mm key typed ~15%
  slower** with more shoulder load. Don't go below ~9–10 mm effective width.
  ([Parhi/Sears/MS Research](https://www.microsoft.com/en-us/research/wp-content/uploads/2006/01/parhi-mobileHCI06.pdf),
  [Springer key-size study](https://link.springer.com/chapter/10.1007/978-3-642-39182-8_28),
  [ResearchGate](https://www.researchgate.net/publication/261950938_The_Effect_of_Key_Size_of_Touch_Screen_Virtual_Keyboards_on_Productivity_Usability_and_Typing_Biomechanics))
- **Adjustable height** is a marquee Gboard feature: **5 levels** (short → tall). Users tune it to trade screen
  real estate vs key size.
  ([Computerworld](https://www.computerworld.com/article/1611264/gboard-android-adjustments.html),
  [MakeTechEasier](https://www.maketecheasier.com/make-keyboard-bigger-android/))
- **One-handed / floating / split** modes: one-handed (shove left/right) is broadly loved on big phones and
  for accessibility; floating (reposition + resize w & h) and split (tablets/foldables) are niche on a phone.
  SwiftKey is praised for resizing **both height and width**.
  ([SiliconANGLE](https://siliconangle.com/2016/05/03/google-keyboard-for-android-gets-major-update-one-handed-mode-adjustable-keyboard-heights-more/),
  [Android Police](https://www.androidpolice.com/replaced-gboard-with-swiftkey/))
- **Bottom padding for gesture-nav (do not skip — correctness):** with zero bottom margin the home-swipe
  gesture region overlaps the spacebar/bottom row, so users trigger Home instead of typing space. Reserve the
  bottom inset / honour system gesture insets so the bottom row sits above the gesture strip.
  ([XDA](https://xdaforums.com/t/how-to-fix-the-gesture-nav-bar-and-prevent-it-from-blocking-the-bottom-part-of-the-keyboard-even-when-the-gesture-bar-and-hint-are-hidden.4656287/),
  [Chris Banes / Android Developers](https://medium.com/androiddevelopers/gesture-navigation-handling-visual-overlaps-4aed565c134c))

---

## 4. Glide/swipe, number row, long-press symbols

### 4a. Glide / swipe typing — **Med** (high value, high effort)
**Preference:** Offered and **on by default**, but never at the cost of tap accuracy.
**Why:** Swipe cuts typos **up to ~48%** in real messaging and reduces finger lifts/fatigue; steady-rhythm
swipers had **~22% lower error rates**. Pure *speed* is a wash or slightly slower than two-thumb tapping
(~7.67 s vs ~8.07 s in one test), so it's an accuracy/comfort feature, not a speed one. **Caveat:** an
over-eager swipe recogniser that reads ordinary taps as gestures is a real complaint — disabling slide-to-type
"fixed" typing for some users. Gate gesture detection behind a clear slop threshold.
([lifetips/Alibaba](https://lifetips.alibaba.com/tech-efficiency/gesture-typing-vs-swipe-keyboard-which-cuts-typos),
[HowToGeek](https://www.howtogeek.com/is-swiping-really-faster-than-typing-on-a-phone-keyboard/),
[MacObserver](https://www.macobserver.com/news/iphone-typing-suddenly-feels-broken-one-simple-keyboard-toggle-is-fixing-it-for-many/))

### 4b. Number row (persistent top row) — **Med**
**Preference:** Provide it as a **toggle**, default off. Power users and password/2FA/number-heavy typists love
an always-on row; the cost is one row of vertical space. Gboard exposes exactly this toggle.
([HowToGeek](https://www.howtogeek.com/285597/how-to-add-a-persistent-number-row-to-androids-gboard-keyboard/),
[Computerworld](https://www.computerworld.com/article/1612658/gboard-settings-android.html))

### 4c. Long-press symbols vs dedicated keys — **Med**
**Preference:** Support **long-press for symbols** (hint glyph in the key corner, hold to access) so the base
layout stays roomy; keep a dedicated symbols layer too. It's an "extra access path," not a replacement.
([Computerworld](https://www.computerworld.com/article/1612658/gboard-settings-android.html))

---

## 5. Haptics, sound, key-press popup preview

### 5a. Haptic feedback — **High** (cheap, strong upside)
**Preference:** Subtle per-key haptic **on by default**, with intensity control.
**Why:** Haptic keyclick **improved typing speed and reduced errors** on flat keyboards (Microsoft Research),
and users rate tactile feedback as positively as audio and above visual-only. Two cues matter: a **click
confirmation** and (harder on glass) a sense of **valleys between keys**. Watch the failure mode: a weak
linear-motor "buzz" feels like buzzing, not a keypress, and users dislike it — tune the waveform/intensity.
([Microsoft Research, Ma et al. WHC 2015](https://www.microsoft.com/en-us/research/wp-content/uploads/2015/06/Ma_etal_WHC2015.pdf),
[ScienceDirect vibrotactile study](https://www.sciencedirect.com/science/article/abs/pii/S0003687020302192),
[Hindawi user-adaptive tactile](https://www.hindawi.com/journals/misy/2018/6126140/))

### 5b. Key-press sound — **Low**
**Preference:** Provide it but **default off** (most users mute it); it adds no accuracy benefit over haptic in
studies. Optional, per the same haptics research.
([Microsoft Research](https://www.microsoft.com/en-us/research/wp-content/uploads/2015/06/Ma_etal_WHC2015.pdf))

### 5c. Key-press popup preview (the zoomed key above the finger) — **Low–Med**, default **off**
**Preference:** Offer it, **default off**, easily toggled.
**Why:** Experienced phone typists watch the *text field*, not the keyboard, and rely on muscle memory; the
flashing enlarged glyph becomes visual noise that "feels distracting" and slower, and it's a **shoulder-surf /
privacy** leak (the pop-ups are even video-keyloggable). It still helps novices and large-key/accessibility
modes, so keep it as an option. Gboard, Samsung, iOS, and AnySoftKeyboard all expose a disable toggle.
([Android Police](https://www.androidpolice.com/i-turned-off-these-gboard-features-typing-feels-better/),
[MakeUseOf](https://www.makeuseof.com/tag/disable-key-press-popups-mobile/),
[Mobigyaan](https://www.mobigyaan.com/disable-character-pop-up-in-google-keyboard-android))

> Note: our engine already shows an instant **press highlight + haptic on DOWN** ([keyboard-touch.md](keyboard-touch.md)),
> which delivers the feedback benefit without the popup's downsides — a good reason to keep the popup off by default.

---

## 6. Customization, cursor control, clipboard, undo/redo, emoji, voice

| Feature | Preference | Why / evidence | Priority |
|---|---|---|---|
| **Spacebar-swipe cursor control** | Yes | Beloved precision feature — slide the spacebar to move the caret; far better than poking at text. ([KeyboardApps](https://www.keyboardapps.net/gboard-gesture-delete-and-cursor-controls), [Pocket-lint](https://www.pocket-lint.com/how-to-use-the-hidden-cursor-on-gboard/)) | **Med** |
| **Delete-key swipe (word delete)** | Yes | Hold backspace + swipe left highlights whole words to delete, with re-add — a Gboard "best kept secret." ([Android Police](https://www.androidpolice.com/gboard-backspace-psa/)) | **Med** |
| **Clipboard manager** | Yes | History + **pinned** snippets (routing numbers, canned replies) beat the one-item system clipboard. ([Android Authority](https://www.androidauthority.com/gboard-features-3533885/)) | **Low–Med** |
| **Undo/redo** | Nice | Pairs with delete-swipe (re-add deleted words); reduces "retype the whole thing." | **Low** |
| **Emoji / GIF / sticker search** | Yes | Universal search is a standout Gboard strength users cite. ([HowToGeek](https://www.howtogeek.com/285824/how-to-search-for-emoji-and-gifs-in-androids-gboard-keyboard/)) | **Low–Med** |
| **Themes / customization** | Some | SwiftKey's 200+ themes / custom-image / key-shape themes are a top reason people switch *to* it; Gboard stays minimalist Material with a dynamic per-app theme. Don't over-invest, but a few good themes + a dynamic Material theme matter. ([Toolify](https://www.toolify.ai/ai-news/swiftkey-vs-gboard-the-ultimate-keyboard-battle-1694631), [AirDroid](https://www.airdroid.com/file-transfer/gboard-vs-swiftkey/), [MakeUseOf](https://www.makeuseof.com/why-i-switched-from-gboard-to-swiftkey/)) | **Low–Med** |
| **Voice typing** | Optional | Users **prefer the keyboard over voice** (ease 4.11 vs 2.46; 21 of 28 chose keyboard) for privacy + "reflection while typing." Provide a mic, but it's not core. ([arXiv mobile-writing study](https://arxiv.org/pdf/2410.00449)) | **Low** |

---

## 7. Complaints that make people switch keyboards — **High** (avoid all of these)

These are the recurring reasons users abandon a keyboard. They dominate every "why I switched" thread, and they
matter more than any feature.

- **Input lag** — "every keypress lands a beat late." Top-tier dealbreaker; keep the input/render path tight.
  ([MacObserver](https://www.macobserver.com/news/ios-26-3-keyboard-issues-whats-fixed-and-whats-not/),
  [KeyboardTester](https://keyboardtester.click/blog/keyboard-not-typing-lagging-sticky-fix-clean-guide.php))
- **Ghost taps / missed keystrokes** — keys silently failing, esp. around i/o, a/s, and the spacebar. This is
  precisely the dead-zone failure mode our engine was rebuilt to kill (§2).
  ([MacObserver](https://www.macobserver.com/news/iphone-typing-suddenly-feels-broken-one-simple-keyboard-toggle-is-fixing-it-for-many/),
  [AttackShark](https://attackshark.com/blogs/knowledges/diagnosing-modifier-ghosting-fix-multi-key-input),
  [keyboard-touch.md](keyboard-touch.md))
- **Aggressive autocorrect** — rewriting/merging multiple words into "a single unreadable line." Be
  conservative; make corrections obvious and easily reversible.
  ([MacObserver](https://www.macobserver.com/news/iphone-typing-suddenly-feels-broken-one-simple-keyboard-toggle-is-fixing-it-for-many/))
- **Over-eager swipe detection** misreading taps as glides (see §4a) — gate gestures behind real slop.
- **Small/cramped keys** — measurably slower (§3); respect minimum key size and height.

---

## Implementing borderless in our IME — the standard approach

This is the design owner's headline goal: keys filling the whole keyboard, no gaps. Here is the standard,
no-dead-zone way to do it, mapped onto our existing engine ([keyboard-touch.md](keyboard-touch.md)).

**Core invariant: touch geometry is constant; only drawing changes.**

1. **Touch target = the full cell, always.** Keep the existing `KeyDetector` model: hit-boxes are grown by each
   key's margin so the inter-key gaps belong to a key, outer boxes clamp to the host edge, and a miss snaps to
   the nearest key centre. The border toggle, the gap size, the corner radius — **none of them touch this.**
   There is no code path where a visible gap becomes an inactive region. (Already true in our engine.)
2. **The "border" is just an inset background drawable on the (inert) key view.** Keys are drawing/theming-only
   Views. Model the key background as a single themeable drawable:
   - **Borderless / full-bleed (default):** the key surface fills (≈) the entire cell — zero or near-zero
     inset, no stroke, flat fill (optionally a 1–2 dp radius). Adjacent fills meet with no visible seam → the
     "keys filling the whole keyboard" look. Use a subtle pressed-state fill for feedback.
   - **Bordered (toggle on):** draw the same cell with an inset rounded-rect background + outline/elevation,
     leaving a small *visual* gap. Layout and hit-boxes are byte-for-byte identical to borderless.
3. **Keep total keyboard height and the cell grid constant** across the toggle and across themes. The border is
   purely a per-key drawable swap; never re-measure rows or shift the bottom row when it changes.
4. **Do NOT shrink the drawn key inside its cell.** This is the Gboard rounded-keys lesson: heavy inset/rounding
   made keys *feel* smaller and was reverted even though touch areas were unchanged
   ([ChromeUnboxed](https://chromeunboxed.com/gboard-tested-very-rounded-keys-we-all-hated-it-and-its-been-reverted/)).
   Full-bleed is the safe, *preferred* direction precisely because the fill maximises perceived key size.
5. **Expose it as a Theme toggle, default borderless** (mirror Gboard). Keep the bordered option for
   small-screen / low-vision / accessibility users (§1).
6. **Decouple visual gap from touch gap as a tunable.** If a hairline gap looks better than a perfectly seamless
   sheet, draw a 1–2 dp inset on the *background only* — the hit-box still fills it. This lets us A/B "seamless
   full-bleed" vs "1 dp hairline" without ever creating a dead zone.

**One-line spec:** *the border is paint, not geometry — the cell always eats the gap.*

---

## Priority cheat-sheet

| # | Item | Priority |
|---|---|---|
| 1 | No dead zones / touch target = full cell | **High** (done) |
| 2 | Borderless default + optional "Key borders" toggle | **High** |
| 3 | Full-bleed (don't shrink perceived key size) | **High** |
| 4 | Min key size ~9–10 mm + comfortable height | **High** |
| 5 | Gesture-nav bottom inset (no spacebar/Home conflict) | **High** |
| 6 | Subtle haptic on key press, with intensity control | **High** |
| 7 | Low latency / no lag, no ghost taps, sane autocorrect | **High** |
| 8 | Glide/swipe typing (gated gesture slop) | **Med** |
| 9 | Number-row toggle | **Med** |
| 10 | Long-press symbols | **Med** |
| 11 | Adjustable keyboard height | **Med** |
| 12 | Spacebar-swipe cursor + delete-swipe | **Med** |
| 13 | One-handed mode | **Low–Med** |
| 14 | Themes / dynamic Material theme | **Low–Med** |
| 15 | Clipboard manager, emoji/GIF search | **Low–Med** |
| 16 | Key-press sound (default off) | **Low** |
| 17 | Key-press popup preview (default off) | **Low–Med** |
| 18 | Voice typing | **Low** |
| 19 | Floating / split modes (phone) | **Low** |
