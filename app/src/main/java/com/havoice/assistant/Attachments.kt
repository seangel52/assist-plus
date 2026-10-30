package com.havoice.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Something sent along with a request: a photo, a scanned barcode, or a text file. */
sealed class Attachment {
    abstract val label: String

    /** A photo (or a PDF page) as JPEG, with any barcodes found in it and their products. */
    class Photo(
        val jpeg: ByteArray,
        val thumbnail: Bitmap,
        override val label: String,
        val barcodes: List<ScannedCode>,
    ) : Attachment()

    class Barcode(val code: ScannedCode) : Attachment() {
        override val label get() = code.product ?: "Barcode ${code.value}"
    }

    class TextFile(val name: String, val text: String) : Attachment() {
        override val label get() = name
    }
}

/** A barcode's digits and, when Open Food Facts knows it, a one-line product summary. */
data class ScannedCode(val value: String, val product: String?)

/** Turns picked files and photos into [Attachment]s on the phone, before anything is sent. */
object Attachments {
    private const val MAX_SIDE = 1600
    private const val THUMB_SIDE = 160
    private const val MAX_PDF_PAGES = 3
    private const val MAX_TEXT_CHARS = 8000

    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()

    /** Reads whatever [uri] points at: an image, a PDF (as page images), or a text file. */
    suspend fun fromUri(context: Context, uri: Uri): List<Attachment> = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri).orEmpty()
        val name = displayName(context, uri)
        when {
            type.startsWith("image/") -> listOf(photo(context, decodeImage(context, uri), name))
            type == "application/pdf" -> pdfPages(context, uri, name)
            type.startsWith("text/") || type == "application/json" -> listOf(textFile(context, uri, name))
            else -> throw HaException("Can't send that kind of file ($type). Photos, PDFs and text files work.")
        }
    }

    fun fromSharedText(text: String): Attachment = Attachment.TextFile("Shared text", text.take(MAX_TEXT_CHARS))

    /** Looks a barcode up in Open Food Facts, the free public food product database. */
    suspend fun scanned(value: String): ScannedCode = ScannedCode(value, lookupProduct(value))

    private suspend fun photo(context: Context, bitmap: Bitmap, label: String): Attachment.Photo {
        val scaled = scaleDown(bitmap, MAX_SIDE)
        val jpeg = ByteArrayOutputStream().use {
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)
            it.toByteArray()
        }
        val codes = try {
            BarcodeScanning.getClient().process(InputImage.fromBitmap(scaled, 0)).await()
                .mapNotNull { it.rawValue }
                .distinct()
        } catch (e: Exception) {
            AppLog.w("Barcode check of a photo failed: ${e.message}")
            emptyList()
        }
        return Attachment.Photo(jpeg, scaleDown(scaled, THUMB_SIDE), label, codes.map { scanned(it) })
    }

    private fun decodeImage(context: Context, uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the camera's rotation, so photos come out upright.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val side = maxOf(info.size.width, info.size.height)
                if (side > MAX_SIDE * 2) decoder.setTargetSampleSize(side / MAX_SIDE)
            }
        } else {
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                ?: throw HaException("Couldn't open that image")
        }

    private suspend fun pdfPages(context: Context, uri: Uri, name: String): List<Attachment> {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw HaException("Couldn't open that PDF")
        val pages = descriptor.use { fd ->
            PdfRenderer(fd).use { pdf ->
                (0 until minOf(pdf.pageCount, MAX_PDF_PAGES)).map { index ->
                    pdf.openPage(index).use { page ->
                        val width = 1400
                        val bitmap = Bitmap.createBitmap(width, width * page.height / page.width, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }
        return pages.mapIndexed { i, page ->
            photo(context, page, if (pages.size > 1) "$name, page ${i + 1}" else name)
        }
    }

    private fun textFile(context: Context, uri: Uri, name: String): Attachment.TextFile {
        val text = context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
            ?: throw HaException("Couldn't open that file")
        return Attachment.TextFile(name, text.take(MAX_TEXT_CHARS))
    }

    private fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Photo"

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val side = maxOf(bitmap.width, bitmap.height)
        if (side <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    }

    private suspend fun lookupProduct(code: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(
                    "https://world.openfoodfacts.org/api/v2/product/$code.json" +
                        "?fields=product_name,brands,serving_size,quantity,nutriments",
                )
                // Open Food Facts asks apps to identify themselves.
                .header("User-Agent", "AssistPlus/0.1 (+https://github.com/SeAngel52/assist-plus)")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val root = JSONObject(response.body?.string().orEmpty())
                if (root.optInt("status") != 1) return@withContext null
                val p = root.getJSONObject("product")
                val n = p.optJSONObject("nutriments") ?: JSONObject()
                val name = listOf(p.optString("product_name"), p.optString("brands").takeIf { it.isNotBlank() }?.let { "($it)" })
                    .filter { !it.isNullOrBlank() }.joinToString(" ")
                val facts = listOfNotNull(
                    p.optString("serving_size").takeIf { it.isNotBlank() }?.let { "serving $it" },
                    n.optDouble("energy-kcal_serving").takeIf { !it.isNaN() }?.let { "%.0f kcal".format(it) },
                    n.optDouble("proteins_serving").takeIf { !it.isNaN() }?.let { "%.0f g protein".format(it) },
                    n.optDouble("carbohydrates_serving").takeIf { !it.isNaN() }?.let { "%.0f g carbs".format(it) },
                    n.optDouble("fat_serving").takeIf { !it.isNaN() }?.let { "%.0f g fat".format(it) },
                    p.optString("quantity").takeIf { it.isNotBlank() }?.let { "package $it" },
                )
                name.takeIf { it.isNotBlank() }?.let { if (facts.isEmpty()) it else "$it — ${facts.joinToString(", ")}" }
            }
        } catch (e: Exception) {
            AppLog.w("Open Food Facts lookup of $code failed: ${e.message}")
            null
        }
    }
}
