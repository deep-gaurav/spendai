package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendai.app.data.local.entity.MerchantMetadata
import com.spendai.app.data.local.entity.MerchantMetadataKind
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [MerchantMetadata] rows.
 *
 * The two reads the callers actually use:
 *  - [getForMerchants]: A2/A3 load metadata for the merchants
 *    in their prompt bundle in a single query. Returns a
 *    `merchantId -> rows` map so the prompt builder can inline
 *    metadata next to each merchant without a second pass.
 *  - [observeForMerchant]: the Merchants screen hot-stream of
 *    one merchant's metadata.
 *
 * Writes go through the [com.spendai.app.domain.agent.insights.MerchantMutator]
 * (a sibling of [com.spendai.app.domain.agent.insights.SqlExecutor]),
 * not directly through this DAO. The mutator's allowlist is the
 * only path that can write to [Merchant] and [MerchantMetadata]
 * from the LLM.
 */
@Dao
interface MerchantMetadataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MerchantMetadata): Long

    @Query("DELETE FROM merchant_metadata WHERE merchantId = :merchantId AND kind = :kind")
    suspend fun deleteForKind(merchantId: Long, kind: String)

    @Query("DELETE FROM merchant_metadata WHERE merchantId = :merchantId")
    suspend fun deleteForMerchant(merchantId: Long)

    @Query(
        "SELECT * FROM merchant_metadata WHERE merchantId = :merchantId " +
            "ORDER BY kind ASC"
    )
    suspend fun getForMerchant(merchantId: Long): List<MerchantMetadata>

    @Query(
        "SELECT * FROM merchant_metadata WHERE merchantId = :merchantId " +
            "ORDER BY kind ASC"
    )
    fun observeForMerchant(merchantId: Long): Flow<List<MerchantMetadata>>

    /**
     * Batched read used by the A2/A3 prompt loader. Returns every
     * metadata row whose `merchantId` is in [merchantIds], in
     * a single SQL roundtrip. The caller groups the rows by
     * `merchantId` in Kotlin so the prompt can be assembled
     * per-merchant without N+1 queries.
     */
    @Query(
        "SELECT * FROM merchant_metadata WHERE merchantId IN (:merchantIds) " +
            "ORDER BY merchantId ASC, kind ASC"
    )
    suspend fun getForMerchants(merchantIds: List<Long>): List<MerchantMetadata>

    /**
     * Stream of every merchant + their metadata, used by the
     * Merchants management screen. The Kotlin side groups by
     * `merchantId` so the UI can render one row per merchant.
     */
    @Query(
        "SELECT m.id AS m_id, m.name AS m_name, m.normalizedName AS m_normalizedName, " +
            "m.vpa AS m_vpa, m.categoryId AS m_categoryId, m.firstSeenAt AS m_firstSeenAt, " +
            "m.isSelf AS m_isSelf, " +
            "mm.id AS mm_id, mm.merchantId AS mm_merchantId, " +
            "mm.kind AS mm_kind, mm.value AS mm_value, mm.createdAt AS mm_createdAt " +
            "FROM merchant m LEFT JOIN merchant_metadata mm ON mm.merchantId = m.id " +
            "ORDER BY m.firstSeenAt DESC, mm.kind ASC"
    )
    fun observeAllWithMetadata(): Flow<List<MerchantWithMetadataRow>>
}

/**
 * Flat projection for the Merchants management screen.
 *
 * The LEFT JOIN means a merchant without metadata gets one row
 * with all `mm_*` fields null. The UI groups by `m_id` so the
 * user sees one row per merchant.
 */
data class MerchantWithMetadataRow(
    val m_id: Long,
    val m_name: String,
    val m_normalizedName: String,
    val m_vpa: String?,
    val m_categoryId: Long?,
    val m_firstSeenAt: Long,
    val m_isSelf: Boolean,
    val mm_id: Long?,
    val mm_merchantId: Long?,
    val mm_kind: String?,
    val mm_value: String?,
    val mm_createdAt: Long?,
)
