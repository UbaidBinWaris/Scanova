package app.scanova.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The platform font is kept on purpose: it carries the fallback chain that
// renders Urdu (Nastaliq/Naskh) and other scripts correctly, and costs nothing
// in APK size. Only weights, sizes and spacing are tuned, for a calmer, more
// editorial look than Material's defaults:
// - headings are semibold with slightly tightened tracking, so they read as a block;
// - body text loses most of Material's wide tracking (0.5 sp → 0.15 sp), which
//   looks airy on paper but loose on a phone;
// - labels are medium weight, buttons semibold, small labels a touch wider for legibility.
// Cursive scripts ignore letter spacing, so none of this affects Urdu.
private val Base = Typography()

internal val ScanovaTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodySmall = Base.bodySmall.copy(lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)
