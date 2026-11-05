package com.carlifemirror.app.link.wireless

import com.carlifemirror.app.carlife.PbReader
import com.carlifemirror.app.carlife.PbWriter
import com.carlifemirror.app.carlife.Wire

/**
 * Кадры Bluetooth-канала беспроводного CarLife:
 * [длина тела u16 BE][резерв u16 = 0][тип u32 BE] + protobuf.
 * Это тот же восьмибайтный заголовок, что у CMD-канала USB.
 */
object BtMsg {
    const val MD_WIRELESS_INFO_REQUEST = 0x00100001
    const val HU_WIRELESS_INFO = 0x00108002
    const val MD_TARGET_INFO_REQUEST = 0x00100004
    const val HU_TARGET_INFO = 0x00108005
    const val HU_IP_REQUEST = 0x00108006
    const val MD_WIFI_IP = 0x00100007
    const val MD_STATUS = 0x00100008
    const val HU_STATUS = 0x00108009

    const val HDR = 8
    const val MAX_BODY = 4096

    fun name(type: Int): String = when (type) {
        MD_WIRELESS_INFO_REQUEST -> "WIRELESS_INFO_REQUEST"
        HU_WIRELESS_INFO -> "WIRELESS_INFO"
        MD_TARGET_INFO_REQUEST -> "TARGET_INFO_REQUEST"
        HU_TARGET_INFO -> "TARGET_INFO"
        HU_IP_REQUEST -> "IP_REQUEST"
        MD_WIFI_IP -> "WIFI_IP"
        MD_STATUS -> "MD_STATUS"
        HU_STATUS -> "HU_STATUS"
        else -> "0x%08X".format(type)
    }

    fun frame(type: Int, body: ByteArray? = null): ByteArray = Wire.cmdInner(type, body)

    /**
     * Тело MD_WIFI_IP: адрес телефона в P2P-группе строкой в поле 1.
     * Формат взят из проверенного Headswap; для обычного хотспота не нужен.
     */
    fun wifiIp(ip: String): ByteArray = PbWriter().string(1, ip).toByteArray()
}

class P2pTarget(
    val deviceName: String?,
    val ssid: String?,
    val passphrase: String?,
    val freqMhz: Int?,
) {
    /** Можно ли войти в группу напрямую по ssid и паролю. */
    val canJoinBySsid: Boolean
        get() = ssid != null && ssid.startsWith("DIRECT-") && ssid.length >= 9 &&
            passphrase != null && passphrase.length in 8..63

    companion object {
        fun parse(body: ByteArray): P2pTarget? {
            val fields = PbReader.parse(body) ?: return null
            val net = PbReader.str(fields, 4)?.split(',')
            return P2pTarget(
                deviceName = PbReader.str(fields, 1)?.takeIf { it.isNotEmpty() },
                ssid = net?.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
                passphrase = net?.getOrNull(1)?.takeIf { it.isNotEmpty() },
                freqMhz = net?.getOrNull(2)?.trim()?.toIntOrNull()
                    ?.takeIf { it in 2400..5900 },
            )
        }
    }
}

class BtFrame(val type: Int, val body: ByteArray)

/** Сборка кадров из произвольно нарезанного RFCOMM-потока. */
class BtFrameParser {
    private var buffer = ByteArray(0)

    var skipped = 0L
        private set

    fun feed(chunk: ByteArray, n: Int = chunk.size): List<BtFrame> {
        buffer += chunk.copyOf(n)
        val out = ArrayList<BtFrame>()
        var pos = 0
        while (buffer.size - pos >= BtMsg.HDR) {
            val len = Wire.u16(buffer, pos)
            if (len > BtMsg.MAX_BODY || Wire.u16(buffer, pos + 2) != 0) {
                pos++
                skipped++
                continue
            }
            if (buffer.size - pos < BtMsg.HDR + len) break
            out += BtFrame(
                Wire.u32(buffer, pos + 4),
                buffer.copyOfRange(pos + BtMsg.HDR, pos + BtMsg.HDR + len),
            )
            pos += BtMsg.HDR + len
        }
        buffer = buffer.copyOfRange(pos, buffer.size)
        return out
    }
}
