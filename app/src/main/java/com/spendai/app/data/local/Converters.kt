package com.spendai.app.data.local

import androidx.room.TypeConverter
import com.spendai.app.data.local.entity.SmsStatus

/**
 * Room can't persist enums natively; round-tripping [SmsStatus] through its
 * name keeps schema diffs readable and avoids `OrdinalTypeConverter`'s
 * fragility (adding/reordering enum entries would silently corrupt old rows).
 */
class Converters {

    @TypeConverter
    fun smsStatusToString(value: SmsStatus): String = value.name

    @TypeConverter
    fun stringToSmsStatus(value: String): SmsStatus = SmsStatus.valueOf(value)
}
