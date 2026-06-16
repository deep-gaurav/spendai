package com.spendai.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders a multi-color vector drawable. We use [Image] with no
 * colorFilter so each path inside the drawable keeps its declared
 * color - this is the only way to get the chunky, multi-color icons
 * that replace the M3 monochrome outlined set.
 */
@Composable
fun CartoonIcon(
    @DrawableRes id: Int,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(id = id),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    )
}
