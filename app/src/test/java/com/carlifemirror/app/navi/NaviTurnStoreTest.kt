package com.carlifemirror.app.navi

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviTurnStoreTest {
    @After
    fun tearDown() {
        NaviTurnStore.clear()
    }

    @Test
    fun navigationStaysActiveWhileNotificationKeyExists() {
        NaviTurnStore.update(
            key = "navi-key",
            nextTurn = 7,
            roadName = "Main Street",
            remainMeters = 300,
            totalMeters = 12_000,
            iconPng = null,
        )
        assertTrue(NaviTurnStore.snapshot().active)

        // Нет обновлений уведомления, но оно всё ещё висит: HUD не должен гаснуть.
        assertTrue(NaviTurnStore.snapshot().active)
    }

    @Test
    fun navigationEndsOnlyWhenNotificationIsRemoved() {
        NaviTurnStore.update("navi-key", 7, "Main Street", 300, 12_000, null)
        NaviTurnStore.remove("navi-key")
        assertFalse(NaviTurnStore.snapshot().active)
    }

    @Test
    fun clearDropsNavigation() {
        NaviTurnStore.update("navi-key", 7, "Main Street", 300, 12_000, null)
        NaviTurnStore.clear()
        assertFalse(NaviTurnStore.snapshot().active)
        assertFalse(NaviTurnStore.isTracked("navi-key"))
    }
}
