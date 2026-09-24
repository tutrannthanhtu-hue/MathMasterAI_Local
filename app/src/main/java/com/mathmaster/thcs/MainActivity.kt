package com.mathmaster.thcs

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var selectedImage: Uri? = null
    private var cameraFile: File? = null
    private var modelFile: File? = null
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var deepCheck: CheckBox

    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectedImage = it; status.text = "Đã chọn ảnh. Bấm AI GIẢI BÀI để OCR + giải." }
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && cameraFile != null) {
            selectedImage = Uri.fromFile(cameraFile!!)
            status.text = "Đã chụp ảnh. Bấm AI GIẢI BÀI để OCR + giải."
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) takePhoto() else status.text = "Cần quyền camera để chụp đề."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        val title = TextView(this).apply { text = "🧠 MathMaster THCS AI 2.0"; textSize = 24f; setPadding(0,0,0,16) }
        root.addView(title)

        val grade = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Lớp 6","Lớp 7","Lớp 8","Lớp 9")) }
        root.addView(grade, LinearLayout.LayoutParams(-1, -2))

        input = EditText(this).apply {
            hint = "Nhập bài toán hoặc dùng camera/ảnh"
            gravity = android.view.Gravity.TOP
            minLines = 5
        }
        root.addView(input, LinearLayout.LayoutParams(-1, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cam = Button(this).apply { text = "📷 Camera"; setOnClickListener { requestCamera() } }
        val gal = Button(this).apply { text = "🖼 Ảnh"; setOnClickListener { gallery.launch("image/*") } }
        val model = Button(this).apply { text = "📦 Chọn GGUF"; setOnClickListener { chooseModel() } }
        row.addView(cam, LinearLayout.LayoutParams(0,-2,1f)); row.addView(gal, LinearLayout.LayoutParams(0,-2,1f)); row.addView(model, LinearLayout.LayoutParams(0,-2,1f))
        root.addView(row)

        deepCheck = CheckBox(this).apply { text = "🔎 Kiểm tra sâu: AI giải → AI phản biện → sửa lỗi"; isChecked = true }
        root.addView(deepCheck)
        val solve = Button(this).apply { text = "🧠 AI GIẢI BÀI"; textSize = 18f; setOnClickListener { solve(grade.selectedItem.toString()) } }
        root.addView(solve)
        status = TextView(this).apply { text = "Chưa chọn model GGUF."; setPadding(0,12,0,12) }
        root.addView(status)
        output = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(output) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1.2f))
        setContentView(root)
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) takePhoto()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun takePhoto() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        cameraFile = File(dir, "math_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", cameraFile!!)
        camera.launch(uri)
    }

    private fun chooseModel() {
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            scope.launch(Dispatchers.IO) {
                val dst = File(getExternalFilesDir("models"), "math-model.gguf")
                dst.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(dst).use { output -> input.copyTo(output) } }
                withContext(Dispatchers.Main) { modelFile = dst; status.text = "Model: ${dst.name} (${dst.length()/1024/1024} MB)" }
            }
        }.launch("*/*")
    }

    private fun solve(grade: String) {
        val model = modelFile ?: run { status.text = "Hãy chọn file .gguf trước."; return }
        scope.launch {
            try {
                status.text = "Đang chuẩn bị đề..."
                val problem = if (input.text.toString().trim().isNotEmpty()) input.text.toString().trim() else selectedImage?.let { ocr(it) }.orEmpty()
                if (problem.isBlank()) { status.text = "Chưa có đề bài."; return@launch }
                input.setText(problem)
                status.text = "AI đang giải trên thiết bị..."
                val answer = withContext(Dispatchers.Default) { runLocal(model, buildPrompt(grade, problem), 1200) }
                var final = answer
                var critique = ""
                if (deepCheck.isChecked) {
                    status.text = "AI đang phản biện lời giải..."
                    critique = withContext(Dispatchers.Default) { runLocal(model, critiquePrompt(problem, answer), 500) }
                    if (!critique.trim().equals("PASS", true)) {
                        status.text = "AI đang sửa lỗi..."
                        final = withContext(Dispatchers.Default) { runLocal(model, repairPrompt(problem, answer, critique), 1200) }
                    }
                }
                output.text = "ĐỀ BÀI\n$problem\n\nLỜI GIẢI\n$final" + if (critique.isNotBlank()) "\n\nKIỂM TRA\n$critique" else ""
                status.text = "Hoàn tất — AI chạy cục bộ, không cần API."
            } catch (e: Exception) { status.text = "Lỗi: ${e.message}" }
        }
    }

    private fun runLocal(file: File, prompt: String, maxTokens: Int): String {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2,6)
        val cfg = LlamaConfig(contextSize = 8192, threads = threads)
        val model = Llama.loadModel(modelPath = file.absolutePath, config = cfg)
        return try { Llama.complete(model, prompt = prompt, systemPrompt = SYSTEM, maxTokens = maxTokens).text.trim() }
        finally { Llama.releaseModel(model) }
    }

    private suspend fun ocr(uri: Uri): String = suspendCancellableCoroutine { cont ->
        try {
            val image = if (uri.scheme == "file") InputImage.fromFilePath(this, uri) else InputImage.fromFilePath(this, uri)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                .addOnSuccessListener { r -> cont.resume(r.text, null) }
                .addOnFailureListener { e -> cont.resumeWith(Result.failure(e)) }
        } catch (e: Exception) { cont.resumeWith(Result.failure(e)) }
    }

    private fun buildPrompt(grade: String, p: String) = """
Bạn là MathMaster THCS AI 2.0. Giải bài toán $grade bằng tiếng Việt.
Đề: $p
Yêu cầu: giải đầy đủ từng câu a/b/c; mỗi phép tính phải có số cụ thể; ưu tiên biến đổi hợp lí, đặt nhân tử chung, hằng đẳng thức, rút gọn; phương trình/bất phương trình phải nêu điều kiện và biến đổi tương đương; không đoán hình học khi thiếu dữ kiện; cuối cùng ghi Đáp số. Tự kiểm tra kết quả trước khi trả lời.
""".trimIndent()

    private fun critiquePrompt(p: String, a: String) = """
Kiểm tra độc lập lời giải sau. Đề: $p
Lời giải: $a
Kiểm tra dấu, phân số, số thập phân, lũy thừa, điều kiện và đáp số. Nếu hoàn toàn đúng, chỉ trả về PASS. Nếu sai, nêu rõ dòng/câu sai, giá trị đúng và lý do.
""".trimIndent()

    private fun repairPrompt(p: String, a: String, c: String) = """
Viết lại lời giải đúng, sạch, bằng tiếng Việt THCS.
Đề: $p
Lời giải cũ: $a
Phản biện: $c
Sửa mọi lỗi được chỉ ra và tự kiểm tra lại. Không nhắc đến quá trình nội bộ của AI.
""".trimIndent()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val SYSTEM = "Bạn là MathMaster THCS AI 2.0, trợ lý toán chạy hoàn toàn trên thiết bị. Ưu tiên tính đúng và lời giải đủ bước. Khi dữ kiện không rõ, nói rõ thay vì đoán."
    }
}
