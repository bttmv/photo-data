package com.example.photoexif

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = extractUri(intent)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) { ExifScreen(initial) }
                }
            }
        }
    }

    private fun extractUri(i: Intent?): Uri? {
        if (i == null) return null
        return when (i.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
    }
}

private val TABS = listOf("ملخص", "كل البيانات", "النص والأرقام", "الملف")

@Composable
fun ExifScreen(initialUri: Uri?) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var uri by remember { mutableStateOf(initialUri) }
    var report by remember { mutableStateOf<Report?>(null) }
    var ocrText by remember { mutableStateOf<String?>(null) }
    var ocrBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }

    // OpenDocument يعيد الملف الأصلي كما هو (بكامل بياناته)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) uri = it
    }

    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        report = null; ocrText = null; error = null; tab = 0
        try {
            report = withContext(Dispatchers.IO) { Analyzer.analyze(context, u) }
        } catch (e: Exception) {
            error = e.message ?: "فشل تحليل الملف"
        }
        ocrBusy = true
        try {
            ocrText = withContext(Dispatchers.IO) { Analyzer.ocr(context, u) }
        } catch (_: Exception) {
            ocrText = ""
        } finally {
            ocrBusy = false
        }
    }

    val ocrPhones = remember(ocrText) { Phones.extract(ocrText ?: "") }

    fun copy(s: String) = clipboard.setText(AnnotatedString(s))

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("المحلّل الذكي للصور", style = MaterialTheme.typography.headlineSmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("image/*")) }) { Text("اختر صورة") }
            val r = report
            if (r != null) {
                OutlinedButton(onClick = { copy(Analyzer.toText(r, ocrPhones, ocrText)) }) { Text("نسخ التقرير") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, Analyzer.toText(r, ocrPhones, ocrText))
                    }
                    context.startActivity(Intent.createChooser(send, "مشاركة التقرير"))
                }) { Text("مشاركة") }
            }
        }

        uri?.let {
            AsyncImage(
                model = it, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(140.dp)
            )
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (uri != null && report == null && error == null) Text("جارٍ التحليل...")

        report?.let { r ->
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                TABS.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (tab) {
                    0 -> {
                        items(r.flags) { f ->
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) { Text(f, Modifier.padding(10.dp)) }
                        }
                        items(r.summary) { row -> InfoRow(row.key, row.value) { copy(row.value) } }
                        r.gps?.let { g ->
                            item { InfoRow("الإحداثيات", "${g.lat}, ${g.lon}") { copy("${g.lat}, ${g.lon}") } }
                            g.altitude?.let { a -> item { InfoRow("الارتفاع", a) { copy(a) } } }
                            item {
                                Button(onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${g.lat},${g.lon}"))
                                        )
                                    } catch (_: Exception) { }
                                }) { Text("فتح الموقع في الخرائط") }
                            }
                        }
                    }
                    1 -> {
                        r.sections.forEach { s ->
                            item { Text(s.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                            items(s.rows) { row -> InfoRow(row.key, row.value) { copy(row.value) } }
                        }
                    }
                    2 -> {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Text(
                                    "ملاحظة: رقم هاتف الجهاز لا يُخزَّن داخل الصورة أصلاً. " +
                                        "هنا تظهر الأرقام المكتوبة داخل الصورة نفسها (عبر OCR) أو في حقولها النصية فقط.",
                                    Modifier.padding(10.dp)
                                )
                            }
                        }
                        item { Text("أرقام في حقول بيانات الصورة", style = MaterialTheme.typography.titleMedium) }
                        if (r.metaPhones.isEmpty()) item { Text("لا يوجد") }
                        items(r.metaPhones) { p -> PhoneRow(p.number, p.source, { copy(p.number) }) { dial(context, p.number) } }

                        item { Text("أرقام مكتوبة داخل الصورة (OCR)", style = MaterialTheme.typography.titleMedium) }
                        if (ocrBusy) item { Text("جارٍ قراءة النص...") }
                        else if (ocrPhones.isEmpty()) item { Text("لا يوجد") }
                        items(ocrPhones) { p -> PhoneRow(p, "نص داخل الصورة", { copy(p) }) { dial(context, p) } }

                        item { Text("النص الكامل المقروء من الصورة", style = MaterialTheme.typography.titleMedium) }
                        item {
                            SelectionContainer {
                                Text(if (ocrText.isNullOrBlank()) "لا يوجد نص" else ocrText!!)
                            }
                        }
                    }
                    else -> items(r.fileRows) { row -> InfoRow(row.key, row.value) { copy(row.value) } }
                }
            }
        }
    }
}

private fun dial(context: android.content.Context, number: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    } catch (_: Exception) { }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun InfoRow(k: String, v: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(k, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.4f))
            Spacer(Modifier.width(8.dp))
            Text(v, modifier = Modifier.weight(0.6f))
        }
    }
}

@Composable
private fun PhoneRow(number: String, source: String, onCopy: () -> Unit, onDial: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(number, style = MaterialTheme.typography.titleMedium)
            Text(source, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = onCopy) { Text("نسخ") }
                TextButton(onClick = onDial) { Text("اتصال") }
            }
        }
    }
}
