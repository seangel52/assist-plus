package com.havoice.assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home Assistant's conversation agents only take text, so attachments are turned into text on
 * the way: photos are saved to HA's media folder and described by an AI Task, barcodes are
 * read on the phone and looked up, and text files are included as-is. The result is the
 * owner's request followed by one bracketed note per attachment.
 */
object AttachmentMessage {
    suspend fun build(
        request: String,
        attachments: List<Attachment>,
        client: HaClient,
        aiTaskEntity: String,
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val notes = attachments.mapIndexed { i, attachment ->
            when (attachment) {
                is Attachment.Photo -> photoNote(request, attachment, client, aiTaskEntity, "assist_${stamp}_${i + 1}.jpg")
                is Attachment.Barcode -> "[Scanned barcode ${codeText(attachment.code)}]"
                is Attachment.TextFile -> "[Attached file \"${attachment.name}\":\n${attachment.text}\n]"
            }
        }
        val said = request.ifBlank { "(Sent without a message.)" }
        return (listOf(said) + notes).joinToString("\n\n")
    }

    private suspend fun photoNote(
        request: String,
        photo: Attachment.Photo,
        client: HaClient,
        aiTaskEntity: String,
        fileName: String,
    ): String {
        val mediaId = client.uploadMedia(photo.jpeg, fileName, "image/jpeg")
        val description = try {
            client.describeImage(aiTaskEntity, mediaId, "image/jpeg", instructions(request))
        } catch (e: HaException) {
            AppLog.w("Photo description failed: ${e.message}")
            "(couldn't be described: ${e.message})"
        }
        val codes = if (photo.barcodes.isEmpty()) "" else
            " Barcodes read from the photo: " + photo.barcodes.joinToString("; ") { codeText(it) } + "."
        return "[Attached photo \"${photo.label}\", saved in Home Assistant media as $mediaId. What it shows: $description$codes]"
    }

    private fun codeText(code: ScannedCode) =
        code.value + (code.product?.let { " — Open Food Facts: $it" } ?: " — not found in Open Food Facts")

    private fun instructions(request: String) =
        "The user sent this image to their home voice assistant" +
            (if (request.isNotBlank()) " with the message: \"$request\"." else " without a message.") +
            " Describe the image so the assistant can act on that message. Transcribe all visible text, brand and " +
            "product names, flavors, numbers, nutrition facts, dates and prices exactly. Start with one sentence on " +
            "what the image shows overall. Plain text only, under 120 words."
}
