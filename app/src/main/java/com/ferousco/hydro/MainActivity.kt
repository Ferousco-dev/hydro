package com.ferousco.hydro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF101410)
private val SurfaceColor = Color(0xFF1B211B)
private val Lime = Color(0xFFD4F17A)
private val Muted = Color(0xFF9DA79A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Background,
                    surface = SurfaceColor,
                    primary = Lime
                )
            ) {
                HydroScreen()
            }
        }
    }
}

@Composable
private fun HydroScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column {
                Text(
                    text = "HYDRO",
                    color = Lime,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )

                Text(
                    text = "Your environmental scout",
                    color = Muted,
                    fontSize = 14.sp
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        Color(0xFF202920),
                        RoundedCornerShape(28.dp)
                    )
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "YOUR ENVIRONMENT",
                        color = Muted,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp
                    )

                    Text(
                        text = "Understand the world outside.",
                        color = Color.White,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 36.sp
                    )
                }

                Text(
                    text = "Environmental data will appear here once the data layer is connected.",
                    color = Lime,
                    fontSize = 13.sp
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceColor, RoundedCornerShape(24.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Environmental snapshot",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "Temperature: Waiting for data",
                    color = Muted
                )

                Text(
                    text = "Humidity: Waiting for data",
                    color = Muted
                )

                Text(
                    text = "Hydro is ready for its next development phase.",
                    color = Lime,
                    fontSize = 12.sp
                )
            }
        }
    }
}
