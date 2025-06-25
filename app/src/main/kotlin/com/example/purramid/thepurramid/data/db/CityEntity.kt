// CityEntity.kt
package com.example.purramid.thepurramid.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Define table name and indices (index on timezone is crucial for lookup)
@Entity(
    tableName = "cities",
    indices = [Index(value = ["timezone_id"], unique = false)]
)
data class CityEntity(
    // Use autoGenerate for a simple primary key if city/country isn't unique enough
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "city_name")
    val name: String,

    @ColumnInfo(name = "country_name")
    val country: String,

    @ColumnInfo(name = "latitude")
    val latitude: Double,

    // Longitude is optional based on previous discussion, include if kept in CSV
    @ColumnInfo(name = "longitude")
    val longitude: Double,

    @ColumnInfo(name = "timezone_id")
    val timezone: String, // The IANA Time Zone ID
)