package com.carlifemirror.app.carlife

/** Один CMD-пакет: тип и тело (null = пустое). */
class OutCmd(val type: Int, val body: ByteArray?)

/**
 * Ответ на HU_AUTHEN_REQUEST, как в Baidu CarLife 8.6.7 (PrivateKeyManager).
 *
 * Ветка `2.0;<rnd>` отвечает md5hex("CARLIFE" + brand + model + sdk + <rnd>).
 * Старая ветка подписывает randomValue закрытым ключом Baidu (RSA/ECB/PKCS1Padding,
 * ENCRYPT_MODE) и кодирует результат в Base64. Именно её присылает VW CNS3
 * (CarLife 2.1), поэтому она здесь обязательна.
 *
 * Чистая JVM-логика: java.util.Base64 и javax.crypto доступны и в unit-тестах,
 * и на Android (minSdk 29).
 */
object HuAuth {
    private const val ERROR_VERSION = "_00.00.00"
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    /** PRIVATE_KEY из PrivateKeyManager (PKCS8, Base64). */
    private const val PRIVATE_KEY =
        "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBALzoBR3Io5wxQH+IMy0ekUE50s9ZOEPOI/y3bSwXSZEqU0EgDGVRnFBYhr8MSEkv2THTn5HKsUM3gHd3rpQhRG2JrcaFY+iIEJs2iueXvfKd8UqTUFQOZrc7xCz1npg4KResY4VAEN+W19ez/DsKiCS5LaxmwE844xWibTgjspY9AgMBAAECgYA7C/cVcTKaztZPRr5gWw6iKbXYNBYfM58SDi+kuX64TleJor/dJ55JivJLY0ZxAfDM304gXw/7Z6zTKui5ypA9duYu1Pohk/e5UUMFr0+cEyWzTjCJp/XaA/gEhH8g311qeWcALSo6q55vYtTJpc/OUn98nlsrOMv4XLgaBI2fwQJBAPBw7TkR4Xuvue2tfxh2Fn2x2ONJSRjckS7EipfdLSsgZCbwdQ2XL/k6VX0lMfQaLQQ6YCF/vrALlgkXFBuzFpECQQDJIWJGcoM95ms546hHBzu7DONmEpEh9b9nMEqRmOkzkitxvGApv1VjvZaX6gMrSANADmoz4lSTGXFea4nviJLtAkEA29v3H4NZMveJxWsrV5vLjx5MG/FMdP5jh2dS7/DgN5pD2lNwRYAk7vnHaErVtccluMMEWj1siZ/ejutaiMWm8QJBAIMM+4nHZ3hXwJoRmj3dmq/AMBL8GhC0nShRMOU5awmtPh13jnjlMHAywgLt+W6kF2oPemegG1dVhqbtDw1CQekCQH3xugE28LS6e29233PUBdipwoyRcbDeth70WDS3l4bl7XlWbTXOWF/D28ZZ49NSr1dRhAQwM8VVC/gHdvNpcqA="

    val privateKey: java.security.PrivateKey by lazy {
        java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(
                java.security.spec.PKCS8EncodedKeySpec(
                    java.util.Base64.getDecoder().decode(PRIVATE_KEY)
                )
            )
    }

    /** Соль MD5_HEAD из libencryption.so Baidu 8.6.7. */
    private const val V2_SALT = "CARLIFE"

    /** String.split(regex) из Java: хвостовые пустые элементы отбрасываются. */
    private fun javaSplit(s: String, sep: String): List<String> {
        val p = s.split(sep)
        return if (p.size == 1) p else p.dropLastWhile { it.isEmpty() }
    }

    /** Ветка `2.0;<rnd>`; условие как у Baidu. */
    fun isV2Branch(random: String?): Boolean {
        if (random == null) return false
        val parts = javaSplit(random, ";")
        return parts.size >= 2 && parts[0] == "2.0" && parts[1].isNotEmpty()
    }

    /**
     * PrivateKeyManager.versionVerify при versionName = Handshake.MD_VERSION.
     * Без "_" у Baidu падает (substring(-1)); здесь строка остаётся как есть.
     * Нечисловые части пропускаются (у Baidu ловится исключение).
     */
    fun versionVerify(str: String, mdVersion: String = Handshake.MD_VERSION): String {
        val idx = str.indexOf('_')
        if (idx < 0) return str
        val suffix = str.substring(idx)
        val stripped = str.replace(suffix, "")
        val theirs = javaSplit(suffix.replace("_", ""), ".")
        val ours = javaSplit(mdVersion, ".")
        if (ours.size != theirs.size) return stripped + ERROR_VERSION
        for (i in ours.indices) {
            val a = theirs[i].toIntOrNull() ?: continue
            val b = ours[i].toIntOrNull() ?: continue
            if (a > b) return stripped + ERROR_VERSION
            if (a < b) return str
        }
        return str
    }

    /** md5(MD5_HEAD + str) в нижнем hex — как getVerifyCode в libencryption.so. */
    fun verifyCode(str: String): String {
        val d = java.security.MessageDigest.getInstance("MD5")
            .digest((V2_SALT + str).toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** brand + model + sdk из MD_INFO — общая часть ключа ветки 2.0. */
    fun mdInfoKey(info: DeviceInfo) = info.brand + info.model + info.sdk

    /**
     * encryptValue для MD_AUTHEN_RESPONSE. Ветка 2.0: verifyCode(mdInfo + часть
     * после ";"). Иначе — RSA-подпись randomValue после versionVerify.
     */
    fun encryptValue(random: String, mdInfo: String = ""): String {
        if (isV2Branch(random)) {
            val parts = javaSplit(random, ";")
            return verifyCode(mdInfo + parts[1])
        }
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, privateKey)
        val sig = cipher.doFinal(versionVerify(random).toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(sig)
    }
}

/**
 * Последовательность MD -> HU для CarLife 2.0, повторяющая поведение штатного
 * приложения Baidu CarLife 8.6.7. Чистый Kotlin, без android.* — удобно тестировать.
 */
object Handshake {
    /** MODULE_STATUS отправляется через 500 мс после ответа на версию протокола. */
    const val MODULE_STATUS_DELAY_MS = 500L

    /** Если ГУ не прислало VIDEO_ENCODER_START, начинаем сами через 1.5 с после INIT_DONE. */
    const val START_FALLBACK_MS = 1500L

    /** Версия, которую ожидает ГУ в MD_INFO. */
    const val MD_VERSION = "8.6.7"

    /** carlifeType: 1 = приложение CarLife. */
    const val MD_TYPE_CARLIFE_APP = 1

    /**
     * Рабочий режим VW CNS3 (CarLife 2.1): 2240x976, 30 FPS, 8 Мбит/с.
     * Эти же значения использует Headswap WIDE_976 и штатный CarLife.
     */
    const val WORKING_WIDTH = 2240
    const val WORKING_HEIGHT = 976
    const val WORKING_FPS = 30
    const val WORKING_BITRATE = 8_000_000

    /**
     * Стартовая пачка сразу после HU_PROTOCOL_VERSION:
     * MATCH_STATUS, FOREGROUND, SCREEN_ON (если нужно), MD_INFO.
     */
    fun startBatch(screenOn: Boolean, info: DeviceInfo): List<OutCmd> {
        val out = ArrayList<OutCmd>()
        out.add(OutCmd(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msgs.matchStatus(1)))
        out.add(OutCmd(Msg.FOREGROUND, null))
        if (screenOn) out.add(OutCmd(Msg.SCREEN_ON, null))
        out.add(OutCmd(Msg.MD_INFO, Msgs.deviceInfo(info)))
        return out
    }

    fun moduleStatus(huMajor: Int) = OutCmd(Msg.MODULE_STATUS, Msgs.moduleStatusList(huMajor))

    /** Ответ на STATISTIC_INFO: MD_AUTHEN_RESULT(true), запрос FEATURE_CONFIG, подписка CAR_DATA. */
    fun onStatisticInfo(sendFeatureConfigRequest: Boolean): List<OutCmd> {
        val out = ArrayList<OutCmd>()
        out.add(OutCmd(Msg.MD_AUTHEN_RESULT, Msgs.authenResult(true)))
        if (sendFeatureConfigRequest) out.add(OutCmd(Msg.MD_FEATURE_CONFIG_REQUEST, null))
        out.add(OutCmd(Msg.CAR_DATA_SUBSCRIBE, null))
        return out
    }

    fun frameRateChangeAllowed(fps: Int) = fps in 3..60

    /** Границы пользовательского разрешения и запасного кадра. */
    const val MIN_VIDEO_W = 320
    const val MAX_VIDEO_W = 3840
    const val MIN_VIDEO_H = 240
    const val MAX_VIDEO_H = 2160
    const val MAX_AUTO_W = 2560
    const val MAX_AUTO_H = 1440

    /** Чётные размеры в допустимых границах и в пределах H.264 level 5.1 по числу макроблоков. */
    fun isValidVideoSize(w: Int, h: Int): Boolean {
        if (w < MIN_VIDEO_W || w > MAX_VIDEO_W || h < MIN_VIDEO_H || h > MAX_VIDEO_H) return false
        if (w % 2 != 0 || h % 2 != 0) return false
        val mb = ((w + 15) / 16) * ((h + 15) / 16)
        return mb <= 36864
    }

    /**
     * Выбор размера кадра. Пользовательский размер имеет приоритет; иначе
     * уважаем запрос ГУ, вписывая его в 2K и сохраняя пропорции. Всё
     * выравнивается до чётных значений.
     */
    fun chooseVideoSize(requestedW: Int, requestedH: Int, targetW: Int, targetH: Int): Triple<Int, Int, String> {
        if (targetW > 0 && targetH > 0) {
            return if (isValidVideoSize(targetW, targetH)) {
                Triple(targetW, targetH, "из настроек")
            } else {
                Triple(1280, 720, "запасной 1280x720")
            }
        }
        val fitted = fitVideoSize(requestedW, requestedH, MAX_AUTO_W, MAX_AUTO_H)
        return fitted?.let { Triple(it.first, it.second, "по запросу ГУ") }
            ?: Triple(1280, 720, "запасной 1280x720")
    }

    /** Вписывает размер в maxW x maxH без искажения пропорций; null — запрос непригоден. */
    fun fitVideoSize(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int>? {
        if (w < MIN_VIDEO_W || h < MIN_VIDEO_H) return null
        val scale = minOf(1f, maxW.toFloat() / w, maxH.toFloat() / h)
        val outW = (w * scale).toInt() and 1.inv()
        val outH = (h * scale).toInt() and 1.inv()
        if (outW < MIN_VIDEO_W || outH < MIN_VIDEO_H) return null
        return outW to outH
    }

    /**
     * Битрейт под размер и fps, с разумными границами. Для рабочего режима
     * 2240x976 фиксируем 8 Мбит/с — ровно столько проверено на VW CNS3.
     */
    fun bitrateFor(w: Int, h: Int, fps: Int): Int {
        if (w == WORKING_WIDTH && h == WORKING_HEIGHT) return WORKING_BITRATE
        val raw = (w.toLong() * h.toLong() * fps.toLong() * 0.14).toLong()
        return raw.coerceIn(1_500_000L, 12_000_000L).toInt()
    }
}
