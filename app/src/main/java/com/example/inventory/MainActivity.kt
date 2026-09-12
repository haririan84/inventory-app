package com.example.inventory

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.inventory.adapter.ItemAdapter
import com.example.inventory.databinding.ActivityMainBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val items = LinkedHashMap<String, Item>()
    private lateinit var adapter: ItemAdapter
    private val orderedList = mutableListOf<Item>()

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var scanning = false
    private var lastCode: String? = null
    private var lastTime = 0L
    private var currentFoundBarcode: String? = null

    private val stateFile: File by lazy { File(filesDir, "inventory_state.json") }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else toast("دسترسی دوربین رد شد")
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importCsv(uri)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) exportCsv(uri)
        }

    private val sampleSaveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) writeSampleCsv(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ItemAdapter(orderedList) { item, newQty ->
            item.qty = newQty.coerceAtLeast(0)
            saveState()
            renderTotals()
        }
        binding.recyclerList.layoutManager = LinearLayoutManager(this)
        binding.recyclerList.adapter = adapter

        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("text/*", "text/comma-separated-values", "*/*"))
        }
        binding.btnExport.setOnClickListener {
            exportLauncher.launch("نتیجه-انبارگردانی.csv")
        }
        binding.btnSample.setOnClickListener {
            sampleSaveLauncher.launch("نمونه-فایل-قطعات.csv")
        }
        binding.btnScan.setOnClickListener { toggleScan() }
        binding.btnAddQty.setOnClickListener { confirmAddQty() }

        loadState()
        refreshList()
    }

    private fun saveState() {
        val arr = JSONArray()
        items.values.forEach { i ->
            val o = JSONObject()
            o.put("barcode", i.barcode)
            o.put("name", i.name)
            o.put("price", i.price)
            o.put("qty", i.qty)
            arr.put(o)
        }
        stateFile.writeText(arr.toString())
    }

    private fun loadState() {
        if (!stateFile.exists()) return
        try {
            val arr = JSONArray(stateFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val item = Item(
                    barcode = o.getString("barcode"),
                    name = o.optString("name", ""),
                    price = o.optDouble("price", 0.0),
                    qty = o.optInt("qty", 0)
                )
                items[item.barcode] = item
            }
            binding.statusText.text = "${items.size} قلم کالا بارگذاری شده"
        } catch (_: Exception) {
        }
    }

    private fun refreshList() {
        orderedList.clear()
        orderedList.addAll(items.values)
        adapter.notifyDataSetChanged()
        renderTotals()
    }

    private fun renderTotals() {
        val totalQty = items.values.sumOf { it.qty }
        val totalValue = items.values.sumOf { it.qty * it.price }
        binding.totalsText.text = "تعداد شمارش‌شده: $totalQty   |   ارزش کل: $totalValue"
    }

    private val barcodeKeys = listOf("بارکد", "کد کالا", "کد", "barcode", "sku", "code")
    private val nameKeys = listOf("نام", "نام کالا", "شرح", "description", "name")
    private val priceKeys = listOf("قیمت", "قیمت واحد", "price")

    private fun findColumn(headers: List<String>, candidates: List<String>): Int {
        headers.forEachIndexed { idx, h ->
            if (candidates.any { it.equals(h.trim(), ignoreCase = true) }) return idx
        }
        headers.forEachIndexed { idx, h ->
            if (candidates.any { h.trim().contains(it, ignoreCase = true) }) return idx
        }
        return -1
    }

    private fun importCsv(uri: Uri) {
        try {
            val lines = contentResolver.openInputStream(uri)?.bufferedReader()?.readLines() ?: emptyList()
            if (lines.isEmpty()) {
                toast("فایل خالی است")
                return
            }
            val headers = splitCsvLine(lines[0])
            val bCol = findColumn(headers, barcodeKeys)
            val nCol = findColumn(headers, nameKeys)
            val pCol = findColumn(headers, priceKeys)

            if (bCol == -1) {
                toast("ستون بارکد/کد کالا در فایل پیدا نشد")
                return
            }

            items.clear()
            for (i in 1 until lines.size) {
                if (lines[i].isBlank()) continue
                val cols = splitCsvLine(lines[i])
                val code = cols.getOrNull(bCol)?.trim() ?: continue
                if (code.isEmpty()) continue
                val name = if (nCol >= 0) cols.getOrNull(nCol)?.trim() ?: "" else ""
                val price = if (pCol >= 0) cols.getOrNull(pCol)?.trim()?.toDoubleOrNull() ?: 0.0 else 0.0
                items[code] = Item(code, name, price, 0)
            }
            saveState()
            refreshList()
            binding.statusText.text = "${items.size} قلم کالا بارگذاری شده"
            toast("فایل با موفقیت بارگذاری شد")
        } catch (e: Exception) {
            toast("خطا در خواندن فایل: ${e.message}")
        }
    }

    private fun splitCsvLine(line: String): List<String> =
        line.split(",").map { it.trim().trim('"') }

    private fun exportCsv(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                val sb = StringBuilder()
                sb.append("بارکد,نام کالا,قیمت واحد,تعداد شمارش شده,ارزش کل\n")
                items.values.forEach { i ->
                    sb.append("${i.barcode},${i.name},${i.price},${i.qty},${i.qty * i.price}\n")
                }
                out.write(sb.toString().toByteArray())
            }
            toast("فایل خروجی ذخیره شد")
        } catch (e: Exception) {
            toast("خطا در ذخیره فایل: ${e.message}")
        }
    }

    private fun writeSampleCsv(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                val sample = """
                    بارکد,نام کالا,قیمت
                    6221031100016,پیچ آلن ۸ میل,15000
                    6221031100023,مهره شش‌گوش ۱۰ میل,8000
                    6221031100030,واشر فنری ۶ میل,2500
                    6221031100047,رول‌بیرینگ ۶۲۰۴,120000
                    6221031100054,اورینگ لاستیکی,4000
                """.trimIndent()
                out.write(sample.toByteArray())
            }
            toast("نمونه فایل ذخیره شد")
        } catch (e: Exception) {
            toast("خطا: ${e.message}")
        }
    }

    private fun toggleScan() {
        if (scanning) {
            stopScan()
            return
        }
        if (items.isEmpty()) {
            toast("ابتدا فایل CSV را بارگذاری کنید")
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        binding.previewView.visibility = View.VISIBLE
        binding.btnScan.text = "توقف اسکن"
        scanning = true
        cameraExecutor = Executors.newSingleThreadExecutor()

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        val scannerOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_QR_CODE, Barcode.FORMAT_ITF
            ).build()
        val scanner = BarcodeScanning.getClient(scannerOptions)

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
            processImage(imageProxy, scanner)
        }

        try {
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        } catch (e: Exception) {
            toast("خطا در راه‌اندازی دوربین: ${e.message}")
        }
    }

    private fun processImage(imageProxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull()?.rawValue
                if (value != null) {
                    val now = System.currentTimeMillis()
                    if (value != lastCode || now - lastTime > 2500) {
                        lastCode = value
                        lastTime = now
                        runOnUiThread { lookupBarcode(value) }
                    }
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun stopScan() {
        scanning = false
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        binding.previewView.visibility = View.GONE
        binding.btnScan.text = "شروع اسکن"
    }

    private fun lookupBarcode(code: String) {
        val item = items[code]
        currentFoundBarcode = code
        binding.foundCard.visibility = View.VISIBLE
        binding.qtyInput.setText("1")
        if (item != null) {
            binding.foundText.text = "بارکد: ${item.barcode}\nنام: ${item.name}\nقیمت: ${item.price}\nتعداد فعلی: ${item.qty}"
        } else {
            items[code] = Item(code, "کالای جدید (بدون مشخصات)", 0.0, 0)
            binding.foundText.text = "کد $code در فایل اصلی پیدا نشد و به‌عنوان کالای جدید اضافه شد."
        }
    }

    private fun confirmAddQty() {
        val code = currentFoundBarcode ?: return
        val add = binding.qtyInput.text.toString().toIntOrNull() ?: 1
        items[code]?.let {
            it.qty += add
            saveState()
            refreshList()
            toast("ثبت شد: ${it.qty}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor?.shutdown()
    }
}
