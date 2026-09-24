package com.speedalert.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

data class GeoPoint(val lat: Double, val lon: Double)

@Entity(tableName = "road_segments")
data class RoadSegment(
    @PrimaryKey val osmId: Long,
    val name: String,
    val highwayType: String,
    val speedKmh: Int,
    val source: String,
    val geometry: List<GeoPoint>
)
