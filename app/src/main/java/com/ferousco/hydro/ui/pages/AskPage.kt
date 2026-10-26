package com.ferousco.hydro.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.HydroUiState
import com.ferousco.hydro.ui.components.AppleActivityIndicator
import com.ferousco.hydro.ui.components.ChatMessageRow
import com.ferousco.hydro.ui.components.HydroIconButton
import com.ferousco.hydro.ui.components.IndicatorSize
import com.ferousco.hydro.ui.components.PhIcon
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

private val Suggestions = listOf(
    "Should I walk 12 km this afternoon?",
    "When is the coolest time today?",
    "What should I carry for a long walk?",
    "Is the air safe for a run right now?"
)

/**
 * The middle of the Ask page. With no conversation yet, a short prompt and suggestions sit in the
 * centre of the panel; once there are messages they scroll, newest at the bottom. The input lives
 * in [AskInputBar], pinned to the bottom of the sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskPage(state: HydroUiState, onAsk: (String) -> Unit) {
    if (!state.assistantEnabled) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "The assistant isn't available right now.",
                style = MaterialTheme.typography.bodyLarge,
                color = HydroColors.TextSecondary,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    if (state.chat.isEmpty() && !state.isAsking) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Ask Hydro", style = MaterialTheme.typography.titleLarge, color = HydroColors.TextPrimary)
            Text(
                "Get answers based on today's heat, air and your plans.",
                style = MaterialTheme.typography.bodyMedium,
                color = HydroColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = HydroDimens.SpaceXs, bottom = HydroDimens.SpaceLg)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm),
                overflow = FlowRowOverflow.Clip
            ) {
                Suggestions.forEach { suggestion ->
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HydroColors.Blue,
                        modifier = Modifier
                            .background(HydroColors.IceBlue, HydroShapes.Capsule)
                            .clickable(role = Role.Button) { onAsk(suggestion) }
                            .padding(horizontal = HydroDimens.SpaceLg, vertical = HydroDimens.SpaceSm + 2.dp)
                    )
                }
            }
        }
        return
    }

    val scroll = rememberScrollState()
    LaunchedEffect(state.chat.size, state.isAsking) { scroll.animateScrollTo(scroll.maxValue) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = HydroDimens.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
    ) {
        state.chat.forEach { ChatMessageRow(it.fromUser, it.text) }
        if (state.isAsking) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm)) {
                AppleActivityIndicator(size = IndicatorSize.Medium, color = HydroColors.TextSecondary, contentDescription = "Hydro is thinking")
                Text("Checking conditions…", style = MaterialTheme.typography.bodyMedium, color = HydroColors.TextSecondary)
            }
        }
    }
}

/** The always-visible message field at the bottom of the Ask page. */
@Composable
fun AskInputBar(
    enabled: Boolean,
    onAsk: (String) -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    fun submit() {
        if (input.isNotBlank() && enabled) {
            onAsk(input)
            input = ""
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = HydroDimens.SpaceSm)
            .background(HydroColors.IceBlue, HydroShapes.Capsule),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(1f).padding(start = HydroDimens.SpaceXl, top = HydroDimens.SpaceMd, bottom = HydroDimens.SpaceMd),
            contentAlignment = Alignment.CenterStart
        ) {
            if (input.isEmpty()) {
                Text("Ask about heat, air or your route", style = MaterialTheme.typography.bodyLarge, color = HydroColors.TextSecondary)
            }
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = HydroColors.TextPrimary),
                cursorBrush = SolidColor(HydroColors.Blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocused() }
            )
        }
        HydroIconButton(
            icon = PhIcon.Send,
            contentDescription = "Send question",
            onClick = ::submit,
            enabled = input.isNotBlank() && enabled,
            tint = HydroColors.Blue
        )
    }
}
