package com.spendai.app.ui.merchants

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.dao.MerchantWithMetadataRow
import com.spendai.app.data.local.entity.MerchantMetadata
import com.spendai.app.data.local.entity.MerchantMetadataKind
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.domain.agent.insights.AgenticAction
import com.spendai.app.domain.agent.insights.MerchantMutator
import com.spendai.app.domain.model.MerchantNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Merchants management screen. Wraps
 * [MerchantRepository] and exposes a single
 * [MerchantsUiState] stream that the screen renders directly.
 *
 * The screen is the manual-edit sibling of the Ask-AI
 * `mutate_merchant` tool: it calls into the same
 * [MerchantRepository] via the [MerchantMutator], so the
 * two surfaces never disagree on what `isSelf = true` or
 * `add a note` means.
 */
class MerchantsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: SpendAiApp
        get() = getApplication<SpendAiApp>()

    private val repo: MerchantRepository = app.merchantRepository
    private val mutator: MerchantMutator = app.merchantMutator

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val _rows = repo.observeAllWithMetadata()
        .map { rows -> groupRows(rows) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    val state: StateFlow<MerchantsUiState> = combine(_rows, _search) { rows, query ->
        MerchantsUiState(
            merchants = filterRows(rows, query),
            search = query,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MerchantsUiState(),
    )

    fun onSearchChange(text: String) {
        _search.value = text
    }

    fun setIsSelf(merchantId: Long, isSelf: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val merchant = repo.getById(merchantId) ?: return@withContext
                val action = if (isSelf) {
                    MerchantActionBuilder.setIsSelf(merchant.name)
                } else {
                    MerchantActionBuilder.clearIsSelf(merchant.name)
                }
                mutator.mutate(action)
            }
        }
    }

    fun addMetadata(
        merchantId: Long,
        kind: MerchantMetadataKind,
        value: String,
    ) {
        if (value.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val merchant = repo.getById(merchantId) ?: return@withContext
                mutator.mutate(
                    MerchantActionBuilder.addMetadata(merchant.name, kind, value)
                )
            }
        }
    }

    fun removeMetadata(merchantId: Long, kind: MerchantMetadataKind) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val merchant = repo.getById(merchantId) ?: return@withContext
                mutator.mutate(
                    MerchantActionBuilder.removeMetadata(merchant.name, kind)
                )
            }
        }
    }

    private fun groupRows(rows: List<MerchantWithMetadataRow>): List<MerchantRow> {
        // Group the flat LEFT-JOIN rows back into one entry per
        // merchant. The metadata list is empty when no rows
        // joined (LEFT JOIN unmatched on mm_* fields).
        val grouped = rows.groupBy { it.m_id }
        return grouped.map { (id, group) ->
            val head = group.first()
            MerchantRow(
                id = id,
                name = head.m_name,
                normalizedName = head.m_normalizedName,
                vpa = head.m_vpa,
                isSelf = head.m_isSelf,
                metadata = group.mapNotNull { row ->
                    val kindStr = row.mm_kind ?: return@mapNotNull null
                    val kind = runCatching { MerchantMetadataKind.valueOf(kindStr) }
                        .getOrNull() ?: return@mapNotNull null
                    MerchantMetadata(
                        id = row.mm_id ?: 0L,
                        merchantId = row.mm_merchantId ?: id,
                        kind = kindStr,
                        value = row.mm_value ?: "",
                        createdAt = row.mm_createdAt ?: 0L,
                    ) to kind
                },
            )
        }.sortedByDescending { it.name.lowercase() }
    }

    private fun filterRows(rows: List<MerchantRow>, query: String): List<MerchantRow> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return rows
        val needle = trimmed.lowercase()
        return rows.filter { row ->
            row.name.lowercase().contains(needle) ||
                (row.vpa?.lowercase()?.contains(needle) == true) ||
                row.metadata.any { it.first.value.lowercase().contains(needle) }
        }
    }
}

/**
 * UI state. `merchants` is the filtered list the screen
 * renders; `search` is the current filter text (kept in
 * state so the field does not lose focus on rotation).
 */
data class MerchantsUiState(
    val merchants: List<MerchantRow> = emptyList(),
    val search: String = "",
)

/**
 * Flat row for the management screen. `metadata` is a
 * list of pairs so the screen can show the kind + value
 * in one line without a second lookup.
 */
data class MerchantRow(
    val id: Long,
    val name: String,
    val normalizedName: String,
    val vpa: String?,
    val isSelf: Boolean,
    val metadata: List<Pair<MerchantMetadata, MerchantMetadataKind>>,
)

/**
 * Build a [AgenticAction.MutateMerchant] for the
 * management screen. The screen goes through the mutator
 * (not the repository directly) so the ripple (self-link
 * writes + reprompt enqueue) runs on every edit, the same
 * way Ask AI does.
 */
private object MerchantActionBuilder {
    fun setIsSelf(merchantName: String) =
        AgenticAction.MutateMerchant(
            thought = "User marked $merchantName as themself via the Merchants screen.",
            matchByName = MerchantNormalizer.normalize(merchantName),
            setIsSelf = true,
        )

    fun clearIsSelf(merchantName: String) =
        AgenticAction.MutateMerchant(
            thought = "User cleared isSelf for $merchantName via the Merchants screen.",
            matchByName = MerchantNormalizer.normalize(merchantName),
            clearIsSelf = true,
        )

    fun addMetadata(
        merchantName: String,
        kind: MerchantMetadataKind,
        value: String,
    ) = AgenticAction.MutateMerchant(
        thought = "User added ${kind.name}=$value to $merchantName via the Merchants screen.",
        matchByName = MerchantNormalizer.normalize(merchantName),
        addMetadata = listOf(
            AgenticAction.MetadataOp(
                kind = kind.name,
                value = value,
            )
        ),
    )

    fun removeMetadata(
        merchantName: String,
        kind: MerchantMetadataKind,
    ) = AgenticAction.MutateMerchant(
        thought = "User removed ${kind.name} from $merchantName via the Merchants screen.",
        matchByName = MerchantNormalizer.normalize(merchantName),
        removeMetadata = listOf(kind.name),
    )
}
