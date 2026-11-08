package com.spn.novelmanager.domain

import kotlin.math.sqrt

/**
 * 只判定「点按 / 拖动」的纯状态机，不消费任何触摸事件。
 *
 * 手势层在收到 DOWN/MOVE/UP 时调用它；一旦移动距离超过触摸阈值，
 * 就认定用户是在拖动，随后放弃单击、双击与长按，让滚动/惯性接管。
 */
class GestureClassifier(private val touchSlop: Float) {

    private var active = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f

    fun onDown(x: Float, y: Float) {
        active = true
        dragging = false
        downX = x
        downY = y
    }

    /** 返回当前手势是否已经判定为拖动。 */
    fun onMove(x: Float, y: Float): Boolean {
        if (!active) return false
        if (!dragging) {
            val dx = x - downX
            val dy = y - downY
            if (sqrt(dx * dx + dy * dy) > touchSlop) dragging = true
        }
        return dragging
    }

    val isDragging: Boolean get() = active && dragging

    /** 抬起：返回 true 表示整个手势始终是点按（从未被拖动过）。 */
    fun onUp(): Boolean {
        val tap = active && !dragging
        active = false
        dragging = false
        return tap
    }

    fun onCancel() {
        active = false
        dragging = false
    }
}
