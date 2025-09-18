package Agent_kt

import java.util.regex.Pattern

/**
 * 工具类，提供各种辅助功能
 */
class Utils {
    companion object {
        /**
         * 从字符串边界信息中提取整数坐标
         * @param stringBounds 包含边界信息的字符串，格式如"[x1,y1][x2,y2]"
         * @return 包含四个坐标值的整数数组 [x1, y1, x2, y2]
         */
        fun getBoundsInt(stringBounds: String): IntArray {
            val bounds = IntArray(4)
            // 定义正则表达式模式来查找括号内的整数值
            val pattern = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
            val matcher = pattern.matcher(stringBounds)

            if (matcher.matches()) {
                // 从匹配的组中提取整数值
                bounds[0] = matcher.group(1)!!.toInt()
                bounds[1] = matcher.group(2)!!.toInt()
                bounds[2] = matcher.group(3)!!.toInt()
                bounds[3] = matcher.group(4)!!.toInt()
            } else {
                println("Invalid input format.")
            }
            return bounds
        }
    }
}