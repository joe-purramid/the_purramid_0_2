// TimeZoneRepository.kt
package com.example.purramid.thepurramid.clock.data

import com.example.purramid.thepurramid.clock.data.CityData
import org.locationtech.jts.geom.Polygon

interface TimeZoneRepository {
    suspend fun getTimeZonePolygons(): Result<Map<String, List<Polygon>>>
    suspend fun getCitiesForTimeZone(tzId: String): List<CityData>
}