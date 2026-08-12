package com.medmission.survey.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Light-only on purpose: the app is used on clinic tablets in daylight, next to the paper
 * form it mirrors, so a dark scheme would fight the "paper" metaphor.
 */
private val MedMissionColorScheme = lightColorScheme(
    primary = ClinicalTeal,
    onPrimary = Color.White,
    primaryContainer = SurfaceTint,
    onPrimaryContainer = ClinicalTeal,
    secondary = ClayAmber,
    onSecondary = Color.White,
    secondaryContainer = SurfaceTint,
    onSecondaryContainer = InkText,
    background = PaperBackground,
    onBackground = InkText,
    surface = PaperBackground,
    onSurface = InkText,
    surfaceVariant = SurfaceTint,
    onSurfaceVariant = MutedSlate,
    outline = MutedSlate,
    outlineVariant = SurfaceTint,
    error = FailedRed,
    onError = Color.White,
)

@Composable
fun MedMissionSurveyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MedMissionColorScheme,
        typography = MedMissionTypography,
        content = content,
    )
}
