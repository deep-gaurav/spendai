package com.spendai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spendai.app.ui.theme.Dimens

/**
 * Chunky three-dot progress strip for the top app bar. The current
 * step is filled in `primary`, the others stay as outlined chips.
 */
@Composable
fun ProgressDots(
    currentStep: Int,
    modifier: Modifier = Modifier,
    totalSteps: Int = 3,
) {
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface

    Row(
        modifier = modifier.semantics { contentDescription = "Step $currentStep of $totalSteps" },
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        repeat(totalSteps) { i ->
            val stepNumber = i + 1
            val isCurrent = stepNumber == currentStep
            val fill = if (isCurrent) primary else surface
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .border(Dimens.BorderThick, outline, CircleShape),
            )
        }
    }
}
