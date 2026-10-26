package app.filemanagerx.domain

/**
 * Case-insensitive "natural" ordering: digit runs compare by numeric value, so `file2` sorts
 * before `file10`.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val left = a[i]
            val right = b[j]
            if (left.isDigit() && right.isDigit()) {
                var endLeft = i
                while (endLeft < a.length && a[endLeft].isDigit()) endLeft++
                var endRight = j
                while (endRight < b.length && b[endRight].isDigit()) endRight++
                val numberLeft = a.substring(i, endLeft).trimStart('0')
                val numberRight = b.substring(j, endRight).trimStart('0')
                if (numberLeft.length != numberRight.length) {
                    return numberLeft.length.compareTo(numberRight.length)
                }
                val byDigits = numberLeft.compareTo(numberRight)
                if (byDigits != 0) return byDigits
                i = endLeft
                j = endRight
            } else {
                val byChar = left.lowercaseChar().compareTo(right.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
