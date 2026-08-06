package com.thelightphone.weather

import androidx.datastore.preferences.core.stringPreferencesKey

internal object WeatherPreferences {
    val LOCATION_QUERY = stringPreferencesKey("location_query")
    val LOCATION_NAME = stringPreferencesKey("location_name")
    val LATITUDE = stringPreferencesKey("latitude")
    val LONGITUDE = stringPreferencesKey("longitude")
    val FORECAST_JSON = stringPreferencesKey("forecast_json")
    val TEMPERATURE_UNIT = stringPreferencesKey("temperature_unit")
}
