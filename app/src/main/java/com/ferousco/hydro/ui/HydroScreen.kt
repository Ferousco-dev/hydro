package com.ferousco.hydro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.ferousco.hydro.ui.components.FloatingTabBar
import com.ferousco.hydro.ui.i18n.LocalStrings
import com.ferousco.hydro.ui.i18n.Strings
import com.ferousco.hydro.ui.i18n.stringsFor
import com.ferousco.hydro.ui.components.PhIcon
import com.ferousco.hydro.ui.components.TabItem
import com.ferousco.hydro.ui.pages.AlertsPage
import com.ferousco.hydro.ui.pages.AskInputBar
import com.ferousco.hydro.ui.pages.AskPage
import com.ferousco.hydro.ui.pages.TrendsPage
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens

private enum class AppTab { Map, Trends, Ask, Alerts }

private fun tabsFor(s: Strings) = listOf(
    TabItem(AppTab.Map, s.tabMap, PhIcon.TabMap),
    TabItem(AppTab.Trends, s.tabTrends, PhIcon.TabTrends),
    TabItem(AppTab.Ask, s.tabAsk, PhIcon.Sparkle),
    TabItem(AppTab.Alerts, s.tabAlerts, PhIcon.TabAlerts)
)

/**
 * Top level of the app: a floating tab bar over four destinations. The map is home; Trends, Ask and
 * Alerts are full screens drawn over it. The map stays composed underneath so its camera and layers
 * are exactly where you left them.
 */
@Composable
fun HydroScreen(state: HydroUiState, actions: MapActions) {
    val strings = stringsFor(state.language)
    CompositionLocalProvider(LocalStrings provides strings) {
        HydroScreenContent(state, actions, tabsFor(strings))
    }
}

@Composable
private fun HydroScreenContent(state: HydroUiState, actions: MapActions, tabs: List<TabItem<AppTab>>) {
    var tab by rememberSaveable { mutableStateOf(AppTab.Map) }
    // The trip panel can jump to the full chat; it is the only action that needs to change tabs.
    val screenActions = actions.copy(onOpenChat = { tab = AppTab.Ask })
    // Opening Alerts re-checks the watch connection (the user may have just changed it in Health Connect).
    LaunchedEffect(tab) { if (tab == AppTab.Alerts) actions.onRefreshHealth() }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabBarSpace = HydroDimens.TabBarHeight + HydroDimens.TabBarGap + HydroDimens.TabBarClearance + navInset
    // The tab bar and the Ask input both ride on this one inset, so they lift together above the keyboard.
    val barInset = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()

    Box(modifier = Modifier.fillMaxSize()) {
        MapTab(state = state, actions = screenActions, bottomSpace = tabBarSpace)

        if (tab != AppTab.Map) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(HydroColors.SnowWhite)
                    // An opaque page must also swallow touches so the map beneath never reacts.
                    .pointerInput(Unit) {}
            ) {
                when (tab) {
                    AppTab.Trends -> ScrollPage(tabBarSpace) { TrendsPage(state) }
                    AppTab.Alerts -> ScrollPage(tabBarSpace) {
                        AlertsPage(
                            state = state,
                            onEnabled = actions.onAlertsEnabled,
                            onSeverity = actions.onMinimumSeverity,
                            onQuietHour = actions.onQuietHour,
                            onConnectHealth = actions.onConnectHealth,
                            onDisconnectHealth = actions.onDisconnectHealth,
                            onAssistantMayUseHealth = actions.onAssistantMayUseHealth,
                            onAnimations = actions.onAnimations,
                            onLanguage = actions.onLanguage
                        )
                    }
                    AppTab.Ask -> Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = HydroDimens.ScreenEdge)
                        ) {
                            AskPage(
                                state = state,
                                onAsk = actions.onAsk,
                                onRetry = actions.onRetryAsk,
                                onNewChat = actions.onNewChat,
                                onRevealed = actions.onRevealed
                            )
                        }
                        if (state.assistantEnabled) {
                            AskInputBar(
                                isAsking = state.isAsking,
                                onAsk = actions.onAsk,
                                onStop = actions.onStopAsk,
                                modifier = Modifier.padding(horizontal = HydroDimens.ScreenEdge)
                            )
                        }
                        // Keep the input clear of the tab bar.
                        Box(
                            modifier = Modifier.height(
                                HydroDimens.TabBarHeight + HydroDimens.TabBarGap + HydroDimens.TabBarClearance + barInset
                            )
                        )
                    }
                    AppTab.Map -> Unit
                }
            }
        }

        // Always visible. Above the keyboard when it is open, above the gesture bar otherwise.
        FloatingTabBar(
            items = tabs,
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 20.dp)
                .padding(bottom = HydroDimens.TabBarGap)
        )
    }
}

/** A full-screen page that scrolls and keeps its last row clear of the tab bar. */
@Composable
private fun ScrollPage(bottomSpace: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HydroDimens.ScreenEdge)
            .padding(top = HydroDimens.SpaceLg, bottom = bottomSpace)
    ) { content() }
}
