package com.example.photoexif

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.file.FileTypeDirectory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Row(val key: String, val value: String)
data class Section(val title: String, val rows: List<Row>)
data class Gps(val lat: Double, val lon: Double, val altitude: String?)
data class PhoneHit(val number: String, val source: String)

data class Report(
    val summary: List<Row>,
    val gps: Gps?,
    val flags: List<String>,
    val sections: List<Section>,
    val fileRows: List<Row>,
    val metaPhones: List<PhoneHit>
)

object Phones {
    private const val AR = "٠١٢٣٤٥٦٧٨٩"
    private const val FA = "۰۱۲۳۴۵۶۷۸۹"
    private val re = Regex("""(?<!\d)(\+?\(?\d[\d\s\-().]{5,18}\d)(?!\d)""")
    private val dateRe = Regex("""^\d{1,4}[-/.:]\d{1,2}[-/.:]\d{1,4}.*""")

    fun normalize(s: String): String = buildString {
        for (c in s) {
            val i = AR.indexOf(c)
            val j = FA.indexOf(c)
            append(
                when {
                    i >= 0 -> '0' + i
                    j >= 0 -> '0' + j
                    else -> c
                }
            )
        }
    }

    /** يستخرج الأرقام التي تشبه أرقام الهواتف من نص (8 إلى 15 رقماً). */
    fun extract(text: String): List<String> {
        val t = normalize(text)
        return re.findAll(t)
            .map { it.value.trim() }
            .filter { v ->
                val d = v.count { ch -> ch.isDigit() }
                d in 8..15 && !dateRe.matches(v)
            }
            .map { v ->
                val digits = v.filter { ch -> ch.isDigit() }
                (if (v.startsWith("+")) "+" else "") + digits
            }
            .distinct()
            .toList()
    }
}

object Analyzer {

    private val TEXT_KEYS = Regex(
        "artist|copyright|comment|description|owner|title|creator|subject|keyword|contact|phone|tel|author|caption|rights",
        RegexOption.IGNORE_CASE
    )

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun hash(bytes: ByteArray, algo: String) =
        hex(MessageDigest.getInstance(algo).digest(bytes))

    fun analyze(context: Context, uri: Uri): Report {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("تعذّر فتح الملف")

        // ---------- معلومات الملف ----------
        var name: String? = null
        try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) name = c.getString(i)
            }
        } catch (_: Exception) { }
        val mime = resolver.getType(uri)
        val fileRows = listOf(
            Row("اسم الملف", name ?: "غير متوفر"),
            Row("النوع (MIME)", mime ?: "غير متوفر"),
            Row("الحجم", "${bytes.size} بايت (%.2f ميغابايت)".format(bytes.size / 1048576.0)),
            Row("MD5", hash(bytes, "MD5")),
            Row("SHA-256", hash(bytes, "SHA-256"))
        )

        // ---------- قراءة كل البيانات ----------
        var metadata: Metadata? = null
        try {
            metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
        } catch (_: Exception) { }

        val sections = mutableListOf<Section>()
        val metaPhones = mutableListOf<PhoneHit>()
        metadata?.directories?.forEach { dir ->
            val rows = mutableListOf<Row>()
            for (tag in dir.tags) {
                val value = try { tag.description ?: "" } catch (_: Exception) { "" }
                rows.add(Row(tag.tagName, value.take(400)))
                if (TEXT_KEYS.containsMatchIn(tag.tagName)) {
                    Phones.extract(value).forEach {
                        metaPhones.add(PhoneHit(it, "${dir.name} ← ${tag.tagName}"))
                    }
                }
            }
            for (e in dir.errors) rows.add(Row("تنبيه", e))
            if (rows.isNotEmpty()) sections.add(Section(dir.name, rows))
        }

        // ---------- الملخص ----------
        val ifd0 = metadata?.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val sub = metadata?.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val gpsDir = metadata?.getFirstDirectoryOfType(GpsDirectory::class.java)
        val ftype = metadata?.getFirstDirectoryOfType(FileTypeDirectory::class.java)

        fun d(tag: Int): String? {
            val v = try { sub?.getDescription(tag) ?: ifd0?.getDescription(tag) } catch (_: Exception) { null }
            return v?.takeIf { it.isNotBlank() }
        }

        val summary = mutableListOf<Row>()
        fun add(label: String, v: String?, always: Boolean = false) {
            if (v != null) summary.add(Row(label, v)) else if (always) summary.add(Row(label, "غير متوفر"))
        }

        val make = d(0x010F)
        val model = d(0x0110)
        add("الشركة المصنّعة", make, true)
        add("موديل الجهاز", model, true)
        add("تاريخ الالتقاط", d(0x9003) ?: d(0x0132), true)
        add("فرق التوقيت", d(0x9011))
        add("جزء الثانية", d(0x9291))
        add("تاريخ التعديل في الملف", d(0x0132))
        add("اسم مالك الكاميرا (إن وُجد)", d(0xA430))
        add("المصوّر (Artist)", d(0x013B))
        add("حقوق النشر", d(0x8298))
        add("الرقم التسلسلي للجهاز", d(0xA431))
        add("العدسة", d(0xA434))
        add("الرقم التسلسلي للعدسة", d(0xA435))
        add("البرنامج", d(0x0131))
        add("العرض × الطول", run {
            val w = d(0xA002); val h = d(0xA003)
            if (w != null && h != null) "$w × $h" else null
        })
        add("الاتجاه", d(0x0112))
        add("ISO", d(0x8827))
        add("فتحة العدسة", d(0x829D))
        add("زمن التعريض", d(0x829A))
        add("البعد البؤري", d(0x920A))
        add("البعد البؤري (35 مم)", d(0xA405))
        add("الفلاش", d(0x9209))
        add("صيغة الملف", ftype?.getString(2))

        // ---------- GPS ----------
        var gps: Gps? = null
        val loc = try { gpsDir?.geoLocation } catch (_: Exception) { null }
        if (loc != null && !loc.isZero) {
            val alt = try { gpsDir?.getDescription(GpsDirectory.TAG_ALTITUDE) } catch (_: Exception) { null }
            gps = Gps(loc.latitude, loc.longitude, alt)
        }

        // ---------- تحليل ----------
        val flags = mutableListOf<String>()
        if (metadata == null || (ifd0 == null && sub == null)) {
            flags.add("لا توجد بيانات EXIF في هذه الصورة؛ غالباً مرّت عبر تطبيق تواصل أو أنها لقطة شاشة أو جُرّدت بياناتها.")
        }
        val software = d(0x0131)?.lowercase()
        if (software != null && Regex("photoshop|lightroom|snapseed|gimp|canva|picsart|facetune|pixlr|paint|editor").containsMatchIn(software)) {
            flags.add("تم تعديل الصورة ببرنامج: ${d(0x0131)}")
        }
        val modified = ifd0?.getString(0x0132)
        val original = sub?.getString(0x9003)
        if (modified != null && original != null && modified != original) {
            flags.add("تاريخ تعديل الملف ($modified) يختلف عن تاريخ الالتقاط ($original).")
        }
        if (make == null && model == null && metadata != null && (ifd0 != null || sub != null)) {
            flags.add("بيانات الجهاز غير موجودة رغم وجود EXIF؛ قد تكون الصورة معدّلة أو معالَجة.")
        }
        if (name?.contains("screenshot", true) == true || name?.contains("لقطة") == true) {
            flags.add("يبدو أن الملف لقطة شاشة وليس صورة من الكاميرا.")
        }
        if (gps != null) flags.add("الصورة تحتوي على إحداثيات موقع GPS.")
        if (metaPhones.isNotEmpty()) flags.add("وُجدت أرقام تشبه أرقام هواتف داخل الحقول النصية للصورة.")

        return Report(summary, gps, flags, sections, fileRows, metaPhones.distinctBy { it.number })
    }

    /** قراءة النص من الصورة محلياً (ML Kit) */
    suspend fun ocr(context: Context, uri: Uri): String = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromFilePath(context, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resumeWithException(it) }
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    fun toText(r: Report, ocrPhones: List<String>, ocrText: String?): String = buildString {
        appendLine("===== تقرير بيانات الصورة =====")
        r.summary.forEach { appendLine("${it.key}: ${it.value}") }
        r.gps?.let { appendLine("الموقع: ${it.lat}, ${it.lon}  (https://maps.google.com/?q=${it.lat},${it.lon})") }
        if (r.flags.isNotEmpty()) { appendLine(); appendLine("--- ملاحظات التحليل ---"); r.flags.forEach { appendLine("• $it") } }
        if (r.metaPhones.isNotEmpty()) { appendLine(); appendLine("--- أرقام داخل بيانات الصورة ---"); r.metaPhones.forEach { appendLine("${it.number}  [${it.source}]") } }
        if (ocrPhones.isNotEmpty()) { appendLine(); appendLine("--- أرقام مكتوبة داخل الصورة (OCR) ---"); ocrPhones.forEach { appendLine(it) } }
        if (!ocrText.isNullOrBlank()) { appendLine(); appendLine("--- النص المقروء من الصورة ---"); appendLine(ocrText) }
        appendLine(); appendLine("--- الملف ---"); r.fileRows.forEach { appendLine("${it.key}: ${it.value}") }
        r.sections.forEach { s ->
            appendLine(); appendLine("--- ${s.title} ---")
            s.rows.forEach { appendLine("${it.key}: ${it.value}") }
        }
    }
}
