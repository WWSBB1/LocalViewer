package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

fun Modifier.keyEventHandler(
    volumeKeysEnabled: () -> Boolean,
    volumeKeysInverted: () -> Boolean,
    volumeKeysSwitchGallery: () -> Boolean = { false },
    movePrevious: () -> Unit,
    moveNext: () -> Unit,
    prevGallery: () -> Unit = {},
    nextGallery: () -> Unit = {},
) = onPreviewKeyEvent {
    if (it.type == KeyEventType.KeyDown) {
        when (it.key) {
            Key.DirectionUp, Key.PageUp -> movePrevious()
            Key.DirectionDown, Key.PageDown -> moveNext()
            Key.VolumeUp if volumeKeysEnabled() ->
                if (volumeKeysSwitchGallery()) {
                    if (volumeKeysInverted()) nextGallery() else prevGallery()
                } else {
                    if (volumeKeysInverted()) moveNext() else movePrevious()
                }
            Key.VolumeDown if volumeKeysEnabled() ->
                if (volumeKeysSwitchGallery()) {
                    if (volumeKeysInverted()) prevGallery() else nextGallery()
                } else {
                    if (volumeKeysInverted()) movePrevious() else moveNext()
                }
            else -> return@onPreviewKeyEvent false
        }
        true
    } else {
        false
    }
}
