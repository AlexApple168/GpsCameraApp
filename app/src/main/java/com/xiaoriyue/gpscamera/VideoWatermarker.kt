package com.xiaoriyue.gpscamera

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.opengl.*
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 影片浮水印處理器。
 *
 * 策略：
 * - 影片保持原始感測器方向（不在 GL 做旋轉），用 orientationHint 讓播放器轉
 * - 浮水印先在「正確顯示方向」畫好（底部右下角），再用 Bitmap 旋轉回原始方向
 * - 這樣 SurfaceTexture 的變換矩陣可以直接套用，不需要跟旋轉矩陣做運算
 */
class VideoWatermarker(private val context: Context) {

    companion object {
        private const val TAG = "VideoWatermarker"
        private const val TIMEOUT_US = 10_000L

        private const val VS = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
uniform mat4 uSTMatrix;
void main() {
  gl_Position = aPosition;
  vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}"""
        private const val FS_EXT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTexCoord;
uniform samplerExternalOES sTexture;
void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }"""

        private const val FS_2D = """
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D sTexture;
void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }"""
    }

    /** 影片用：標準貼圖座標（OES texture 由 stMatrix 處理翻轉） */
    private val quadBuf: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f))
            position(0)
        }

    /**
     * 浮水印用：Y 軸翻轉的貼圖座標。
     * Bitmap 的原點在左上角，OpenGL 貼圖原點在左下角，
     * 所以 2D texture 需要翻轉 Y 才不會上下顛倒。
     */
    private val wmQuadBuf: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f))
            position(0)
        }

    private val identityMatrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private val syncObj = Object()
    @Volatile private var frameAvailable = false

    fun processAsync(inputFile: File, lines: List<String>, onDone: (Uri?) -> Unit) {
        Thread {
            val uri = try {
                doProcess(inputFile, lines)
            } catch (e: Exception) {
                Log.e(TAG, "浮水印處理失敗，儲存原始影片", e)
                saveToMediaStore(inputFile)
            }
            onDone(uri)
        }.start()
    }

    private fun doProcess(inputFile: File, lines: List<String>): Uri? {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        var vidIdx = -1; var audIdx = -1
        var vidFmt: MediaFormat? = null; var audFmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("video/") && vidIdx == -1) { vidIdx = i; vidFmt = f }
            else if (m.startsWith("audio/") && audIdx == -1) { audIdx = i; audFmt = f }
        }
        if (vidIdx == -1 || vidFmt == null) return saveToMediaStore(inputFile)

        val rawW = vidFmt.getInteger(MediaFormat.KEY_WIDTH)
        val rawH = vidFmt.getInteger(MediaFormat.KEY_HEIGHT)

        // MediaExtractor 的 track format 常常讀不到 KEY_ROTATION（回傳 0），
        // 改用 MediaMetadataRetriever 讀取容器層的 rotation metadata，這個才可靠
        val rotation = readRotation(inputFile)
        Log.i(TAG, "影片尺寸=${rawW}x${rawH}, rotation=$rotation")

        val br = try { vidFmt.getInteger(MediaFormat.KEY_BIT_RATE) } catch (_: Exception) { maxOf(rawW * rawH * 2, 2_000_000) }
        val fps = try { vidFmt.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) { 30 }

        // 不做尺寸交換，輸出跟原始感測器一樣的尺寸
        val outW = rawW
        val outH = rawH

        // ── Encoder ──
        val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, br)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encSurface = encoder.createInputSurface()
        encoder.start()

        // ── EGL ──
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2); EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        val cfgAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numCfg = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, configs, 0, 1, numCfg, 0)
        val eglCtx = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encSurface,
            intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurf, eglSurf, eglCtx)

        // ── GL Programs ──
        val progExt = makeProgram(VS, FS_EXT)
        val prog2D = makeProgram(VS, FS_2D)

        // ── OES Texture (解碼器輸出) ──
        val texIds = IntArray(2); GLES20.glGenTextures(2, texIds, 0)
        val oesTex = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(rawW, rawH)
        st.setOnFrameAvailableListener { synchronized(syncObj) { frameAvailable = true; syncObj.notifyAll() } }
        val decSurface = Surface(st)

        // ── 浮水印 Texture ──
        val wmTex = texIds[1]
        val wmBmp = makeWatermarkBitmap(rawW, rawH, lines, rotation)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wmTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, wmBmp, 0)
        wmBmp.recycle()

        // ── Decoder ──
        val decoder = MediaCodec.createDecoderByType(vidFmt.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(vidFmt, decSurface, null, 0)
        decoder.start()

        // ── Muxer（設定 orientationHint 讓播放器處理旋轉）──
        val outFile = File(context.cacheDir, "wm_${System.currentTimeMillis()}.mp4")
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) muxer.setOrientationHint(rotation)
        var muxVidTrk = -1; var muxAudTrk = -1; var muxStarted = false

        extractor.selectTrack(vidIdx)
        var extractDone = false; var decDone = false; var encDone = false
        val bi = MediaCodec.BufferInfo()

        // ── 主處理迴圈 ──
        while (!encDone) {
            // 1) 餵解碼器
            if (!extractDone) {
                val idx = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val buf = decoder.getInputBuffer(idx)!!
                    val sz = extractor.readSampleData(buf, 0)
                    if (sz < 0) {
                        decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractDone = true
                    } else {
                        decoder.queueInputBuffer(idx, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // 2) 從解碼器取出畫面 → OpenGL 渲染（影片 + 浮水印）→ 送進編碼器
            if (!decDone) {
                val idx = decoder.dequeueOutputBuffer(bi, TIMEOUT_US)
                if (idx >= 0) {
                    val eos = (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (eos) decDone = true
                    val pts = bi.presentationTimeUs
                    decoder.releaseOutputBuffer(idx, bi.size > 0)

                    if (bi.size > 0) {
                        synchronized(syncObj) {
                            while (!frameAvailable) syncObj.wait(5000)
                            frameAvailable = false
                        }
                        st.updateTexImage()

                        val stMatrix = FloatArray(16)
                        st.getTransformMatrix(stMatrix)

                        GLES20.glViewport(0, 0, outW, outH)
                        GLES20.glClearColor(0f, 0f, 0f, 1f)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                        // 畫影片幀（直接用 SurfaceTexture 變換，不做額外旋轉）
                        drawQuad(progExt, oesTex, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stMatrix, quadBuf)

                        // 畫浮水印（用 Y 翻轉的貼圖座標，修正 Bitmap 與 GL 原點差異）
                        GLES20.glEnable(GLES20.GL_BLEND)
                        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                        drawQuad(prog2D, wmTex, GLES20.GL_TEXTURE_2D, identityMatrix, wmQuadBuf)
                        GLES20.glDisable(GLES20.GL_BLEND)

                        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurf, pts * 1000)
                        EGL14.eglSwapBuffers(eglDisplay, eglSurf)
                    }
                    if (eos) encoder.signalEndOfInputStream()
                }
            }

            // 3) 從編碼器取出壓縮後的資料 → 寫入 Muxer
            val idx = encoder.dequeueOutputBuffer(bi, TIMEOUT_US)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxVidTrk = muxer.addTrack(encoder.outputFormat)
                if (audFmt != null) muxAudTrk = muxer.addTrack(audFmt)
                muxer.start(); muxStarted = true
            } else if (idx >= 0) {
                val buf = encoder.getOutputBuffer(idx)!!
                if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bi.size = 0
                if (bi.size > 0 && muxStarted) {
                    buf.position(bi.offset); buf.limit(bi.offset + bi.size)
                    muxer.writeSampleData(muxVidTrk, buf, bi)
                }
                encoder.releaseOutputBuffer(idx, false)
                if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encDone = true
            }
        }

        // ── 音軌直接複製 ──
        if (audIdx >= 0 && muxAudTrk >= 0 && muxStarted) {
            extractor.unselectTrack(vidIdx)
            extractor.selectTrack(audIdx)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val abuf = ByteBuffer.allocate(256 * 1024)
            val abi = MediaCodec.BufferInfo()
            while (true) {
                abuf.clear()
                val sz = extractor.readSampleData(abuf, 0)
                if (sz < 0) break
                abi.set(0, sz, extractor.sampleTime,
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(muxAudTrk, abuf, abi)
                extractor.advance()
            }
        }

        // ── 釋放資源 ──
        decoder.stop(); decoder.release()
        encoder.stop(); encoder.release()
        if (muxStarted) { muxer.stop(); muxer.release() }
        decSurface.release(); st.release()
        GLES20.glDeleteTextures(2, texIds, 0)
        GLES20.glDeleteProgram(progExt); GLES20.glDeleteProgram(prog2D)
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurf)
        EGL14.eglDestroyContext(eglDisplay, eglCtx)
        EGL14.eglTerminate(eglDisplay)
        encSurface.release(); extractor.release()

        val uri = saveToMediaStore(outFile)
        outFile.delete()
        return uri
    }

    /** 用 MediaMetadataRetriever 讀取影片的 rotation metadata（比 MediaExtractor 可靠） */
    private fun readRotation(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "讀取 rotation 失敗，預設為 0", e)
            0
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    // ── GL 輔助方法 ──

    private fun makeProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs); GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val msg = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $msg")
        }
        return shader
    }

    private fun drawQuad(program: Int, texId: Int, texTarget: Int, matrix: FloatArray, buf: FloatBuffer) {
        GLES20.glUseProgram(program)
        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")

        GLES20.glUniformMatrix4fv(matLoc, 1, false, matrix, 0)

        buf.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, buf)

        buf.position(2)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, buf)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(texTarget, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    // ── 浮水印 Bitmap ──

    /**
     * 先在「正確顯示方向」（直式畫面）畫好浮水印，
     * 再用 Bitmap 旋轉回原始感測器方向，這樣跟影片疊合後，
     * 播放器套用 rotation metadata 時浮水印就會出現在正確位置。
     */
    private fun makeWatermarkBitmap(rawW: Int, rawH: Int, lines: List<String>, rotation: Int): Bitmap {
        // Step 1: 計算顯示方向的尺寸
        val dispW = if (rotation == 90 || rotation == 270) rawH else rawW
        val dispH = if (rotation == 90 || rotation == 270) rawW else rawH

        // Step 2: 在顯示方向畫浮水印（右下角）
        val displayBmp = Bitmap.createBitmap(dispW, dispH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(displayBmp)
        drawWatermarkOnCanvas(canvas, dispW, dispH, lines)

        // Step 3: 如果有旋轉，把浮水印 bitmap 轉回原始感測器方向
        if (rotation == 0) return displayBmp

        val matrix = android.graphics.Matrix()
        // 播放器會順時針轉 rotation 度，所以浮水印要先逆時針轉 rotation 度回去
        matrix.postRotate(-rotation.toFloat(), dispW / 2f, dispH / 2f)
        val rawBmp = Bitmap.createBitmap(displayBmp, 0, 0, dispW, dispH, matrix, true)
        displayBmp.recycle()

        // createBitmap 回傳的尺寸可能因旋轉而不同，縮放到精確的 rawW × rawH
        if (rawBmp.width != rawW || rawBmp.height != rawH) {
            val scaled = Bitmap.createScaledBitmap(rawBmp, rawW, rawH, true)
            rawBmp.recycle()
            return scaled
        }
        return rawBmp
    }

    private fun drawWatermarkOnCanvas(canvas: Canvas, w: Int, h: Int, lines: List<String>) {
        val textSize = w * 0.028f
        val padding = w * 0.02f
        val lineHeight = textSize * 1.3f

        val textPaint = Paint().apply {
            color = Color.WHITE
            this.textSize = textSize
            isAntiAlias = true
            setShadowLayer(6f, 2f, 2f, Color.BLACK)
            textAlign = Paint.Align.RIGHT
        }

        // 自動換行
        val allLines = mutableListOf<String>()
        for (line in lines) {
            val maxW = w - padding * 2
            if (textPaint.measureText(line) <= maxW) {
                allLines.add(line)
            } else {
                val sb = StringBuilder()
                for (ch in line) {
                    if (textPaint.measureText(sb.toString() + ch) > maxW && sb.isNotEmpty()) {
                        allLines.add(sb.toString()); sb.clear()
                    }
                    sb.append(ch)
                }
                if (sb.isNotEmpty()) allLines.add(sb.toString())
            }
        }

        val blockH = lineHeight * allLines.size
        val bgPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
        canvas.drawRect(0f, h - blockH - padding * 1.5f, w.toFloat(), h.toFloat(), bgPaint)

        var y = h - padding - (allLines.size - 1) * lineHeight
        for (line in allLines) {
            canvas.drawText(line, w - padding, y, textPaint)
            y += lineHeight
        }
    }

    // ── 存入相簿 ──

    fun saveToMediaStore(file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "GPS_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GpsCamera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }
}
