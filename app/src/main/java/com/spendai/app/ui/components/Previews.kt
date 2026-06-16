package com.spendai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spendai.app.R
import com.spendai.app.ui.theme.Dimens
import com.spendai.app.ui.theme.SpendAiTheme

@Preview(name = "StickerCard", showBackground = true)
@Composable
private fun StickerCardPreview() {
    SpendAiTheme {
        Surface(Modifier.fillMaxSize()) {
            StickerCard(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "SpendAI",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "A bold cartoony sticker card with halftone dots and an offset shadow.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(name = "BigPrimaryButton", showBackground = true)
@Composable
private fun BigPrimaryButtonPreview() {
    SpendAiTheme {
        Surface(Modifier.fillMaxSize()) {
            BigPrimaryButton(
                onClick = {},
                text = "Continue",
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Preview(name = "BigOutlinedButton", showBackground = true)
@Composable
private fun BigOutlinedButtonPreview() {
    SpendAiTheme {
        Surface(Modifier.fillMaxSize()) {
            BigOutlinedButton(
                onClick = {},
                text = "Cancel",
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Preview(name = "SectionLabel", showBackground = true)
@Composable
private fun SectionLabelPreview() {
    SpendAiTheme {
        Surface(Modifier.fillMaxSize()) {
            SectionLabel(
                text = "Required",
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Preview(name = "ProgressDots", showBackground = true)
@Composable
private fun ProgressDotsPreview() {
    SpendAiTheme {
        Surface(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ProgressDots(currentStep = 1)
                ProgressDots(currentStep = 2)
                ProgressDots(currentStep = 3)
            }
        }
    }
}

@Preview(name = "CartoonIcon", showBackground = true)
@Composable
private fun CartoonIconPreview() {
    SpendAiTheme {
        Surface(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                CartoonIcon(id = R.drawable.ic_sms_cartoon, size = 56.dp)
                CartoonIcon(id = R.drawable.ic_bell_cartoon, size = 56.dp)
                CartoonIcon(id = R.drawable.ic_check_cartoon, size = 56.dp)
                CartoonIcon(id = R.drawable.ic_cloud_download_cartoon, size = 56.dp)
            }
        }
    }
}

@Preview(name = "Hero illustrations", showBackground = true)
@Composable
private fun HeroPreview() {
    SpendAiTheme {
        Surface(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                CartoonIcon(id = R.drawable.art_sms_mascot, size = 100.dp)
                CartoonIcon(id = R.drawable.art_download_mascot, size = 100.dp)
                CartoonIcon(id = R.drawable.art_test_mascot, size = 100.dp)
            }
        }
    }
}
