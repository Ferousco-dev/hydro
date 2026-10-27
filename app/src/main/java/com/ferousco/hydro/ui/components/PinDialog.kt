package com.ferousco.hydro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes

/** Asks for a short name ("Work", "Home") before pinning the place you are looking at. */
@Composable
fun PinDialog(
    defaultName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // The suggested name starts selected, so typing replaces it.
    var field by remember { mutableStateOf(TextFieldValue(defaultName, TextRange(0, defaultName.length))) }
    val name = field.text
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    HydroDialog(
        onDismiss = onDismiss,
        icon = PhIcon.Pin,
        title = "Pin this place",
        message = "Give it a name, like Work or Home. Tap it later to see advice for getting there.",
        primaryLabel = "Pin",
        onPrimary = { onConfirm(name) },
        primaryEnabled = name.isNotBlank(),
        secondaryLabel = "Cancel"
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .background(HydroColors.IceBlue, HydroShapes.Input)
                .padding(horizontal = HydroDimens.SpaceXl, vertical = HydroDimens.SpaceMd),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = field,
                onValueChange = { field = if (it.text.length <= 24) it else field },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = HydroColors.TextPrimary, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(HydroColors.Blue),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Name for this place" }
            )
        }
    }
}
