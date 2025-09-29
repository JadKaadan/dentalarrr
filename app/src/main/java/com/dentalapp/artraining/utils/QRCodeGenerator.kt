package com.dentalapp.artraining.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.dentalapp.artraining.data.PatientSession
import com.dentalapp.artraining.data.SerializablePatientSession
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import android.util.Log
import java.util.*

object QRCodeGenerator {

    private const val TAG = "QRCodeGenerator"
    private const val QR_CODE_SIZE = 512
    private const val PREFIX = "DENTAL_SESSION:"

    fun generateQRCode(session: PatientSession, size: Int = QR_CODE_SIZE): Bitmap {
        try {
            val serializableSession = SerializablePatientSession.fromPatientSession(session)
            val json = serializableSession.toJson()
            val qrContent = PREFIX + compressData(json)

            Log.d(TAG, "Generating QR code, data size: ${qrContent.length} chars")

            return createQRCodeBitmap(qrContent, size)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate QR code", e)
            throw e
        }
    }

    private fun createQRCodeBitmap(content: String, size: Int): Bitmap {
        val hints = hashMapOf<EncodeHintType, Any>()
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
        hints[EncodeHintType.MARGIN] = 1
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"

        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }

        return bitmap
    }

    fun parseQRCode(qrContent: String): PatientSession? {
        try {
            if (!qrContent.startsWith(PREFIX)) {
                Log.e(TAG, "Invalid QR code prefix")
                return null
            }

            val data = qrContent.removePrefix(PREFIX)
            val json = decompressData(data)

            val serializableSession = SerializablePatientSession.fromJson(json)
            return serializableSession.toPatientSession()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse QR code", e)
            return null
        }
    }

    private fun compressData(json: String): String {
        return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    private fun decompressData(compressed: String): String {
        return String(Base64.getDecoder().decode(compressed), Charsets.UTF_8)
    }
}
