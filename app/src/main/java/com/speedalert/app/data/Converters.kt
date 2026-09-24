package com.speedalert.app.data

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

class Converters {
    @TypeConverter
    fun fromGeometry(points: List<GeoPoint>): String {
        val arr = JSONArray()
        points.forEach { p ->
            val obj = JSONObject()
            obj.put("lat", p.lat)
            obj.put("lon", p.lon)
            arr.put(obj)
        }
        return arr.toString()
    }

    @TypeConverter
    fun toGeometry(data: String): List<GeoPoint> {
        val arr = JSONArray(data)
        val list = mutableListOf<GeoPoint>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(GeoPoint(obj.getDouble("lat"), obj.getDouble("lon")))
        }
        return list
    }
}
