package com.smsping.otg

/** Kết quả giải mã báo cáo PING. */
data class KetQua(
    var er: Boolean = false,
    var mr: String = "",
    var sdtDcPing: String = "",
    var tPing: String = "",
    var tReport: String = "",
    var kq: String = "",
    var kqSms: String = ""
)

object PduCodec {

    // 84 = mã VN; hoán vị nửa-octet 9 số thuê bao (bỏ số 0 đầu)
    fun swapDigits(sdt: String): String =
        "" + sdt[2] + sdt[1] + sdt[4] + sdt[3] + sdt[6] + sdt[5] + sdt[8] + sdt[7] + "F" + sdt[9]

    /**
     * PDU "ping thầm" tới 1 số VN. smscPrefix nhét địa chỉ SMSC vào đầu PDU
     * -> modem không cần có SMSC sẵn (khỏi lỗi "SMSC address unknown").
     * "00" = dùng SMSC lưu trên SIM. AT+CMGS=19 GIỮ NGUYÊN (chỉ đếm phần TPDU).
     */
    fun buildPingPdu(sdt: String, smscPrefix: String = "00"): String {
        val p = if (smscPrefix.isEmpty()) "00" else smscPrefix
        return p + "71000B9148" + swapDigits(sdt) + "000800050401020000"
    }

    /** Mã hoá SMSC vào đầu PDU: [độ dài octet][91][số đã hoán vị]. "+84900000023" -> "07914809000020F3". */
    fun encodeSmscPrefix(intlNumber: String?): String {
        if (intlNumber.isNullOrEmpty()) return "00"
        val d = (if (intlNumber.startsWith("+")) intlNumber.substring(1) else intlNumber)
            .filter { it.isDigit() }
        if (d.isEmpty()) return "00"
        val padded = if (d.length % 2 == 0) d else d + "F"
        val sb = StringBuilder()
        var i = 0
        while (i < padded.length) { sb.append(padded[i + 1]); sb.append(padded[i]); i += 2 }
        val swapped = sb.toString()
        val octets = 1 + swapped.length / 2
        return String.format("%02X", octets) + "91" + swapped
    }

    /**
     * Giải mã STATUS-REPORT theo cấu trúc (bền hơn bảng chỉ số cứng).
     * Tự nhận PDU có/không kèm địa chỉ SMSC ở đầu (bản tin +CDS có SMSC, ví dụ "0791...").
     */
    fun decode(input: String): KetQua {
        val r = KetQua()
        val s = input.filter { it.isLetterOrDigit() }.uppercase()
        try {
            var p = 0
            val b0 = s.substring(0, 2).toInt(16)
            // Octet đầu là MTI status-report (bit0-1 == 10) thì KHÔNG có SMSC;
            // ngược lại là ĐỘ DÀI địa chỉ SMSC -> bỏ qua khối SMSC.
            if ((b0 and 0x03) != 0x02) p = 2 + b0 * 2

            val fo = s.substring(p, p + 2).toInt(16); p += 2
            if ((fo and 0x03) != 0x02) return r // không phải STATUS-REPORT

            r.mr = s.substring(p, p + 2).toInt(16).toString(); p += 2

            val raDigits = s.substring(p, p + 2).toInt(16); p += 2
            p += 2 // type-of-address (91)
            val raOct = (raDigits + 1) / 2
            val raSwapped = s.substring(p, p + raOct * 2); p += raOct * 2
            var ra = decodeSemiOctet(raSwapped)
            ra = when {
                ra.startsWith("84") -> "0" + ra.substring(2)
                !ra.startsWith("0") -> "0" + ra
                else -> ra
            }
            r.sdtDcPing = ra

            r.tPing = decodeTimestamp(s.substring(p, p + 14)); p += 14
            r.tReport = decodeTimestamp(s.substring(p, p + 14)); p += 14

            r.kq = s.substring(p, p + 2)
            r.er = true
        } catch (_: Exception) { r.er = false }

        r.kqSms = r.kq
        KQ_TABLE[r.kq]?.let { (vi, sms) -> r.kq = vi; r.kqSms = sms }
        return r
    }

    private fun decodeSemiOctet(sw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < sw.length) { sb.append(sw[i + 1]); sb.append(sw[i]); i += 2 }
        return sb.toString().replace("F", "").replace("f", "")
    }

    // 7 octet: YY MM DD HH MM SS TZ (mỗi octet đảo nửa-byte) -> "HH:MM:SS, ngay DD/MM/20YY"
    private fun decodeTimestamp(ts: String): String {
        fun sw(i: Int): String = "" + ts[i + 1] + ts[i]
        return sw(6) + ":" + sw(8) + ":" + sw(10) + ", ngay " + sw(4) + "/" + sw(2) + "/20" + sw(0)
    }

    // Mô tả mã kết quả (Vi = tiếng Việt, Sms = ngắn gọn)
    private val KQ_TABLE: Map<String, Pair<String, String>> = linkedMapOf(
        "00" to ("Số điện thoại bạn PING đang ONLINE." to "SDT PING ONLINE."),
        "01" to ("SMS đã gửi tới đích, SMSC không xác nhận việc phát." to "SMSC can not send."),
        "02" to ("SMS được thay thế bởi SMSC." to "SMS replace SMSC."),
        "03" to ("Lower End of the Reserved Values in This Sector." to "Lower End of the Reserved Values in This Sector."),
        "0F" to ("High End of the Reserved Values in This Sector." to "High End of the Reserved Values in This Sector."),
        "10" to ("Lower End of Values Specific to each SMSC." to "Lower End of Values Specific to each SMSC."),
        "1F" to ("High End of Values Specific to each SMSC in This Sector." to "High End of Values Specific to each SMSC in This Sector."),
        "20" to ("Congestion." to "Congestion."),
        "60" to ("Congestion." to "Congestion."),
        "21" to ("ĐT đích bận." to "SDT ban."),
        "61" to ("ĐT đích bận." to "SDT ban."),
        "22" to ("Không hồi đáp (máy tắt/ngoài vùng)." to "SDT Khong hoi dap."),
        "62" to ("Không hồi đáp (máy tắt/ngoài vùng)." to "SDT Khong hoi dap."),
        "23" to ("Service rejected." to "Service rejected."),
        "63" to ("Service rejected." to "Service rejected."),
        "24" to ("service not available." to "service not available."),
        "64" to ("service not available." to "service not available."),
        "25" to ("Lỗi ở ĐT đích." to "Loi o DT dich."),
        "65" to ("Lỗi ở ĐT đích." to "Loi o DT dich."),
        "26" to ("Lower End of the Reserved Values in This Sector." to "Lower End of the Reserved Values in This Sector."),
        "66" to ("Lower End of the Reserved Values in This Sector." to "Lower End of the Reserved Values in This Sector."),
        "2F" to ("High End of the Reserved Values in This Sector." to "High End of the Reserved Values in This Sector."),
        "6F" to ("High End of the Reserved Values in This Sector." to "High End of the Reserved Values in This Sector."),
        "30" to ("Lower End of Values Specific to each SMSC." to "Lower End of Values Specific to each SMSC."),
        "70" to ("Lower End of Values Specific to each SMSC." to "Lower End of Values Specific to each SMSC."),
        "3F" to ("High End of Values Specific to each SMSC in This Sector." to "High End of Values Specific to each SMSC in This Sector."),
        "7F" to ("High End of Values Specific to each SMSC in This Sector." to "High End of Values Specific to each SMSC in This Sector."),
        "40" to ("Remote procedure error." to "Remote procedure error."),
        "41" to ("Incompatible destination." to "Incompatible destination."),
        "42" to ("Connection rejected by ĐT đích." to "Connection rejected by DT dich."),
        "43" to ("Not obtainable." to "Not obtainable."),
        "44" to ("Quality of service not available." to "Quality of service not available."),
        "45" to ("Số điện thoại KHÔNG CÓ THỰC." to "SDT PING KHONG CO THUC."),
        "46" to ("Hết hạn gửi SMS. SMSC đã xóa tin." to "Het han. SMS xoa TN"),
        "47" to ("SMS Deleted by originating ĐT đích." to "SMS Deleted by originating DT dich."),
        "48" to ("SMS Deleted by SMSC Administration." to "SMS Deleted by SMSC Administration."),
        "49" to ("SMS does not exist." to "SMS does not exist."),
        "4A" to ("Lower End of the Reserved Values in This Sector." to "Lower End of the Reserved Values in This Sector."),
        "4F" to ("High End of the Reserved Values in This Sector." to "High End of the Reserved Values in This Sector."),
        "50" to ("Lower End of Values Specific to each SMSC." to "Lower End of Values Specific to each SMSC."),
        "5F" to ("High End of Values Specific to each SMSC in This Sector." to "High End of Values Specific to each SMSC in This Sector.")
    )
}
