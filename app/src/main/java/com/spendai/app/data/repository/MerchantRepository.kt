package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.MerchantDao
import com.spendai.app.data.local.dao.MerchantMetadataDao
import com.spendai.app.data.local.dao.MerchantWithMetadataRow
import com.spendai.app.data.local.entity.Merchant
import com.spendai.app.data.local.entity.MerchantMetadata
import com.spendai.app.data.local.entity.MerchantMetadataKind
import kotlinx.coroutines.flow.Flow

/**
 * Repository over [MerchantDao] and the per-merchant
 * [MerchantMetadataDao]. Owns the dedup invariant: a
 * `(merchantId, kind)` pair is unique, so writes that
 * touch an existing row use upsert.
 *
 * Both the [com.spendai.app.domain.agent.insights.MerchantMutator]
 * (the Ask-AI write path) and the
 * [com.spendai.app.ui.merchants.MerchantsViewModel] go through
 * this class. The mutator and the management UI use the same
 * primitives, so the two surfaces never disagree on what
 * "isSelf = true" or "add a note" means.
 */
class MerchantRepository(
    private val dao: MerchantDao,
    private val metadataDao: MerchantMetadataDao,
) {
    suspend fun insert(row: Merchant): Long = dao.insertIgnore(row)
    suspend fun update(row: Merchant) = dao.update(row)
    suspend fun getById(id: Long): Merchant? = dao.getById(id)
    suspend fun findByNormalizedName(name: String): Merchant? = dao.findByNormalizedName(name)
    suspend fun findByVpa(vpa: String): Merchant? = dao.findByVpa(vpa)
    suspend fun getAllOnce(): List<Merchant> = dao.getAllOnce()
    fun observeAll(): Flow<List<Merchant>> = dao.observeAll()

    /**
     * The most-recently-seen [limit] merchants. A2 ships a slice of
     * the merchant table into its prompt bundle so the model can
     * match an incoming SMS to an existing row; capping the slice
     * keeps the prompt comfortably under a 64K total context.
     */
    suspend fun getRecent(limit: Int): List<Merchant> = dao.getRecent(limit)

    /**
     * Flip the `isSelf` flag. The InsightsDao SQL reads this column
     * on every aggregate query; flipping it on for a merchant
     * immediately drops that merchant's transactions from the home
     * KPIs, the category donut, the daily trend, the top-merchant
     * bar, and the day-of-week chart. A2 also picks the flag up on
     * the next SMS and returns `merchant.kind = "none"` for any
     * matching row, so the "Own Account" attribution stops appearing in
     * fresh transactions.
     */
    suspend fun setIsSelf(id: Long, isSelf: Boolean) = dao.updateIsSelf(id, isSelf)

    // --- metadata ---

    /**
     * Upsert one row of merchant context. Single-valued kinds
     * (NOTE, CATEGORY_HINT, LABEL) replace any existing row of
     * the same kind for the same merchant; multi-valued kinds
     * could be added in future by simply adding to the enum
     * and documenting the new uniqueness rule.
     */
    suspend fun putMetadata(
        merchantId: Long,
        kind: MerchantMetadataKind,
        value: String,
        now: Long,
    ): Long {
        val existing = metadataDao.getForMerchant(merchantId)
            .firstOrNull { it.kind == kind.name }
        val row = MerchantMetadata(
            id = existing?.id ?: 0L,
            merchantId = merchantId,
            kind = kind.name,
            value = value,
            createdAt = existing?.createdAt ?: now,
        )
        return metadataDao.upsert(row)
    }

    suspend fun removeMetadata(merchantId: Long, kind: MerchantMetadataKind) {
        metadataDao.deleteForKind(merchantId, kind.name)
    }

    suspend fun getMetadata(merchantId: Long): List<MerchantMetadata> =
        metadataDao.getForMerchant(merchantId)

    suspend fun getMetadataForMerchants(merchantIds: List<Long>): List<MerchantMetadata> {
        if (merchantIds.isEmpty()) return emptyList()
        return metadataDao.getForMerchants(merchantIds)
    }

    fun observeMetadataForMerchant(merchantId: Long): Flow<List<MerchantMetadata>> =
        metadataDao.observeForMerchant(merchantId)

    /**
     * Hot stream of every merchant joined to their metadata. The
     * Merchants management screen renders one row per merchant and
     * expands into the metadata list on tap.
     */
    fun observeAllWithMetadata(): Flow<List<MerchantWithMetadataRow>> =
        metadataDao.observeAllWithMetadata()
}
