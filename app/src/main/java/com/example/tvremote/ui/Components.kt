package com.example.tvremote.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Large (>= 56dp) remote button. If [holdKey] callbacks are supplied the button supports press-and-hold
 * (the ViewModel decides between native press/release and repeated clicks based on TV capability).
 */
@Composable
fun RemoteButton(
    icon: ImageVector? = null,
    text: String? = null,
    description: String,
    enabled: Boolean = true,
    haptics: Boolean,
    size: Dp = 64.dp,
    round: Boolean = true,
    onHoldStart: (() -> Unit)? = null,
    onHoldEnd: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val shape = if (round) CircleShape else RoundedCornerShape(20.dp)
    val bg = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val fg = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        modifier = modifier
            .size(size)
            .background(bg, shape)
            .semantics { contentDescription = description + if (enabled) "" else ", not supported on this TV"; role = Role.Button }
            .pointerInput(enabled, onHoldStart != null) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    if (haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (onHoldStart != null) {
                        onHoldStart(); tryAwaitRelease(); onHoldEnd?.invoke()
                    } else {
                        val released = tryAwaitRelease(); if (released) onClick()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg)
        else Text(text.orEmpty(), color = fg, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
