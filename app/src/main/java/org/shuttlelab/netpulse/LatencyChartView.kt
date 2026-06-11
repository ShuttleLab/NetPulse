package org.shuttlelab.netpulse

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * 轻量级延迟折线图：展示最近若干次检测的延迟波动。
 * 纯 Canvas 绘制，无第三方依赖，契合 App 的轻量定位。
 *
 * 数据按时间升序传入（最旧在左、最新在右）；ping <= 0 视为检测失败，按断线处理。
 * 阈值配色与列表保持一致：>1000ms 红、>500ms 黄，其余主题绿。
 */
class LatencyChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var data: List<Long> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = COLOR_LINE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LABEL
        textSize = sp(10f)
    }

    /** 传入按时间升序排列的延迟序列（毫秒），失败用 <= 0 表示。 */
    fun setData(samples: List<Long>) {
        data = samples
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = data.size
        val padTop = dp(12f)
        val padBottom = dp(6f)
        val padLeft = dp(4f)
        val padRight = dp(4f)
        val plotW = width - padLeft - padRight
        val plotH = height - padTop - padBottom
        val bottomY = padTop + plotH
        if (plotW <= 0f || plotH <= 0f) return

        val valid = data.filter { it > 0 }
        if (valid.isEmpty()) return
        val peak = valid.max()
        // 纵轴量程：0 ~ 峰值留 15% 余量，保证波动可见、绝对量级直观
        val maxV = max(peak.toFloat() * 1.15f, 1f)

        fun xAt(i: Int): Float =
            if (n <= 1) padLeft + plotW / 2f
            else padLeft + plotW * i / (n - 1).toFloat()

        fun yAt(v: Long): Float =
            padTop + plotH * (1f - (v.toFloat() / maxV)).coerceIn(0f, 1f)

        // 阈值参考线（仅在量程内才绘制）
        drawThreshold(canvas, 500f, maxV, COLOR_WARN, padLeft, plotW, padTop, plotH)
        drawThreshold(canvas, 1000f, maxV, COLOR_BAD, padLeft, plotW, padTop, plotH)

        // 折线 + 渐隐填充（遇失败点断段）
        val line = Path()
        val fill = Path()
        var started = false
        var segFirstX = 0f
        var segLastX = 0f
        for (i in 0 until n) {
            val v = data[i]
            if (v <= 0) {
                if (started) closeFill(fill, segFirstX, segLastX, bottomY)
                started = false
                continue
            }
            val px = xAt(i)
            val py = yAt(v)
            if (!started) {
                line.moveTo(px, py)
                fill.moveTo(px, bottomY)
                fill.lineTo(px, py)
                segFirstX = px
                started = true
            } else {
                line.lineTo(px, py)
                fill.lineTo(px, py)
            }
            segLastX = px
        }
        if (started) closeFill(fill, segFirstX, segLastX, bottomY)

        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)

        // 数据点：按阈值着色
        for (i in 0 until n) {
            val v = data[i]
            if (v <= 0) continue
            dotPaint.color = colorFor(v)
            canvas.drawCircle(xAt(i), yAt(v), dp(2.5f), dotPaint)
        }

        // 峰值标签（语言中立，仅数字）
        canvas.drawText("${peak}ms", padLeft, labelPaint.textSize, labelPaint)
    }

    private fun drawThreshold(
        canvas: Canvas,
        value: Float,
        maxV: Float,
        color: Int,
        padLeft: Float,
        plotW: Float,
        padTop: Float,
        plotH: Float,
    ) {
        if (value > maxV) return
        val y = padTop + plotH * (1f - value / maxV)
        gridPaint.color = color
        gridPaint.alpha = 80
        canvas.drawLine(padLeft, y, padLeft + plotW, y, gridPaint)
    }

    private fun closeFill(fill: Path, firstX: Float, lastX: Float, bottomY: Float) {
        fill.lineTo(lastX, bottomY)
        fill.lineTo(firstX, bottomY)
        fill.close()
    }

    private fun colorFor(v: Long): Int = when {
        v > 1000 -> COLOR_BAD
        v > 500 -> COLOR_WARN
        else -> COLOR_LINE
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    companion object {
        private const val COLOR_LINE = 0xFF00E676.toInt() // 主题绿
        private const val COLOR_FILL = 0x2200E676         // 绿色 ~13% 透明填充
        private const val COLOR_WARN = 0xFFFFD600.toInt() // 黄：>500ms
        private const val COLOR_BAD = 0xFFFF5252.toInt()  // 红：>1000ms
        private const val COLOR_LABEL = 0xFF888888.toInt()
    }
}
