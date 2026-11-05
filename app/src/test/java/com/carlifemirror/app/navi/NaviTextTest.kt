package com.carlifemirror.app.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviTextTest {
    @Test
    fun distanceParsesMetricAndImperialUnits() {
        assertEquals(500, NaviText.distanceMeters("500 m"))
        assertEquals(300, NaviText.distanceMeters("In 300 m, turn right"))
        assertEquals(1_200, NaviText.distanceMeters("1,2 км"))
        assertEquals(2_500, NaviText.distanceMeters("2.5 km"))
        assertEquals(805, NaviText.distanceMeters("0.5 mi"))
        assertEquals(1_200, NaviText.distanceMeters("1.2 км · 5 мин"))
        assertNull(NaviText.distanceMeters("500"))
        assertNull(NaviText.distanceMeters("5 мин"))
    }

    @Test
    fun maneuversAreRecognizedInRussianAndEnglish() {
        assertEquals(NaviText.Maneuver.RIGHT, NaviText.maneuverOf("Turn right onto Main St"))
        assertEquals(NaviText.Maneuver.SHARP_LEFT, NaviText.maneuverOf("Turn sharp left ahead"))
        assertEquals(NaviText.Maneuver.SLIGHT_RIGHT, NaviText.maneuverOf("Turn slight right"))
        assertEquals(NaviText.Maneuver.KEEP_LEFT, NaviText.maneuverOf("Keep left on Riverside"))
        assertEquals(NaviText.Maneuver.KEEP_RIGHT, NaviText.maneuverOf("Keep right at the fork"))
        assertEquals(NaviText.Maneuver.MERGE_LEFT, NaviText.maneuverOf("Merge left onto the highway"))
        assertEquals(
            NaviText.Maneuver.ROUNDABOUT_EXIT,
            NaviText.maneuverOf("At the roundabout, take the 2nd exit"),
        )
        assertEquals(NaviText.Maneuver.U_TURN, NaviText.maneuverOf("Make a U-turn"))
        assertEquals(NaviText.Maneuver.STRAIGHT, NaviText.maneuverOf("Continue straight"))
        assertEquals(NaviText.Maneuver.DESTINATION, NaviText.maneuverOf("Arrive at destination"))

        assertEquals(NaviText.Maneuver.LEFT, NaviText.maneuverOf("Поверните налево на улицу Ленина"))
        assertEquals(NaviText.Maneuver.RIGHT, NaviText.maneuverOf("Поверните направо"))
        assertEquals(NaviText.Maneuver.KEEP_RIGHT, NaviText.maneuverOf("Держитесь правее"))
        assertEquals(NaviText.Maneuver.U_TURN, NaviText.maneuverOf("Разворот через 200 м"))
        assertEquals(NaviText.Maneuver.ROUNDABOUT, NaviText.maneuverOf("Круговое движение"))
        assertEquals(NaviText.Maneuver.DESTINATION, NaviText.maneuverOf("Вы прибыли в пункт назначения"))
    }

    @Test
    fun roadNameIsExtractedFromNotificationTitle() {
        assertEquals("Main Street", NaviText.roadNameOf("Turn right onto Main Street"))
        assertEquals("Main St", NaviText.roadNameOf("Continue on Main St"))
        assertEquals("Ring Road", NaviText.roadNameOf("At the roundabout, take the 2nd exit onto Ring Road"))
        assertEquals(
            "улицу Ленина",
            NaviText.roadNameOf("Поверните направо на улицу Ленина"),
        )
        // Если маркера нет в заголовке, пробуем текст уведомления.
        assertEquals(
            "Main Street",
            NaviText.roadNameOf("Google Maps", "Turn right onto Main Street"),
        )
        // Если маркера нет совсем, показываем заголовок как есть — лучше, чем пустая строка.
        assertEquals("Turn right", NaviText.roadNameOf("Turn right"))
    }

    @Test
    fun routeDistanceIsOnlyUsedWhenItIsLargerThanRemaining() {
        assertEquals(12_000, NaviText.routeMeters("12 km · 15 min", "", 500))
        assertEquals(2_000, NaviText.routeMeters("", "2 km", 200))
        assertEquals(0, NaviText.routeMeters("300 m", "", 500))
        assertEquals(0, NaviText.routeMeters("5 min", "12 min", 500))
    }

    @Test
    fun hudTextHelpersFormatManeuverIconAndDistance() {
        assertEquals("Поверните направо", NaviText.maneuverText(NaviText.Maneuver.RIGHT))
        assertEquals("Разворот", NaviText.maneuverText(NaviText.Maneuver.U_TURN))
        assertEquals("Пункт назначения", NaviText.maneuverText(NaviText.Maneuver.DESTINATION))
        assertEquals("turn_right.png", NaviText.iconName(NaviText.Maneuver.RIGHT))
        assertEquals("turn_dest.png", NaviText.iconName(NaviText.Maneuver.DESTINATION))
        assertEquals("500 м", NaviText.distanceDesc(500))
        assertEquals("1 км", NaviText.distanceDesc(1_000))
        assertEquals("1,2 км", NaviText.distanceDesc(1_200))
        assertEquals("", NaviText.distanceDesc(0))
    }

    @Test
    fun arrivalIsDetectedSeparatelyFromRegularTurn() {
        assertTrue(NaviText.isArrived("Arrive at destination"))
        assertTrue(NaviText.isArrived("Вы прибыли в пункт назначения"))
    }

    @Test
    fun timeIsParsedFromMinutesAndHours() {
        assertEquals(300, NaviText.timeSeconds("5 min"))
        assertEquals(300, NaviText.timeSeconds("In 5 minutes"))
        assertEquals(300, NaviText.timeSeconds("5 мин"))
        assertEquals(300, NaviText.timeSeconds("5 минут"))
        assertEquals(4_800, NaviText.timeSeconds("1 ч 20 мин"))
        assertEquals(4_800, NaviText.timeSeconds("1 hour 20 min"))
        assertEquals(7_200, NaviText.timeSeconds("2 h"))
        assertNull(NaviText.timeSeconds("500 m"))
        assertNull(NaviText.timeSeconds("Поверните направо"))
    }

    @Test
    fun timeDescriptionIsShortForHud() {
        assertEquals("5 мин", NaviText.timeDesc(300))
        assertEquals("1 мин", NaviText.timeDesc(30))
        assertEquals("1 ч 20 мин", NaviText.timeDesc(4_800))
        assertEquals("2 ч", NaviText.timeDesc(7_200))
        assertEquals("", NaviText.timeDesc(0))
    }

    @Test
    fun roadNameIsShortenedAndEmptyRoadFallsBackToManeuver() {
        assertEquals("Направо", NaviText.fitRoadName("", NaviText.Maneuver.RIGHT))
        assertEquals("Налево", NaviText.fitRoadName("  ", NaviText.Maneuver.LEFT))
        assertEquals(
            "Main Street",
            NaviText.fitRoadName("В сторону Main Street", NaviText.Maneuver.RIGHT),
        )
        assertEquals(
            "ул. Ленина",
            NaviText.fitRoadName("  ул.   Ленина  ", NaviText.Maneuver.LEFT),
        )
        val long = NaviText.fitRoadName(
            "Очень длинное название улицы, которое не помещается на приборке",
            NaviText.Maneuver.RIGHT,
        )
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 31)
        assertTrue(long.startsWith("Очень длинное"))
        assertTrue(long.count { it.isLetter() } >= 2)
    }
}
