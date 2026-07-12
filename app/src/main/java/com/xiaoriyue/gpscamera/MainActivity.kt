package com.xiaoriyue.gpscamera

import android.Manifest
import android.content.ContentValues
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastLocation: Location? = null
    private var lastAddress: String = "地址擷取中..."

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN)

    // 每秒更新一次畫面上顯示的時間
    private val clockTicker = object : Runnable {
        override fun run() {
            updateOverlayText()
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
        gpsOverlayText = findViewById(R.id.gpsOverlayText)
        val captureButton = findViewById<android.widget.Button>(R.id.captureButton)

        cameraExecutor = Executors.newSingleThreadExecutor()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        captureButton.setOnClickListener { takePhoto() }

        requestNeededPermissions()
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

        if (needed.isEmpty()) {
            startCamera()
            startLocationUpdates()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // ---------- 相機 ----------

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                Log.e(TAG, "相機綁定失敗", e)
                Toast.makeText(this, "相機啟動失敗：${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
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

    private fun updateOverlayText() {
        val now = timeFormat.format(Date())
        gpsOverlayText.text = "$lastAddress\n$now"
    }

    // ---------- 拍照 ----------

    private fun takePhoto() {
        val capture = imageCapture ?: return

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()

                val watermarked = drawGpsWatermark(bitmap, lastAddress, timeFormat.format(Date()))
                saveBitmapToGallery(watermarked)
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

    /** 將 GPS 地址與時間畫在照片右下角，回傳新的 Bitmap */
    private fun drawGpsWatermark(source: Bitmap, address: String, time: String): Bitmap {
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

        val lines = wrapAddress(address, textPaint, result.width - padding * 2)
        val allLines = lines + time

        val lineHeight = textSize * 1.3f
        val blockHeight = lineHeight * allLines.size

        // 半透明底色，讓文字更清楚
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

    /** 簡單依畫面寬度換行，避免地址過長被裁切 */
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

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(clockTicker)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "GpsCameraApp"
    }
}
