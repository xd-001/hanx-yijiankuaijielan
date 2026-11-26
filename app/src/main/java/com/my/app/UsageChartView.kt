package com.my.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 极简打开次数曲线：浅紫面积 + 紫色折线 + 底部多个时间刻度。
 * 不引入任何第三方图表库，保持 APK 体积和隐私边界不变。
 */
class UsageChartView(context: Context) : View(context) {

    private var values: List<Int> = emptyList()
    private var axisLabels: List<String> = emptyList()

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
        textSize = dp(9f)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C7C7CC")
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.ROUND
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** 跟随外观令牌：折线/圆点用强调色，面积用强调浅底，刻度与标签用三级文字 */
    fun setColors(accent: Int, soft: Int, label: Int) {
        linePaint.color = accent
        dotPaint.color = accent
        fillPaint.color = soft
        labelPaint.color = label
        tickPaint.color = label
        invalidate()
    }

    /** labels 从早到晚，至少 2 个；只画首、尾和中间若干个刻度 */
    fun setData(data: List<Int>, labels: List<String>) {
        values = data
        axisLabels = labels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft + dp(4f)
        val right = width - paddingRight - dp(4f)
        val top = paddingTop + dp(8f)
        val bottom = height - paddingBottom - dp(20f)
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

        // 点太多（比如 30 天）就不画圆点了，否则挤成一条实线
        if (values.size <= 20) {
            for (i in values.indices) {
                if (values[i] <= 0) continue
                val x = left + step * i
                val y = bottom - (values[i].toFloat() / maxValue) * (bottom - top)
                canvas.drawCircle(x, y, dp(2.4f), dotPaint)
            }
        }

        drawAxis(canvas, left, right, bottom)
    }

    /** 底部刻度：短竖线 + 日期，首个左对齐、末个右对齐，中间居中 */
    private fun drawAxis(canvas: Canvas, left: Float, right: Float, bottom: Float) {
        val labels = axisLabels
        if (labels.size < 2) return

        val span = right - left
        val textY = height - paddingBottom - dp(4f)
        for (i in labels.indices) {
            val x = left + span * i / (labels.size - 1)
            canvas.drawLine(x, bottom + dp(1f), x, bottom + dp(4f), tickPaint)

            labelPaint.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                labels.lastIndex -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(labels[i], x, textY, labelPaint)
        }
    }
}
