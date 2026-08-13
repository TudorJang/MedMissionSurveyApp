package com.medmission.survey.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's single source of truth for color. The palette deliberately evokes the paper
 * form this app digitizes: off-white stock, near-black ink, a clinical teal for structure
 * and a clay accent used sparingly for section numbering.
 */

/** Primary. Section titles, primary buttons, section-number labels. */
val ClinicalTeal = Color(0xFF0F5C57)

/** Screen/app background — the paper form's own off-white stock, not stark white. */
val PaperBackground = Color(0xFFF6F5F1)

/** Primary body text: a slightly warm near-black. */
val InkText = Color(0xFF1B2421)

/** Secondary accent, used sparingly (section-number chips, dividers). */
val ClayAmber = Color(0xFFC9772E)

/** Card/section background tint used for grouping. */
val SurfaceTint = Color(0xFFE4E1D8)

/**
 * Muted accent for the physician/AI-only sections, so they read as visually distinct
 * from the sections the tablet operator actually fills in. Darkened from the original
 * 0xFF8A8D86 (~3.09:1 on PaperBackground) to clear WCAG AA's 4.5:1 for normal text —
 * this color is used for body text throughout the read-only sections, not just accents.
 */
val MutedSlate = Color(0xFF5F6359)

/** Sync-status colors. Shared by HomeScreen so the hex values live in exactly one place. */
val DraftGrey = Color(0xFF757575)
val PendingAmber = Color(0xFFF9A825)
val SentGreen = Color(0xFF2E7D32)
val FailedRed = Color(0xFFC62828)
