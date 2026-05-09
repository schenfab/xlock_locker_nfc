package com.fschenkel.xlocklocker

import android.content.ComponentName
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val tag = "MainActivity"

    companion object {
        // Salto ISO 7816 AID — confirmed from dumpsys nfc output (JustIN Mobile registers this).
        // The XLock reader sends SELECT with this AID; we must register it to intercept traffic.
        const val SALTO_AID = "A000000743CC843413925E20C59B0100"
    }

    private lateinit var repo: BadgeRepository
    private var nfcAdapter: NfcAdapter? = null
    private var cardEmulation: CardEmulation? = null
    private var scanningMode = false
    private var scanLogText = ""

    private lateinit var tvStatus: TextView
    private lateinit var tvUid: TextView
    private lateinit var tvAid: TextView
    private lateinit var tvPairs: TextView
    private lateinit var tvHceStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var layoutBadgeInfo: View
    private lateinit var btnScan: Button
    private lateinit var btnClear: Button
    private lateinit var tvVersion: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = BadgeRepository(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter != null) {
            cardEmulation = CardEmulation.getInstance(nfcAdapter!!)
        }

        tvStatus = findViewById(R.id.tvStatus)
        tvUid = findViewById(R.id.tvUid)
        tvAid = findViewById(R.id.tvAid)
        tvPairs = findViewById(R.id.tvPairs)
        tvHceStatus = findViewById(R.id.tvHceStatus)
        tvLog = findViewById(R.id.tvLog)
        layoutBadgeInfo = findViewById(R.id.layoutBadgeInfo)
        btnScan = findViewById(R.id.btnScan)
        btnClear = findViewById(R.id.btnClear)
        tvVersion = findViewById(R.id.tvVersion)
        tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        btnScan.setOnClickListener { toggleScanMode() }
        btnClear.setOnClickListener { clearBadge() }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        if (nfcAdapter == null) {
            tvStatus.text = getString(R.string.status_no_nfc)
            btnScan.isEnabled = false
            return
        }
        if (!nfcAdapter!!.isEnabled) {
            tvStatus.text = getString(R.string.status_nfc_off)
            btnScan.isEnabled = false
            return
        }
        if (scanningMode) {
            enableReaderMode()
        }
        // Claim the Salto AID dynamically so JustIN Mobile retains it when this app is closed.
        registerAids(repo.load()?.aids ?: emptyList())
        val preferred = cardEmulation?.setPreferredService(this, ComponentName(this, HceService::class.java))
        Log.d(tag, "setPreferredService = $preferred")
        refreshUi()
        refreshLog()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        cardEmulation?.unsetPreferredService(this)
        // Release the Salto AID so JustIN Mobile handles door access when we are not in foreground.
        val component = ComponentName(this, HceService::class.java)
        cardEmulation?.registerAidsForService(component, CardEmulation.CATEGORY_OTHER, listOf("F000000000"))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        @Suppress("DEPRECATION")
        val nfcTag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        if (nfcTag != null) {
            handleTag(nfcTag)
        }
    }

    private fun toggleScanMode() {
        if (!scanningMode) {
            scanningMode = true
            btnScan.text = "Stop Scanning"
            tvStatus.text = getString(R.string.status_scanning)
            enableReaderMode()
        } else {
            stopScanMode()
        }
    }

    private fun stopScanMode() {
        scanningMode = false
        btnScan.text = getString(R.string.btn_scan)
        nfcAdapter?.disableReaderMode(this)
        refreshUi()
    }

    private fun enableReaderMode() {
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        nfcAdapter?.enableReaderMode(this, ::handleTag, flags, null)
    }

    private fun handleTag(tag: Tag) {
        Log.d(this.tag, "Tag detected: ${tag.id.toHex()}, techs: ${tag.techList.toList()}")
        try {
            val data = readBadge(tag)
            repo.save(data)
            registerAids(data.aids)
            runOnUiThread {
                stopScanMode()
                refreshUi()
                scanLogText = buildLogText(data)
                refreshLog()
                Toast.makeText(this, "Badge scanned! ${data.exchanges.size} APDU pairs recorded.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(this.tag, "Badge read error", e)
            runOnUiThread {
                scanLogText = "ERROR: ${e.message}"
                refreshLog()
                Toast.makeText(this, "Read failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun readBadge(nfcTag: Tag): BadgeRepository.BadgeData {
        val uid = nfcTag.id.toHex()
        val techList = nfcTag.techList.map { it.substringAfterLast(".") }
        val exchanges = mutableListOf<Pair<String, String>>()
        val aids = mutableListOf<String>()

        val isoDep = IsoDep.get(nfcTag) ?: throw IllegalStateException("Tag does not support IsoDep")
        isoDep.connect()
        isoDep.timeout = 3000

        fun transceive(cmd: ByteArray): ByteArray {
            val resp = isoDep.transceive(cmd)
            exchanges.add(cmd.toHex() to resp.toHex())
            Log.d(tag, "CMD: ${cmd.toHex()} → ${resp.toHex()}")
            return resp
        }

        try {
            // ISO 7816: SELECT by name with no AID (returns ATR or default app)
            transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x00))

            // SELECT the Salto AID — this is what the XLock reader actually sends
            // (confirmed via dumpsys nfc: JustIN Mobile registers A000000743CC843413925E20C59B0100)
            val saltoAid = "A000000743CC843413925E20C59B0100".hexToBytes()
            val saltoSelect = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, saltoAid.size.toByte()) +
                    saltoAid + byteArrayOf(0x00)
            val saltoResp = transceive(saltoSelect)
            // If the Salto app is present, keep issuing commands until the exchange is complete
            if (saltoResp.size >= 2) {
                val sw = (saltoResp[saltoResp.size - 2].toInt() and 0xFF shl 8) or
                        (saltoResp[saltoResp.size - 1].toInt() and 0xFF)
                Log.d(tag, "Salto SELECT response SW: %04X".format(sw))
            }

            // DeSFire: GET_VERSION (0x60) — may require up to 3 calls for 0xAF continuation
            var resp = transceive(byteArrayOf(0x90.toByte(), 0x60, 0x00, 0x00, 0x00))
            repeat(2) {
                if (resp.size >= 2 && resp.last() == 0xAF.toByte()) {
                    resp = transceive(byteArrayOf(0x90.toByte(), 0xAF.toByte(), 0x00, 0x00, 0x00))
                }
            }

            // DeSFire: GET_APPLICATION_IDS (0x6A)
            val appIdsResp = transceive(byteArrayOf(0x90.toByte(), 0x6A.toByte(), 0x00, 0x00, 0x00))

            // Response: list of 3-byte AIDs followed by status 91 00
            if (appIdsResp.size >= 2) {
                val payload = appIdsResp.dropLast(2).toByteArray()
                var i = 0
                while (i + 2 < payload.size) {
                    val appId = payload.slice(i..i + 2).toByteArray()
                    val appIdHex = appId.toHex()
                    aids.add(appIdHex)
                    i += 3

                    // SELECT_APPLICATION (0x5A) for this 3-byte AID
                    val selectApp = byteArrayOf(0x90.toByte(), 0x5A.toByte(), 0x00, 0x00, 0x03) + appId + byteArrayOf(0x00)
                    val selectResp = transceive(selectApp)

                    if (selectResp.size >= 2 && selectResp.last() == 0x00.toByte()) {
                        readAppFiles(::transceive)
                    }
                }
            }

            // Re-select master application (AID = 000000) to reset state
            transceive(byteArrayOf(0x90.toByte(), 0x5A.toByte(), 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00))

        } finally {
            runCatching { isoDep.close() }
        }

        return BadgeRepository.BadgeData(uid, techList, aids, exchanges)
    }

    private fun readAppFiles(transceive: (ByteArray) -> ByteArray) {
        // GET_FILE_IDS (0x6F)
        val fileIdsResp = transceive(byteArrayOf(0x90.toByte(), 0x6F.toByte(), 0x00, 0x00, 0x00))
        if (fileIdsResp.size < 3) return
        val fileIds = fileIdsResp.dropLast(2).toByteArray()

        for (fileId in fileIds) {
            // GET_FILE_SETTINGS (0xF5)
            transceive(byteArrayOf(0x90.toByte(), 0xF5.toByte(), 0x00, 0x00, 0x01, fileId, 0x00))

            // READ_DATA (0xBD): offset=0, length=0 (read all)
            val readData = byteArrayOf(
                0x90.toByte(), 0xBD.toByte(), 0x00, 0x00, 0x07,
                fileId,
                0x00, 0x00, 0x00,  // offset (little-endian)
                0x00, 0x00, 0x00,  // length 0 = read all
                0x00
            )
            var resp = transceive(readData)
            // Collect continuation frames (0xAF)
            repeat(10) {
                if (resp.size >= 2 && resp.last() == 0xAF.toByte()) {
                    resp = transceive(byteArrayOf(0x90.toByte(), 0xAF.toByte(), 0x00, 0x00, 0x00))
                }
            }

            // READ_RECORDS (0xBB) for record files
            val readRecords = byteArrayOf(
                0x90.toByte(), 0xBB.toByte(), 0x00, 0x00, 0x07,
                fileId,
                0x00, 0x00, 0x00,  // record offset
                0x00, 0x00, 0x00,  // record count 0 = read all
                0x00
            )
            resp = transceive(readRecords)
            repeat(10) {
                if (resp.size >= 2 && resp.last() == 0xAF.toByte()) {
                    resp = transceive(byteArrayOf(0x90.toByte(), 0xAF.toByte(), 0x00, 0x00, 0x00))
                }
            }
        }
    }

    private fun registerAids(aids: List<String>) {
        val ce = cardEmulation ?: return
        val component = ComponentName(this, HceService::class.java)
        // Always include the Salto AID (confirmed from dumpsys) plus any discovered DeSFire AIDs
        val isoAids = mutableListOf(SALTO_AID)
        aids.forEach { a ->
            val padded = if (a.length >= 10) a else a.padEnd(10, '0')
            if (padded !in isoAids) isoAids.add(padded)
        }
        val registered = ce.registerAidsForService(component, CardEmulation.CATEGORY_OTHER, isoAids)
        Log.d(tag, "registerAidsForService($isoAids) = $registered")
    }

    private fun clearBadge() {
        repo.clear()
        repo.clearHceLog()
        val ce = cardEmulation
        if (ce != null) {
            val component = ComponentName(this, HceService::class.java)
            ce.registerAidsForService(component, CardEmulation.CATEGORY_OTHER, listOf("F000000000"))
        }
        scanLogText = ""
        tvLog.text = ""
        refreshUi()
    }

    private fun refreshUi() {
        if (nfcAdapter == null) return

        val data = repo.load()
        if (data != null) {
            tvStatus.text = "Badge ready."
            layoutBadgeInfo.visibility = View.VISIBLE
            tvUid.text = data.uid.chunked(2).joinToString(":")
            tvAid.text = data.aids.joinToString(", ").ifEmpty { "(none discovered)" }
            tvPairs.text = data.exchanges.size.toString()
            tvHceStatus.text = getString(R.string.hce_active)
            tvHceStatus.setTextColor(getColor(R.color.green_700))
        } else {
            if (!scanningMode) tvStatus.text = getString(R.string.status_no_badge)
            layoutBadgeInfo.visibility = View.GONE
            tvHceStatus.text = getString(R.string.hce_inactive)
            tvHceStatus.setTextColor(getColor(R.color.red_700))
        }
    }

    private fun buildLogText(data: BadgeRepository.BadgeData): String {
        val sb = StringBuilder()
        sb.appendLine("=== Badge scan ===")
        sb.appendLine("UID: ${data.uid.chunked(2).joinToString(":")}")
        sb.appendLine("Tech: ${data.techList.joinToString(", ")}")
        sb.appendLine("DeSFire AIDs: ${data.aids.joinToString(", ").ifEmpty { "(none)" }}")
        sb.appendLine("--- APDU exchanges ---")
        data.exchanges.forEach { (cmd, resp) ->
            sb.appendLine("→ $cmd")
            sb.appendLine("← $resp")
        }
        return sb.toString()
    }

    private fun refreshLog() {
        val sb = StringBuilder()
        val hceLog = repo.getHceLog()
        sb.appendLine("=== HCE activity (lock → phone) ===")
        if (hceLog.isEmpty()) {
            sb.appendLine("(none yet — open app, then tap phone to lock)")
        } else {
            sb.append(hceLog)
        }
        if (scanLogText.isNotEmpty()) {
            sb.appendLine()
            sb.append(scanLogText)
        }
        tvLog.text = sb.toString().trim()
    }
}
