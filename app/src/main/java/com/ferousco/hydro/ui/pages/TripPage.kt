package com.ferousco.hydro.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.core.trip.TripMode
import com.ferousco.hydro.ui.HydroUiState
import com.ferousco.hydro.ui.TripUi
import com.ferousco.hydro.ui.components.ChatMessageRow
import com.ferousco.hydro.ui.components.HourlyBarChart
import com.ferousco.hydro.ui.components.HydroIconButton
import com.ferousco.hydro.ui.components.HydroTextButton
import com.ferousco.hydro.ui.components.MetricTile
import com.ferousco.hydro.ui.components.MetricTileRow
import com.ferousco.hydro.ui.components.PhIcon
import com.ferousco.hydro.ui.components.SegmentedControl
import com.ferousco.hydro.ui.components.SheetSectionTitle
import com.ferousco.hydro.ui.components.SkeletonText
import com.ferousco.hydro.ui.components.StatusPill
import com.ferousco.hydro.ui.components.ThinkingRow
import com.ferousco.hydro.ui.components.TypingDots
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

/** What the trip panel can do. Grouped so [TripPage] stays a plain function of state. */
data class TripPageActions(
    val onBack: () -> Unit,
    val onMode: (TripMode) -> Unit,
    val onRetry: () -> Unit,
    val onPinClick: () -> Unit,
    val onAsk: (String) -> Unit,
    val onStopAsk: () -> Unit,
    val onRetryAsk: () -> Unit,
    val onRevealed: (Long) -> Unit,
    val onOpenChat: () -> Unit
)

/**
 * The panel while you are going somewhere. At rest it shows where, how, how long and how risky;
 * pull it up for the advice, the heat chart and a chat that already knows the trip.
 */
@Composable
fun TripPage(state: HydroUiState, trip: TripUi, actions: TripPageActions) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = HydroDimens.SpaceXl)) {
        TripHeader(trip, actions)

        SegmentedControl(
            options = TripMode.entries.map { it to it.label },
            selected = trip.mode,
            onSelect = actions.onMode,
            modifier = Modifier.padding(top = HydroDimens.SpaceMd)
        )

        when {
            trip.isLoading -> LoadingSummary()
            trip.error != null -> {
                Text(
                    trip.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HydroColors.Danger,
                    modifier = Modifier.padding(top = HydroDimens.SpaceMd)
                )
                HydroTextButton("Try again", actions.onRetry, modifier = Modifier.offset(x = -HydroDimens.SpaceMd))
            }
            else -> {
                Summary(trip)
                Advice(trip)
                Chart(trip)
                TripChat(state, actions)
            }
        }
    }
}

@Composable
private fun TripHeader(trip: TripUi, actions: TripPageActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HydroIconButton(PhIcon.Back, "Back to the map", actions.onBack, modifier = Modifier.offset(x = -HydroDimens.SpaceMd))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                trip.destinationName,
                style = MaterialTheme.typography.titleLarge,
                color = HydroColors.TextPrimary,
                maxLines = 2
            )
            Text(
                "From where you are",
                style = MaterialTheme.typography.bodyMedium,
                color = HydroColors.TextSecondary
            )
        }
        HydroIconButton(
            icon = PhIcon.Pin,
            contentDescription = if (trip.pinLabel != null) "Unpin ${trip.pinLabel}" else "Pin this place",
            onClick = actions.onPinClick,
            selected = trip.pinLabel != null,
            tint = if (trip.pinLabel != null) HydroColors.Blue else HydroColors.TextPrimary
        )
    }
}

@Composable
private fun LoadingSummary() {
    Column(
        modifier = Modifier.padding(top = HydroDimens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
    ) {
        SkeletonText(widthFraction = 0.45f, lineHeight = 26.dp)
        SkeletonText(widthFraction = 0.9f)
        SkeletonText(widthFraction = 0.7f)
    }
}

@Composable
private fun Summary(trip: TripUi) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = HydroDimens.SpaceLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
    ) {
        Text(
            listOfNotNull(trip.durationMinutes?.let { formatDuration(it) }, trip.distanceKm?.let(::formatDistanceKm))
                .joinToString(" · "),
            style = MaterialTheme.typography.titleLarge,
            color = HydroColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        trip.advice?.takeIf { it.urgency != "UNKNOWN" }?.let { advice ->
            val feels = trip.hours.firstOrNull { it.isNow }?.heatIndexC ?: trip.hours.firstOrNull()?.heatIndexC
            val (fg, bg) = feelColors(feels, trip.worstCategory, advice.urgency)
            StatusPill(feelWord(feels, trip.worstCategory), fg, bg)
        }
    }
    if (trip.straightLine) {
        Text(
            "Estimated from straight-line distance.",
            style = MaterialTheme.typography.bodySmall,
            color = HydroColors.TextSecondary,
            modifier = Modifier.padding(top = HydroDimens.SpaceXs)
        )
    }
}

@Composable
private fun Advice(trip: TripUi) {
    val advice = trip.advice ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = HydroDimens.SpaceLg)
            .background(HydroColors.IceBlue.copy(alpha = 0.6f), HydroShapes.Control)
            .padding(HydroDimens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm)
    ) {
        Text(advice.headline, style = MaterialTheme.typography.titleMedium, color = HydroColors.TextPrimary)
        trip.wearableNote?.let { note ->
            Text("From your watch", style = MaterialTheme.typography.labelMedium, color = HydroColors.TextSecondary)
            Text(note, style = MaterialTheme.typography.bodyMedium, color = HydroColors.TextPrimary)
        }
        when {
            trip.aiBrief != null -> Text(trip.aiBrief, style = MaterialTheme.typography.bodyMedium, color = HydroColors.TextPrimary)
            trip.aiWorking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)) {
                TypingDots()
                Text("Hydro is looking at your trip", style = MaterialTheme.typography.bodySmall, color = HydroColors.TextSecondary)
            }
        }
    }

    MetricTileRow(
        modifier = Modifier.padding(top = HydroDimens.SpaceSm),
        first = { m ->
            MetricTile(
                icon = PhIcon.Droplet,
                label = "Water",
                value = advice.waterBottles?.let { if (it == 1) "1 bottle" else "$it bottles" } ?: "None needed",
                note = if (advice.waterBottles != null) "500 ml each" else null,
                modifier = m
            )
        },
        second = { m ->
            MetricTile(
                icon = PhIcon.Thermometer,
                label = "Heat on the way",
                value = feelWord(trip.hours.firstOrNull { it.isNow }?.heatIndexC ?: trip.hours.firstOrNull()?.heatIndexC, trip.worstCategory),
                note = null,
                modifier = m
            )
        }
    )

    if (advice.waterNote != null) {
        Text(advice.waterNote, style = MaterialTheme.typography.bodySmall, color = HydroColors.TextSecondary, modifier = Modifier.padding(top = HydroDimens.SpaceSm))
    }
    if (advice.clothing.isNotEmpty()) {
        SheetSectionTitle("What to wear")
        advice.clothing.forEach { Line(it) }
    }
    if (advice.tips.isNotEmpty()) {
        SheetSectionTitle("Good to know")
        advice.tips.forEach { Line(it) }
    }
}

@Composable
private fun Line(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = HydroColors.TextPrimary,
        modifier = Modifier.padding(bottom = HydroDimens.SpaceXs)
    )
}

@Composable
private fun Chart(trip: TripUi) {
    if (trip.hours.isEmpty()) return
    SheetSectionTitle("Heat at ${trip.destinationName}")
    val summary = "Heat for the next ${trip.hours.size} hours at ${trip.destinationName}. " +
        (trip.hours.maxByOrNull { it.heatIndexC }?.let { "Hottest at ${it.label}." } ?: "")
    HourlyBarChart(hours = trip.hours, summary = summary)
    Text(
        "Brighter bars are when you will be out.",
        style = MaterialTheme.typography.bodySmall,
        color = HydroColors.TextSecondary,
        modifier = Modifier.padding(top = HydroDimens.SpaceXs)
    )
}

/** A chat that already knows the trip: the latest exchange, a message field and a link to the full chat. */
@Composable
private fun TripChat(state: HydroUiState, actions: TripPageActions) {
    if (!state.assistantEnabled) return
    SheetSectionTitle("Ask about this trip")
    Column(verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)) {
        val recent = state.chat.takeLast(2)
        val lastError = state.chat.lastOrNull()?.takeIf { it.isError }
        recent.forEach { message ->
            ChatMessageRow(
                message = message,
                canRetry = message === lastError && !state.isAsking,
                onRetry = actions.onRetryAsk,
                onRevealed = actions.onRevealed,
                onGrow = {}
            )
        }
        if (state.isAsking) ThinkingRow(state.askStatus ?: "Thinking")
        com.ferousco.hydro.ui.pages.AskInputBar(
            isAsking = state.isAsking,
            onAsk = actions.onAsk,
            onStop = actions.onStopAsk,
            placeholder = "How much water? What to wear?"
        )
        if (state.chat.isNotEmpty()) HydroTextButton("Open full chat", actions.onOpenChat, modifier = Modifier.offset(x = -HydroDimens.SpaceMd))
    }
}
