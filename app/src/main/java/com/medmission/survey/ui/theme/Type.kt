package com.medmission.survey.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography overrides. The system font family is used on purpose — bundling font assets
 * is out of scope, so hierarchy is carried by weight, size, color and letter spacing.
 */
val MedMissionTypography = Typography().let { base ->
    base.copy(
        // Screen / section-group header.
        titleLarge = base.titleLarge.copy(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = ClinicalTeal,
        ),
        // Section card header.
        titleMedium = base.titleMedium.copy(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            letterSpacing = 0.5.sp,
            color = ClinicalTeal,
        ),
        // Field labels and body copy.
        bodyMedium = base.bodyMedium.copy(
            fontFamily = FontFamily.Default,
            color = InkText,
        ),
        // Read-only physician-section notes.
        bodySmall = base.bodySmall.copy(
            fontFamily = FontFamily.Default,
            fontStyle = FontStyle.Italic,
            color = MutedSlate,
        ),
    )
}
