package com.shiyinplayer.data.local

import androidx.room.TypeConverter
import com.shiyinplayer.data.model.MediaSourceType

/** Room 类型转换器：MediaSourceType 枚举 <-> String。 */
class Converters {
    @TypeConverter
    fun fromMediaSourceType(value: MediaSourceType): String = value.name

    @TypeConverter
    fun toMediaSourceType(value: String): MediaSourceType =
        MediaSourceType.entries.firstOrNull { it.name == value } ?: MediaSourceType.LOCAL
}
