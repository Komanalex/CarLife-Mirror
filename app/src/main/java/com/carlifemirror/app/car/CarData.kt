package com.carlifemirror.app.car

import com.carlifemirror.app.carlife.PbReader
import com.carlifemirror.app.carlife.Hex
import java.util.LinkedHashMap

/** Один модуль из CAR_DATA_SUBSCRIBE_RSP / CarlifeVehicleInfo. */
data class VehicleModule(
    val moduleID: Int,
    val flag: Int,
    val frequency: Int,
    val supportFlag: Int,
)

/**
 * Разбор всех известных ответов ГУ по автомобильным данным CarLife.
 * Держим только числа/строки, без сырых protobuf-объектов.
 */
object CarDataParser {
    const val GPS = 0
    const val VELOCITY = 1
    const val GYROSCOPE = 2
    const val ACCELERATION = 3
    const val GEAR = 4
    const val OIL = 5

    val allModules = listOf(GPS, VELOCITY, GYROSCOPE, ACCELERATION, GEAR, OIL)

    fun moduleName(id: Int): String = when (id) {
        GPS -> "GPS"
        VELOCITY -> "VELOCITY"
        GYROSCOPE -> "GYROSCOPE"
        ACCELERATION -> "ACCELERATION"
        GEAR -> "GEAR"
        OIL -> "OIL"
        else -> "MODULE_$id"
    }

    fun moduleListText(ids: Collection<Int>): String =
        if (ids.isEmpty()) "—" else ids.sorted().joinToString(", ") { "$it ${moduleName(it)}" }

    fun parseVehicleInfoList(body: ByteArray): List<VehicleModule>? {
        val fields = PbReader.parse(body) ?: return null
        val list = PbReader.all(fields, 2)
        if (list.isEmpty()) return emptyList()
        val out = ArrayList<VehicleModule>(list.size)
        for (item in list) {
            val sub = item.data?.let { PbReader.parse(it) } ?: continue
            out.add(
                VehicleModule(
                    moduleID = PbReader.int(sub, 1) ?: continue,
                    flag = PbReader.int(sub, 2) ?: 0,
                    frequency = PbReader.int(sub, 3) ?: 0,
                    supportFlag = PbReader.int(sub, 4) ?: 0,
                )
            )
        }
        return out
    }

    fun parseSpeed(body: ByteArray): LinkedHashMap<String, String> {
        val f = PbReader.parse(body)
        return linkedMapOf(
            "speed" to (f?.let { PbReader.int(it, 1) }?.toString() ?: "—"),
            "timeStamp" to (f?.let { PbReader.long(it, 2) }?.toString() ?: "—"),
        )
    }

    fun parseGps(body: ByteArray): LinkedHashMap<String, String> {
        val f = PbReader.parse(body)
        val names = listOf(
            "antennaState", "signalQuality", "latitude", "longitude", "height",
            "speed", "heading", "year", "month", "day", "hrs", "min", "sec",
            "fix", "hdop", "pdop", "vdop", "satsUsed", "satsVisible",
            "horPosError", "vertPosError", "northSpeed", "eastSpeed", "vertSpeed",
        )
        val out = LinkedHashMap<String, String>()
        names.forEachIndexed { i, name ->
            out[name] = (if (i < 24) f?.let { PbReader.int(it, i + 1) } else null)?.toString() ?: "—"
        }
        out["timeStamp"] = f?.let { PbReader.long(it, 25) }?.toString() ?: "—"
        return out
    }

    fun parseGyroscope(body: ByteArray): LinkedHashMap<String, String> {
        val f = PbReader.parse(body)
        return linkedMapOf(
            "gyroType" to (f?.let { PbReader.int(it, 1) }?.toString() ?: "—"),
            "gyroX" to (f?.let { PbReader.double(it, 2) }?.toString() ?: "—"),
            "gyroY" to (f?.let { PbReader.double(it, 3) }?.toString() ?: "—"),
            "gyroZ" to (f?.let { PbReader.double(it, 4) }?.toString() ?: "—"),
            "timeStamp" to (f?.let { PbReader.long(it, 5) }?.toString() ?: "—"),
        )
    }

    fun parseAcceleration(body: ByteArray): LinkedHashMap<String, String> {
        val f = PbReader.parse(body)
        return linkedMapOf(
            "accX" to (f?.let { PbReader.double(it, 1) }?.toString() ?: "—"),
            "accY" to (f?.let { PbReader.double(it, 2) }?.toString() ?: "—"),
            "accZ" to (f?.let { PbReader.double(it, 3) }?.toString() ?: "—"),
            "timeStamp" to (f?.let { PbReader.long(it, 4) }?.toString() ?: "—"),
        )
    }

    fun parseOil(body: ByteArray): LinkedHashMap<String, String> {
        val f = PbReader.parse(body)
        return linkedMapOf(
            "level" to (f?.let { PbReader.int(it, 1) }?.toString() ?: "—"),
            "range" to (f?.let { PbReader.int(it, 2) }?.toString() ?: "—"),
            "lowFuelWarning" to (f?.let { PbReader.int(it, 3) }?.let { if (it != 0) "да" else "нет" } ?: "—"),
        )
    }

    fun parseGear(body: ByteArray): LinkedHashMap<String, String> {
        val f = PbReader.parse(body)
        return linkedMapOf("gear" to (f?.let { PbReader.int(it, 1) }?.toString() ?: "—"))
    }

    fun raw(body: ByteArray): String =
        if (body.isEmpty()) "—" else Hex.compact(body, 512)
}
