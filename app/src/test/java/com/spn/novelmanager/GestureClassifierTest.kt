package com.spn.novelmanager

import com.spn.novelmanager.domain.GestureClassifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureClassifierTest {

    @Test
    fun `未超过移动阈值的按下抬起是点按`() {
        val classifier = GestureClassifier(touchSlop = 10f)
        classifier.onDown(100f, 100f)
        assertFalse(classifier.onMove(100f, 104f))
        assertTrue(classifier.onUp())
    }

    @Test
    fun `移动超过阈值后判定为拖动并不再产生点按`() {
        val classifier = GestureClassifier(touchSlop = 10f)
        classifier.onDown(100f, 100f)
        assertFalse(classifier.onMove(100f, 106f))
        assertTrue(classifier.onMove(100f, 130f))
        assertTrue(classifier.isDragging)
        assertFalse("拖动抬起不应被当作点按", classifier.onUp())
    }

    @Test
    fun `一次拖动后状态复位可以继续识别下一次点按`() {
        val classifier = GestureClassifier(touchSlop = 10f)
        classifier.onDown(0f, 0f)
        assertTrue(classifier.onMove(0f, 40f))
        assertFalse(classifier.onUp())

        classifier.onDown(0f, 0f)
        assertFalse(classifier.onMove(0f, 2f))
        assertTrue(classifier.onUp())
    }

    @Test
    fun `取消后不再产生点按`() {
        val classifier = GestureClassifier(touchSlop = 10f)
        classifier.onDown(0f, 0f)
        classifier.onCancel()
        assertFalse(classifier.onUp())
        assertFalse(classifier.isDragging)
    }
}
