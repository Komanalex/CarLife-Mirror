package com.carlifemirror.app.car

import com.carlifemirror.app.carlife.PbWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarDataParserTest {
    @Test
    fun vehicleInfoListParsesAllModules() {
        val gps = PbWriter().int32(1, 0).int32(2, 1).int32(3, 1000).int32(4, 1).toByteArray()
        val speed = PbWriter().int32(1, 1).int32(2, 1).int32(3, 500).toByteArray()
        val body = PbWriter().int32(1, 2).bytes(2, gps).bytes(2, speed).toByteArray()

        val list = CarDataParser.parseVehicleInfoList(body)!!
        assertEquals(2, list.size)
        assertEquals(0, list[0].moduleID)
        assertEquals(1, list[0].flag)
        assertEquals(1000, list[0].frequency)
        assertEquals(1, list[1].moduleID)
        assertEquals(500, list[1].frequency)
    }

    @Test
    fun speedParsesValueAndTimestamp() {
        val body = PbWriter().int32(1, 42).int64(2, 123_456L).toByteArray()
        val data = CarDataParser.parseSpeed(body)
        assertEquals("42", data["speed"])
        assertEquals("123456", data["timeStamp"])
    }

    @Test
    fun gpsParsesAllFields() {
        val w = PbWriter()
        for (i in 1..24) w.int32(i, i * 10)
        w.int64(25, 777L)
        val data = CarDataParser.parseGps(w.toByteArray())
        assertEquals("10", data["antennaState"])
        assertEquals("240", data["vertSpeed"])
        assertEquals("777", data["timeStamp"])
        assertEquals(25, data.size)
    }

    @Test
    fun gyroscopeAndAccelerationParseDoubles() {
        val gyro = CarDataParser.parseGyroscope(
            PbWriter().int32(1, 2).double(2, 1.5).double(3, -2.25).double(4, 0.0)
                .int64(5, 100L).toByteArray()
        )
        assertEquals("2", gyro["gyroType"])
        assertEquals("1.5", gyro["gyroX"])
        assertEquals("-2.25", gyro["gyroY"])
        assertEquals("0.0", gyro["gyroZ"])

        val acc = CarDataParser.parseAcceleration(
            PbWriter().double(1, 0.1).double(2, 0.2).double(3, 9.8).toByteArray()
        )
        assertEquals("0.1", acc["accX"])
        assertEquals("0.2", acc["accY"])
        assertEquals("9.8", acc["accZ"])
    }

    @Test
    fun oilAndGearParseValues() {
        val oil = CarDataParser.parseOil(
            PbWriter().int32(1, 55).int32(2, 420).bool(3, true).toByteArray()
        )
        assertEquals("55", oil["level"])
        assertEquals("420", oil["range"])
        assertEquals("да", oil["lowFuelWarning"])

        val gear = CarDataParser.parseGear(PbWriter().int32(1, 3).toByteArray())
        assertEquals("3", gear["gear"])
    }

    @Test
    fun moduleNamesCoverAllSubscriptions() {
        assertEquals("GPS", CarDataParser.moduleName(0))
        assertEquals("VELOCITY", CarDataParser.moduleName(1))
        assertEquals("GYROSCOPE", CarDataParser.moduleName(2))
        assertEquals("ACCELERATION", CarDataParser.moduleName(3))
        assertEquals("GEAR", CarDataParser.moduleName(4))
        assertEquals("OIL", CarDataParser.moduleName(5))
        assertTrue(CarDataParser.moduleListText(CarDataParser.allModules).contains("OIL"))
    }
}
