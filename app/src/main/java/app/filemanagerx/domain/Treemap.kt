package app.filemanagerx.domain

data class TreemapRect(val x: Double, val y: Double, val width: Double, val height: Double)

/** Squarified treemap layout (Bruls, Huizing, van Wijk): tiles stay close to squares, so labels fit. */
object Treemap {
    /**
     * Lays [values] out inside a [width] x [height] box. Values must be positive and sorted from the
     * largest to the smallest; the result has one rectangle per value, in the same order, and their
     * areas are proportional to the values.
     */
    fun layout(values: List<Double>, width: Double, height: Double): List<TreemapRect> {
        require(width > 0.0 && height > 0.0) { "The box must have a positive size" }
        if (values.isEmpty()) return emptyList()
        require(values.all { it > 0.0 }) { "Values must be positive" }

        val total = values.sum()
        val areas = values.map { it / total * width * height }
        val result = ArrayList<TreemapRect>(values.size)

        var x = 0.0
        var y = 0.0
        var boxWidth = width
        var boxHeight = height
        var start = 0
        while (start < areas.size) {
            val side = minOf(boxWidth, boxHeight)
            var end = start + 1
            var best = worstRatio(areas, start, end, side)
            while (end < areas.size) {
                val next = worstRatio(areas, start, end + 1, side)
                if (next > best) break
                best = next
                end++
            }
            val rowArea = areas.subList(start, end).sum()
            if (boxWidth >= boxHeight) {
                // A column on the left edge, as tall as the box.
                val columnWidth = rowArea / boxHeight
                var top = y
                for (index in start until end) {
                    val tileHeight = areas[index] / columnWidth
                    result += TreemapRect(x, top, columnWidth, tileHeight)
                    top += tileHeight
                }
                x += columnWidth
                boxWidth -= columnWidth
            } else {
                // A row along the top edge, as wide as the box.
                val rowHeight = rowArea / boxWidth
                var left = x
                for (index in start until end) {
                    val tileWidth = areas[index] / rowHeight
                    result += TreemapRect(left, y, tileWidth, rowHeight)
                    left += tileWidth
                }
                y += rowHeight
                boxHeight -= rowHeight
            }
            start = end
        }
        return result
    }

    private fun worstRatio(areas: List<Double>, from: Int, to: Int, side: Double): Double {
        var sum = 0.0
        var largest = Double.MIN_VALUE
        var smallest = Double.MAX_VALUE
        for (index in from until to) {
            val area = areas[index]
            sum += area
            if (area > largest) largest = area
            if (area < smallest) smallest = area
        }
        val squared = side * side
        return maxOf(squared * largest / (sum * sum), sum * sum / (squared * smallest))
    }
}
