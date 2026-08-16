package com.phonelink.app.scanner

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * 文档检测门面（DocumentDetector）：
 *
 * 架构：
 * 1. 底座：MakeACopy DocQuadNet-256 语义分割与四角热图检测引擎 (Apache 2.0)；
 * 2. 门控：5 维 DocumentPresence / Quality Gate 评估；
 * 3. 精修：原图高分辨率 Sobel 梯度带 Huber 边缘拟合；
 * 4. 容错：优雅降级至 LowConfidence / NotFound 手动调整。
 */
object DocumentDetector {

    private const val TAG = "DocumentDetector"

    @Volatile
    private var engine: DocumentDetectionEngine? = null

    @Volatile
    var lastTimings: DetectionTimings = DetectionTimings()
        private set

    @Volatile
    var lastResult: DocumentDetectionResult? = null
        private set

    /**
     * 初始化检测引擎单例。
     */
    fun init(context: Context) {
        if (engine == null) {
            synchronized(this) {
                if (engine == null) {
                    engine = DocQuadNetDetectionEngine(context.applicationContext)
                    Log.i(TAG, "DocumentDetector initialized with DocQuadNetDetectionEngine")
                }
            }
        }
    }

    /**
     * 拍摄后高质量文档检测（协程后台执行）。
     */
    suspend fun detectHighQuality(context: Context, bitmap: Bitmap): DocumentDetectionResult {
        init(context)
        val currentEngine = engine ?: DocQuadNetDetectionEngine(context.applicationContext).also { engine = it }
        val result = currentEngine.detect(bitmap)
        lastResult = result
        lastTimings = result.timings
        Log.i(
            TAG,
            "detectHighQuality complete: status=${result::class.simpleName}, " +
                    "total=${result.timings.totalMs}ms " +
                    "(prep=${result.timings.preprocessMs}ms, " +
                    "infer=${result.timings.inferenceMs}ms, " +
                    "post=${result.timings.postprocessMs}ms, " +
                    "gate=${result.timings.qualityGateMs}ms, " +
                    "refine=${result.timings.edgeRefineMs}ms)"
        )
        return result
    }

    /**
     * 实时检测（兼容保留）。
     */
    fun detectLive(bitmap: Bitmap): DocumentDetectionResult {
        // 当前实时检测暂关，直接返回 NotFound 以保持相机取景流畅
        return DocumentDetectionResult.NotFound()
    }
}