package com.smsping.otg

import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var usb: UsbAtManager

    private lateinit var spDevices: Spinner
    private lateinit var btnScan: Button
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var lbStatus: TextView
    private lateinit var rbPing: RadioButton
    private lateinit var rbAt: RadioButton
    private lateinit var layoutPing: LinearLayout
    private lateinit var layoutAt: LinearLayout
    private lateinit var etTarget: EditText
    private lateinit var btnPing: Button
    private lateinit var etAt: EditText
    private lateinit var cbCr: CheckBox
    private lateinit var btnSendAt: Button
    private lateinit var btnCtrlZ: Button
    private lateinit var btnAccount: Button
    private lateinit var btnSignal: Button
    private lateinit var btnClr: Button
    private lateinit var tvRaw: TextView
    private lateinit var tvDecode: TextView
    private lateinit var tvLatest: TextView

    private var portEntries: List<Pair<UsbDevice, Int>> = emptyList()

    // ---- Bảng SMSC theo nhà mạng (MCC 452): 01 Mobi/02 Vina/04 Viettel/05 Vietnamobile/07 Gmobile ----
    private val smscByNetwork = mapOf(
        "01" to "+84900000023", "02" to "+8491020005", "04" to "+84980200030",
        "05" to "+84925252525", "07" to "+84995252525"
    )

    private var smscPduPrefix = "00"

    // Buffer nhận liên tục (dùng cho query + tự giải mã +CDS)
    private val rxLock = Any()
    private val rxBuf = StringBuilder()
    private val decodedPdus = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spDevices = findViewById(R.id.spDevices)
        btnScan = findViewById(R.id.btnScan)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        lbStatus = findViewById(R.id.lbStatus)
        rbPing = findViewById(R.id.rbPing)
        rbAt = findViewById(R.id.rbAt)
        layoutPing = findViewById(R.id.layoutPing)
        layoutAt = findViewById(R.id.layoutAt)
        etTarget = findViewById(R.id.etTarget)
        btnPing = findViewById(R.id.btnPing)
        etAt = findViewById(R.id.etAt)
        cbCr = findViewById(R.id.cbCr)
        btnSendAt = findViewById(R.id.btnSendAt)
        btnCtrlZ = findViewById(R.id.btnCtrlZ)
        btnAccount = findViewById(R.id.btnAccount)
        btnSignal = findViewById(R.id.btnSignal)
        btnClr = findViewById(R.id.btnClr)
        tvRaw = findViewById(R.id.tvRaw)
        tvDecode = findViewById(R.id.tvDecode)
        // Tạo dòng thông báo kết quả bằng code (không cần sửa layout) — chèn ngay trên khung KẾT QUẢ
        tvLatest = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(20, 24, 20, 24)
            setBackgroundColor(Color.rgb(238, 238, 238))
            setTextColor(Color.rgb(85, 85, 85))
            text = "Chưa có kết quả PING"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        (tvDecode.parent as? LinearLayout)?.let { p -> p.addView(tvLatest, p.indexOfChild(tvDecode)) }
        tvRaw.movementMethod = ScrollingMovementMethod()
        tvDecode.movementMethod = ScrollingMovementMethod()

        usb = UsbAtManager(this, ::onData, ::onStatus)
        usb.register()

        btnScan.setOnClickListener { scanDevices() }
        btnConnect.setOnClickListener { onConnectClick() }
        btnDisconnect.setOnClickListener { usb.disconnect() }
        btnPing.setOnClickListener { onPing() }
        btnSendAt.setOnClickListener {
            if (!usb.isOpen) { toast("Kết nối trước đã"); return@setOnClickListener }
            usb.write(if (cbCr.isChecked) etAt.text.toString() + "\r" else etAt.text.toString())
        }
        btnCtrlZ.setOnClickListener { if (usb.isOpen) usb.write("\u001a") }
        btnAccount.setOnClickListener { onAccount() }
        btnSignal.setOnClickListener {
            if (usb.isOpen) lifecycleScope.launch(Dispatchers.IO) {
                query("AT\r\n", 400, "OK"); query("AT+CSQ\r\n", 800, "\\+CSQ")
            }
        }
        btnClr.setOnClickListener {
            tvRaw.text = ""; tvDecode.text = ""
            tvLatest.text = "Chưa có kết quả PING"; tvLatest.setTextColor(Color.rgb(85, 85, 85))
            synchronized(rxLock) { rxBuf.setLength(0) }; decodedPdus.clear()
        }

        rbPing.setOnCheckedChangeListener { _, checked -> if (checked) showPing(true) }
        rbAt.setOnCheckedChangeListener { _, checked -> if (checked) showPing(!checked) }
        rbPing.isChecked = true
        cbCr.isChecked = true
        setUiConnected(false)
        scanDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { usb.unregister() } catch (_: Throwable) {}
    }

    private fun showPing(ping: Boolean) {
        layoutPing.visibility = if (ping) LinearLayout.VISIBLE else LinearLayout.GONE
        layoutAt.visibility = if (ping) LinearLayout.GONE else LinearLayout.VISIBLE
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun appendRaw(t: String) { tvRaw.append(t) }

    // ---------- Nhận dữ liệu từ modem ----------
    private fun onData(text: String) {
        synchronized(rxLock) {
            rxBuf.append(text)
            if (rxBuf.length > 16000) rxBuf.delete(0, rxBuf.length - 16000)
        }
        runOnUiThread { appendRaw(text); autoDecodeCds() }
    }

    private fun onStatus(connected: Boolean) = runOnUiThread { setUiConnected(connected) }

    private fun setUiConnected(connected: Boolean) {
        btnConnect.isEnabled = !connected
        btnDisconnect.isEnabled = connected
        lbStatus.text = if (connected) "Đã kết nối" else "Chưa kết nối"
        lbStatus.setTextColor(if (connected) Color.rgb(0, 150, 0) else Color.RED)
    }

    private fun autoDecodeCds() {
        val snapshot = synchronized(rxLock) { rxBuf.toString() }
        val re = Regex("\\+CDS:\\s*\\d+\\s*([0-9A-Fa-f]{40,})")
        for (m in re.findAll(snapshot)) {
            val pdu = m.groupValues[1]
            if (!decodedPdus.add(pdu)) continue
            val k = PduCodec.decode(pdu)
            if (!k.er) continue

            val online = k.kqSms == "SDT PING ONLINE."
            // Dòng thông báo lớn: kết quả vừa có (khỏi cuộn)
            tvLatest.text = if (online) "📶 ${k.sdtDcPing} : ONLINE" else "⛔ ${k.sdtDcPing} : OFFLINE"
            tvLatest.setTextColor(if (online) Color.rgb(0, 150, 0) else Color.RED)

            // Lịch sử: mới nhất lên ĐẦU
            val line = "${k.sdtDcPing} : ${if (online) "ONLINE" else "OFFLINE"}" +
                "  (${k.kq})\n   PING ${k.mr} | nhận ${k.tPing} | phát ${k.tReport}\n\n"
            tvDecode.text = line + tvDecode.text
            tvDecode.scrollTo(0, 0)
        }
    }

    // ---------- Quét & kết nối ----------
    private fun scanDevices() {
        val entries = ArrayList<Pair<UsbDevice, Int>>()
        val names = ArrayList<String>()
        // Ưu tiên modem đúng VID; nếu không thấy (dongle VID lạ) thì liệt kê hết USB cho chọn tay
        val matched = usb.findMatchingDevices()
        val devices = if (matched.isNotEmpty()) matched else usb.allDevices()
        for (device in devices) {
            for (i in 0 until device.interfaceCount) {
                entries.add(device to i)
                names.add("${device.deviceName} - Interface $i/${device.interfaceCount} (VID ${device.vendorId})")
            }
        }
        portEntries = entries
        val display = if (names.isEmpty()) listOf("Không tìm thấy USB — cắm dongle SIM7600CE qua OTG rồi Quét") else names
        spDevices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, display)
    }

    private fun onConnectClick() {
        val entry = portEntries.getOrNull(spDevices.selectedItemPosition)
        if (entry == null) { toast("Không tìm thấy modem. Cắm SIM7600 qua OTG rồi bấm Quét"); return }
        lbStatus.text = "Đang kết nối interface ${entry.second}..."
        lbStatus.setTextColor(Color.rgb(220, 140, 0))
        usb.connect(entry.first, entry.second) { ok, msg ->
            runOnUiThread {
                if (ok) lifecycleScope.launch(Dispatchers.IO) { afterConnected() }
                else { toast(msg); setUiConnected(false) }
            }
        }
    }

    // ---------- Sau khi mở cổng: đánh thức modem, IMEI, tự gắn SMSC ----------
    private suspend fun afterConnected() {
        var buf = query("AT\r\n", 1500, "OK")
        var i = 0
        while (i < 10 && !buf.contains("OK")) { buf = query("AT\r\n", 1500, "OK"); i++ }

        // Đọc IMEI chỉ để tham khảo — KHÔNG chặn thiết bị nữa (chạy được mọi modem/dongle).
        query("AT+CGSN\r\n", 1500, "\\d{15}")

        // Nhận diện hãng modem để gửi đúng lệnh đặt chế độ mạng
        val vendor = query("AT+CGMI\r\n", 800, "OK").uppercase()
        when {
            vendor.contains("SIMCOM") || vendor.contains("SIMTECH") ->
                query("AT+CNMP=2\r\n", 1000, "OK")                       // SIMCom (SIM7600...): tự động
            vendor.contains("QUECTEL") ->
                query("AT+QCFG=\"nwscanmode\",0,1\r\n", 1000, "OK")      // Quectel: tự động
            // hãng khác: để chế độ mặc định của modem
        }

        // Dọn sạch bộ nhớ SMS (tránh Memory full)
        for (mem in listOf("SM", "ME", "SR")) {
            query("AT+CPMS=\"$mem\",\"$mem\",\"$mem\"\r\n", 600, "OK")
            query("AT+CMGD=1,4\r\n", 1200, "OK")
        }
        query("AT+CPMS=\"ME\",\"ME\",\"ME\"\r\n", 600, "OK")

        // Nhận diện nhà mạng theo IMSI -> nhét SMSC vào PDU
        var smsc = detectSmsc(query("AT+CIMI\r\n", 2500, "\\d{15}"))
        if (smsc == null) smsc = detectSmsc(query("AT+CPSI?\r\n", 2500, "452"))
        if (smsc == null) smsc = detectSmsc(query("AT+COPS?\r\n", 2500, "452"))

        if (smsc != null) {
            smscPduPrefix = PduCodec.encodeSmscPrefix(smsc)
            query("AT+CSCA=\"$smsc\",145\r\n", 800, "OK")
        } else {
            smscPduPrefix = "00"
            runOnUiThread { toast("Chưa dò được nhà mạng của SIM — thử Connect lại") }
        }
        query("AT+CNMI=1,0,0,1,0\r\n", 500, "OK")
        query("AT+CLIP=1\r\n", 400, "OK")
        runOnUiThread { toast("Đã sẵn sàng PING") }
    }

    private fun detectSmsc(data: String?): String? {
        val d = data ?: return null
        val mnc = Regex("452[\\s\\-]*(0[1-9])").find(d)?.groupValues?.get(1) ?: return null
        return smscByNetwork[mnc]
    }

    // ---------- PING ----------
    private fun validPhone(sdt: String) = sdt.length >= 10 && sdt.all { it.isDigit() } && sdt[0] == '0'

    private fun onPing() {
        if (!usb.isOpen) { toast("Kết nối modem trước khi PING"); return }
        val sdt = etTarget.text.toString().trim()
        if (!validPhone(sdt)) { toast("SĐT phải 10 số và bắt đầu bằng 0"); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val pdu = PduCodec.buildPingPdu(sdt, smscPduPrefix)
            query("AT+CMGF=0\r\n", 600, "OK")
            query("AT+CMGS=19\r\n", 1000, ">")
            usb.write(pdu)
            delay(300)
            usb.write("\u001a")
        }
    }

    // ---------- Kiểm tra tài khoản (tự chọn theo nhà mạng) ----------
    private fun onAccount() {
        if (!usb.isOpen) { toast("Kết nối modem trước"); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val imsi = query("AT+CIMI\r\n", 2500, "\\d{15}")
            val mnc = Regex("452(0[1-9])").find(imsi)?.groupValues?.get(1) ?: ""

            query("AT+CREG=1\r\n", 500, "OK")
            for (i in 0 until 12)
                if (Regex("\\+CREG:\\s*\\d,\\s*[15]").containsMatchIn(query("AT+CREG?\r\n", 500, "\\+CREG:"))) break

            // USSD *101# (dùng chung mọi nhà mạng) + giải phóng phiên cũ + thử lại
            query("AT+CMGF=1\r\n", 500, "OK")
            query("AT+CSCS=\"GSM\"\r\n", 500, "OK")
            query("AT+CUSD=2\r\n", 800, null)
            var r = ""
            for (a in 0 until 3) {
                r = query("AT+CUSD=1,\"*101#\"\r\n", 12000, "\\+CUSD:|ERROR")
                if (Regex("\\+CUSD:").containsMatchIn(r)) break
                query("AT+CUSD=2\r\n", 800, null); delay(2000)
            }
            if (Regex("\\+CUSD:").containsMatchIn(r)) { query("AT+CMGF=0\r\n", 400, "OK"); return@launch }

            // USSD hỏng + là Viettel -> Viettel ngừng USSD từ 13/05/2026: nhắn "TK" gửi 191
            if (mnc == "04") {
                query("AT+CNMI=2,2,0,0,0\r\n", 500, "OK")
                query("AT+CSCS=\"GSM\"\r\n", 500, "OK")
                usb.write("AT+CMGS=\"191\"\r\n"); delay(600)
                usb.write("TK"); delay(200); usb.write("\u001a")
                val rr = query("", 12000, "\\+CMT:")
                query("AT+CNMI=1,0,0,1,0\r\n", 400, "OK")
                query("AT+CMGF=0\r\n", 400, "OK")
                if (!Regex("\\+CMT:").containsMatchIn(rr))
                    runOnUiThread { toast("Viettel đã ngừng *101#. Đã nhắn TK→191, xem trả lời ở khung RAW.") }
                return@launch
            }

            query("AT+CMGF=0\r\n", 400, "OK")
            runOnUiThread { toast("Chưa lấy được TK (mạng bận/retry). Đợi ~10s rồi bấm lại.") }
        }
    }

    // ---------- Gửi 1 lệnh, chờ khớp mustMatch hoặc hết timeout ----------
    private suspend fun query(cmd: String, timeoutMs: Long, mustMatch: String?): String {
        val start = synchronized(rxLock) { rxBuf.length }
        if (cmd.isNotEmpty()) usb.write(cmd)
        val re = mustMatch?.let { Regex(it) }
        var waited = 0L
        while (waited < timeoutMs) {
            delay(80); waited += 80
            val cur = synchronized(rxLock) { if (start <= rxBuf.length) rxBuf.substring(start) else rxBuf.toString() }
            if (re != null && re.containsMatchIn(cur)) return cur
        }
        return synchronized(rxLock) { if (start <= rxBuf.length) rxBuf.substring(start) else rxBuf.toString() }
    }
}
