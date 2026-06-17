package com.spendai.app.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.data.local.entity.PendingReview
import com.spendai.app.data.local.entity.PendingReviewKind
import com.spendai.app.ui.components.BigOutlinedButton
import com.spendai.app.ui.components.BigPrimaryButton
import com.spendai.app.ui.components.CartoonIcon
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.theme.Dimens
import androidx.compose.ui.unit.dp

@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
    ) {
        Text(
            text = stringResource(R.string.review_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (ui.items.isEmpty()) {
            StickerCard {
                Text(
                    text = stringResource(R.string.review_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
                items(ui.items, key = { it.id }) { item ->
                    ReviewCard(
                        item = item,
                        onAccept = { viewModel.accept(item.id) },
                        onReject = { viewModel.reject(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(
    item: PendingReview,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CartoonIcon(
                    id = when (item.kind) {
                        PendingReviewKind.SOURCE.name -> R.drawable.ic_review_cartoon
                        else -> R.drawable.ic_bell_cartoon
                    },
                    size = 28.dp,
                )
                Spacer(Modifier.size(Dimens.SpaceSm))
                SectionLabel(
                    when (item.kind) {
                        PendingReviewKind.SOURCE.name -> stringResource(R.string.review_source_label)
                        else -> stringResource(R.string.review_title)
                    }
                )
            }
            Text(
                text = item.promptSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            ) {
                BigPrimaryButton(
                    onClick = onAccept,
                    text = stringResource(R.string.review_accept),
                    modifier = Modifier.weight(1f),
                )
                BigOutlinedButton(
                    onClick = onReject,
                    text = stringResource(R.string.review_reject),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
