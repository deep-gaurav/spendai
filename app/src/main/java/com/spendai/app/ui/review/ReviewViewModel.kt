package com.spendai.app.ui.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.PendingReview
import com.spendai.app.data.local.entity.PendingReviewKind
import com.spendai.app.data.local.entity.PendingReviewResolution
import com.spendai.app.data.repository.PendingReviewRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReviewUiState(
    val items: List<PendingReview> = emptyList(),
)

class ReviewViewModel(application: Application) : AndroidViewModel(application) {

    private val app: SpendAiApp
        get() = getApplication<SpendAiApp>()

    private val reviews: PendingReviewRepository = app.pendingReviewRepository

    private val source: Flow<ReviewUiState> = combine(
        reviews.observeOpen(PendingReviewKind.SOURCE.name),
        reviews.observeOpen(PendingReviewKind.TRANSACTION.name),
    ) { sourceItems, txnItems ->
        ReviewUiState(items = (sourceItems + txnItems).sortedBy { it.createdAt })
    }

    val ui: StateFlow<ReviewUiState> = source.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ReviewUiState(),
    )

    fun accept(id: Long) {
        viewModelScope.launch {
            reviews.resolve(id, PendingReviewResolution.ACCEPTED, System.currentTimeMillis())
        }
    }

    fun reject(id: Long) {
        viewModelScope.launch {
            reviews.resolve(id, PendingReviewResolution.REJECTED, System.currentTimeMillis())
        }
    }
}
