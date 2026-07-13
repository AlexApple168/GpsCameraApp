package com.xiaoriyue.gpscamera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraSelector
import kotlin.math.atan

/**
 * 用 Camera2 特徵值列舉裝置實際擁有的鏡頭，並用「視角大小」「最近對焦距離」
 * 這類特徵粗略分類成 標準／廣角／微距／前置。
 *
 * 說明：Android 並沒有官方欄位直接標示「這顆是廣角鏡頭」或「這顆是微距鏡頭」，
 * 這裡是用視角(FOV)、最近對焦距離做推測分類，多數手機準確，但無法保證每一支機型都完全正確。
 */
object CameraLensHelper {

    data class LensOption(val id: String, val label: String)

    private data class LensInfo(
        val id: String,
        val facing: Int,
        val fovDeg: Double?,
        val minFocusDiopter: Float
    )

    fun detectLensOptions(context: Context): List<LensOption> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()

        val backCams = mutableListOf<LensInfo>()
        val frontCams = mutableListOf<LensInfo>()

        try {
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

                val focal = focalLengths?.minOrNull()
                val fovDeg = if (focal != null && focal > 0f && sensorSize != null) {
                    Math.toDegrees(2 * atan((sensorSize.width / (2 * focal)).toDouble()))
                } else null

                val info = LensInfo(id, facing, fovDeg, minFocus)
                when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> backCams.add(info)
                    CameraCharacteristics.LENS_FACING_FRONT -> frontCams.add(info)
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val options = mutableListOf<LensOption>()

        // 標準：以 id 數字最小者當作主鏡頭（絕大多數裝置慣例上 id "0" 是主鏡頭）
        val standard = backCams.minByOrNull { it.id.toIntOrNull() ?: Int.MAX_VALUE }
        if (standard != null) {
            options.add(LensOption(standard.id, "標準"))
        }

        val others = backCams.filter { it.id != standard?.id }

        // 廣角：視角明顯比標準鏡頭大的鏡頭
        val wide = others.filter { it.fovDeg != null }
            .maxByOrNull { it.fovDeg!! }
        val standardFov = standard?.fovDeg ?: 0.0
        if (wide != null && (wide.fovDeg ?: 0.0) > standardFov + 10.0) {
            options.add(LensOption(wide.id, "廣角"))
        }

        // 微距：扣掉廣角後，最近對焦距離特別短（diopter 值特別高）的鏡頭
        val macroCandidates = others.filter { it.id != wide?.id }
        val macro = macroCandidates.maxByOrNull { it.minFocusDiopter }
        if (macro != null && macro.minFocusDiopter >= 8f) {
            options.add(LensOption(macro.id, "微距"))
        }

        // 前置：取 id 數字最小的前鏡頭
        val front = frontCams.minByOrNull { it.id.toIntOrNull() ?: Int.MAX_VALUE }
        if (front != null) {
            options.add(LensOption(front.id, "前置"))
        }

        return options
    }

    /** 依指定的 Camera2 camera id 建立對應的 CameraSelector */
    @OptIn(ExperimentalCamera2Interop::class)
    fun selectorForCameraId(cameraId: String): CameraSelector {
        val filter = CameraFilter { cameraInfos ->
            cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
        }
        return CameraSelector.Builder().addCameraFilter(filter).build()
    }
}
