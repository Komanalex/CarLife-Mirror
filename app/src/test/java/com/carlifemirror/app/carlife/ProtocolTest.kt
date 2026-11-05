package com.carlifemirror.app.carlife

import com.carlifemirror.app.navi.NaviField6
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.util.Base64

class HandshakeTest {
    @Test
    fun v2BranchRequiresVersionAndNonEmptyRandom() {
        assertTrue(HuAuth.isV2Branch("2.0;abc"))
        assertTrue(HuAuth.isV2Branch("2.0;abc;"))
        assertFalse(HuAuth.isV2Branch("2.0;"))
        assertFalse(HuAuth.isV2Branch("1.0;abc"))
        assertFalse(HuAuth.isV2Branch("2.0"))
        assertFalse(HuAuth.isV2Branch(null))
    }

    @Test
    fun verifyCodeMatchesBaiduV2Hash() {
        assertEquals("402f77cef5a240f9e8a3d035366d0b4c", HuAuth.verifyCode("0123456789abcdef"))
        assertEquals(
            HuAuth.verifyCode("Pixel8abc"),
            HuAuth.encryptValue("2.0;abc", "Pixel8"),
        )
    }

    @Test
    fun oldRsaBranchSignsVersionVerifiedRandom() {
        assertEquals("abc_8.6.7", HuAuth.versionVerify("abc_8.6.7"))
        assertEquals("abc_8.6.6", HuAuth.versionVerify("abc_8.6.6"))
        assertEquals("abc_00.00.00", HuAuth.versionVerify("abc_8.6.8"))
        assertEquals(
            "MvlUsZoKaNrrWI2fNhgt_00.00.00",
            HuAuth.versionVerify("MvlUsZoKaNrrWI2fNhgt_0"),
        )
        assertEquals("no-version", HuAuth.versionVerify("no-version"))

        val random = "MvlUsZoKaNrrWI2fNhgt_0"
        val signature = HuAuth.encryptValue(random, "Pixel8")
        assertEquals(HuAuth.versionVerify(random), decryptRsaWithPublic(signature))
    }

    /** Проверяем подпись через открытую операцию RSA: m = sig^e mod n, затем снимаем PKCS#1 v1.5. */
    private fun decryptRsaWithPublic(signatureBase64: String): String {
        val key = HuAuth.privateKey as java.security.interfaces.RSAPrivateCrtKey
        val sig = BigInteger(1, Base64.getDecoder().decode(signatureBase64))
        var block = sig.modPow(key.publicExponent, key.modulus).toByteArray()
        var off = 0
        if (block.isNotEmpty() && block[0] == 0.toByte()) off = 1
        assertTrue(block[off].toInt() == 1 || block[off].toInt() == 2)
        var end = off + 1
        while (end < block.size && block[end] != 0.toByte()) end++
        return String(block, end + 1, block.size - end - 1, Charsets.UTF_8)
    }

    @Test
    fun videoSizeFollowsSettingsAndPreservesHubAspect() {
        val (a, b, _) = Handshake.chooseVideoSize(1920, 1080, 1280, 720)
        assertEquals(1280, a)
        assertEquals(720, b)
        val (c, d, _) = Handshake.chooseVideoSize(1280, 720, 0, 0)
        assertEquals(1280, c)
        assertEquals(720, d)
        val (e, f, _) = Handshake.chooseVideoSize(2560, 1440, 0, 0)
        assertEquals(2560, e)
        assertEquals(1440, f)
        val (g, h, _) = Handshake.chooseVideoSize(1279, 719, 0, 0)
        assertEquals(1278, g)
        assertEquals(718, h)
        // 4K-запрос в режиме «Авто» вписывается в 2K без искажения пропорций.
        val (i, j, _) = Handshake.chooseVideoSize(3840, 2160, 0, 0)
        assertEquals(2560, i)
        assertEquals(1440, j)
    }

    @Test
    fun customResolutionIsExactAndValidated() {
        val (a, b, _) = Handshake.chooseVideoSize(1920, 1080, 2500, 976)
        assertEquals(2500, a)
        assertEquals(976, b)
        val (w, h, _) = Handshake.chooseVideoSize(1920, 1080, 2240, 976)
        assertEquals(2240, w)
        assertEquals(976, h)
        assertTrue(Handshake.isValidVideoSize(2240, 976))
        assertTrue(Handshake.isValidVideoSize(2500, 976))
        assertTrue(Handshake.isValidVideoSize(3840, 2160))
        assertFalse(Handshake.isValidVideoSize(2501, 976))
        assertFalse(Handshake.isValidVideoSize(2500, 975))
        assertFalse(Handshake.isValidVideoSize(2500, 2162))
        assertFalse(Handshake.isValidVideoSize(4096, 2160))
        val (c, d, _) = Handshake.chooseVideoSize(1920, 1080, 2501, 976)
        assertEquals(1280, c)
        assertEquals(720, d)
    }

    @Test
    fun fpsAndBitrateStayWithinProtocolLimits() {
        assertEquals(3_870_720, Handshake.bitrateFor(1280, 720, 30))
        assertEquals(8_000_000, Handshake.bitrateFor(2240, 976, 30))
        assertTrue(Handshake.bitrateFor(320, 240, 15) >= 1_500_000)
        assertEquals(12_000_000, Handshake.bitrateFor(1920, 1080, 60))
        assertEquals(12_000_000, Handshake.bitrateFor(2560, 1440, 60))
        assertTrue(Handshake.frameRateChangeAllowed(15))
        assertTrue(Handshake.frameRateChangeAllowed(30))
        assertTrue(Handshake.frameRateChangeAllowed(60))
        assertFalse(Handshake.frameRateChangeAllowed(61))
        assertEquals(2240, Handshake.WORKING_WIDTH)
        assertEquals(976, Handshake.WORKING_HEIGHT)
        assertEquals(30, Handshake.WORKING_FPS)
    }

    @Test
    fun sdkIsReportedAsTwentyNineOnNewAndroid() {
        assertEquals(Pair("29", 29), DeviceInfo.reportedSdk(33))
        assertEquals(Pair("29", 29), DeviceInfo.reportedSdk(29))
        assertEquals(Pair("30", 30), DeviceInfo.reportedSdk(30))
    }
}

class ProtocolTest {
    @Test
    fun commandAndDataMessagesRoundTrip() {
        val cmd = Wire.cmdInner(Msg.HU_AUTHEN_REQUEST, byteArrayOf(1, 2, 3))
        val parsedCmd = Wire.parseCmd(cmd)
        assertNotNull(parsedCmd)
        assertEquals(Msg.HU_AUTHEN_REQUEST, parsedCmd!!.type)
        assertEquals(3, parsedCmd.declaredLen)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsedCmd.body)
        assertFalse(parsedCmd.truncated)

        val data = Wire.dataInner(Msg.VIDEO_DATA, 12345, byteArrayOf(9, 8))
        val parsedData = Wire.parseData(data)
        assertNotNull(parsedData)
        assertEquals(Msg.VIDEO_DATA, parsedData!!.type)
        assertEquals(12345, parsedData.time)
        assertArrayEquals(byteArrayOf(9, 8), parsedData.data)
    }

    @Test
    fun shortOrMalformedMessagesAreRejected() {
        assertNull(Wire.parseCmd(ByteArray(7)))
        assertNull(Wire.parseData(ByteArray(11)))
    }

    @Test
    fun audioInitAndMediaDataMatchHeadswapFormat() {
        val initBody = Msgs.audioInit(48_000, 2, 16)
        assertArrayEquals(
            byteArrayOf(
                0x08, 0x80.toByte(), 0xF7.toByte(), 0x02,
                0x10, 0x02,
                0x18, 0x10,
            ),
            initBody,
        )
        val init = PbReader.parse(initBody)
        assertNotNull(init)
        assertEquals(48_000, PbReader.int(init!!, 1))
        assertEquals(2, PbReader.int(init, 2))
        assertEquals(16, PbReader.int(init, 3))

        val pcm = ByteArray(8_192) { (it and 0xFF).toByte() }
        val packet = Wire.dataInner(Msg.MEDIA_DATA, 123_456, pcm)
        val parsed = Wire.parseData(packet)
        assertNotNull(parsed)
        assertEquals(Msg.MEDIA_DATA, parsed!!.type)
        assertEquals(123_456, parsed.time)
        assertArrayEquals(pcm, parsed.data)
    }

    @Test
    fun frameParserReassemblesSplitBulkFrames() {
        val inner = Wire.dataInner(Msg.VIDEO_DATA, 7, byteArrayOf(4, 5, 6))
        val frame = Wire.outerHeader(Ch.VIDEO, inner.size) + inner
        val parser = FrameParser()
        val got = ArrayList<Pair<Int, ByteArray>>()
        parser.feed(frame, 0, 5) { c, b -> got.add(Pair(c, b)) }
        assertEquals(0, got.size)
        parser.feed(frame, 5, frame.size - 5) { c, b -> got.add(Pair(c, b)) }
        assertEquals(1, got.size)
        assertEquals(Ch.VIDEO, got[0].first)
        assertArrayEquals(inner, got[0].second)
        assertEquals(0, parser.pending())
    }

    @Test
    fun writeSequenceSplitsPayloadIntoProtocolChunks() {
        val payload = ByteArray(Wire.CHUNK * 2 + 17) { (it and 0xFF).toByte() }
        val parts = Wire.writeSequence(Ch.VIDEO, payload)
        assertEquals(4, parts.size)
        assertEquals(Wire.CHUNK, parts[1].size)
        assertEquals(Wire.CHUNK, parts[2].size)
        assertEquals(17, parts[3].size)
        assertEquals(Ch.VIDEO, Wire.u32(parts[0], 0))
        assertEquals(payload.size, Wire.u32(parts[0], 4))
    }

    @Test
    fun protobufWriterAndReaderRoundTrip() {
        val body = PbWriter()
            .int32(1, 42)
            .string(2, "hello")
            .bool(3, true)
            .toByteArray()
        val fields = PbReader.parse(body)
        assertNotNull(fields)
        assertEquals(42, PbReader.int(fields!!, 1))
        assertEquals("hello", PbReader.str(fields, 2))
        assertEquals(1, PbReader.int(fields, 3))
    }

    @Test
    fun naviNextTurnBodyMatchesProtocolFields() {
        val icon = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val body = Msgs.naviNextTurn(
            action = 1,
            nextTurn = 7,
            roadName = "улицу Ленина",
            totalDistance = 12_000,
            remainDistance = 300,
            turnIcon = icon,
        )
        val fields = PbReader.parse(body)
        assertNotNull(fields)
        assertEquals(1, PbReader.int(fields!!, 1))
        assertEquals(7, PbReader.int(fields, 2))
        assertEquals("улицу Ленина", PbReader.str(fields, 3))
        assertEquals(12_000, PbReader.int(fields, 4))
        assertEquals(300, PbReader.int(fields, 5))
        assertArrayEquals(icon, PbReader.all(fields, 6).first().data!!)
    }

    @Test
    fun naviNextTurnCanSendTimeInsteadOfIcon() {
        val body = Msgs.naviNextTurn(
            action = 2,
            nextTurn = 7,
            roadName = "улицу Ленина",
            totalDistance = 12_000,
            remainDistance = 300,
            turnIcon = byteArrayOf(1, 2, 3),
            timeSeconds = 300,
            field6 = NaviField6.TIME,
        )
        val fields = PbReader.parse(body)!!
        assertEquals(300, PbReader.int(fields, 6))
        assertEquals(1, PbReader.all(fields, 6).size)
        assertNull(PbReader.all(fields, 6).first().data)
    }

    @Test
    fun naviNextTurnCanSendIconAndTimeTogether() {
        val icon = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val body = Msgs.naviNextTurn(
            action = 2,
            nextTurn = 7,
            roadName = "улицу Ленина",
            totalDistance = 12_000,
            remainDistance = 300,
            turnIcon = icon,
            timeSeconds = 300,
            field6 = NaviField6.BOTH,
        )
        val fields = PbReader.parse(body)!!
        assertEquals(300, PbReader.int(fields, 6))
        assertEquals(2, PbReader.all(fields, 6).size)
        assertArrayEquals(icon, PbReader.all(fields, 6).first { it.wire == 2 }.data!!)
    }

    @Test
    fun naviNextTurnCanOmitField6() {
        val body = Msgs.naviNextTurn(
            action = 2,
            nextTurn = 7,
            roadName = "улицу Ленина",
            totalDistance = 12_000,
            remainDistance = 300,
            turnIcon = byteArrayOf(1, 2, 3),
            timeSeconds = 300,
            field6 = NaviField6.NONE,
        )
        val fields = PbReader.parse(body)!!
        assertTrue(PbReader.all(fields, 6).isEmpty())
    }

    @Test
    fun naviNextTurnEndsWithoutIcon() {
        val body = Msgs.naviNextTurn(3, 24, "Пункт назначения", 0, 0, null)
        val fields = PbReader.parse(body)
        assertNotNull(fields)
        assertEquals(3, PbReader.int(fields!!, 1))
        assertEquals(0, PbReader.all(fields, 6).first().data!!.size)
    }

    @Test
    fun naviBikeInfoBodyMatchesProtocolFields() {
        val icon = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val timestamp = 1_700_000_000_123L
        val body = Msgs.naviBikeInfo(
            icon = icon,
            iconName = "turn_right.png",
            turnInfo1 = "Поверните направо",
            turnInfo2 = "Main St",
            remainDistanceDesc = "500 м",
            remainTimeDesc = "",
            speed = 0,
            timeStamp = timestamp,
        )
        val fields = PbReader.parse(body)
        assertNotNull(fields)
        assertArrayEquals(icon, PbReader.all(fields!!, 1).first().data!!)
        assertEquals(icon.size, PbReader.int(fields, 2))
        assertEquals(0, PbReader.int(fields, 3))
        assertEquals("turn_right.png", PbReader.str(fields, 4))
        assertEquals("Поверните направо", PbReader.str(fields, 5))
        assertEquals("Main St", PbReader.str(fields, 6))
        assertEquals("500 м", PbReader.str(fields, 7))
        assertEquals("", PbReader.str(fields, 8))
        assertEquals(0, PbReader.int(fields, 9))
        assertEquals(timestamp, PbReader.all(fields, 10).first().num)
    }

    @Test
    fun featureConfigParsesItemsAndBtFields() {
        val item = PbWriter().string(1, "CONTENT_ENCRYPTION").int32(2, 1).toByteArray()
        val body = PbWriter()
            .bytes(2, item)
            .int32(3, 1)
            .string(4, "CarLife BT")
            .string(5, "AA:BB:CC:DD:EE:FF")
            .toByteArray()
        val cfg = FeatureConfig.parse(body)
        assertNotNull(cfg)
        assertEquals(1, cfg!!.value("CONTENT_ENCRYPTION"))
        assertEquals(true, cfg.huBtAudioSupport)
        assertEquals("CarLife BT", cfg.huBtName)
        assertEquals("AA:BB:CC:DD:EE:FF", cfg.huBtMac)
    }

    @Test
    fun touchMessagesAreDecodedForDirectAndLegacyTypes() {
        val direct = PbWriter().int32(1, 2).int32(2, 100).int32(3, 200).int32(4, 300).int32(5, 400).toByteArray()
        val action = CarlifeTouch.parse(Msg.TOUCH_ACTION, direct)
        assertNotNull(action)
        assertEquals(2, action!!.masked)
        assertEquals(2, action.fingers)
        assertEquals(100, action.x)
        assertEquals(400, action.py)

        val downBody = PbWriter().int32(1, 10).int32(2, 20).toByteArray()
        val down = CarlifeTouch.parse(Msg.TOUCH_ACTION_DOWN, downBody)
        assertNotNull(down)
        assertEquals(0, down!!.masked)
        assertEquals(10, down.x)
        assertEquals(20, down.y)

        val longPress = CarlifeTouch.parse(Msg.TOUCH_LONG_PRESS, downBody)
        assertNotNull(longPress)
        assertEquals(3, longPress!!.masked)

        val twoFingers = PbWriter()
            .int32(1, 300)
            .int32(2, 400)
            .int32(3, 500)
            .int32(4, 600)
            .toByteArray()
        val pointerDown = CarlifeTouch.parse(Msg.TOUCH_ACTION_POINTER_DOWN, twoFingers)
        assertNotNull(pointerDown)
        assertEquals(5, pointerDown!!.masked)
        assertEquals(1, pointerDown.action ushr 8)
        assertEquals(300, pointerDown.x)
        assertEquals(400, pointerDown.y)
        assertEquals(500, pointerDown.px)
        assertEquals(600, pointerDown.py)
        assertEquals(2, pointerDown.fingers)

        val otherPointerUp = CarlifeTouch.parse(Msg.TOUCH_ACTION_OTHERPOINTER_UP, twoFingers)
        assertNotNull(otherPointerUp)
        assertEquals(6, otherPointerUp!!.masked)
        assertEquals(1, otherPointerUp.action ushr 8)
    }
}

class AnnexBTest {
    @Test
    fun startCodesAreNormalizedToFourBytes() {
        val input = byteArrayOf(0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3)
        val out = AnnexB.normalize(input)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3),
            out,
        )
        assertArrayEquals(out, AnnexB.normalize(out))
    }

    @Test
    fun nalTypesAreReadWithoutCopyingPayload() {
        val stream = byteArrayOf(
            0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68, 0, 0, 0, 1, 0x65,
        )
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(stream))
        assertTrue(AnnexB.startsWithSps(stream))
    }

    @Test
    fun firstKeyframeReceivesSpsPpsBeforeIdr() {
        val csd = byteArrayOf(0, 0, 0, 1, 0x67, 66, 0, 42, 1, 0, 0, 0, 1, 0x68, 2)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 3, 4)
        val out = AnnexB.ensureSpsPps(idr, csd)
        assertNotNull(out)
        assertEquals(listOf(7, 8, 5), AnnexB.nalTypes(out!!))
        assertTrue(AnnexB.hasSpsAndPps(out))
    }

    @Test
    fun keyframeWithOwnParameterSetsIsNotDuplicated() {
        val frame = byteArrayOf(
            0, 0, 0, 1, 0x67, 66, 0, 42, 1,
            0, 0, 0, 1, 0x68, 2,
            0, 0, 0, 1, 0x65, 3,
        )
        assertArrayEquals(frame, AnnexB.ensureSpsPps(frame, null)!!)
        assertNull(AnnexB.ensureSpsPps(byteArrayOf(0, 0, 0, 1, 0x65), null))
    }

    @Test
    fun queueLimitUsesFramesAndByteBacklog() {
        assertFalse(VideoQueueLimit.overflow(0, 0, 0.0))
        assertFalse(VideoQueueLimit.overflow(30, 64 * 1024, 0.0))
        assertTrue(VideoQueueLimit.overflow(31, 0, 0.0))
        assertTrue(VideoQueueLimit.overflow(0, 600 * 1024, 0.0))
        assertFalse(VideoQueueLimit.overflow(0, 600 * 1024, 2_000_000.0))
        assertTrue(VideoQueueLimit.overflow(0, 1_100_000, 2_000_000.0))
        assertTrue(VideoQueueLimit.overflow(0, 70_000, 100_000.0))
        assertEquals(500, VideoQueueLimit.backlogMs(500_000, 1_000_000.0))
        assertEquals(0, VideoQueueLimit.backlogMs(500_000, 0.0))
    }

    @Test
    fun highLevelSpsIsAdjustedToFrameSize() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 66, 0, 52, 1)
        val fixed = AnnexB.fixSps(sps, 1280, 720, 30)
        assertEquals(0x80.toByte(), fixed[6])
        assertEquals(31.toByte(), fixed[7])
        assertEquals(sps.size, fixed.size)
    }

    @Test
    fun lowLevelSpsIsLeftUntouched() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 66, 0, 31, 1)
        assertArrayEquals(sps, AnnexB.fixSps(sps, 1280, 720, 30))
    }

    @Test
    fun levelAccountsForFrameRateAndSize() {
        assertEquals(40, AnnexB.levelFor(1920, 1080, 30))
        assertEquals(42, AnnexB.levelFor(1920, 1080, 60))
        assertEquals(50, AnnexB.levelFor(2500, 976, 60))
        assertEquals(51, AnnexB.levelFor(2560, 1440, 60))
        assertEquals(52, AnnexB.levelFor(3840, 2160, 60))
    }

    @Test
    fun underreportedLevelIsRaisedForSixtyFps() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 66, 0, 40, 1)
        val fixed = AnnexB.fixSps(sps, 1920, 1080, 60)
        assertEquals(0x80.toByte(), fixed[6])
        assertEquals(42.toByte(), fixed[7])
        assertEquals(sps.size, fixed.size)
    }
}
