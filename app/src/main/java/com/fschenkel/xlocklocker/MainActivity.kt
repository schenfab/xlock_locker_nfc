package com.fschenkel.xlocklocker

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val tag = "MainActivity"

    private lateinit var repo: BadgeRepository
    private var nfcAdapter: NfcAdapter? = null
    private var cardEmulation: CardEmulation? = null
    private var scanningMode = false

    private lateinit var tvStatus: TextView
    private lateinit var tvUid: TextView
    private lateinit var tvAid: TextView
    private lateinit var tvPairs: TextView
    private lateinit var tvHceStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var layoutBadgeInfo: View
    private lateinit var btnScan: Button
    private lateinit var btnClear: Button

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
        // Make this the preferred HCE service while in foreground so we receive
        // all ISO-DEP APDUs regardless of AID routing.
        cardEmulation?.setPreferredService(this, ComponentName(this, HceService::class.java))
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        cardEmulation?.unsetPreferredService(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val nfcTag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
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
                appendLog(buildLogText(data))
                Toast.makeText(this, "Badge scanned! ${data.exchanges.size} APDU pairs recorded.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(this.tag, "Badge read error", e)
            runOnUiThread {
                appendLog("ERROR: ${e.message}")
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
        if (aids.isEmpty()) return
        val ce = cardEmulation ?: return
        val component = ComponentName(this, HceService::class.java)
        // Convert 3-byte DeSFire AIDs to valid 5-byte ISO 7816 AIDs by padding
        val isoAids = aids.map { desfire3byte ->
            if (desfire3byte.length >= 10) desfire3byte else desfire3byte.padEnd(10, '0')
        }
        val registered = ce.registerAidsForService(component, CardEmulation.CATEGORY_OTHER, isoAids)
        Log.d(tag, "registerAidsForService($isoAids) = $registered")
    }

    private fun clearBadge() {
        repo.clear()
        val ce = cardEmulation
        if (ce != null) {
            val component = ComponentName(this, HceService::class.java)
            ce.registerAidsForService(component, CardEmulation.CATEGORY_OTHER, listOf("F000000000"))
        }
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

    private fun appendLog(text: String) {
        tvLog.text = "${tvLog.text}\n$text".trim()
    }
}
