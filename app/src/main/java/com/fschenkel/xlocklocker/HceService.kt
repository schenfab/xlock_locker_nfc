package com.fschenkel.xlocklocker

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

class HceService : HostApduService() {

    private val repo by lazy { BadgeRepository(this) }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        val hex = commandApdu.toHex()
        Log.d(TAG, "APDU received: $hex")

        val response = repo.lookupResponse(hex)
        return if (response != null) {
            val respHex = response.toHex()
            Log.d(TAG, "APDU reply:    $respHex")
            repo.appendHceLog("→ $hex\n  ← $respHex")
            response
        } else {
            Log.w(TAG, "APDU unknown:  $hex — returning 6F00")
            repo.appendHceLog("→ $hex\n  ← 6F00 (no match in transcript)")
            APDU_UNKNOWN
        }
    }

    override fun onDeactivated(reason: Int) {
        val reasonStr = when (reason) {
            DEACTIVATION_LINK_LOSS -> "link loss"
            DEACTIVATION_DESELECTED -> "deselected"
            else -> "unknown ($reason)"
        }
        Log.d(TAG, "HCE deactivated: $reasonStr")
        repo.appendHceLog("--- session end: $reasonStr ---")
    }

    companion object {
        private const val TAG = "HceService"

        // SW 6F 00 = No precise diagnosis (catch-all error response)
        private val APDU_UNKNOWN = byteArrayOf(0x6F.toByte(), 0x00.toByte())
    }
}
