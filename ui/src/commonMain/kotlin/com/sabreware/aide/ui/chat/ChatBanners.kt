package com.sabreware.aide.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.AppNotice
import com.sabreware.aide.core.designsystem.LocalPlaceholderStyle
import com.sabreware.aide.core.designsystem.NoticeSeverity
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.PlaceholderLayout
import com.sabreware.aide.core.designsystem.PlaceholderStyle
import com.sabreware.aide.core.designsystem.currentTimeMillis
import com.sabreware.aide.core.designsystem.resources.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
internal fun IncognitoEmptyState(modifier: Modifier = Modifier) {
    Placeholder(
        modifier = modifier.fillMaxSize(),
        iconRes = Res.drawable.ic_lc_ghost,
        subtitle = "Incognito chats aren't saved to history or used to train models. " +
            "Closing the chat discards it.",
    )
}

/**
 * The hero greeting, plus the model call-to-action when [modelPrompt] is non-null.
 *
 * The CTA lives here rather than in a banner on purpose: a top banner displaced the sidebar and app bar on
 * every route, and the top bar's "No model" pill already carries the same state and opens the same sheet.
 *
 * Drawn in [PlaceholderLayout] like every other empty state (incognito included), so switching between
 * them keeps the content in one place. The prompt hangs BELOW the greeting, so it can fade in or out
 * (a settled answer landing) without moving the greeting a single pixel.
 */
@Composable
internal fun EmptyStateGreeting(
    errorMessage: String?,
    modelPrompt: String?,
    modelActionLabel: String,
    onSetUpModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val greeting = rememberGreeting()
    // The chat home is the one hero empty state: it sits lower than the app-wide position, near the centre.
    CompositionLocalProvider(LocalPlaceholderStyle provides PlaceholderStyle.Hero) {
        PlaceholderLayout(modifier = modifier.fillMaxSize()) {
            Text(
                text = greeting,
                // displayMedium = the serif 30sp hero slot (was headlineMedium/sans).
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            // Last non-null prompt, so the fade-out still has its text to fade.
            var shownPrompt by remember { mutableStateOf(modelPrompt) }
            if (modelPrompt != null) shownPrompt = modelPrompt
            AnimatedVisibility(visible = modelPrompt != null, enter = fadeIn(), exit = fadeOut()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = shownPrompt.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onSetUpModel) { Text(modelActionLabel) }
                }
            }
            if (errorMessage != null) {
                Spacer(Modifier.height(16.dp))
                // The shared severity-colored notice card — bare onSurfaceVariant text here read as body copy.
                AppNotice(errorMessage, severity = NoticeSeverity.Error)
            }
        }
    }
}

private enum class TimeOfDay { Morning, Afternoon, Evening, Night, LateNight }

private fun timeOfDay(hour: Int): TimeOfDay = when (hour) {
    in 5..11 -> TimeOfDay.Morning
    in 12..16 -> TimeOfDay.Afternoon
    in 17..20 -> TimeOfDay.Evening
    in 21..23 -> TimeOfDay.Night
    else -> TimeOfDay.LateNight
}

private val GreetingsByTime: Map<TimeOfDay, List<String>> = mapOf(
    TimeOfDay.Morning to listOf(
        "Fresh start?",
        "First thought of the day?",
        "Coffee kicking in yet?",
        "What's on the docket?",
        "Ready when you are.",
        "Morning brain, let's go.",
    ),
    TimeOfDay.Afternoon to listOf(
        "Mid-day momentum?",
        "What are we cracking?",
        "Pick up where you left off?",
        "Post-lunch thoughts?",
        "Let's make the afternoon count.",
        "Back at it?",
    ),
    TimeOfDay.Evening to listOf(
        "Wrapping up loose ends?",
        "One more thing before dinner?",
        "End-of-day puzzle?",
        "Golden hour ideas?",
        "What's the evening project?",
        "Time to think out loud?",
    ),
    TimeOfDay.Night to listOf(
        "Quiet hours, big thoughts.",
        "Night shift?",
        "Let's keep it low and slow.",
        "What's keeping you up?",
        "Brain still buzzing?",
        "After-hours brainstorm?",
    ),
    TimeOfDay.LateNight to listOf(
        "Burning the midnight oil?",
        "We meet again, night owl.",
        "Still up? Same.",
        "3 a.m. epiphany?",
        "Sleep can wait.",
        "Just you, me, and the dark.",
    ),
)

@Composable
private fun rememberGreeting(): String = rememberSaveable {
    val hour = Instant.fromEpochMilliseconds(currentTimeMillis())
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .hour
    GreetingsByTime[timeOfDay(hour)].orEmpty().randomOrNull() ?: "Hello"
}
