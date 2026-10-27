package com.ferousco.hydro.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.HydroUiState
import com.ferousco.hydro.ui.components.ChatMessageRow
import com.ferousco.hydro.ui.components.HydroIconButton
import com.ferousco.hydro.ui.components.PhIcon
import com.ferousco.hydro.ui.components.PhosphorIcon
import com.ferousco.hydro.ui.components.ThinkingRow
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes
import kotlinx.coroutines.launch

private val Suggestions = listOf(
    "Should I walk 12 km this afternoon?",
    "When is the coolest time today?",
    "What should I carry for a long walk?",
    "Is the air safe for a run right now?"
)

/**
 * The conversation. Empty: a short prompt and suggestions sit in the middle. Active: a pinned
 * "Ask Hydro" header with New chat, messages that scroll (newest at the bottom), and a live
 * "thinking" line while an answer is prepared. The input lives in [AskInputBar].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskPage(
    state: HydroUiState,
    onAsk: (String) -> Unit,
    onRetry: () -> Unit,
    onNewChat: () -> Unit,
    onRevealed: (Long) -> Unit
) {
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
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val scrollToEnd: () -> Unit = { scope.launch { scroll.scrollTo(scroll.maxValue) } }
    LaunchedEffect(state.chat.size, state.isAsking, state.askStatus) { scroll.animateScrollTo(scroll.maxValue) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Ask Hydro",
                style = MaterialTheme.typography.titleMedium,
                color = HydroColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            HydroIconButton(PhIcon.Plus, "New chat", onNewChat)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .verticalScroll(scroll)
                .padding(bottom = HydroDimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceLg)
        ) {
            val lastError = state.chat.lastOrNull()?.takeIf { it.isError }
            state.chat.forEach { message ->
                ChatMessageRow(
                    message = message,
                    canRetry = message === lastError && !state.isAsking,
                    onRetry = onRetry,
                    onRevealed = onRevealed,
                    onGrow = scrollToEnd
                )
            }
            if (state.isAsking) ThinkingRow(state.askStatus ?: "Thinking")
        }
    }
}

/**
 * The message field, pinned above the keyboard. It grows to four lines. Return on the on-screen
 * keyboard sends, and so does Enter on a hardware keyboard (Shift+Enter adds a new line). While an
 * answer is being prepared the send button becomes Stop.
 */
@Composable
fun AskInputBar(
    isAsking: Boolean,
    onAsk: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask about heat, air or your route"
) {
    var input by remember { mutableStateOf("") }
    fun submit() {
        if (input.isNotBlank() && !isAsking) {
            onAsk(input)
            input = ""
        }
    }
    val canSend = input.isNotBlank() && !isAsking

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = HydroDimens.SpaceSm)
            .background(HydroColors.IceBlue, HydroShapes.Input),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .padding(start = HydroDimens.SpaceXl, top = HydroDimens.SpaceMd, bottom = HydroDimens.SpaceMd),
            contentAlignment = Alignment.CenterStart
        ) {
            if (input.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = HydroColors.TextSecondary)
            }
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = HydroColors.TextPrimary),
                cursorBrush = SolidColor(HydroColors.Blue),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                            submit()
                            true
                        } else {
                            false
                        }
                    }
                    .semantics { contentDescription = "Message" }
            )
        }
        Box(
            modifier = Modifier
                .padding(6.dp)
                .size(40.dp)
                .background(if (isAsking || canSend) HydroColors.Blue else HydroColors.IceBlueStrong, CircleShape)
                .clickable(enabled = isAsking || canSend, role = Role.Button) { if (isAsking) onStop() else submit() }
                .semantics { contentDescription = if (isAsking) "Stop" else "Send" },
            contentAlignment = Alignment.Center
        ) {
            PhosphorIcon(
                icon = if (isAsking) PhIcon.Stop else PhIcon.ArrowUp,
                contentDescription = null,
                size = HydroDimens.IconCompact,
                tint = Color.White
            )
        }
    }
}
