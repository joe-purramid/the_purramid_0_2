// TimeZoneRepositoryImpl.kt
package com.example.purramid.thepurramid.clock.data

import android.content.Context
import android.util.Log
// Adjust DB/DI imports
import com.example.purramid.thepurramid.data.db.CityDao
import com.example.purramid.thepurramid.data.db.CityEntity
import com.example.purramid.thepurramid.data.db.TimeZoneBoundaryEntity
import com.example.purramid.thepurramid.data.db.TimeZoneDao
import com.example.purramid.thepurramid.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.io.WKTWriter
import org.locationtech.jts.io.geojson.GeoJsonReader
import javax.inject.Inject
import javax.inject.Singleton
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.io.InputStreamReader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


@Singleton
class TimeZoneRepositoryImpl @Inject constructor(
    private val timeZoneDao: TimeZoneDao,
    private val cityDao: CityDao,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher // Inject dispatcher
) : TimeZoneRepository {

    private val TAG = "TimeZoneRepository"
    private val CITIES_CSV_ASSET_PATH = "cities_timezones.csv"
    private val cityDbMutex = Mutex()
    private val GEOJSON_ASSET_NAME = "time_zones.geojson"
    private val timeZoneDbMutex = Mutex()
    @Volatile private var cityDbPopulated = false // Flag to check if DB is populated

    // Reusable JTS WKTReader instance
    private val wktReader = WKTReader()
    private val wktWriter = WKTWriter()

    // Implement getTimeZonePolygons
    override suspend fun getTimeZonePolygons(): Result<Map<String, List<Polygon>>> = withContext(ioDispatcher) {
        populateTimeZoneDbFromGeoJsonIfNeeded()
        Log.d(TAG, "Fetching timezone polygons from Room DB...")
        try {
            // 1. Query Room for all stored boundaries
            val boundaryEntities = timeZoneDao.getAllBoundaries()

            if (boundaryEntities.isEmpty()) {
                Log.w(TAG, "No timezone boundaries found in Room database. Was it populated?")
                // Return success with empty map or failure depending on desired behavior
                return@withContext Result.success(emptyMap())
            }

            // 2. Group entities by tzid and parse WKT strings into Polygons
            // Using groupBy + mapValues is efficient for potentially multiple polygons per tzid
            val polygonMap = boundaryEntities
                .groupBy { it.tzid } // Group by time zone ID
                .mapValues { (_, entities) -> // For each tzid and its list of entities
                    entities.flatMap { entity -> // Flatten the list of polygon lists
                        parseWktToPolygons(entity.polygonWkt) // Parse WKT for each entity
                    }
                }
                .filterValues { it.isNotEmpty() } // Remove entries where WKT parsing failed

            Log.d(TAG, "Successfully loaded and parsed polygons for ${polygonMap.size} time zones.")
            Result.success(polygonMap) // Return the resulting map

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get or parse timezone polygons from Room DB", e)
            Result.failure(e) // Return failure
        }
    }

    // --- Implementation for getCitiesForTimeZone using Room ---
    override suspend fun getCitiesForTimeZone(tzId: String): List<CityData> = withContext(ioDispatcher) {
        populateCityDbFromCsvIfNeeded() // Ensure DB has data

        // Query Room database
        val cityEntities = cityDao.getCitiesByTimezone(tzId)

        // Map Entity to Data class for use by ViewModel
        cityEntities.map { entity ->
            CityData(
                name = entity.name,
                country = entity.country,
                latitude = entity.latitude,
                longitude = entity.longitude,
                timezone = entity.timezone
            )
        }
    }

    private suspend fun populateTimeZoneDbFromGeoJsonIfNeeded() {
        // Quick check without lock
        if (timeZoneDbPopulated) return

        timeZoneDbMutex.withLock {
            // Double check after lock
            if (timeZoneDbPopulated) return@withLock

            try {
                val count = timeZoneDao.getCount() // Check if the boundary table is populated
                if (count == 0) {
                    Log.i(TAG, "TimeZone boundary database empty. Populating from GeoJSON: $GEOJSON_ASSET_NAME")
                    val boundariesToInsert = parseBoundariesFromGeoJson() // Call helper to parse
                    if (boundariesToInsert.isNotEmpty()) {
                        timeZoneDao.insertAll(boundariesToInsert) // Insert into Room
                        Log.i(TAG, "Successfully inserted ${boundariesToInsert.size} timezone boundaries into Room DB.")
                    } else {
                        Log.w(TAG, "No valid timezone boundaries found in GeoJSON to insert.")
                    }
                    timeZoneDbPopulated = true // Mark as populated (even if empty/failed to prevent retries)
                } else {
                    Log.d(TAG, "TimeZone boundary database already populated ($count entries).")
                    timeZoneDbPopulated = true // Mark as populated
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during TimeZone boundary DB population check/process", e)
                // Keep flag false to allow potential retry later
            }
        }
    }

    // --- Helper function to parse GeoJSON and convert to Entities ---
    private fun parseBoundariesFromGeoJson(): List<TimeZoneBoundaryEntity> {
        val boundaryEntities = mutableListOf<TimeZoneBoundaryEntity>()
        try {
            context.assets.open(GEOJSON_ASSET_NAME).use { inputStream ->
                // Use JTS GeoJsonReader for robust parsing
                val reader = GeoJsonReader()
                // GeoJsonReader reads Geometry directly, FeatureCollection might need different handling
                // Assuming a standard FeatureCollection structure as per your snippet
                val featureCollection = reader.read(InputStreamReader(inputStream))

                // Check if parsing resulted in a FeatureCollection (a GeometryCollection in JTS terms)
                if (featureCollection is org.locationtech.jts.geom.GeometryCollection && featureCollection.geometryType == "GeometryCollection") {
                    Log.d(TAG, "Parsing ${featureCollection.numGeometries} features from GeoJSON...")
                    for (i in 0 until featureCollection.numGeometries) {
                        val geometry = featureCollection.getGeometryN(i)
                        // GeoJsonReader stores properties in userData
                        val userData = geometry.userData as? Map<*, *>
                        val tzid = userData?.get("tzid")?.toString() // Get tzid from properties

                        if (tzid != null && tzid.isNotBlank() && (geometry is Polygon || geometry is MultiPolygon)) {
                            try {
                                // Convert the JTS Geometry (Polygon/MultiPolygon) to WKT String
                                val wktString = wktWriter.write(geometry)
                                if (wktString.isNotBlank()) {
                                    boundaryEntities.add(TimeZoneBoundaryEntity(tzid = tzid, polygonWkt = wktString))
                                } else {
                                    Log.w(TAG,"Generated empty WKT for tzid $tzid")
                                }
                            } catch (wktEx: Exception) {
                                Log.e(TAG, "Error converting geometry to WKT for tzid $tzid", wktEx)
                            }
                        } else {
                            Log.w(TAG, "Skipping feature: Invalid geometry type (${geometry.geometryType}) or missing/blank tzid.")
                        }
                    }
                } else {
                    Log.e(TAG, "Parsed GeoJSON root was not a FeatureCollection/GeometryCollection.")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException reading GeoJSON from assets", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error parsing GeoJSON", e)
        }
        Log.d(TAG, "Parsed ${boundaryEntities.size} valid timezone boundaries from GeoJSON.")
        return boundaryEntities
    }

    // --- Logic to populate Room DB from CSV only once ---
    private suspend fun populateCityDbFromCsvIfNeeded() {
        // Quick check without lock first
        if (cityDbPopulated) return

        cityDbMutex.withLock {
            // Double check after lock acquired
            if (cityDbPopulated) return@withLock

            try {
                val count = cityDao.getCount()
                if (count == 0) {

                    // Database is empty, populate from CSV
                    Log.i(TAG, "City database empty. Populating from CSV: $CITIES_CSV_ASSET_PATH")
                    val citiesToInsert = parseCitiesFromCsv()

                    if (citiesToInsert.isNotEmpty()) {
                        cityDao.insertAll(citiesToInsert) // Insert into DB (also inside try)
                        Log.i(TAG, "Successfully inserted ${citiesToInsert.size} cities into Room DB.")
                    } else {
                        Log.w(TAG, "No valid cities found in CSV to insert.")
                    }
                    // Mark as populated only if the whole process succeeded without exceptions
                    cityDbPopulated = true

                } else {
                    // DB already has data from a previous session
                    Log.d(TAG, "City database already populated ($count entries).")
                    cityDbPopulated = true // Mark as populated
                }
            } catch (e: IOException) {
                Log.e(TAG, "IOException during city DB population check/process", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error during city DB population check/process", e)
            }
        }
    }

    // Helper function to parse the CSV asset (keeps main logic cleaner)
    private fun parseCitiesFromCsv(): List<CityEntity> {
        val cityEntities = mutableListOf<CityEntity>()
        context.assets.open(CITIES_CSV_ASSET_PATH).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.readLine() // Skip header
                var lineNumber = 1
                reader.forEachLine { line ->
                    lineNumber++
                    try {
                        val tokens = line.split(",").map { it.trim('"', ' ') }
                        if (tokens.size == 6) { // utc_std,city,lat,lng,country,timezone
                            val entity = CityEntity(
                                name = tokens[1],
                                country = tokens[4],
                                latitude = tokens[2].toDouble(),
                                longitude = tokens[3].toDouble(),
                                timezone = tokens[5]
                            )
                            if (entity.timezone.isNotBlank() && entity.name.isNotBlank()) {
                                cityEntities.add(entity)
                            } else {
                                Log.w(TAG, "Skipping line $lineNumber: blank city/timezone")
                            }
                        } else {
                            Log.w(TAG, "Skipping line $lineNumber: wrong column count")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping line $lineNumber due to error: ${e.message}")
                    }
                }
            }
        } // .use automatically closes streams, even if IOException occurs inside
        return cityEntities
    }

     // Helper to parse WKT string back to a list containing one Polygon
    private fun parseWktToPolygons(wkt: String): List<Polygon> {
         if (wkt.isNullOrBlank()) return emptyList()
         return try {
            val geometry = WKTReader().read(wkt)
             when (geometry) {
                 is Polygon -> listOf(geometry)
                 is MultiPolygon -> {
                     // Extract individual polygons from MultiPolygon
                     (0 until geometry.numGeometries).mapNotNull { geometry.getGeometryN(it) as? Polygon }
                 }
                 else -> {
                     Log.w(TAG, "Parsed WKT is not a Polygon or MultiPolygon: ${geometry.geometryType}")
                     emptyList()
                 }
             }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WKT: $wkt", e);
             emptyList()
        }
    }
}