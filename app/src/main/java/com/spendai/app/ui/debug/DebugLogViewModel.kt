package com.spendai.app.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.IngestionLog
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.data.repository.IngestionLogRepository
import com.spendai.app.data.repository.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DebugLogRow(
    val log: IngestionLog,
    val sender: String,
    val bodyPreview: String,
)

data class DebugLogDetailState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val log: IngestionLog? = null,
    val rawSms: RawSmsMessage? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DebugLogViewModel(application: Application) : AndroidViewModel(application) {

    private val app: SpendAiApp
        get() = getApplication<SpendAiApp>()

    private val logRepo: IngestionLogRepository = app.ingestionLogRepository
    private val smsRepo: SmsRepository = app.smsRepository

    /**
     * Bumps on demand (after the detail screen reads, or on a
     * navigation back). We re-read logs and join with raw SMS
     * bodies each time. This is cheap (default 200 rows, one
     * DB hit per row) and keeps the data fresh.
     */
    private val refresh = MutableStateFlow(0L)

    val rows: StateFlow<List<DebugLogRow>> = refresh
        .flatMapLatest { _ ->
            flow {
                val logs = withContext(Dispatchers.IO) { logRepo.getRecent() }
                emit(logs.map { log -> log.toRow() })
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private suspend fun IngestionLog.toRow(): DebugLogRow {
        val raw = withContext(Dispatchers.IO) { smsRepo.getById(rawSmsId) }
        val preview = raw?.msgBody?.lineSequence()?.first()?.take(80).orEmpty()
        return DebugLogRow(
            log = this,
            sender = raw?.senderAddress.orEmpty(),
            bodyPreview = preview,
        )
    }

    fun refresh() {
        refresh.value = System.currentTimeMillis()
    }

    suspend fun loadDetail(id: Long): DebugLogDetailState {
        val log = withContext(Dispatchers.IO) { logRepo.getById(id) } ?: return DebugLogDetailState(
            loading = false, notFound = true,
        )
        val raw = withContext(Dispatchers.IO) { smsRepo.getById(log.rawSmsId) }
        return DebugLogDetailState(loading = false, notFound = false, log = log, rawSms = raw)
    }
}
