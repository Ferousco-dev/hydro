package com.ferousco.hydro.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * Hydro's tap feedback: the pressed element fades slightly, with no square ripple or highlight.
 * It is installed once in [HydroTheme], so every clickable gets it without extra code.
 */
object HydroPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === HydroPressIndication

    override fun hashCode(): Int = -1

    private class PressNode(private val interactionSource: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var pressed = false

        override fun onAttach() {
            coroutineScope.launch {
                interactionSource.interactions.collect { interaction ->
                    val nowPressed = when (interaction) {
                        is PressInteraction.Press -> true
                        is PressInteraction.Release, is PressInteraction.Cancel -> false
                        else -> pressed
                    }
                    if (nowPressed != pressed) {
                        pressed = nowPressed
                        invalidateDraw()
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            if (pressed) {
                val paint = Paint().apply { alpha = PRESSED_ALPHA }
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
                    drawContent()
                    canvas.restore()
                }
            } else {
                drawContent()
            }
        }
    }

    private const val PRESSED_ALPHA = 0.55f
}
