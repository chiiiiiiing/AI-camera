package com.example.aicamera.overlay

import android.graphics.PointF
import android.graphics.RectF

/**
 * 坐标映射器 — 归一化坐标 → View 像素坐标。
 *
 * ## 核心问题
 * 模型输出的坐标是归一化的（0.0–1.0），需要映射到 Overlay View 的实际像素。
 *
 * ## 前置摄像头镜像
 * CameraX 预览自动镜像前置摄像头画面，但 ImageAnalysis 的帧**不自动镜像**。
 * 因此：前置摄像头时，需要用 (1.0 - x) 翻转 X 坐标。
 *
 * ## 旋转
 * `imageProxy.imageInfo.rotationDegrees` 告知当前帧的旋转角度，
 * 坐标映射时需要根据旋转角度做相应的变换。
 */
object CoordinateMapper {

    /**
     * 归一化坐标 → View 像素坐标。
     *
     * @param normX 归一化 X（0.0–1.0）
     * @param normY 归一化 Y（0.0–1.0）
     * @param viewWidth Overlay View 宽度（px）
     * @param viewHeight Overlay View 高度（px）
     * @param isFrontCamera 是否前置摄像头（true 则翻转 X）
     * @param rotationDegrees 帧旋转角度
     */
    fun mapPoint(
        normX: Float,
        normY: Float,
        viewWidth: Int,
        viewHeight: Int,
        isFrontCamera: Boolean = false,
        rotationDegrees: Int = 0
    ): PointF {
        val x = if (isFrontCamera) {
            (1.0f - normX) * viewWidth
        } else {
            normX * viewWidth
        }
        val y = normY * viewHeight

        return applyRotation(PointF(x, y), viewWidth, viewHeight, rotationDegrees)
    }

    /**
     * 归一化矩形 → View 像素矩形。
     * ★ 支持 rotationDegrees：通过映射四个角点后取 bounding box。
     */
    fun mapRect(
        normRect: RectF,
        viewWidth: Int,
        viewHeight: Int,
        isFrontCamera: Boolean = false,
        rotationDegrees: Int = 0
    ): RectF {
        if (rotationDegrees == 0 && !isFrontCamera) {
            // 快速路径：无需变换
            return RectF(
                normRect.left * viewWidth,
                normRect.top * viewHeight,
                normRect.right * viewWidth,
                normRect.bottom * viewHeight
            )
        }

        // 映射四个角点后取 bounding box
        val p1 = mapPoint(normRect.left, normRect.top, viewWidth, viewHeight, isFrontCamera, rotationDegrees)
        val p2 = mapPoint(normRect.right, normRect.top, viewWidth, viewHeight, isFrontCamera, rotationDegrees)
        val p3 = mapPoint(normRect.right, normRect.bottom, viewWidth, viewHeight, isFrontCamera, rotationDegrees)
        val p4 = mapPoint(normRect.left, normRect.bottom, viewWidth, viewHeight, isFrontCamera, rotationDegrees)

        return RectF(
            minOf(p1.x, p2.x, p3.x, p4.x),
            minOf(p1.y, p2.y, p3.y, p4.y),
            maxOf(p1.x, p2.x, p3.x, p4.x),
            maxOf(p1.y, p2.y, p3.y, p4.y)
        )
    }

    /**
     * 归一化尺寸 → View 像素尺寸。
     */
    fun mapSize(
        normWidth: Float,
        normHeight: Float,
        viewWidth: Int,
        viewHeight: Int
    ): Pair<Float, Float> {
        return Pair(normWidth * viewWidth, normHeight * viewHeight)
    }

    /**
     * 归一化半径 → View 像素半径。
     * 取 View 宽高中的较小者作为基准。
     */
    fun mapRadius(normRadius: Float, viewWidth: Int, viewHeight: Int): Float {
        val base = minOf(viewWidth, viewHeight).toFloat()
        return normRadius * base
    }

    /**
     * 按旋转角度对坐标做变换。
     *
     * 大多数情况下 rotationDegrees 为 0 或 90。
     * CameraX 的 imageProxy.rotationDegrees 值域：0 / 90 / 180 / 270。
     */
    private fun applyRotation(
        point: PointF,
        viewWidth: Int,
        viewHeight: Int,
        rotationDegrees: Int
    ): PointF {
        return when (rotationDegrees) {
            0 -> point
            90 -> {
                // 旋转 90°：x' = h - y, y' = x
                PointF(viewHeight - point.y, point.x)
            }
            180 -> {
                // 旋转 180°：x' = w - x, y' = h - y
                PointF(viewWidth - point.x, viewHeight - point.y)
            }
            270 -> {
                // 旋转 270°：x' = y, y' = w - x
                PointF(point.y, viewWidth - point.x)
            }
            else -> point
        }
    }

    /**
     * 计算两点之间的距离（像素）。
     */
    fun distance(a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * 计算两点之间的角度（弧度）。
     */
    fun angle(a: PointF, b: PointF): Float {
        return kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()).toFloat()
    }
}
