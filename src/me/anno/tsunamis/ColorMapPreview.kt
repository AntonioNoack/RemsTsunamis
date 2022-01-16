package me.anno.tsunamis

import me.anno.config.DefaultStyle.black
import me.anno.gpu.drawing.DrawRectangles.drawRect
import me.anno.gpu.drawing.DrawTexts.drawSimpleTextCharByChar
import me.anno.gpu.drawing.DrawTexts.monospaceFont
import me.anno.image.colormap.LinearColorMap
import me.anno.maths.Maths
import me.anno.maths.Maths.clamp
import me.anno.maths.Maths.mix
import me.anno.tsunamis.io.ColorMap
import me.anno.ui.base.Panel
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
        drawBackground()
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
            maxValue = colorMap.max
            minValue = colorMap.min
            for (y in max(y0, ys) until min(y1, ye)) {
                val v = (y - ys).toFloat() / (ye - ys).toFloat()
                val color = colorMap.getColor(mix(colorMap.max, colorMap.min, v))
                drawRect(xs, y, stripeW, 1, color)
            }
        } else {
            for (y in max(y0, ys) until min(y1, ye)) {
                val v = 1f - (y - ys) * 2f / (ye - ys)
                val color = linColorMap.getColor(v)
                drawRect(xs, y, stripeW, 1, color)
            }
        }
        // draw numbers
        val xe = xs + stripeW + padding // where the text starts
        val offset = -font.sampleHeight / 2 // center the text vertically on top / bottom
        val numNumbers = clamp((ye - ys) / (2 * font.sampleHeight), 2, 5)
        for (i in 0 until numNumbers) {
            val v = i / (numNumbers - 1f)
            // todo round value to a reasonable number of digits
            // todo make all numbers the same length (?), align their commas
            var text = mix(maxValue, minValue, v).toString()
            if (text.endsWith(".0")) text = text.substring(0, text.length - 2)
            if (!text.startsWith('-')) text = "+$text"
            val y = mix(ys-1, ye, v)
            drawSimpleTextCharByChar(xe, y + offset, 0, text, AxisAlignment.MIN)
            // draw a small stripe to show where exactly the number belongs to
            drawRect(xe - padding, y, padding / 2, 1, black)
        }
    }

    companion object {

        // todo we should centralize all indicator colors
        val linColorMap = LinearColorMap(0x0055ff or black, -1, 0xff0000 or black)
            .clone(-1f, 1f)

    }
}