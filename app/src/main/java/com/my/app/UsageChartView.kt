package com.my.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 极简打开次数曲线：浅紫面积 + 紫色折线 + 首尾时间标签。
 * 不引入任何第三方图表库，保持 APK 体积和隐私边界不变。
 */
class UsageChartView(context: Context) : View(context) {

    private var values: List<Int> = emptyList()
    private var leftLabel: String = ""
    private var rightLabel: String = ""

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EFECFE")
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6C5CE7")
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6C5CE7")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C7C7CC")
        textSize = dp(10f)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    fun setData(data: List<Int>, left: String, right: String) {
        values = data
        leftLabel = left
        rightLabel = right
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft + dp(4f)
        val right = width - paddingRight - dp(4f)
        val top = paddingTop + dp(8f)
        val bottom = height - paddingBottom - dp(18f)
        if (right <= left || bottom <= top) return

        val maxValue = maxOf(1, values.maxOrNull() ?: 1)
        val step = if (values.size > 1) (right - left) / (values.size - 1) else 0f
        val path = Path()
        val area = Path()
        var lastX = left
        var lastY = bottom

        for (i in values.indices) {
            val x = left + step * i
            val y = bottom - (values[i].toFloat() / maxValue) * (bottom - top)
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, bottom)
                area.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
            lastX = x
            lastY = y
        }
        area.lineTo(lastX, bottom)
        area.close()

        canvas.drawPath(area, fillPaint)
        canvas.drawPath(path, linePaint)

        for (i in values.indices) {
            if (values[i] <= 0) continue
            val x = left + step * i
            val y = bottom - (values[i].toFloat() / maxValue) * (bottom - top)
            canvas.drawCircle(x, y, dp(2.4f), dotPaint)
        }

        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(leftLabel, left, height - dp(3f), labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(rightLabel, right, height - dp(3f), labelPaint)
    }
}
