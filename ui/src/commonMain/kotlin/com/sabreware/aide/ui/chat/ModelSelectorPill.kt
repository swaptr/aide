package com.sabreware.aide.ui.chat

import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.AidePill
import com.sabreware.aide.core.designsystem.MarqueeText
import com.sabreware.aide.core.designsystem.SkeletonCapsule
import com.sabreware.aide.core.designsystem.rememberSkeletonShimmer
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.ui.models.iconRes
import com.sabreware.aide.ui.models.providerKind
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * The pill wraps its label, up to what the header band leaves (capped at [PillMaxWidth] so a wide window does
 * not stretch a long name into a bar), and sits centered in the band. A short name gets a snug chip; a name
 * wider than the room walks ([MarqueeText]) instead of widening the control past it. A model switch changes
 * the width, so the size animates rather than jumping under the user's thumb.
 */
private val PillMaxWidth = 320.dp

/** The provider icon's slot, reserved whether or not there IS a provider, so the label never shifts. */
private val PillIconSize = 16.dp

/** The shimmer bar standing in for the name — deliberately shorter than the slot, like real text. */
private val PillSkeletonLabelWidth = 104.dp

/**
 * The chat header's model chip: current model name + provider icon. Tapping opens the unified model flow
 * ([com.sabreware.aide.ui.models.ModelDialog]) to switch or add a model.
 *
 * Loading and loaded share [ModelPillFrame], so the surface, the width, the padding and the chevron are the
 * SAME composable geometry in both states — see [ModelSelectorPillSkeleton].
 */
@Composable
internal fun ModelSelectorPill(
    label: String,
    provider: ProviderId?,
    onClick: () -> Unit,
    /**
     * The name is the user's choice but not yet resolved (its source is still answering, or a switch is in
     * flight). Dimmed, not disabled: the pill stays tappable — changing your mind is always allowed; only
     * acting on the model (send) waits.
     */
    pending: Boolean = false,
) {
    val labelAlpha by animateFloatAsState(if (pending) PENDING_LABEL_ALPHA else 1f, label = "pillPending")
    ModelPillFrame(onClick = onClick, provider = provider) {
        MarqueeText(
            modifier = Modifier.alpha(labelAlpha),
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * Placeholder while the active model resolves at startup.
 *
 * It is the REAL pill with a shimmer bar where the name goes, not a bare capsule of guessed size: the
 * container, its width, the padding and the chevron are then pixel-identical across the transition, so only
 * the label changes. A free-standing `SkeletonCapsule` had neither the chevron nor the pill's
 * font-scale-derived height, so the header re-laid out when the name landed.
 */
@Composable
internal fun ModelSelectorPillSkeleton() {
    val labelHeight = with(LocalDensity.current) {
        MaterialTheme.typography.titleSmall.lineHeight.toDp()
    }
    ModelPillFrame(onClick = null, provider = null) {
        SkeletonCapsule(
            width = PillSkeletonLabelWidth,
            height = labelHeight,
            shimmer = rememberSkeletonShimmer(),
        )
    }
}

/**
 * The one pill geometry: a reserved icon slot at the start, the label at its own width, the chevron after it —
 * the whole centered in the band and no wider than the band or [PillMaxWidth]. When the label does not fit,
 * it takes whatever is left and walks ([MarqueeText]). Height is whatever the label and the 18.dp chevron
 * need at the current font scale, and both states compute it from the same code, so it cannot differ.
 */
@Composable
private fun ModelPillFrame(
    onClick: (() -> Unit)?,
    provider: ProviderId?,
    label: @Composable () -> Unit,
) {
    // The band hands its slot over at a fixed width and places it at the start; centering is the pill's.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AidePill(
            modifier = Modifier.widthIn(max = PillMaxWidth).animateContentSize(),
            onClick = onClick,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            leading = {
                if (provider == null) {
                    Spacer(Modifier.size(PillIconSize))
                } else {
                    Icon(
                        painterResource(provider.pillIconRes()),
                        contentDescription = null,
                        modifier = Modifier.size(PillIconSize),
                    )
                }
            },
            trailing = {
                Icon(
                    painterResource(Res.drawable.ic_lc_chevron_down),
                    contentDescription = "Switch model",
                    modifier = Modifier.size(18.dp),
                )
            },
        ) {
            // fill = false: the label keeps its own width when it fits and is capped to the rest when it does not.
            Box(
                modifier = Modifier.weight(1f, fill = false),
                contentAlignment = Alignment.CenterStart,
                content = { label() },
            )
        }
    }
}

private fun ProviderId.pillIconRes(): DrawableResource = providerKind().iconRes()

/** How far a not-yet-resolved name recedes: readable, but visibly not settled. */
private const val PENDING_LABEL_ALPHA = 0.55f
