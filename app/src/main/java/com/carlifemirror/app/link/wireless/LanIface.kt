package com.carlifemirror.app.link.wireless

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * IPv4-интерфейс локальной сети, по которому идёт маяк беспроводного CarLife.
 * Для телефона это чаще всего точка доступа (swlan/ap), но подходит и
 * домашняя/автомобильная Wi-Fi-сеть, к которой подключён телефон.
 */
class LanIface(
    val name: String,
    val ip: Inet4Address,
    val prefix: Int,
    val broadcast: Inet4Address,
) {
    fun contains(a: InetAddress): Boolean {
        if (a !is Inet4Address) return false
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        return (toInt(a) and mask) == (toInt(ip) and mask)
    }

    /** Адреса узлов подсети: только /24 и уже, иначе рассылка слишком большая. */
    fun hosts(): List<Inet4Address> {
        if (prefix < 24 || prefix > 30) return emptyList()
        val mask = -1 shl (32 - prefix)
        val net = toInt(ip) and mask
        val count = 1 shl (32 - prefix)
        val me = toInt(ip)
        return (1 until count - 1)
            .map { net + it }
            .filter { it != me }
            .map { fromInt(it) }
    }

    override fun toString() = "$name ${ip.hostAddress}/$prefix"

    companion object {
        fun toInt(a: Inet4Address): Int =
            a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

        fun fromInt(v: Int): Inet4Address = InetAddress.getByAddress(
            byteArrayOf(
                (v ushr 24).toByte(),
                (v ushr 16).toByte(),
                (v ushr 8).toByte(),
                v.toByte(),
            )
        ) as Inet4Address

        /** Мобильные данные, loopback, туннели и P2P в маяк ветки B не входят. */
        fun eligibleName(name: String): Boolean =
            excludedPrefixes.none { name.startsWith(it) }

        /** В хотспотную сеть шлём ещё и unicast: часть ГУ не ловит broadcast. */
        fun sweepName(name: String): Boolean = name != "wlan0"

        private val excludedPrefixes = listOf(
            "rmnet", "ccmni", "lo", "dummy", "tun", "ppp", "p2p",
            "ip6tnl", "sit", "v4-", "clat",
        )

        fun scan(): List<LanIface> {
            val out = ArrayList<LanIface>()
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                try {
                    if (!ni.isUp || ni.isLoopback || !eligibleName(ni.name)) continue
                } catch (_: SocketException) {
                    continue
                }
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    val b = ia.broadcast as? Inet4Address
                        ?: broadcastOf(a, ia.networkPrefixLength.toInt())
                    out += LanIface(ni.name, a, ia.networkPrefixLength.toInt(), b)
                }
            }
            return out
        }

        private fun broadcastOf(ip: Inet4Address, prefix: Int): Inet4Address {
            val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
            return fromInt((toInt(ip) and mask) or mask.inv())
        }
    }
}
