package com.tvmedia.openlist.data.model

/**
 * Natural-order string comparison: runs of digits compare by numeric value.
 *
 * A plain lexicographic sort puts `E10` before `E02` and `10` before `2`, which is exactly what
 * breaks episode ordering inside a media folder. Comparison is case-insensitive.
 */
internal object NaturalOrder : Comparator<String> {

    override fun compare(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val leftChar = left[i]
            val rightChar = right[j]
            if (leftChar.isDigit() && rightChar.isDigit()) {
                val leftEnd = digitRunEnd(left, i)
                val rightEnd = digitRunEnd(right, j)
                val leftDigits = left.substring(i, leftEnd).trimStart('0')
                val rightDigits = right.substring(j, rightEnd).trimStart('0')
                // Leading zeros are already stripped, so a longer run means a larger value.
                if (leftDigits.length != rightDigits.length) return leftDigits.length - rightDigits.length
                val byValue = leftDigits.compareTo(rightDigits)
                if (byValue != 0) return byValue
                i = leftEnd
                j = rightEnd
            } else {
                val byChar = leftChar.lowercaseChar().compareTo(rightChar.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (left.length - i) - (right.length - j)
    }

    private fun digitRunEnd(value: String, from: Int): Int {
        var index = from
        while (index < value.length && value[index].isDigit()) index++
        return index
    }
}
