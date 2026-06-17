package com.spendai.app.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.IngestionLog
import com.spendai.app.data.local.entity.IngestionLogA1
import com.spendai.app.data.local.entity.IngestionLogA2
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.repository.IngestionLogRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.worker.DailyParsingWorker
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
    val canRetry: Boolean,
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
        val canRetry = a1Outcome == IngestionLogA1.SKIPPED_A1 ||
            a2Outcome == IngestionLogA2.SKIPPED_A2
        return DebugLogRow(
            log = this,
            sender = raw?.senderAddress.orEmpty(),
            bodyPreview = preview,
            canRetry = canRetry,
        )
    }

    fun refresh() {
        refresh.value = System.currentTimeMillis()
    }

    /**
     * Enqueue a one-shot worker that drives the pipeline on a
     * single stuck message. The pipeline is naturally idempotent
     * — a successful retry commits a new transaction, and a
     * failure just writes a fresh audit log row.
     */
    fun retry(rawSmsId: Long) {
        val request = OneTimeWorkRequestBuilder<DailyParsingWorker>()
            .setInputData(DailyParsingWorker.retryInputData(rawSmsId))
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            DailyParsingWorker.UNIQUE_RETRY,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    suspend fun loadDetail(id: Long): DebugLogDetailState {
        val log = withContext(Dispatchers.IO) { logRepo.getById(id) } ?: return DebugLogDetailState(
            loading = false, notFound = true,
        )
        val raw = withContext(Dispatchers.IO) { smsRepo.getById(log.rawSmsId) }
        return DebugLogDetailState(loading = false, notFound = false, log = log, rawSms = raw)
    }
}
