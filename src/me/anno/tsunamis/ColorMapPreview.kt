package me.anno.tsunamis

import me.anno.config.DefaultStyle.black
import me.anno.gpu.drawing.DrawRectangles.drawRect
import me.anno.gpu.drawing.DrawTexts.drawSimpleTextCharByChar
import me.anno.gpu.drawing.DrawTexts.monospaceFont
import me.anno.maths.Maths.clamp
import me.anno.maths.Maths.mix
import me.anno.tsunamis.FluidSimMod.Companion.linColorMap
import me.anno.tsunamis.io.ColorMap
import me.anno.ui.Panel
import me.anno.ui.base.constraints.AxisAlignment
import me.anno.ui.style.Style
import kotlin.math.max
import kotlin.math.min

class ColorMapPreview(val sim: FluidSim, style: Style) : Panel(style) {

    private val stripeW = 20
    private val maxW = stripeW + 100
    private val maxH = 250

    private var padding = 5

    override fun calculateSize(w: Int, h: Int) {
        minW = maxW
        minH = maxH
        this.w = minW
        this.h = minH
    }

    override fun onDraw(x0: Int, y0: Int, x1: Int, y1: Int) {
        drawBackground(x0, y0, x1, y1)
        val font = monospaceFont.value
        val xs = x + padding
        val ys = y + padding + font.sampleHeight / 2
        val ye = y + h - padding - font.sampleHeight / 2
        // draw colors
        var colorMap: ColorMap? = null
        if (sim.visualization == Visualisation.HEIGHT_MAP) {
            colorMap = ColorMap.read(sim.colorMap, true)
        }
        var maxValue = sim.maxVisualizedValue
        var minValue = -maxValue
        // border in black
        drawRect(xs - 1, ys - 1, stripeW + 2, ye - ys + 2, black)
        if (colorMap != null) {
            maxValue = colorMap.max * sim.colorMapScale
            minValue = colorMap.min * sim.colorMapScale
            for (y in max(y0, ys) until min(y1, ye)) {
                val v = (y - ys).toFloat() / (ye - ys).toFloat()
                val color = colorMap.getColor(mix(colorMap.max, colorMap.min, v))
                drawRect(xs, y, stripeW, 1, color)
            }
        } else {
            val lcm = linColorMap
            for (y in max(y0, ys) until min(y1, ye)) {
                val v = 1f - (y - ys) * 2f / (ye - ys)
                val color = lcm.getColor(v)
                drawRect(xs, y, stripeW, 1, color)
            }
        }
        // draw numbers
        val xe = xs + stripeW + padding // where the text starts
        val offset = -font.sampleHeight / 2 // center the text vertically on top / bottom
        val numNumbers = clamp((ye - ys) / (2 * font.sampleHeight), 2, 5)
        // todo make all numbers the same length, align their commas
        // round value to a reasonable number of digits
        // find whether we need an exponent
        // val absMax = max(abs(minValue), abs(maxValue))
        // val needsExponent = absMax < 1e-2f || absMax > 1e7f
        // val digits = if (needsExponent) 1 else floor(log10(absMax)).toInt() + 1
        // val commaDigits = 7 - digits
        for (i in 0 until numNumbers) {
            val v = i / (numNumbers - 1f)
            val text = mix(maxValue, minValue, v).toString()
            val y = mix(ys - 1, ye, v)
            drawSimpleTextCharByChar(xe, y + offset, 0, text, AxisAlignment.MIN)
            // draw a small stripe to show where exactly the number belongs to
            drawRect(xe - padding, y, padding / 2, 1, black)
        }
    }
}