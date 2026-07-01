package com.smartsystem.autoclicker.models

import java.util.UUID

/**
 * A single detection target — a text string to search for on screen.
 * When found, the app auto-taps its center coordinate.
 *
 * @param id        Unique identifier
 * @param label     Human-readable name shown in the UI
 * @param textQuery Text string to detect via ML Kit OCR (case-insensitive)
 * @param delayAfterMs  Milliseconds to wait after tapping before moving to the next target
 * @param enabled   Whether this target is active during detection
 */
data class DetectionTarget(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val textQuery: String,
    val delayAfterMs: Long = 500L,
    val enabled: Boolean = true
)
