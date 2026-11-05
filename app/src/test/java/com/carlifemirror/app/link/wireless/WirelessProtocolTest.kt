package com.carlifemirror.app.link.wireless

import com.carlifemirror.app.carlife.Msg
import com.carlifemirror.app.carlife.PbWriter
import com.carlifemirror.app.carlife.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.Inet4Address

class WirelessProtocolTest {

    @Test
    fun btFramesSurviveArbitraryRfcommChunks() {
        val body = PbWriter().int32(1, 2).int32(2, 5180).toByteArray()
        val frame = BtMsg.frame(BtMsg.HU_WIRELESS_INFO, body)
        val parser = BtFrameParser()
        val result = ArrayList<BtFrame>()
        for (byte in frame) {
            result += parser.feed(byteArrayOf(byte))
        }
        assertEquals(1, result.size)
        assertEquals(BtMsg.HU_WIRELESS_INFO, result[0].type)
        assertEquals(body.toList(), result[0].body.toList())
        assertEquals(0L, parser.skipped)
    }

    @Test
    fun btFrameParserSkipsGarbageBeforeAValidFrame() {
        val frame = BtMsg.frame(BtMsg.HU_IP_REQUEST)
        val stream = ByteArray(8) { 0xFF.toByte() } + frame
        val frames = BtFrameParser().feed(stream)
        assertEquals(1, frames.size)
        assertEquals(BtMsg.HU_IP_REQUEST, frames[0].type)
    }

    @Test
    fun p2pTargetParsesSsidPasswordAndFrequency() {
        val body = PbWriter()
            .string(1, "DIRECT-Volkswagen")
            .string(4, "DIRECT-abc,password123,5180")
            .toByteArray()
        val target = P2pTarget.parse(body)
        assertNotNull(target)
        assertEquals("DIRECT-abc", target!!.ssid)
        assertEquals("password123", target.passphrase)
        assertEquals(5180, target.freqMhz)
        assertTrue(target.canJoinBySsid)
    }

    @Test
    fun innerCodecReadsMessagesFromSeparateStreams() {
        val cmd = Wire.cmdInner(Msg.HU_PROTOCOL_VERSION, PbWriter().int32(1, 2).int32(2, 1).toByteArray())
        val video = Wire.dataInner(Msg.VIDEO_DATA, 123, byteArrayOf(1, 2, 3, 4))

        val cmdRead = InnerCodec.read(
            1,
            DataInputStream(ByteArrayInputStream(cmd)),
        )
        val videoRead = InnerCodec.read(
            2,
            DataInputStream(ByteArrayInputStream(video)),
        )

        assertEquals(cmd.toList(), cmdRead!!.toList())
        assertEquals(video.toList(), videoRead!!.toList())
    }

    @Test(expected = BadInnerHeader::class)
    fun innerCodecRejectsTypeFromAnotherChannel() {
        val wrong = Wire.cmdInner(Msg.VIDEO_DATA, null)
        InnerCodec.read(1, DataInputStream(ByteArrayInputStream(wrong)))
    }

    @Test
    fun innerCodecAcceptsCmdHeartbeatFromVw() {
        val heartbeat = Wire.cmdInner(Msg.VIDEO_HEARTBEAT, null)
        val read = InnerCodec.read(1, DataInputStream(ByteArrayInputStream(heartbeat)))
        assertEquals(heartbeat.toList(), read!!.toList())
    }

    @Test
    fun lanInterfaceHostsStayInsideSubnet() {
        val ip = Inet4Address.getByName("192.168.43.1") as Inet4Address
        val broadcast = Inet4Address.getByName("192.168.43.255") as Inet4Address
        val lan = LanIface("wlan1", ip, 24, broadcast)
        val hosts = lan.hosts()
        assertEquals(253, hosts.size)
        assertTrue(lan.contains(Inet4Address.getByName("192.168.43.100")))
        assertTrue(!lan.contains(Inet4Address.getByName("192.168.44.1")))
    }

    @Test
    fun tcp6PortsMatchBaiduCarLife() {
        assertEquals(7240, Tcp6Ports.DEFAULT[1])
        assertEquals(8240, Tcp6Ports.DEFAULT[2])
        assertEquals(9240, Tcp6Ports.DEFAULT[3])
        assertEquals(9241, Tcp6Ports.DEFAULT[4])
        assertEquals(9242, Tcp6Ports.DEFAULT[5])
        assertEquals(9340, Tcp6Ports.DEFAULT[6])
        assertEquals(9440, Tcp6Ports.DATA)
    }
}
