package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.ParsedSmsDao
import com.spendai.app.data.local.entity.ParsedSms
import kotlinx.coroutines.flow.Flow

class ParsedSmsRepository(private val dao: ParsedSmsDao) {
    suspend fun insert(row: ParsedSms): Long = dao.insert(row)
    suspend fun getByRawSms(rawSmsId: Long): ParsedSms? = dao.getByRawSms(rawSmsId)
    suspend fun getById(id: Long): ParsedSms? = dao.getById(id)
    suspend fun deleteByRawSms(rawSmsId: Long) = dao.deleteByRawSms(rawSmsId)
    fun observeAll(): Flow<List<ParsedSms>> = dao.observeAll()
}
