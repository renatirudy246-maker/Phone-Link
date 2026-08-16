package com.phonelink.app.scanner.feedback

/**
 * 本地扫描反馈数据集（Phase 4B-D2）配置常量。
 * 所有采集决策阈值集中在此，禁止散落 hard-code。
 */
object ScannerFeedbackConfig {

    /** 元数据 schema 版本。 */
    const val SCHEMA_VERSION = 1

    /** 当前检测模型标识（与 docquad/docquadnet256_trained_opset17.ort 对应）。 */
    const val MODEL_NAME = "DocQuadNet-256"

    /** 模型文件 SHA-256（assets/docquad/docquadnet256_trained_opset17.ort 实测值）。 */
    const val MODEL_SHA256 = "aaef348eb81709d26f7e8974401795b141d70ba88bc69792c779fbae102eadaa"

    /** USER_CORRECTED 判定阈值：四角最大误差 ≥ 0.3% 归一化对角线。 */
    const val USER_CORRECTED_MIN_MAX_DELTA = 0.003f

    /** 判定"该角被用户调整"的最小误差（浮点噪声容忍）。 */
    const val CORNER_ADJUSTED_EPSILON = 0.0001f

    /** CLEAN_SUCCESS 确定性采样率（%）：hash(sampleId) % 100 < 该值。 */
    const val CLEAN_SUCCESS_SAMPLE_RATE_PERCENT = 5

    /** Pending 反馈队列上限：样本数。 */
    const val MAX_PENDING_SAMPLES = 100

    /** Pending 反馈队列上限：总字节数。 */
    const val MAX_PENDING_BYTES = 500L * 1024 * 1024

    /** GT 标签来源：用户确认四边形（pseudo/manual GT，不是绝对真理）。 */
    const val LABEL_SOURCE = "user_confirmed_quad"
}