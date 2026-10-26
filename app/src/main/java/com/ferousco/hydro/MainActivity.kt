package com.ferousco.hydro

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.ui.ChatMessage
import com.ferousco.hydro.ui.RecommendationUi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ferousco.hydro.location.DeviceLocationProvider
import com.ferousco.hydro.ui.HydroUiState
import com.ferousco.hydro.ui.HydroViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Background = Color(0xFF101410)
private val CardColor = Color(0xFF1B211B)
private val ElevatedCard = Color(0xFF202920)
private val Lime = Color(0xFFD4F17A)
private val Muted = Color(0xFF9DA79A)
private val Warning = Color(0xFFFFC46B)
private val Danger = Color(0xFFFF8A80)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Background,
                    surface = CardColor,
                    primary = Lime
                )
            ) {
                HydroApp()
            }
        }
    }
}

@Composable
private fun HydroApp(viewModel: HydroViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val locationProvider = remember { DeviceLocationProvider(context) }

    // Alerts are silently rejected on Android 13+ unless this is granted, so ask for it up front.
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refresh()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        if (!locationProvider.hasLocationPermission()) {
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    HydroScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onActivity = viewModel::setActivity,
        onAsk = viewModel::ask,
        onRequestLocation = {
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    )
}

@Composable
private fun HydroScreen(
    state: HydroUiState,
    onRefresh: () -> Unit,
    onActivity: (ActivityCategory) -> Unit,
    onAsk: (String) -> Unit,
    onRequestLocation: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Header(state)

            if (state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Lime,
                    trackColor = CardColor
                )
            }

            state.errorMessage?.let { message ->
                MessageCard(
                    title = "Could not load current conditions",
                    body = message,
                    accent = Danger
                )
            }

            if (state.airTemperatureCelsius != null || state.heatIndexCelsius != null) {
                CurrentConditionsCard(state)
                HeatAssessmentCard(state)
                DetailsCard(state)
            } else if (!state.isLoading && state.errorMessage == null) {
                MessageCard(
                    title = "No reading yet",
                    body = "Pull a fresh observation to see current conditions.",
                    accent = Muted
                )
            }

            ProvenanceRow(state)

            ActivityChips(state.activity, onActivity)
            state.recommendation?.let { RecommendationCard(it) }
            state.recommendationError?.let {
                MessageCard("Advice unavailable", it, Warning)
            }
            AssistantCard(state, onAsk)

            Button(
                onClick = onRefresh,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lime,
                    contentColor = Background,
                    disabledContainerColor = CardColor,
                    disabledContentColor = Muted
                )
            ) {
                Text(
                    text = if (state.isLoading) "Fetching…" else "Refresh conditions",
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (!state.usingDeviceLocation) {
                TextButton(onClick = onRequestLocation) {
                    Text(
                        text = "Use my location",
                        color = Lime,
                        fontSize = 13.sp
                    )
                }
            }

            Text(
                text = "Data: Open-Meteo (CC-BY 4.0). Hydro reports deterministic NOAA/NWS " +
                    "Heat Index values and never presents a medical safety guarantee.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun Header(state: HydroUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "HYDRO",
            color = Lime,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp
        )
        Text(
            text = state.locationLabel,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Your environmental scout",
            color = Muted,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun CurrentConditionsCard(state: HydroUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElevatedCard, RoundedCornerShape(28.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "YOUR ENVIRONMENT",
            color = Muted,
            fontSize = 11.sp,
            letterSpacing = 2.sp
        )

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = state.airTemperatureCelsius.formatOrDash(0),
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = " °C",
                color = Muted,
                fontSize = 22.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }

        val feelsLike = state.feelsLikeCelsius
        if (feelsLike != null) {
            Text(
                text = "Feels like ${feelsLike.formatOrDash(1)} °C",
                color = Muted,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun HeatAssessmentCard(state: HydroUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "HEAT INDEX",
            color = Muted,
            fontSize = 11.sp,
            letterSpacing = 2.sp
        )

        if (state.heatIndexCelsius != null) {
            Text(
                text = "${state.heatIndexCelsius.formatOrDash(1)} °C " +
                    "(${state.heatIndexFahrenheit.formatOrDash(1)} °F)",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = state.heatCategory ?: "Category unavailable",
                color = Lime,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            state.heatCategorySummary?.let {
                Text(text = it, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        } else {
            Text(
                text = state.assessmentExplanation ?: "Heat index not available.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            if (state.missingVariables.isNotEmpty()) {
                Text(
                    text = "Missing: ${state.missingVariables.joinToString(", ")}",
                    color = Warning,
                    fontSize = 12.sp
                )
            }
        }

        state.uvCategory?.let {
            Text(text = "UV: $it", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DetailsCard(state: HydroUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "DETAILS",
            color = Muted,
            fontSize = 11.sp,
            letterSpacing = 2.sp
        )
        DetailRow("Relative humidity", state.relativeHumidityPercent.formatOrDash(0), "%")
        DetailRow("Wind speed", state.windSpeedMetersPerSecond.formatOrDash(1), "m/s")
        DetailRow("UV index", state.uvIndex.formatOrDash(1), "")
    }
}

@Composable
private fun DetailRow(label: String, value: String, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Muted, fontSize = 14.sp)
        Text(
            text = if (value == "—") value else "$value $unit".trim(),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ProvenanceRow(state: HydroUiState) {
    if (state.deliveryOriginLabel == null) return
    val accent = if (state.isLiveAndFresh) Lime else Warning
    val timestamp = state.observationTime?.let {
        DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(it)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${state.deliveryOriginLabel} · ${state.freshnessLabel}",
            color = accent,
            fontSize = 11.sp,
            letterSpacing = 1.sp
        )
        if (timestamp != null) {
            Text(text = "Observed $timestamp", color = Muted, fontSize = 11.sp)
        }
    }

    if (!state.isLiveAndFresh) {
        Text(
            text = "This reading is not a fresh live measurement. Treat it as cached or aging data.",
            color = Warning,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun MessageCard(title: String, body: String, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = title, color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(text = body, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

private fun Double?.formatOrDash(decimals: Int): String =
    this?.let { String.format(Locale.getDefault(), "%.${decimals}f", it) } ?: "—"


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityChips(selected: ActivityCategory, onSelect: (ActivityCategory) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PLANNING FOR", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActivityCategory.values().forEach { category ->
                FilterChip(
                    selected = category == selected,
                    onClick = { onSelect(category) },
                    label = { Text(category.displayName, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Lime,
                        selectedLabelColor = Background,
                        containerColor = CardColor,
                        labelColor = Muted
                    )
                )
            }
        }
    }
}

@Composable
private fun RecommendationCard(rec: RecommendationUi) {
    val accent = when (rec.urgency) {
        "CRITICAL", "HIGH" -> Danger
        "MODERATE" -> Warning
        "LOW" -> Lime
        else -> Muted
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElevatedCard, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("WHAT TO DO · ${rec.urgency}", color = accent, fontSize = 11.sp, letterSpacing = 2.sp)
        Text(rec.headline, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(rec.summary, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        rec.bestWindow?.let {
            Text("Coolest window: $it", color = Lime, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        rec.actions.forEach { (title, detail) ->
            Column {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        if (!rec.isCurrentData) {
            Text("Based on aging data. Refresh before relying on this.", color = Warning, fontSize = 11.sp)
        }
        rec.limitations.forEach {
            Text("• $it", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun AssistantCard(state: HydroUiState, onAsk: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("ASK HYDRO", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
        if (!state.assistantEnabled) {
            Text(
                "Add GEMINI_API_KEY to local.properties and rebuild to enable the assistant. " +
                    "Everything above works without it.",
                color = Muted, fontSize = 12.sp, lineHeight = 16.sp
            )
            return@Column
        }
        state.chat.forEach { ChatBubble(it) }
        if (state.isAsking) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lime, trackColor = Background)
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Should I walk 12 km this afternoon?", fontSize = 13.sp) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                onAsk(input); input = ""
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Lime,
                unfocusedBorderColor = Muted
            )
        )
        Button(
            onClick = { onAsk(input); input = "" },
            enabled = input.isNotBlank() && !state.isAsking,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Background)
        ) { Text("Ask", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (message.fromUser) ElevatedCard else Background,
                RoundedCornerShape(16.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            if (message.fromUser) "You" else "Hydro",
            color = if (message.fromUser) Muted else Lime,
            fontSize = 11.sp
        )
        Text(message.text, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
        message.note?.let { Text("Tools: $it", color = Muted, fontSize = 10.sp) }
    }
}
