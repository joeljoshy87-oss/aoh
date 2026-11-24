package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AcousticFrameTelemetry
import com.example.model.ActuatorType
import com.example.model.HapticHardwareCapabilities
import com.example.model.TuningConfig
import com.example.ui.theme.LocalSubPulseColors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Master Haptic Threshold & Intensity Node.
 *
 * Central command node for the SubPulse haptic engine providing:
 * 1. Dynamic Hardware Status Badge: Green for Flagship LRA, Amber for Budget ERM.
 * 2. Live VU & Threshold Needle Meter: Real-time progress bar of acoustic low-end energy
 *    with vertical gate threshold needle; glows cyan when signal breaches threshold, dim gray when gated.
 * 3. Master Trigger Gate Slider: Sets acoustic energy threshold (0% to 100%).
 * 4. Master Drive Ceiling Slider: Sets peak output drive ceiling (0% to 100%).
 * 5. Force ERM Mode Toggle: Manual fallback override for legacy coin/budget motors.
 * 6. Master Input Low-Pass Filter: Switch toggle and Cutoff Slider (80 Hz to 450 Hz) with dynamic readout.
 * 7. Engine Throttle Cadence: Switch toggle, virtual cadence RPM slider (18 Hz to 48 Hz), and real-time animated cadence RPM indicator badge.
 * 8. Test Gate Pulse Button: Instantly fires a calibrated gate test pulse.
 */
@Composable
fun HapticMasterNodeCard(
    tuning: TuningConfig,
    telemetry: AcousticFrameTelemetry,
    hardware: HapticHardwareCapabilities,
    onUpdateTuning: ((TuningConfig) -> TuningConfig) -> Unit,
    onPlayTestPulse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val signalColors = LocalSubPulseColors.current

    val isErm = tuning.forceErmMode || hardware.actuatorType == ActuatorType.BUDGET_LEGACY_ERM
    val badgeText = if (isErm) {
        stringResource(R.string.badge_erm_budget)
    } else {
        stringResource(R.string.badge_lra_flagship)
    }
    val badgeColor = if (isErm) signalColors.actuator else signalColors.liveGreen
    val badgeContainerColor = if (isErm) signalColors.actuatorContainer else signalColors.liveGreenContainer

    // Acoustic MIR low-end RMS energy: max(subRms, kickBody)
    val rawAcousticLevel = max(telemetry.features.subRms, telemetry.features.kickBody)
    val gateThreshold = tuning.masterThreshold.coerceIn(0f, 1f)
    val physicalGateLevel = gateThreshold * 0.40f

    // Normalized against maximum gate scale (0.40f) so threshold needle and signal match 1:1
    val normalizedEnergy = (rawAcousticLevel / 0.40f).coerceIn(0f, 1f)
    val isBreached = rawAcousticLevel >= physicalGateLevel &&
            rawAcousticLevel > 0.001f &&
            !tuning.hapticsMuted &&
            tuning.masterDrive > 0.02f

    val animatedEnergy by animateFloatAsState(
        targetValue = normalizedEnergy,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 600f),
        label = "master_vu_energy"
    )

    // Engine Throttle Cadence virtual firing frequency calculation
    val isBassActive = telemetry.bassDepth > 0.05f &&
            !tuning.hapticsMuted &&
            tuning.masterDrive > 0.02f
    val basePitchFactor = (telemetry.dominantLowFreqHz - 30f).coerceIn(0f, 60f) * 0.28f
    val liveCadenceHz = ((20f + basePitchFactor) * (0.60f + 0.60f * tuning.engineCadenceRpm)).coerceIn(18f, 48f)
    val targetCadenceHz = if (tuning.engineThrottleEnabled) {
        if (isBassActive) liveCadenceHz else (20f * (0.60f + 0.60f * tuning.engineCadenceRpm)).coerceIn(18f, 48f)
    } else {
        0f
    }

    val animatedCadenceHz by animateFloatAsState(
        targetValue = targetCadenceHz,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "cadence_hz_anim"
    )
    val displayCadenceHz = animatedCadenceHz.roundToInt()
    val displayRpm = (animatedCadenceHz * 60f).roundToInt()

    val cadenceBadgeText = if (!tuning.engineThrottleEnabled) {
        "OFF (CONTINUOUS)"
    } else if (isBassActive) {
        "$displayCadenceHz Hz • $displayRpm RPM"
    } else {
        "$displayCadenceHz Hz • $displayRpm RPM (IDLE)"
    }

    val cadenceBadgeTextColor = if (!tuning.engineThrottleEnabled) {
        colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    } else if (isBassActive) {
        signalColors.liveGreen
    } else {
        signalColors.actuator
    }

    val cadenceBadgeBorderColor = if (!tuning.engineThrottleEnabled) {
        colorScheme.outlineVariant.copy(alpha = 0.6f)
    } else if (isBassActive) {
        signalColors.liveGreen.copy(alpha = 0.7f)
    } else {
        signalColors.actuator.copy(alpha = 0.7f)
    }

    val cadenceBadgeContainerColor = if (!tuning.engineThrottleEnabled) {
        colorScheme.background
    } else if (isBassActive) {
        signalColors.liveGreenContainer
    } else {
        signalColors.actuatorContainer
    }

    val cadenceBadgeDotColor = if (!tuning.engineThrottleEnabled) {
        colorScheme.outlineVariant
    } else if (isBassActive) {
        signalColors.liveGreen
    } else {
        signalColors.actuator
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(22.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header: Title, Subtitle, and Dynamic Hardware Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = null,
                        tint = signalColors.bass,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.card_master_node_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.card_master_node_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Dynamic Hardware Status Badge (Flagship LRA green vs Budget ERM amber)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeContainerColor)
                        .border(1.dp, badgeColor.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .testTag("hardware_actuator_badge"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = badgeColor
                        )
                    }
                }
            }

            // Live VU & Threshold Needle Meter
            MasterThresholdVuMeter(
                energy = animatedEnergy,
                threshold = gateThreshold,
                isBreached = isBreached,
                signalColors = signalColors,
                backgroundColor = colorScheme.background,
                outlineColor = colorScheme.outlineVariant,
                textColor = colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            // Master Trigger Gate Slider
            MasterNodeSliderRow(
                label = stringResource(R.string.label_master_threshold),
                valueText = "${(tuning.masterThreshold * 100).roundToInt()}%",
                value = tuning.masterThreshold,
                range = 0f..1f,
                accent = signalColors.bass,
                testTag = "slider_master_threshold",
                onValueChange = { v -> onUpdateTuning { it.copy(masterThreshold = v) } }
            )

            // Master Output Drive Ceiling Slider
            MasterNodeSliderRow(
                label = stringResource(R.string.label_master_drive_ceiling),
                valueText = "${(tuning.masterDrive * 100).roundToInt()}%",
                value = tuning.masterDrive,
                range = 0f..1f,
                accent = signalColors.actuator,
                testTag = "slider_master_drive_ceiling",
                onValueChange = { v -> onUpdateTuning { it.copy(masterDrive = v) } }
            )

            // Force ERM Pulse Mode Toggle
            MasterNodeToggleRow(
                label = stringResource(R.string.label_force_erm),
                subtitle = stringResource(R.string.desc_force_erm),
                checked = tuning.forceErmMode,
                accent = signalColors.actuator,
                testTag = "toggle_force_erm",
                onCheckedChange = { checked -> onUpdateTuning { it.copy(forceErmMode = checked) } }
            )

            // Master Input Low-Pass Filter Block
            MasterNodeToggleRow(
                label = stringResource(R.string.label_master_lpf),
                subtitle = stringResource(R.string.desc_master_lpf),
                checked = tuning.masterLpfEnabled,
                accent = signalColors.bass,
                testTag = "toggle_master_lpf",
                onCheckedChange = { checked -> onUpdateTuning { it.copy(masterLpfEnabled = checked) } }
            )

            val lpfCutoffHz = tuning.masterLpfCutoffHz.roundToInt()
            val lpfCutoffDescription = when {
                lpfCutoffHz <= 140 -> "$lpfCutoffHz Hz — Strict Sub Only"
                lpfCutoffHz <= 280 -> "$lpfCutoffHz Hz — Vocals & Noise Rejection"
                else -> "$lpfCutoffHz Hz — Wide Bass & Kick Body"
            }
            MasterNodeSliderRow(
                label = stringResource(R.string.label_master_lpf_cutoff),
                valueText = lpfCutoffDescription,
                value = tuning.masterLpfCutoffHz,
                range = 80f..450f,
                accent = signalColors.bass,
                testTag = "slider_master_lpf_cutoff",
                onValueChange = { v -> onUpdateTuning { it.copy(masterLpfCutoffHz = v) } }
            )

            // Engine Throttle Cadence Block
            MasterNodeToggleRow(
                label = stringResource(R.string.label_engine_throttle),
                subtitle = stringResource(R.string.desc_engine_throttle),
                checked = tuning.engineThrottleEnabled,
                accent = signalColors.actuator,
                testTag = "toggle_engine_throttle",
                onCheckedChange = { checked -> onUpdateTuning { it.copy(engineThrottleEnabled = checked) } }
            )

            // Engine Throttle Cadence Range Header & Animated RPM Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.desc_cadence_range),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )

                // Real-time animated cadence RPM indicator badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(cadenceBadgeContainerColor)
                        .border(1.dp, cadenceBadgeBorderColor, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("badge_engine_cadence_rpm"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(cadenceBadgeDotColor)
                        )
                        Text(
                            text = cadenceBadgeText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = cadenceBadgeTextColor
                        )
                    }
                }
            }

            val nominalCadenceHz = (20f * (0.60f + 0.60f * tuning.engineCadenceRpm)).coerceIn(18f, 48f).roundToInt()
            MasterNodeSliderRow(
                label = stringResource(R.string.label_engine_cadence_rpm),
                valueText = "${(tuning.engineCadenceRpm * 100).roundToInt()}% ($nominalCadenceHz Hz)",
                value = tuning.engineCadenceRpm,
                range = 0f..1f,
                accent = signalColors.actuator,
                testTag = "slider_engine_cadence_rpm",
                onValueChange = { v -> onUpdateTuning { it.copy(engineCadenceRpm = v) } }
            )

            // Test Gate Pulse Button
            Button(
                onClick = onPlayTestPulse,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = signalColors.actuator.copy(alpha = 0.18f),
                    contentColor = signalColors.actuator
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, signalColors.actuator.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .minimumInteractiveComponentSize()
                    .testTag("action_test_master_pulse")
            ) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.action_test_master_pulse),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun MasterThresholdVuMeter(
    energy: Float,
    threshold: Float,
    isBreached: Boolean,
    signalColors: com.example.ui.theme.SubPulseSignalColors,
    backgroundColor: Color,
    outlineColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val barGlowColor by animateColorAsState(
        targetValue = if (isBreached) signalColors.bass else textColor.copy(alpha = 0.35f),
        animationSpec = tween(durationMillis = 120),
        label = "vu_bar_color"
    )

    Column(
        modifier = modifier.testTag("meter_master_vu"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Readout row: Energy % | Gate % | Open / Gated pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = if (isBreached) signalColors.bass else textColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "ACOUSTIC ENERGY: ${(energy * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isBreached) signalColors.bass else textColor
                )
            }
            Text(
                text = "GATE: ${(threshold * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = signalColors.actuator
            )
            Text(
                text = if (isBreached) "TRIGGER OPEN" else "GATED",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (isBreached) signalColors.bass else textColor.copy(alpha = 0.6f)
            )
        }

        // Live VU canvas with energy progress fill and vertical threshold needle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(backgroundColor)
                .border(1.dp, outlineColor, RoundedCornerShape(8.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())

                // Energy fill
                val fillWidth = (energy.coerceIn(0f, 1f) * size.width).coerceAtLeast(0f)
                if (fillWidth > 0f) {
                    if (isBreached) {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(signalColors.bassDeep, signalColors.bass),
                                startX = 0f,
                                endX = fillWidth
                            ),
                            size = Size(fillWidth, size.height),
                            cornerRadius = corner
                        )
                    } else {
                        drawRoundRect(
                            color = barGlowColor,
                            size = Size(fillWidth, size.height),
                            cornerRadius = corner
                        )
                    }
                }

                // Vertical gate threshold needle
                val needleX = (threshold.coerceIn(0f, 1f) * size.width).coerceIn(0f, size.width)
                val needleColor = if (isBreached) Color.White else signalColors.actuator
                drawLine(
                    color = needleColor,
                    start = Offset(needleX, 0f),
                    end = Offset(needleX, size.height),
                    strokeWidth = 2.5.dp.toPx()
                )
                // Small indicator beads at top and bottom of needle
                drawCircle(
                    color = needleColor,
                    radius = 2.5.dp.toPx(),
                    center = Offset(needleX, 2.5.dp.toPx())
                )
                drawCircle(
                    color = needleColor,
                    radius = 2.5.dp.toPx(),
                    center = Offset(needleX, size.height - 2.5.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun MasterNodeSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = colorScheme.background
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}

@Composable
private fun MasterNodeToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colorScheme.background,
                checkedTrackColor = accent
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}
