package com.aird.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aird.R

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int, opticalSize: Float): Font = Font(
    resId = R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opticalSize),
    ),
)

val InterText: FontFamily = FontFamily(
    inter(400, 14f),
    inter(500, 14f),
    inter(600, 14f),
    inter(700, 14f),
)

val InterDisplay: FontFamily = FontFamily(
    inter(500, 28f),
    inter(600, 28f),
    inter(700, 28f),
    inter(800, 28f),
)

private fun style(
    family: FontFamily,
    weight: Int,
    size: Float,
    line: Float,
    tracking: TextUnit,
) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking,
)

val AirdTypography = Typography(
    displayLarge = style(InterDisplay, 700, 52f, 58f, (-0.025).em),
    displayMedium = style(InterDisplay, 700, 42f, 48f, (-0.022).em),
    displaySmall = style(InterDisplay, 700, 34f, 40f, (-0.02).em),
    headlineLarge = style(InterDisplay, 700, 30f, 36f, (-0.018).em),
    headlineMedium = style(InterDisplay, 700, 26f, 32f, (-0.015).em),
    headlineSmall = style(InterDisplay, 600, 22f, 28f, (-0.012).em),
    titleLarge = style(InterText, 600, 20f, 26f, (-0.01).em),
    titleMedium = style(InterText, 600, 16f, 22f, (-0.006).em),
    titleSmall = style(InterText, 600, 14f, 20f, 0.em),
    bodyLarge = style(InterText, 400, 16f, 23f, (-0.006).em),
    bodyMedium = style(InterText, 400, 14f, 20f, 0.em),
    bodySmall = style(InterText, 400, 12.5f, 17f, 0.004.em),
    labelLarge = style(InterText, 600, 14f, 20f, 0.004.em),
    labelMedium = style(InterText, 600, 12f, 16f, 0.01.em),
    labelSmall = style(InterText, 600, 11f, 14f, 0.02.em),
)

val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")
