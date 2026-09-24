package com.sabreware.aide.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.sabreware.aide.core.designsystem.resources.Res
import com.sabreware.aide.core.designsystem.resources.google_sans_variable
import com.sabreware.aide.core.designsystem.resources.libre_baskerville_variable
import com.sabreware.aide.core.designsystem.resources.libre_baskerville_variable_italic
import com.sabreware.aide.core.domain.prefs.ChatFontStyle
import org.jetbrains.compose.resources.Font

// CMP's resource `Font()` is @Composable, so the app font families are built inside composition and handed
// down via CompositionLocals — the app-font "single knob" is now [ProvideAideFonts] + these locals.

/** Google Sans — OFL variable sans. CMP's [Font] maps the requested [FontWeight] onto the variable `wght`
 *  axis, so each role picks its true weight (no explicit variationSettings needed). */
@Composable
private fun googleSansFamily(): FontFamily = FontFamily(
    Font(Res.font.google_sans_variable, weight = FontWeight.Normal),
    Font(Res.font.google_sans_variable, weight = FontWeight.Medium),
    Font(Res.font.google_sans_variable, weight = FontWeight.SemiBold),
    Font(Res.font.google_sans_variable, weight = FontWeight.Bold),
)

/** Libre Baskerville — OFL variable serif (`wght` 400–700 + italic). Text serif; large x-height reads
 *  well at body sizes. Axis caps at 700, so weights above Bold map to the 700 master. */
@Composable
private fun libreBaskervilleFamily(): FontFamily = FontFamily(
    Font(Res.font.libre_baskerville_variable, weight = FontWeight.Normal),
    Font(Res.font.libre_baskerville_variable, weight = FontWeight.Medium),
    Font(Res.font.libre_baskerville_variable, weight = FontWeight.SemiBold),
    Font(Res.font.libre_baskerville_variable, weight = FontWeight.Bold),
    Font(Res.font.libre_baskerville_variable_italic, weight = FontWeight.Normal, style = FontStyle.Italic),
    Font(Res.font.libre_baskerville_variable_italic, weight = FontWeight.Medium, style = FontStyle.Italic),
    Font(Res.font.libre_baskerville_variable_italic, weight = FontWeight.SemiBold, style = FontStyle.Italic),
    Font(Res.font.libre_baskerville_variable_italic, weight = FontWeight.Bold, style = FontStyle.Italic),
)

// App font roles — read via these locals so the whole app restyles from [ProvideAideFonts]. AppSans = all
// UI chrome; AppSerif = hero greeting, "Aide" wordmark, chat prose.
val LocalAppSans: ProvidableCompositionLocal<FontFamily> = staticCompositionLocalOf { FontFamily.SansSerif }
val LocalAppSerif: ProvidableCompositionLocal<FontFamily> = staticCompositionLocalOf { FontFamily.Serif }

/** Builds the app font families (in composition) and provides them as [LocalAppSans]/[LocalAppSerif]. */
@Composable
fun ProvideAideFonts(content: @Composable () -> Unit) {
    val sans = googleSansFamily()
    val serif = libreBaskervilleFamily()
    CompositionLocalProvider(
        LocalAppSans provides sans,
        LocalAppSerif provides serif,
        content = content,
    )
}

/** Compressed type scale, M3 sizes: 30 display/hero · 22 headline+title · 18 subtitle · 16 bodyLarge +
 *  labels · 14 bodyMedium (the one description size) · 12 caption. Display = serif, the rest = sans.
 *  Reads the app-font locals ([ProvideAideFonts] must wrap it — [com.sabreware.aide.core.designsystem.theme.AideTheme] does). */
@Composable
fun aideTypography(): Typography {
    val appSans = LocalAppSans.current
    val appSerif = LocalAppSerif.current
    return Typography(
    displayLarge = TextStyle(
        fontFamily = appSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = appSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = appSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = appSans,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = appSans,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = appSans,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = appSans,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    // Menu and list text is regular weight: a row's title, a tile's label and a section label read clean and
    // lean, and the gray subtitle beneath carries the hierarchy. Only headings (titleLarge) stay bold.
    titleMedium = TextStyle(
        fontFamily = appSans,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = appSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = appSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = appSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = appSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = appSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = appSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = appSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    )
}

/**
 * The chat text-size multiplier (1.0 = default), provided app-wide from the user's "Font Size" setting
 * and overridden locally by the live preview. Read ONLY by chat message rendering ([aideMarkdownTypography]
 * + the bubble text styles) so adjusting it scales chat prose without touching app chrome.
 */
val LocalFontScale = staticCompositionLocalOf { 1f }

/**
 * Chat-prose typeface, provided app-wide beside [LocalFontScale]. Read ONLY by chat prose rendering — chrome
 * and the user's own typed turns stay on the app sans.
 */
val LocalChatFontStyle = staticCompositionLocalOf { ChatFontStyle.Serif }

@Composable
fun chatFontFamily(style: ChatFontStyle = LocalChatFontStyle.current): FontFamily = when (style) {
    ChatFontStyle.Serif -> LocalAppSerif.current
    ChatFontStyle.Sans -> LocalAppSans.current
    ChatFontStyle.Mono -> FontFamily.Monospace
}

/** Multiply a style's font + line height by [scale] (no-op at 1.0; skips unspecified units). */
fun TextStyle.scaleFont(scale: Float): TextStyle =
    if (scale == 1f) {
        this
    } else {
        copy(
            fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
            lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
        )
    }

@Composable
fun aideMarkdownTypography(scale: Float = LocalFontScale.current): MarkdownTypography {
    val t = MaterialTheme.typography
    val prose = chatFontFamily()
    // remember() else streaming tokens build fresh instances and invalidate Markdown stability → flicker.
    return remember(t, scale, prose) {
        val codeStyle = t.bodyMedium.copy(fontFamily = FontFamily.Monospace).scaleFont(scale)
        val serifBody = t.bodyLarge.copy(fontFamily = prose).scaleFont(scale)
        DefaultMarkdownTypography(
            h1 = t.headlineLarge.scaleFont(scale),
            h2 = t.headlineMedium.scaleFont(scale),
            h3 = t.headlineSmall.scaleFont(scale),
            h4 = t.titleLarge.scaleFont(scale),
            h5 = t.titleMedium.copy(fontWeight = FontWeight.SemiBold).scaleFont(scale),
            h6 = t.titleSmall.copy(fontWeight = FontWeight.SemiBold).scaleFont(scale),
            text = serifBody,
            code = codeStyle,
            inlineCode = codeStyle,
            quote = serifBody.copy(fontStyle = FontStyle.Italic),
            paragraph = serifBody,
            ordered = serifBody,
            bullet = serifBody,
            list = serifBody,
            textLink = TextLinkStyles(
                style = SpanStyle(fontWeight = FontWeight.SemiBold),
            ),
            table = t.bodyMedium.scaleFont(scale),
        )
    }
}

/** The plain-text body style matching [aideMarkdownTypography]'s paragraph style. Used for the streaming
 *  tail + placeholders in `StreamingMarkdown` so they read continuously with finished Markdown blocks. */
@Composable
fun aideMarkdownBodyStyle(scale: Float = LocalFontScale.current): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(fontFamily = chatFontFamily()).scaleFont(scale)
