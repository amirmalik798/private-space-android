package com.privatespace.android.data

import java.util.Locale

/**
 * MIME hints for common photo, camera RAW and document formats.
 * These mappings select external Android viewers only. Vault storage and
 * AES-GCM encryption/decryption always preserve the source bytes unchanged.
 */
object VaultFormatSupport {
    private val mimeByExtension = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "jfif" to "image/jpeg",
        "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif",
        "bmp" to "image/bmp", "dib" to "image/bmp", "tif" to "image/tiff", "tiff" to "image/tiff",
        "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif",
        "svg" to "image/svg+xml", "ico" to "image/x-icon", "icns" to "image/icns",
        "jp2" to "image/jp2", "j2k" to "image/jp2", "jpf" to "image/jp2", "jpx" to "image/jp2",
        "psd" to "image/vnd.adobe.photoshop", "ai" to "application/postscript", "eps" to "application/postscript",

        // Common video containers. Codec playback still depends on the Android device/viewer.
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mov" to "video/quicktime",
        "3gp" to "video/3gpp", "3gpp" to "video/3gpp", "3g2" to "video/3gpp2",
        "mkv" to "video/x-matroska", "webm" to "video/webm",
        "avi" to "video/x-msvideo", "mpg" to "video/mpeg", "mpeg" to "video/mpeg",
        "mpe" to "video/mpeg", "m2v" to "video/mpeg", "vob" to "video/mpeg",
        "ts" to "video/mp2t", "mts" to "video/mp2t", "m2ts" to "video/mp2t",
        "ogv" to "video/ogg",
        "wmv" to "video/x-ms-wmv", "asf" to "video/x-ms-asf",
        "flv" to "video/x-flv", "f4v" to "video/mp4",
        "dv" to "video/x-dv", "mxf" to "application/mxf",

        // Popular camera RAW formats; opening them depends on an installed viewer.
        "dng" to "image/x-adobe-dng", "cr2" to "image/x-canon-cr2", "cr3" to "image/x-canon-cr3",
        "nef" to "image/x-nikon-nef", "nrw" to "image/x-nikon-nrw", "arw" to "image/x-sony-arw",
        "srf" to "image/x-sony-srf", "sr2" to "image/x-sony-sr2", "raf" to "image/x-fuji-raf",
        "rw2" to "image/x-panasonic-rw2", "orf" to "image/x-olympus-orf", "pef" to "image/x-pentax-pef",

        "pdf" to "application/pdf", "txt" to "text/plain", "text" to "text/plain", "log" to "text/plain",
        "md" to "text/markdown", "markdown" to "text/markdown", "rtf" to "application/rtf",
        "csv" to "text/csv", "tsv" to "text/tab-separated-values", "tab" to "text/tab-separated-values",
        "html" to "text/html", "htm" to "text/html", "xhtml" to "application/xhtml+xml",
        "xml" to "application/xml", "json" to "application/json", "yaml" to "application/yaml",
        "yml" to "application/yaml", "tex" to "application/x-tex",
        "xps" to "application/vnd.ms-xpsdocument", "oxps" to "application/oxps", "epub" to "application/epub+zip",

        // Microsoft Word and templates.
        "doc" to "application/msword", "dot" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "dotx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "docm" to "application/vnd.ms-word.document.macroEnabled.12",
        "dotm" to "application/vnd.ms-word.template.macroEnabled.12",

        // Microsoft Excel and templates.
        "xls" to "application/vnd.ms-excel", "xlt" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xltx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        "xlsm" to "application/vnd.ms-excel.sheet.macroEnabled.12",
        "xlsb" to "application/vnd.ms-excel.sheet.binary.macroEnabled.12",

        // Microsoft PowerPoint and templates.
        "ppt" to "application/vnd.ms-powerpoint", "pps" to "application/vnd.ms-powerpoint",
        "pot" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "ppsx" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "potx" to "application/vnd.openxmlformats-officedocument.presentationml.template",
        "pptm" to "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
        "ppsm" to "application/vnd.ms-powerpoint.slideshow.macroEnabled.12",

        // OpenDocument formats.
        "odt" to "application/vnd.oasis.opendocument.text",
        "ott" to "application/vnd.oasis.opendocument.text-template",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "ots" to "application/vnd.oasis.opendocument.spreadsheet-template",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "otp" to "application/vnd.oasis.opendocument.presentation-template",
        "odg" to "application/vnd.oasis.opendocument.graphics",
        "odf" to "application/vnd.oasis.opendocument.formula",

        // Apple iWork formats; external viewer availability varies.
        "pages" to "application/x-iwork-pages-sffpages",
        "numbers" to "application/x-iwork-numbers-sffnumbers",
        "key" to "application/x-iwork-keynote-sffkey"
    )

    fun mimeTypeForName(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() }
            ?: return null
        return mimeByExtension[extension]
    }

    fun isKnownMajorFormat(fileName: String): Boolean = mimeTypeForName(fileName) != null

    fun isImageMimeType(mimeType: String): Boolean =
        mimeType.startsWith("image/", ignoreCase = true)

    fun isVideoMimeType(mimeType: String): Boolean =
        mimeType.startsWith("video/", ignoreCase = true)

    enum class FileKind {
        DOCUMENT,
        PHOTO,
        VIDEO
    }

    fun kindForFile(fileName: String, mimeType: String): FileKind {
        // Prefer a known extension over a generic/incorrect provider MIME hint.
        val effectiveMimeType = mimeTypeForName(fileName) ?: mimeType
        return when {
            isVideoMimeType(effectiveMimeType) -> FileKind.VIDEO
            isImageMimeType(effectiveMimeType) -> FileKind.PHOTO
            else -> FileKind.DOCUMENT
        }
    }

    enum class TopicFilter(val title: String) {
        ALL("All"),
        DOCUMENTS("Documents"),
        PHOTOS("Photos"),
        VIDEOS("Videos");

        fun matches(file: VaultFile): Boolean =
            when (this) {
                ALL -> true
                DOCUMENTS -> kindForFile(file.displayName, file.mimeType) == FileKind.DOCUMENT
                PHOTOS -> kindForFile(file.displayName, file.mimeType) == FileKind.PHOTO
                VIDEOS -> kindForFile(file.displayName, file.mimeType) == FileKind.VIDEO
            }

        fun count(files: List<VaultFile>): Int = files.count(::matches)
    }

    val commonFormatSummary: String =
        "Photos: JPG, PNG, WebP, GIF, BMP, HEIC/HEIF, AVIF, TIFF and RAW · " +
            "Video: MP4, M4V, MOV, 3GP, MKV, WebM, AVI, MPEG, TS, OGV, WMV and FLV · " +
            "Documents: PDF, Word, Excel, PowerPoint, OpenDocument, RTF, TXT, CSV, HTML and EPUB"
}
