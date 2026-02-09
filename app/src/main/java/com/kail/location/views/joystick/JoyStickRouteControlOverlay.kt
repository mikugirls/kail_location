package com.kail.location.views.joystick

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
@Composable
fun JoyStickRouteControlOverlay(
    isPaused: Boolean,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onToggleAdjust: () -> Unit,
    onWindowDrag: (Float, Float) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(260.dp)
            .wrapContentHeight()
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onWindowDrag(dragAmount.x, dragAmount.y)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Text(
                text = "Route Control",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterEnd).size(24.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onPauseResume,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Menu,
                    contentDescription = if (isPaused) "Resume" else "Pause",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isPaused) "Resume" else "Pause")
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Stop",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Stop")
            }

            Button(
                onClick = onToggleAdjust,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Adjust",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Adjust")
            }
        }
    }
}

@Composable
fun JoyStickRouteAdjustOverlay(
    progress: Float,
    speed: Double,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: (Float) -> Unit,
    onSpeedChange: (Double) -> Unit,
    onWindowDrag: (Float, Float) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(260.dp)
            .wrapContentHeight()
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onWindowDrag(dragAmount.x, dragAmount.y)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Text(
                text = "调整",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterEnd).size(24.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
            }
        }

        Text(text = "进度", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Slider(
            value = progress,
            onValueChange = { onProgressChange(it) },
            onValueChangeFinished = { onProgressChangeFinished(progress) },
            modifier = Modifier.fillMaxWidth()
        )

        Text(text = "速度: ${String.format("%.1f", speed)} km/h", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Slider(
            value = speed.toFloat(),
            onValueChange = { onSpeedChange(it.toDouble()) },
            valueRange = 1f..120f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
