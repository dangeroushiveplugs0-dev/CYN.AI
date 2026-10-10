package com.cyn.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Safe first-pass attachment inspection.
 * It extracts bounded text and container metadata without loading whole files into RAM.
 * Model-backed vision, PDF, office and 3D parsing are routed as explicit future capabilities.
 */
data class FileInspection(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: String,
    val summary: String,
    val extractedText: String = ""
) {
    fun toModelContext(): String = buildString {
        appendLine("Attached file: $name")
        appendLine("Detected type: $kind ($mimeType)")
        appendLine("Size: ${if (sizeBytes >= 1024L * 1024L) String.format(Locale.US, "%.2f MiB", sizeBytes / (1024.0 * 1024.0)) else "$sizeBytes bytes"}")
        appendLine("Inspection: $summary")
        if (extractedText.isNotBlank()) {
            appendLine("Extracted content (bounded preview):")
            appendLine(extractedText)
        }
    }
}

object AttachmentInspector {
    private const val MAX_TEXT_BYTES = 48 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 80
    private const val MAX_PDF_BYTES = 24L * 1024L * 1024L
    private const val MAX_PDF_PAGES = 12

    fun inspect(context: Context, uri: Uri): FileInspection {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val kind = classify(ext, mime)

        return when {
            ext in setOf("txt", "md", "kt", "java", "kts", "gradle", "py", "js", "ts", "tsx", "jsx", "html", "css", "xml", "json", "yaml", "yml", "toml", "ini", "csv", "log", "sh", "sql", "c", "h", "cpp", "hpp", "rs", "go", "swift", "dart", "obj", "mtl", "gltf", "svg", "srt", "vtt") ->
                inspectText(resolver.openInputStream(uri), name, mime, size, kind)
            ext in setOf("zip", "jar", "apk", "docx", "xlsx", "pptx", "odt", "ods", "odp") ->
                inspectZip(resolver.openInputStream(uri), name, mime, size, kind)
            ext == "glb" -> inspectGlb(resolver.openInputStream(uri), name, mime, size)
            ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif") ->
                FileInspection(name, mime, size, "Image", "Image attachment detected. Visual understanding requires a compatible vision model/runtime; no visual description is fabricated.")
            ext in setOf("mp4", "mov", "mkv", "webm", "avi", "m4v") ->
                FileInspection(name, mime, size, "Video", "Video attachment detected. Frame sampling and video understanding require a compatible multimodal runtime.")
            ext in setOf("mp3", "wav", "m4a", "ogg", "flac", "aac", "opus") ->
                FileInspection(name, mime, size, "Audio", "Audio attachment detected. Transcription or audio understanding requires a compatible audio model/runtime.")
            ext == "pdf" ->
                inspectPdf(context, resolver.openInputStream(uri), name, mime, size)
            ext in setOf("blend", "fbx", "dae", "stl", "ply", "usd", "usdz", "3mf") ->
                FileInspection(name, mime, size, "3D asset", "3D asset detected. This pass records file metadata only; geometry/material inspection requires a format parser.")
            ext in setOf("gguf", "bin", "safetensors", "onnx", "pt", "pth", "tflite") ->
                FileInspection(name, mime, size, "Model/weights file", "Model-like binary detected. It is not executed or loaded automatically; use the model library for GGUF models.")
            else -> inspectBinary(resolver.openInputStream(uri), name, mime, size, kind)
        }
    }

    private fun classify(ext: String, mime: String): String = when {
        ext in setOf("kt", "java", "kts", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "rs", "go", "swift", "dart", "sql", "sh") -> "Source code"
        ext in setOf("json", "yaml", "yml", "toml", "ini", "xml", "csv") -> "Structured text/data"
        ext in setOf("obj", "mtl", "gltf", "svg") -> "Text-based asset"
        mime.startsWith("image/") -> "Image"
        mime.startsWith("video/") -> "Video"
        mime.startsWith("audio/") -> "Audio"
        mime.startsWith("text/") -> "Text"
        else -> "Binary or unknown format"
    }

    private fun inspectText(stream: java.io.InputStream?, name: String, mime: String, size: Long, kind: String): FileInspection {
        val bytes = stream?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var remaining = MAX_TEXT_BYTES
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count <= 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.toByteArray()
        } ?: byteArrayOf()
        val text = bytes.toString(Charsets.UTF_8).replace("\u0000", "")
        val preview = text.take(12000)
        val clipped = if (size > bytes.size && bytes.size >= MAX_TEXT_BYTES) " (preview capped at 48 KiB)" else ""
        return FileInspection(name, mime, size, kind, "Read a bounded UTF-8 preview$clipped.", preview)
    }

    private fun inspectZip(stream: java.io.InputStream?, name: String, mime: String, size: Long, kind: String): FileInspection {
        val entries = mutableListOf<String>()
        val extracted = mutableListOf<String>()
        var extractedBytes = 0
        var truncated = false
        runCatching {
            stream?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (entries.size < MAX_ARCHIVE_ENTRIES) {
                        val entry = zip.nextEntry ?: break
                        entries.add((if (entry.isDirectory) "[dir] " else "") + entry.name)
                        val officeTextEntry = !entry.isDirectory && isOfficeTextEntry(entry.name)
                        if (officeTextEntry && extractedBytes < MAX_TEXT_BYTES) {
                            val remaining = MAX_TEXT_BYTES - extractedBytes
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(2048)
                            var entryBytes = 0
                            while (entryBytes < remaining) {
                                val count = zip.read(buffer, 0, minOf(buffer.size, remaining - entryBytes))
                                if (count <= 0) break
                                output.write(buffer, 0, count)
                                entryBytes += count
                            }
                            val decoded = output.toByteArray().toString(Charsets.UTF_8)
                            val readable = decoded.replace(Regex("<[^>]*>"), " ")
                                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                                .replace("&quot;", "\"").replace("&apos;", "'")
                                .replace(Regex("\\s+"), " ").trim()
                            if (readable.isNotBlank()) {
                                extracted.add("[" + entry.name + "]\n" + readable.take(8000))
                                extractedBytes += entryBytes
                            }
                        }
                        zip.closeEntry()
                    }
                    truncated = entries.size == MAX_ARCHIVE_ENTRIES
                }
            }
        }.onFailure { entries.add("Could not read archive directory: " + it.javaClass.simpleName) }
        val listing = entries.joinToString("\n")
        val text = extracted.joinToString("\n\n")
        val summary = "Archive/container directory listing:" +
            (if (truncated) " (first " + MAX_ARCHIVE_ENTRIES + " entries only)" else "") +
            (if (text.isNotBlank()) " Office-document text was extracted within a " + MAX_TEXT_BYTES + "-byte safety limit." else "")
        return FileInspection(name, mime, size, kind, summary, if (text.isNotBlank()) text else listing)
    }

    private fun isOfficeTextEntry(path: String): Boolean {
        val normalized = path.lowercase(Locale.ROOT)
        return normalized == "word/document.xml" ||
            normalized == "word/footnotes.xml" ||
            normalized == "word/endnotes.xml" ||
            normalized == "xl/sharedstrings.xml" ||
            normalized == "xl/workbook.xml" ||
            normalized.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) ||
            normalized == "content.xml" ||
            normalized == "meta.xml"
    }

    private fun inspectPdf(context: Context, stream: java.io.InputStream?, name: String, mime: String, size: Long): FileInspection {
        if (size > MAX_PDF_BYTES) {
            stream?.close()
            return FileInspection(name, mime, size, "PDF document", "PDF is larger than the safe local extraction limit of 24 MiB. It is retained as an attachment, but this pass skips text extraction to limit memory and storage use.")
        }
        var stagingFile: java.io.File? = null
        val temp = runCatching {
            val file = java.io.File.createTempFile("cyn-pdf-", ".pdf", context.cacheDir)
            stagingFile = file
            val copied = stream?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        total += count
                        if (total > MAX_PDF_BYTES) throw IllegalArgumentException("PDF exceeds safe extraction limit")
                        output.write(buffer, 0, count)
                    }
                    total
                }
            } ?: 0L
            file to copied
        }.getOrElse {
            stream?.close()
            stagingFile?.delete()
            return FileInspection(name, mime, size, "PDF document", "Could not safely stage PDF for text extraction: ${it.javaClass.simpleName}. The original attachment remains untouched.")
        }
        return try {
            org.apache.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
            org.apache.pdfbox.pdmodel.PDDocument.load(temp.first).use { document ->
                val pages = document.numberOfPages
                if (pages == 0) {
                    FileInspection(name, mime, size, "PDF document", "PDF opened, but it contains no pages.")
                } else {
                    val stripper = org.apache.pdfbox.text.PDFTextStripper().apply {
                        startPage = 1
                        endPage = minOf(pages, MAX_PDF_PAGES)
                    }
                    val extracted = stripper.getText(document).take(12000)
                    val pageNote = "Read text from ${minOf(pages, MAX_PDF_PAGES)} of $pages page(s), capped at 12,000 characters."
                    FileInspection(
                        name, mime, if (size >= 0L) size else temp.second, "PDF document",
                        if (extracted.isBlank()) "PDF opened successfully, but no extractable text was found. It may be scanned or image-based. $pageNote" else "PDF text extracted locally. $pageNote",
                        extracted
                    )
                }
            }
        } catch (error: Exception) {
            FileInspection(name, mime, size, "PDF document", "PDF text extraction failed safely: ${error.javaClass.simpleName}. Scanned pages still require OCR.")
        } finally {
            temp.first.delete()
        }
    }

    private fun inspectGlb(stream: java.io.InputStream?, name: String, mime: String, size: Long): FileInspection {
        val header = runCatching {
            stream?.use { input ->
                val bytes = ByteArray(12)
                var offset = 0
                while (offset < bytes.size) {
                    val n = input.read(bytes, offset, bytes.size - offset)
                    if (n <= 0) break
                    offset += n
                }
                if (offset < 12) "GLB header is incomplete."
                else {
                    val magic = String(bytes, 0, 4, Charsets.US_ASCII)
                    val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val version = buffer.getInt(4)
                    val declaredLength = buffer.getInt(8).toLong() and 0xffffffffL
                    if (magic != "glTF") "Unexpected header; file may be mislabeled."
                    else "Valid glTF binary header. Version $version; declared file length $declaredLength bytes."
                }
            } ?: "Unable to open file."
        }.getOrElse { "Could not inspect GLB header: ${it.javaClass.simpleName}" }
        return FileInspection(name, mime, size, "3D asset (GLB)", "$header Geometry and materials are not parsed in this pass.")
    }

    private fun inspectBinary(stream: java.io.InputStream?, name: String, mime: String, size: Long, kind: String): FileInspection {
        val signature = runCatching {
            stream?.use { input ->
                val bytes = ByteArray(16)
                val count = input.read(bytes)
                if (count <= 0) "No readable bytes."
                else bytes.take(count).joinToString(" ") { "%02X".format(it) }
            } ?: "Unable to open file."
        }.getOrElse { "Read failed: ${it.javaClass.simpleName}" }
        return FileInspection(name, mime, size, kind, "No dedicated parser for this extension yet. First 16-byte signature (hex): $signature")
    }
}
