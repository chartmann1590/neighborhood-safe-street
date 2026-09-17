package com.neighborhood.safestreet.common.serialization

import kotlinx.serialization.json.Json

object JsonHelper {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
}
