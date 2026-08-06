package com.thelightphone.sdk.shared

import kotlin.time.Instant

data class LightServerPushCredentials(val pushEndpoint: String, val pushRegistrationDate: Instant)
data class LightServerData(val pushCredentials: LightServerPushCredentials?)