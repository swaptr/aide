# IME keyboard touch engine

The QWERTY surface uses a 1:1 port of **AOSP LatinIME's** touch pipeline (the same model used by
HeliBoard / OpenBoard / AnySoftKeyboard / FUTO, and by FlorisBoard via a single `pointerInteropFilter`).
Keys are inert Android Views (drawing + theming only); **one surface owns the raw `MotionEvent`
stream and does manual per-pointer hit-testing**. This is the only architecture that reliably
registers fast taps — per-key click/gesture listeners drop them.

## Why we were ghosting (the bug this replaced)

Every key carried a 3 dp layout margin, so there was a **6 dp dead strip between every key and every
row**. The old host hit-tested with strict bounds containment and dropped any touch that landed in a
gap (`hitTest(x,y) ?: return`). At speed, fingers land in those gaps constantly → silently lost keys
and lost actions (space / enter / backspace too). LatinIME never drops: its hit-boxes include the
gaps and it snaps every touch to the nearest key.

## Architecture (files in `:platform:android:surface:ime`, package `platform.android.surface.ime.widget`)

| File | LatinIME analogue | Role |
|---|---|---|
| `KeyTouchDispatcher.kt` (`KeyboardTouchHost`) | `MainKeyboardView` | Owns the gesture (`onInterceptTouchEvent=true` + `requestDisallowInterceptTouchEvent` on DOWN), routes each pointer to its tracker, holds the engine + tuning, rebuilds the detector snapshot on layout. |
| `KeyDetector.kt` | `KeyDetector` / `ProximityInfo` | `(x,y) → Key`. Hit-boxes are grown by each key's margin so gaps belong to a key (no dead zone) and outer boxes clamp to the host edge; `detect()` returns the first containing cell, else nearest centre. |
| `PointerTracker.kt` | `PointerTracker` | One per finger. Press on DOWN, **code on UP** keyed to the down-key with 8 dp hysteresis; repeatable keys emit on DOWN + auto-repeat; long-press opens the more-keys popup; historical-sample replay; up→down noise filter. |
| `PointerTrackerQueue.kt` | `PointerTrackerQueue` | Active trackers in press order → n-key rollover (commit older fingers first on out-of-order release). |
| `KeyTimerHandler.kt` | `TimerHandler` / `TimerProxy` | Long-press + auto-repeat as delayed **UI-thread** messages (no background timers). |

## Behaviour notes

- **Code fires on UP**, not DOWN (LatinIME model). Instant press highlight + haptic on DOWN; the
  character lands on lift, on the key captured at DOWN. A sloppy tap that drifts < 8 dp still types
  the key you pressed. This also removed the old speculative-commit-then-retract path
  (`onReplaceLastChar` / `onDeleteLastChar` / `onCancelBase` are gone).
- **Backspace** is repeatable: first delete on DOWN, then auto-repeat; switches to word-delete after
  `KeyboardSpec.REPEAT_WORDS_AFTER`.
- **Long-press** (alts keys) opens the popup; release commits the highlighted alternate, slide-off
  commits nothing, a quick tap commits the base char. While a popup is open, extra fingers are
  ignored (matches LatinIME).
- **Press feedback** mirrors LatinIME's `setPressedKeyGraphics` — a pressed-*graphic* swap, **not** a
  ripple. A `RippleDrawable` draws two coordinated layers (an expanding foreground ring + an
  area-fade background); driven by the framework they cohere, but under our **manual `isPressed` +
  release-linger** they desync and read as a *double* animation (worst on the filled Tonal/Accent
  keys). Keys therefore use a `StateListDrawable` that swaps to a flat pressed colour (native,
  framework-driven buttons keep the ripple). Performant — a drawable state change + one reused
  `postDelayed`, no per-tap allocation:
  1. On DOWN: `view.isPressed = true` → the key's pressed colour appears instantly (borderless keys
     use `AideButtonStyle.Borderless.pressedBg`; filled keys a translucent fg overlay via
     `pressedTint`); plus `KEYBOARD_TAP` haptic.
  2. On UP: the highlight **lingers `PRESS_HOLD_MS` (80 ms)** past release via the reused
     `AideButton.unpressAction` runnable, so even a very fast tap flashes visibly. Slide-off / cancel
     clear it immediately; a re-press cancels the pending linger.

## User settings (adjustable appearance)

Four knobs are user-adjustable (Settings → Keyboard), each a typed `PrefKey` on `KeyboardPrefs`
(`:platform:android:surface:ime`, `ime/prefs/KeyboardPrefs.kt`), persisted in the `user_prefs` DataStore,
read via `PreferenceStore` and applied **live** to the running keyboard — no restart:

| Setting | Key (`KeyboardPrefs`) | Effect |
|---|---|---|
| Key style | `KeyStyle` (`kb_key_style`, `KeyStyle` enum, default `Borderless`) | Borderless ↔ Bordered — swaps the letter/space/punct resting background. |
| Keyboard height | `Height` (`kb_height`, `KeyboardHeight` enum, `scale`) | Scales `keyHeight` + the body height (Short/Default/Tall). |
| Number row | `NumberRowEnabled` (`kb_number_row`, default off) | Adds a digit row atop LETTERS (a 5th row → that layer is taller; the IME resizes on layer switch). |
| AI features | `AiEnabled` (`kb_ai_enabled`, default on) | Off hides the whole assistant `TransformBar` (incl. mic) and reclaims its height — a plain keyboard. |

Wiring: the four prefs are combined into `TransformController.keyboardAppearance`
(`StateFlow<KeyboardAppearance>`, seeded **Eagerly** so `KeyboardPage` reads a correct value at build
time — no first-frame flash). `KeyboardPage` collects it in `onAttach`; a change rebuilds the
`KeyFactory`/`KeyboardRowBuilder` (resolved `borderless` / `keyHeightDp` / `keyMarginDp` / `numberRow`)
and re-runs `rebuild()`, which recomputes `desiredHeightDp` and calls `notifyHeightChanged()`. The
service combines `aiEnabled` with `headerVisible` to gate the bar. The system IME-settings gear is
wired via `android:settingsActivity` in `res/xml/method.xml` (→ `MainActivity`).

## Borderless keys

`KeyStyle.Borderless` is the default, matching Gboard's preferred look (see
`docs/keyboard-preferences.md`): letter / space / punctuation keys have **no resting box** — a
key-shaped highlight (`AideButtonStyle.Borderless`, a pressed-state background) appears only on
touch. Keys stay **spaced** (same `keyMargin` as the bordered look), so the highlight reads as a
distinct, inset rounded key with gaps to its neighbours — **not** an edge-to-edge blob (the earlier
`margin = 0` full-bleed made the tinted function keys merge into a gray slab). Borderless vs bordered
is now a **pure per-key resting-background swap**; height and geometry are identical, so the keyboard
never shifts and the touch geometry (already gapless via the detector) is unchanged. Function keys
(shift / backspace / symbols / enter) keep their tint in both modes.

## Engine tuning

The five timing/threshold knobs are bundled into `PointerTracker.Tuning` (built once by the host
from the constants below) rather than scattered across the `Env` interface.

## Tuning constants (LatinIME defaults, `KeyboardTouchHost`)

| Constant | Value | Meaning |
|---|---|---|
| `LONGPRESS_MS` | 300 ms | long-press → popup (×3 while sliding) |
| `REPEAT_START_MS` | 400 ms | delay before auto-repeat begins |
| `REPEAT_INTERVAL_MS` | 50 ms | auto-repeat interval |
| `HYSTERESIS_DP` | 8 dp | finger must clear the key edge by this to switch keys |
| `NOISE_TIME_MS` / `NOISE_DIST_DP` | 40 ms / 12.6 dp | up→down firmware-bounce filter |

## Performance & memory

Keys are inert `AideButton` views (theming/a11y) — the canonical alternative to LatinIME's
data-only keys drawn on a Canvas; validated by FlorisBoard (Compose-drawn keys, touch routed through
one filter). We pay N idle views for simpler rendering, and keep the touch path Canvas-grade:

- **Allocation-free keypress path.** `detect()` is a single early-returning pass over ~30 cells; the
  tracker caches its current `KeyDetector.Entry`, so a steady finger short-circuits on one
  `contains()`. `KeyTimerHandler` uses pooled `obtainMessage`, `unpressAction` is one reused
  Runnable per key, and `onTap`/`onRepeat` lambdas are built once at key creation — no per-tap
  garbage. A grid (LatinIME's 32×16 `ProximityInfo`) is unnecessary at this key count.
- **No leaks.** `onDetachedFromWindow` cancels every timer and tracker; `KeyboardPage.onDetach`
  dismisses the more-keys `PopupWindow` (else its window token could outlive the page); `pageScope`
  / `requestEditorJob` are cancelled on detach/destroy. No static Context/View refs.
- **Raw (screen) coords** are read only on the popup path, not for every move sample.
