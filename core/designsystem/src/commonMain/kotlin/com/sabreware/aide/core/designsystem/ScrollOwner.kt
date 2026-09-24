package com.sabreware.aide.core.designsystem

/**
 * Who scrolls a surface's body. **The default everywhere is [Surface]**, so content never has to ask for a
 * scroll and nothing is ever clipped because someone forgot one.
 *
 * The rule is the one Compose itself imposes: a vertically scrolling container measures its content with an
 * unbounded height, and a same-axis scroller inside it (`LazyColumn`, `verticalScroll`, a pager of lists)
 * throws on that unbounded height. So there are exactly two kinds of body, and the kind is a property of the
 * CONTENT, never of where it is shown:
 *
 * - [Surface] — ordinary content (forms, menus, text, cards). The host wraps it in a bounded `verticalScroll`
 *   under its pinned header, so it takes its natural height and scrolls whatever the window cannot fit — a
 *   phone in landscape, a small desktop window, a keyboard taking half the screen. The content must NOT
 *   scroll itself.
 * - [Content] — a body that already is a scroller: a `LazyColumn`, a tabbed pager of lists, a search results
 *   list. The host hands it a height-BOUNDED slot and the content scrolls inside it. Only these declare
 *   themselves.
 *
 * The hosts that apply it: [AppDialog] (always [Surface] for a leaf dialog), [PageScaffold] (the page says
 * which, default [Surface]), [AppPage] (always [Surface]). [PlaceholderLayout] infers it from its
 * constraints. Raw [AppScaffold] is for fill-height list screens only, where the list is the scroller.
 */
enum class ScrollOwner { Surface, Content }
