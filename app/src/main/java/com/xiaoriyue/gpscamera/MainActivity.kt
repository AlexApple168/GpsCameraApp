package com.xiaoriyue.gpscamera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var gpsOverlayText: TextView
    private lateinit var recordingTimerText: TextView
    private lateinit var modeToggleButton: Button
    private lateinit var captureButton: Button
    private lateinit var brightnessSeekBar: SeekBar

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var camera: Camera? = null
    private var isVideoMode = false
    private var isRecording = false
    private var recordingStartTimeMs = 0L
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var selectedLensId: String? = null
    private lateinit var lensSwitchBar: android.widget.LinearLayout

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastLocation: Location? = null
    private var lastAddress: String = "地址擷取中..."

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN)

    // 每秒更新一次畫面上顯示的時間 / 疊字內容
    private val clockTicker = object : Runnable {
        override fun run() {
            updateOverlayText()
            mainHandler.postDelayed(this, 1000)
        }
    }

    // 錄影中，每秒更新已錄影秒數
    private val recordingTicker = object : Runnable {
        override fun run() {
            val elapsedSec = (System.currentTimeMillis() - recordingStartTimeMs) / 1000
            val minutes = elapsedSec / 60
            val seconds = elapsedSec % 60
            recordingTimerText.text = "● %02d:%02d".format(minutes, seconds)
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lastLocation = location
            reverseGeocode(location)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true
        val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true

        if (cameraGranted) {
            setupLensOptions()
            startCamera()
        } else {
            Toast.makeText(this, "需要相機權限才能使用本 App", Toast.LENGTH_LONG).show()
        }

        if (locationGranted) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "未授予定位權限，將無法在照片顯示 GPS 資訊", Toast.LENGTH_LONG).show()
            gpsOverlayText.text = "尚未授予定位權限"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        // 使用 COMPATIBLE（TextureView）模式，確保 GPS 疊字在錄影模式下也一定會顯示在預覽畫面最上層
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        gpsOverlayText = findViewById(R.id.gpsOverlayText)
        recordingTimerText = findViewById(R.id.recordingTimerText)
        captureButton = findViewById(R.id.captureButton)
        modeToggleButton = findViewById(R.id.modeToggleButton)
        brightnessSeekBar = findViewById(R.id.brightnessSeekBar)
        lensSwitchBar = findViewById(R.id.lensSwitchBar)
        val settingsButton = findViewById<Button>(R.id.settingsButton)
        val galleryButton = findViewById<Button>(R.id.galleryButton)

        cameraExecutor = Executors.newSingleThreadExecutor()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        captureButton.setOnClickListener { onCaptureButtonClicked() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        galleryButton.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        modeToggleButton.setOnClickListener { toggleMode() }
        setupBrightnessSlider()

        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayText()
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (needed.isEmpty()) {
            setupLensOptions()
            startCamera()
            startLocationUpdates()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // ---------- 模式切換（拍照／錄影） ----------

    private fun toggleMode() {
        if (isRecording) {
            Toast.makeText(this, "請先停止錄影再切換模式", Toast.LENGTH_SHORT).show()
            return
        }
        isVideoMode = !isVideoMode
        modeToggleButton.text = if (isVideoMode) "模式：錄影" else "模式：拍照"
        captureButton.text = if (isVideoMode) "開始錄影" else "拍照"
        startCamera()
    }

    // ---------- 相機 ----------

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val cameraSelector = currentCameraSelector

            try {
                cameraProvider.unbindAll()

                camera = if (isVideoMode) {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
                } else {
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                }

                applyBrightness(brightnessSeekBar.progress)
            } catch (e: Exception) {
                Log.e(TAG, "相機綁定失敗", e)
                Toast.makeText(this, "相機啟動失敗：${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- 鏡頭切換 ----------

    private fun setupLensOptions() {
        val options = CameraLensHelper.detectLensOptions(this)
        if (options.isEmpty()) return

        if (selectedLensId == null || options.none { it.id == selectedLensId }) {
            val default = options.firstOrNull { it.label == "標準" } ?: options.first()
            selectedLensId = default.id
            currentCameraSelector = CameraLensHelper.selectorForCameraId(default.id)
        }

        renderLensButtons(options)
    }

    private fun renderLensButtons(options: List<CameraLensHelper.LensOption>) {
        lensSwitchBar.removeAllViews()
        for (option in options) {
            val button = Button(this)
            button.text = option.label
            button.textSize = 12f
            button.setPadding(28, 8, 28, 8)
            button.setTextColor(Color.WHITE)
            button.setAllCaps(false)
            button.minWidth = 0
            button.minimumWidth = 0
            val isSelected = option.id == selectedLensId
            button.setBackgroundColor(Color.parseColor(if (isSelected) "#4285F4" else "#333333"))

            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = 12
            button.layoutParams = params

            button.setOnClickListener {
                if (isRecording) {
                    Toast.makeText(this, "請先停止錄影再切換鏡頭", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (selectedLensId != option.id) {
                    selectedLensId = option.id
                    currentCameraSelector = CameraLensHelper.selectorForCameraId(option.id)
                    renderLensButtons(options)
                    startCamera()
                }
            }

            lensSwitchBar.addView(button)
        }
    }

    // ---------- 亮度（曝光補償）調整 ----------

    private fun setupBrightnessSlider() {
        brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyBrightness(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /** progress: 0~100，對應相機支援的曝光補償範圍（50 為預設中間值） */
    private fun applyBrightness(progress: Int) {
        val cam = camera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        if (range.lower == 0 && range.upper == 0) return // 裝置不支援曝光調整

        val ratio = progress / 100f
        val index = (range.lower + (range.upper - range.lower) * ratio).toInt()
            .coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(index)
    }

    // ---------- 定位 ----------

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        mainHandler.post(clockTicker)
    }

    private fun reverseGeocode(location: Location) {
        cameraExecutor.execute {
            val address = try {
                val geocoder = Geocoder(this, Locale.TAIWAN)
                @Suppress("DEPRECATION")
                val results: List<Address>? = geocoder.getFromLocation(
                    location.latitude, location.longitude, 1
                )
                results?.firstOrNull()?.getAddressLine(0)
            } catch (e: Exception) {
                Log.w(TAG, "反地理編碼失敗", e)
                null
            } ?: "%.5f, %.5f".format(location.latitude, location.longitude)

            lastAddress = address
            mainHandler.post { updateOverlayText() }
        }
    }

    /** 組合即時疊字內容：地址／經緯度（可選）／自訂文字（可選）／時間 */
    private fun updateOverlayText() {
        val now = timeFormat.format(Date())
        val lines = mutableListOf(lastAddress)

        if (Prefs.getShowLatLon(this)) {
            lastLocation?.let {
                lines.add("%.6f, %.6f".format(it.latitude, it.longitude))
            }
        }

        val customText = Prefs.getCustomText(this)
        if (customText.isNotBlank()) {
            lines.add(customText)
        }

        lines.add(now)
        gpsOverlayText.text = lines.joinToString("\n")
    }

    // ---------- 拍照 / 錄影按鈕統一入口 ----------

    private fun onCaptureButtonClicked() {
        if (isVideoMode) {
            if (isRecording) stopRecording() else startRecording()
        } else {
            takePhoto()
        }
    }

    // ---------- 拍照 ----------

    private fun takePhoto() {
        val capture = imageCapture ?: return

        val captureLocation = lastLocation
        val captureAddress = lastAddress
        val captureTime = timeFormat.format(Date())
        val captureCustomText = Prefs.getCustomText(this)
        val captureShowLatLon = Prefs.getShowLatLon(this)

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()

                val watermarked = drawGpsWatermark(
                    source = bitmap,
                    address = captureAddress,
                    time = captureTime,
                    location = captureLocation,
                    showLatLon = captureShowLatLon,
                    customText = captureCustomText
                )
                saveBitmapToGallery(watermarked)

                if (captureLocation != null) {
                    LocationLogger.append(
                        this@MainActivity,
                        "[照片] $captureTime",
                        captureLocation.latitude,
                        captureLocation.longitude,
                        captureAddress
                    )
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "拍照失敗", exception)
                mainHandler.post {
                    Toast.makeText(this@MainActivity, "拍照失敗：${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** 將地址／經緯度／自訂文字／時間畫在照片右下角，回傳新的 Bitmap */
    private fun drawGpsWatermark(
        source: Bitmap,
        address: String,
        time: String,
        location: Location?,
        showLatLon: Boolean,
        customText: String
    ): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val textSize = result.width * 0.028f
        val padding = result.width * 0.02f

        val textPaint = Paint().apply {
            color = Color.WHITE
            this.textSize = textSize
            isAntiAlias = true
            setShadowLayer(6f, 2f, 2f, Color.BLACK)
            textAlign = Paint.Align.RIGHT
        }

        val addressLines = wrapAddress(address, textPaint, result.width - padding * 2)

        val allLines = mutableListOf<String>().apply {
            addAll(addressLines)
            if (showLatLon && location != null) {
                add("%.6f, %.6f".format(location.latitude, location.longitude))
            }
            if (customText.isNotBlank()) {
                add(customText)
            }
            add(time)
        }

        val lineHeight = textSize * 1.3f
        val blockHeight = lineHeight * allLines.size

        val bgPaint = Paint().apply {
            color = Color.argb(140, 0, 0, 0)
        }
        canvas.drawRect(
            0f,
            result.height - blockHeight - padding * 1.5f,
            result.width.toFloat(),
            result.height.toFloat(),
            bgPaint
        )

        var y = result.height - padding - (allLines.size - 1) * lineHeight
        for (line in allLines) {
            canvas.drawText(line, result.width - padding, y, textPaint)
            y += lineHeight
        }

        return result
    }

    private fun wrapAddress(address: String, paint: Paint, maxWidth: Float): List<String> {
        if (paint.measureText(address) <= maxWidth) return listOf(address)

        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (ch in address) {
            val candidate = current.toString() + ch
            if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                lines.add(current.toString())
                current = StringBuilder().append(ch)
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val filename = "GPS_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GpsCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri == null) {
            mainHandler.post { Toast.makeText(this, "儲存失敗", Toast.LENGTH_LONG).show() }
            return
        }

        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        mainHandler.post {
            Toast.makeText(this, "照片已儲存至相簿", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 錄影 ----------

    private fun startRecording() {
        val capture = videoCapture ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "需要麥克風權限才能錄影", Toast.LENGTH_SHORT).show()
            return
        }

        val filename = "GPS_${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GpsCamera")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val startLocation = lastLocation
        val startAddress = lastAddress
        val startTime = timeFormat.format(Date())

        activeRecording = capture.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        captureButton.text = "停止錄影"
                        recordingStartTimeMs = System.currentTimeMillis()
                        recordingTimerText.text = "● 00:00"
                        recordingTimerText.visibility = android.view.View.VISIBLE
                        mainHandler.post(recordingTicker)
                        if (startLocation != null) {
                            LocationLogger.append(
                                this, "[影片開始] $startTime",
                                startLocation.latitude, startLocation.longitude, startAddress
                            )
                        }
                        Toast.makeText(this, "開始錄影", Toast.LENGTH_SHORT).show()
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        captureButton.text = "開始錄影"
                        mainHandler.removeCallbacks(recordingTicker)
                        recordingTimerText.visibility = android.view.View.GONE
                        if (event.hasError()) {
                            Toast.makeText(this, "錄影發生錯誤：${event.error}", Toast.LENGTH_LONG).show()
                        } else {
                            val endTime = timeFormat.format(Date())
                            val endLocation = lastLocation
                            if (endLocation != null) {
                                LocationLogger.append(
                                    this, "[影片結束] $endTime",
                                    endLocation.latitude, endLocation.longitude, lastAddress
                                )
                            }
                            Toast.makeText(this, "影片已儲存至相簿", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> Unit
                }
            }
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(clockTicker)
        mainHandler.removeCallbacks(recordingTicker)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        activeRecording?.stop()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "GpsCameraApp"
    }
}
