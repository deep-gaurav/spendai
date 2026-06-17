package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.TransactionLinkDao
import com.spendai.app.data.local.entity.TransactionLink

class TransactionLinkRepository(private val dao: TransactionLinkDao) {
    suspend fun insertIgnore(row: TransactionLink): Long = dao.insertIgnore(row)
    suspend fun getAllOnce(): List<TransactionLink> = dao.getAllOnce()
}
