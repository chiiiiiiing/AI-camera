package com.example.aicamera.pipeline

/**
 * 构图状态机 — 来自队友设计文档（05-构图引导交互）。
 *
 * ## 状态流转
 * ```
 * [NO_SUBJECT] ──检测到主体──▶ [SUBJECT_FOUND]
 *                                   │
 *                          用户调整构图
 *                                   │
 *                                   ▼
 *                             [ADJUSTING]
 *                                   │
 *                          构图达标 (连续 5 帧)
 *                                   │
 *                                   ▼
 *                            [GOOD_COMPOSITION]
 *                                   │
 *                          用户拍摄 / 提示
 *                                   │
 *                                   ▼
 *                             [CAPTURED]
 * ```
 *
 * ## 滞回机制
 * 需要连续 5 帧 "构图达标" 才进入 GOOD_COMPOSITION，
 * 避免单帧抖动造成 UI 闪烁。
 */
class CompositionStateMachine {

    /** ★ 连续达标帧数阈值 */
    private val goodFrameThreshold = 5

    /** 当前状态 */
    var currentState: CompositionState = CompositionState.NO_SUBJECT
        private set

    /** 连续达标帧计数器 */
    private var goodFrameCount = 0

    /** 最近一次状态变化 */
    var onStateChanged: ((CompositionState, CompositionState) -> Unit)? = null

    /**
     * 输入一帧的构图质量，更新状态机。
     *
     * @param quality 当前帧的构图质量评估
     * @return 更新后的状态
     */
    fun update(quality: CompositionQuality): CompositionState {
        val previousState = currentState

        currentState = when (previousState) {
            CompositionState.NO_SUBJECT -> {
                if (quality.hasSubject) {
                    goodFrameCount = 0
                    CompositionState.SUBJECT_FOUND
                } else {
                    CompositionState.NO_SUBJECT
                }
            }

            CompositionState.SUBJECT_FOUND -> {
                when {
                    !quality.hasSubject -> {
                        goodFrameCount = 0
                        CompositionState.NO_SUBJECT
                    }
                    quality.isGood -> {
                        goodFrameCount++
                        if (goodFrameCount >= goodFrameThreshold) {
                            CompositionState.GOOD_COMPOSITION
                        } else {
                            CompositionState.SUBJECT_FOUND
                        }
                    }
                    else -> {
                        goodFrameCount = 0
                        CompositionState.ADJUSTING
                    }
                }
            }

            CompositionState.ADJUSTING -> {
                when {
                    !quality.hasSubject -> {
                        goodFrameCount = 0
                        CompositionState.NO_SUBJECT
                    }
                    quality.isGood -> {
                        goodFrameCount++
                        if (goodFrameCount >= goodFrameThreshold) {
                            CompositionState.GOOD_COMPOSITION
                        } else {
                            CompositionState.ADJUSTING
                        }
                    }
                    else -> {
                        goodFrameCount = maxOf(0, goodFrameCount - 1)
                        CompositionState.ADJUSTING
                    }
                }
            }

            CompositionState.GOOD_COMPOSITION -> {
                when {
                    !quality.hasSubject -> {
                        goodFrameCount = 0
                        CompositionState.NO_SUBJECT
                    }
                    !quality.isGood -> {
                        goodFrameCount = maxOf(0, goodFrameCount - 1)
                        if (goodFrameCount < goodFrameThreshold) {
                            CompositionState.ADJUSTING
                        } else {
                            CompositionState.GOOD_COMPOSITION
                        }
                    }
                    else -> {
                        goodFrameCount = goodFrameThreshold // 保持满
                        CompositionState.GOOD_COMPOSITION
                    }
                }
            }

            CompositionState.CAPTURED -> {
                goodFrameCount = 0
                CompositionState.NO_SUBJECT
            }
        }

        if (previousState != currentState) {
            onStateChanged?.invoke(previousState, currentState)
        }

        return currentState
    }

    /** 拍照后标记 CAPTURED → 自动回到 NO_SUBJECT */
    fun markCaptured() {
        val previousState = currentState
        currentState = CompositionState.CAPTURED
        goodFrameCount = 0
        onStateChanged?.invoke(previousState, currentState)
        // 自动回弹到 NO_SUBJECT
        currentState = CompositionState.NO_SUBJECT
    }

    /** 重置状态机 */
    fun reset() {
        currentState = CompositionState.NO_SUBJECT
        goodFrameCount = 0
    }
}

// ── 枚举与数据类 ──────────────────────────────────────────────

enum class CompositionState {
    NO_SUBJECT,        // 未识别主体
    SUBJECT_FOUND,     // 已识别主体
    ADJUSTING,         // 正在调整
    GOOD_COMPOSITION,  // 构图较好
    CAPTURED           // 已拍摄
}

/**
 * 单帧构图质量评估。
 *
 * MVP 阶段从 FakeInference 结果中推断：
 * - hasSubject: subjectBox 存在
 * - isGood: confidence > 0.85 && 非水平仪严重倾斜
 */
data class CompositionQuality(
    val hasSubject: Boolean,
    val isGood: Boolean,
    val confidence: Float = 0f,
    val strategy: String = ""
)
