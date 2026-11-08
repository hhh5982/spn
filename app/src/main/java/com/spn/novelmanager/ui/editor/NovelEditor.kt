package com.spn.novelmanager.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.TextKeyListener
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LineHeightSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spn.novelmanager.domain.GestureClassifier
import com.spn.novelmanager.domain.InlineNode
import com.spn.novelmanager.domain.InlineParser
import com.spn.novelmanager.model.AppSettings
import com.spn.novelmanager.model.FlipMode
import com.spn.novelmanager.ui.theme.ReadingColors
import com.spn.novelmanager.ui.theme.readerTextColor
import com.spn.novelmanager.viewmodel.ReaderSession

private class TouchState {
    var downX: Float = 0f
    var contentY: Float = 0f
    var viewportY: Float = 0f
    var lastDownTime: Long = Long.MIN_VALUE
    var dragCancelSent: Boolean = false
}

/** 待执行的滚动跳转。-1 表示没有请求。 */
private class ScrollRequest {
    /** 目标位置按「显示文本中的字符偏移」保存，布局变化后仍可重新换算成像素。 */
    var offset: Int = -1
    /** 在该时刻之前持续校正（图片/字号/键盘导致的高度变化会让像素位置漂移）。 */
    var settleUntil: Long = 0L
}

private class NovelEditText(context: Context) : EditText(context) {
    var selectionListener: ((Int, Int) -> Unit)? = null
    var caretColor: Int = 0xFF000000.toInt()
    var caretEnabled: Boolean = false

    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val caretWidthPx = (2f * resources.displayMetrics.density).coerceAtLeast(2f)
    private var caretOn = true
    private val caretBlink = object : Runnable {
        override fun run() {
            caretOn = !caretOn
            invalidate()
            postDelayed(this, 500L)
        }
    }

    /** 正文永远不消费触摸：滚动交给 ScrollView，点按/长按/双击交给观察式手势层。 */
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        removeCallbacks(caretBlink)
        caretOn = true
        if (gainFocus) postDelayed(caretBlink, 500L)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(caretBlink)
        super.onDetachedFromWindow()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // 光标移动/输入后立刻点亮，避免刚好落在闪烁的暗相看起来像没光标。
        caretOn = true
        invalidate()
        selectionListener?.invoke(selStart, selEnd)
    }

    /**
     * 系统 EditText 光标在本项目环境下不会绘制（focus/visible 均为 true 也看不到），
     * 这里在正文层自绘一根 2dp 竖线，保证编辑态光标肉眼可见。
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawImagePreviews(canvas)
        if (!caretEnabled || !isFocused() || !caretOn) return
        if (selectionStart != selectionEnd) return
        val layout = layout ?: return
        val offset = selectionStart.coerceIn(0, text?.length ?: 0)
        val line = layout.getLineForOffset(offset)
        val x = totalPaddingLeft + layout.getPrimaryHorizontal(offset)
        val top = totalPaddingTop + layout.getLineTop(line)
        val bottom = totalPaddingTop + layout.getLineBottom(line)
        if (x < 0f || x > width.toFloat()) return
        caretPaint.color = caretColor
        canvas.drawRect(x, top.toFloat(), x + caretWidthPx, bottom.toFloat(), caretPaint)
    }

    /** 编辑态插图预览：画在标记行上方由 [ImagePreviewSpan] 撑开的空白里。 */
    private fun drawImagePreviews(canvas: Canvas) {
        val layout = layout ?: return
        val content = text ?: return
        val spans = content.getSpans(0, content.length, ImagePreviewSpan::class.java)
        if (spans.isEmpty()) return
        for (span in spans) {
            val start = content.getSpanStart(span)
            if (start < 0) continue
            val line = layout.getLineForOffset(start)
            val lineTop = (totalPaddingTop + layout.getLineTop(line)).toFloat()
            val left = totalPaddingLeft.toFloat()
            val top = lineTop + span.gapPx
            canvas.drawBitmap(
                span.bitmap,
                null,
                RectF(left, top, left + span.widthPx, top + span.heightPx),
                previewPaint
            )
        }
    }
}

/**
 * 纵向滚动容器：SCROLL 模式走原生滚动 + 惯性；
 * PAGE 模式只消费触摸不滚动，整页翻动由手势层按点击区域驱动。
 */
private class PageScrollView(context: Context) : ScrollView(context) {
    var pageMode: Boolean = false

    private fun maxScrollInternal(): Int {
        val child = getChildAt(0) ?: return 0
        return (child.measuredHeight - height).coerceAtLeast(0)
    }

    fun turnPage(forward: Boolean) {
        if (maxScrollInternal() <= 0) return
        val page = height.coerceAtLeast(1)
        val target = if (forward) scrollY + page else scrollY - page
        smoothScrollTo(0, target.coerceIn(0, maxScrollInternal()))
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // 整页翻动模式不允许拖动滚动，仅保留事件流给手势层判定点按。
        if (pageMode) return true
        return super.onTouchEvent(ev)
    }
}

/**
 * 阅读 / 编辑一体化正文区。
 *
 * 手势层只观察、不消费事件：`setOnTouchListener` 永远返回 false，
 * 滚动与惯性完全交给 ScrollView。手指移动超过阈值（判定为拖动）时，
 * 向 GestureDetector 发送 ACTION_CANCEL，彻底放弃单击/双击/长按，
 * 保证滚动与点按不会互相抢事件。
 */
@Composable
fun NovelEditor(
    session: ReaderSession,
    settings: AppSettings,
    colors: ReadingColors,
    onMiddleTap: () -> Unit,
    onReadOffsetChanged: (Int) -> Unit,
    onSelectionChanged: (Int) -> Unit,
    onQuoteJump: (String) -> Unit,
    onMessage: (String) -> Unit,
    onUserScrolled: () -> Unit = {},
    bottomInset: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val editing by session.editing.collectAsStateWithLifecycle()
    val display by session.display.collectAsStateWithLifecycle()
    val editBody by session.editBody.collectAsStateWithLifecycle()
    val images by session.imageBitmaps.collectAsStateWithLifecycle()
    val imagesState = rememberUpdatedState(images)
    val editable by session.editable.collectAsStateWithLifecycle()
    val jumpTarget by session.jumpTarget.collectAsStateWithLifecycle()
    val caretTarget by session.caretTarget.collectAsStateWithLifecycle()
    val editChapterIndex by session.editChapterIndex.collectAsStateWithLifecycle()

    val editText = remember { NovelEditText(context) }
    val scrollView = remember { PageScrollView(context) }
    val touchSlop = remember { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    val classifier = remember { GestureClassifier(touchSlop) }
    val suppress = remember { arrayOf(false) }

    /** 跳转请求放在 remember 的共享对象里：每次重组都会重建普通局部变量，布局回调会读到旧实例导致跳转丢失。 */
    val scrollRequest = remember { ScrollRequest() }

    /** 手势回调与触摸监听可能来自不同组合阶段，按下坐标必须放在 remember 的共享对象里。 */
    val touch = remember { TouchState() }

    val settingsState = rememberUpdatedState(settings)
    val colorsState = rememberUpdatedState(colors)
    val displayState = rememberUpdatedState(display)
    val editBodyState = rememberUpdatedState(editBody)
    val editingState = rememberUpdatedState(editing)
    val middleTapState = rememberUpdatedState(onMiddleTap)
    val readOffsetState = rememberUpdatedState(onReadOffsetChanged)
    val selectionState = rememberUpdatedState(onSelectionChanged)
    val quoteJumpState = rememberUpdatedState(onQuoteJump)
    val messageState = rememberUpdatedState(onMessage)
    val userScrolledState = rememberUpdatedState(onUserScrolled)

    fun imm(): InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    fun applyStyle(current: AppSettings, currentColors: ReadingColors) {
        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, current.fontSizeSp)
        editText.typeface = Typeface.create(
            current.fontFamily,
            if (current.bold) Typeface.BOLD else Typeface.NORMAL
        )
        editText.setTextColor(readerTextColor(current, currentColors).toArgb())
        editText.setLineSpacing(0f, current.lineSpacing)
        editText.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        editText.hint = if (editingState.value) "开始写作……" else null
        editText.setHintTextColor(currentColors.secondaryText.copy(alpha = 0.5f).toArgb())
        editText.paintFlags = if (current.underline) {
            editText.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        } else {
            editText.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
        applyEditorCursor(editText, currentColors.accent.toArgb())
        editText.caretColor = currentColors.accent.toArgb()
    }

    fun copyToClipboard(value: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("小说段落", value))
    }

    /** [contentY] 已经是正文内容坐标（含滚动量）。 */
    fun offsetAtContent(x: Float, contentY: Float): Int {
        val layout = editText.layout ?: return 0
        val localX = x - editText.totalPaddingLeft
        val localY = contentY - editText.totalPaddingTop
        val line = layout.getLineForVertical(localY.toInt().coerceAtLeast(0))
        return layout.getOffsetForHorizontal(line, localX)
            .coerceIn(0, editText.text?.length ?: 0)
    }

    fun updateReadOffset() {
        val layout = editText.layout ?: return
        val top = (scrollView.scrollY + editText.totalPaddingTop).coerceAtLeast(0)
        val line = layout.getLineForVertical(top)
        val offset = layout.getLineStart(line).coerceIn(0, displayState.value.text.length)
        readOffsetState.value(offset)
    }

    fun maxScroll(): Int = (editText.measuredHeight - scrollView.height).coerceAtLeast(0)

    /**
     * 按字符偏移应用当前跳转。返回 false 表示布局尚未就绪。
     *
     * 目标只存字符偏移，每次重算像素位置，因此图片异步加载、字号行距变化、
     * 键盘弹出导致的整体高度变化都不会让落点漂移。
     */
    fun applyPendingScroll(): Boolean {
        if (scrollRequest.offset < 0) return false
        val layout = editText.layout ?: return false
        if (editText.measuredHeight <= 0 || scrollView.height <= 0) return false
        val length = editText.text?.length ?: 0
        val safe = scrollRequest.offset.coerceIn(0, length)
        val line = layout.getLineForOffset(safe)
        // 目标行顶部对齐容器顶部；超出可滚动范围时夹住，避免滚出空白页。
        val desired = (editText.totalPaddingTop + layout.getLineTop(line))
            .coerceIn(0, maxScroll())
        if (scrollView.scrollY != desired) scrollView.scrollTo(0, desired)
        if (SystemClock.uptimeMillis() >= scrollRequest.settleUntil) {
            scrollRequest.offset = -1
        }
        return true
    }

    fun settleScroll() {
        if (scrollRequest.offset < 0) return
        if (applyPendingScroll()) {
            scrollView.postDelayed({ settleScroll() }, 60L)
        } else {
            // 布局未就绪：等下一帧再试，请求保留在 scrollRequest 中。
            scrollView.post { settleScroll() }
        }
    }

    /**
     * 在 [settleMs] 内反复校正：布局每次变化都重新按字符偏移对齐到行顶。
     * 阅读态的会话偏移在开始时锚定到目标章节，之后不再由滚动位置回写覆盖。
     */
    fun requestScroll(offset: Int, settleMs: Long) {
        scrollRequest.offset = offset.coerceAtLeast(0)
        scrollRequest.settleUntil = SystemClock.uptimeMillis() + settleMs
        if (!editingState.value) readOffsetState.value(scrollRequest.offset)
        settleScroll()
    }

    /** 编辑态：让光标所在行留在可视区域内。 */
    fun ensureCaretVisible() {
        val layout = editText.layout ?: return
        if (scrollView.height <= 0) return
        val offset = editText.selectionStart.coerceAtLeast(0)
        if (offset > (editText.text?.length ?: 0)) return
        val line = layout.getLineForOffset(offset)
        val top = editText.totalPaddingTop + layout.getLineTop(line)
        val bottom = editText.totalPaddingTop + layout.getLineBottom(line)
        val viewportTop = scrollView.scrollY
        val viewportBottom = viewportTop + scrollView.height
        when {
            top < viewportTop -> scrollView.scrollTo(0, top.coerceIn(0, maxScroll()))
            bottom > viewportBottom ->
                scrollView.scrollTo(0, (bottom - scrollView.height).coerceIn(0, maxScroll()))
        }
    }

    fun applyReadMode() {
        suppress[0] = true
        editText.setTextIsSelectable(false)
        editText.isFocusable = false
        editText.isFocusableInTouchMode = false
        editText.isCursorVisible = false
        editText.keyListener = null
        editText.movementMethod = null
        // setInputType 会按 inputType 重置单行标记，必须在其后恢复多行换行。
        editText.inputType = InputType.TYPE_NULL
        editText.setSingleLine(false)
        editText.setHorizontallyScrolling(false)
        editText.maxLines = Int.MAX_VALUE
        editText.hint = null
        editText.isClickable = false
        editText.isLongClickable = false
        editText.isVerticalScrollBarEnabled = false
        editText.caretEnabled = false
        suppress[0] = false
    }

    fun applyEditMode() {
        suppress[0] = true
        editText.isFocusable = true
        editText.isFocusableInTouchMode = true
        editText.setTextIsSelectable(false)
        editText.isCursorVisible = true
        editText.movementMethod = null
        editText.keyListener = TextKeyListener.getInstance()
        editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        editText.setSingleLine(false)
        editText.setHorizontallyScrolling(false)
        editText.maxLines = Int.MAX_VALUE
        editText.hint = "开始写作……"
        editText.isClickable = true
        editText.isLongClickable = false
        editText.caretEnabled = true
        suppress[0] = false
        editText.requestFocus()
        // 进入编辑态的瞬间视图可能还没挂到窗口，requestFocus 会静默失败导致看不到光标；
        // 挂到消息队列后重试一次，并确保 cursor 可见、cursor drawable 与当前主题一致。
        editText.post {
            if (!editingState.value) return@post
            if (!editText.hasFocus()) editText.requestFocus()
            editText.isCursorVisible = true
            applyEditorCursor(editText, colorsState.value.accent.toArgb())
            editText.caretColor = colorsState.value.accent.toArgb()
            editText.invalidate()
        }
    }

    /** 编辑态点击正文：移动光标、唤出输入法，并把光标行滚到键盘上方。 */
    fun placeCaretAt(x: Float, contentY: Float) {
        val offset = offsetAtContent(x, contentY)
        editText.setSelection(offset.coerceIn(0, editText.text?.length ?: 0))
        editText.requestFocus()
        imm().showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        editText.post { ensureCaretVisible() }
        editText.postDelayed({ ensureCaretVisible() }, 250L)
        editText.postDelayed({ ensureCaretVisible() }, 600L)
    }

    /** 预览可用宽度：正文内容区宽度，首帧尚未测量时退回屏幕宽度。 */
    fun contentWidth(): Int {
        val measured = editText.width - editText.totalPaddingLeft - editText.totalPaddingRight
        if (measured > 0) return measured
        val screen = context.resources.displayMetrics.widthPixels
        return (screen - editText.totalPaddingLeft - editText.totalPaddingRight).coerceAtLeast(1)
    }

    /**
     * 重建阅读态正文。
     *
     * 重排期间的滚动**一律不写回会话偏移**：否则 scrollTo(0,0) 触发的 onScrollChange
     * 会把「刚点开的章节偏移」覆盖成 0，跳转看起来就像完全没生效。
     */
    fun renderRead(raw: String, preserveScroll: Boolean) {
        val y = scrollView.scrollY
        suppress[0] = true
        val built = buildSpanned(
            context, raw, settingsState.value, colorsState.value, images,
            editMode = false, maxWidthPx = contentWidth()
        )
        // 先切到纯阅读态再灌文本：反过来做时 inputType=TYPE_NULL 会把正文截断到 5000 字，
        // 可滚动高度只覆盖前两章，后面的章节跳转/整页翻页全部被 clamp 在同一处。
        applyReadMode()
        editText.setText(built, TextView.BufferType.SPANNABLE)
        if (preserveScroll) {
            scrollView.scrollTo(0, y.coerceIn(0, maxScroll()))
        } else {
            scrollView.scrollTo(0, 0)
        }
        suppress[0] = false
    }

    fun renderEdit(raw: String, keepSelection: Boolean) {
        val selection = if (keepSelection) editText.selectionStart else 0
        suppress[0] = true
        editText.setText(
            buildSpanned(
                context, raw, settingsState.value, colorsState.value, images,
                editMode = true, maxWidthPx = contentWidth()
            ),
            TextView.BufferType.EDITABLE
        )
        suppress[0] = false
        val safe = selection.coerceIn(0, raw.length)
        editText.setSelection(safe)
        ensureCaretVisible()
    }

    /**
     * 图片位图是异步加载的：插入标记后文本已经等于 editBody，靠文本比较的守卫会跳过重建，
     * 标记就会一直以裸文本显示。这里只就地替换 span，不改写文本、不动光标与撤销栈。
     */
    fun refreshInlineSpans(bitmaps: Map<String, Bitmap?>) {
        val editable = editText.text as? Spannable ?: return
        for (type in listOf(
            ImageSpan::class.java,
            ImagePreviewSpan::class.java,
            ForegroundColorSpan::class.java
        )) {
            for (span in editable.getSpans(0, editable.length, type)) {
                editable.removeSpan(span)
            }
        }
        val rendered = buildSpanned(
            context,
            editable.toString(),
            settingsState.value,
            colorsState.value,
            bitmaps,
            editMode = true,
            maxWidthPx = contentWidth()
        )
        for (type in listOf(
            ImageSpan::class.java,
            ImagePreviewSpan::class.java,
            ForegroundColorSpan::class.java
        )) {
            for (span in rendered.getSpans(0, rendered.length, type)) {
                editable.setSpan(
                    span,
                    rendered.getSpanStart(span),
                    rendered.getSpanEnd(span),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    val readDetector = remember {
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val height = scrollView.height
                    if (height <= 0) return true
                    if (settingsState.value.flipMode == FlipMode.PAGE) {
                        // 整页翻动：上三分之一点击翻上一页，下三分之一点击翻下一页。
                        when {
                            touch.viewportY < height / 3f -> scrollView.turnPage(forward = false)
                            touch.viewportY > height * 2f / 3f -> scrollView.turnPage(forward = true)
                            else -> middleTapState.value()
                        }
                        return true
                    }
                    if (touch.viewportY >= height / 3f && touch.viewportY <= height * 2f / 3) {
                        middleTapState.value()
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    val paragraph = InlineParser.paragraphAt(
                        displayState.value.text,
                        offsetAtContent(touch.downX, touch.contentY)
                    )
                    if (paragraph.isNotBlank()) {
                        copyToClipboard(paragraph)
                        messageState.value("已复制当前段落")
                    }
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val name = InlineParser.quoteAt(
                        displayState.value.text,
                        offsetAtContent(touch.downX, touch.contentY)
                    ) ?: return false
                    quoteJumpState.value(name)
                    return true
                }

                override fun onDoubleTapEvent(e: MotionEvent): Boolean = true
            }
        )
    }

    val editDetector = remember {
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    placeCaretAt(touch.downX, touch.contentY)
                    // 编辑态点击正文同时通知外部收起工具栏（工具栏已隐藏时为无副作用）。
                    middleTapState.value()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    val paragraph = InlineParser.paragraphAt(
                        editBodyState.value,
                        offsetAtContent(touch.downX, touch.contentY)
                    )
                    if (paragraph.isNotBlank()) {
                        copyToClipboard(paragraph)
                        messageState.value("已复制当前段落")
                    }
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val name = InlineParser.quoteAt(
                        editBodyState.value,
                        offsetAtContent(touch.downX, touch.contentY)
                    ) ?: return false
                    quoteJumpState.value(name)
                    return true
                }

                override fun onDoubleTapEvent(e: MotionEvent): Boolean = true
            }
        )
    }

    /**
     * 只观察、不消费：始终返回 false。事件统一换算到视口坐标后投递给手势识别器；
     * 一旦判定为拖动，就向识别器发送 CANCEL，点按/长按让位给滚动。
     */
    fun deliver(event: MotionEvent, fromChild: Boolean) {
        val action = event.actionMasked
        val viewportY = if (fromChild) event.y - scrollView.scrollY else event.y
        if (action == MotionEvent.ACTION_DOWN) {
            if (event.downTime == touch.lastDownTime) return
            touch.lastDownTime = event.downTime
            touch.dragCancelSent = false
            touch.downX = event.x
            touch.viewportY = viewportY
            touch.contentY = viewportY + scrollView.scrollY
            classifier.onDown(event.x, viewportY)
            // 用户开始操作就放弃仍在进行的跳转校正，避免把用户滚到一半又拽回去。
            scrollRequest.offset = -1
        }

        val detector = if (editingState.value) editDetector else readDetector

        fun forward(e: MotionEvent) {
            if (fromChild && scrollView.scrollY != 0) {
                val translated = MotionEvent.obtain(e)
                translated.setLocation(e.x, e.y - scrollView.scrollY)
                detector.onTouchEvent(translated)
                translated.recycle()
            } else {
                detector.onTouchEvent(e)
            }
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> forward(event)
            MotionEvent.ACTION_MOVE -> {
                if (classifier.onMove(event.x, viewportY)) {
                    if (!touch.dragCancelSent) {
                        touch.dragCancelSent = true
                        // 手指拖动的第一刻通知外部收起工具栏，避免遮挡正文。
                        userScrolledState.value()
                        val cancel = MotionEvent.obtain(
                            event.downTime,
                            event.eventTime,
                            MotionEvent.ACTION_CANCEL,
                            event.x,
                            viewportY,
                            0
                        )
                        detector.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                } else if (!touch.dragCancelSent) {
                    forward(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                val tap = classifier.onUp()
                if (tap) forward(event)
                touch.dragCancelSent = false
            }
            MotionEvent.ACTION_CANCEL -> {
                classifier.onCancel()
                if (!touch.dragCancelSent) forward(event)
                touch.dragCancelSent = false
            }
            else -> if (!touch.dragCancelSent) forward(event)
        }
    }

    AndroidView(
        // Compose 默认不裁剪子级：不裁剪时滚动出可视区的正文会画到顶部栏上面。
        modifier = modifier.fillMaxSize().clipToBounds(),
        factory = {
            val horizontal = with(density) { 20.dp.toPx().toInt() }
            val vertical = with(density) { 14.dp.toPx().toInt() }
            editText.setPadding(horizontal, vertical, horizontal, vertical)
            editText.gravity = Gravity.TOP or Gravity.START
            editText.isSingleLine = false
            editText.setHorizontallyScrolling(false)
            editText.isVerticalScrollBarEnabled = false
            editText.setTextIsSelectable(false)
            editText.isFocusable = false
            editText.isFocusableInTouchMode = false
            editText.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            applyStyle(settingsState.value, colorsState.value)

            scrollView.isVerticalScrollBarEnabled = false
            scrollView.isFillViewport = false
            scrollView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            scrollView.addView(
                editText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )

            editText.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                applyPendingScroll()
            }
            scrollView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                applyPendingScroll()
                // 输入法弹出/收起会改变可视高度：编辑态把光标行保持在键盘上方。
                val heightChanged = (bottom - top) != (oldBottom - oldTop)
                if (heightChanged && editingState.value) {
                    scrollView.post { ensureCaretVisible() }
                }
            }

            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (suppress[0] || !editingState.value) return
                    session.editBody(s?.toString().orEmpty())
                    selectionState.value(editText.selectionStart)
                    // 内联改文本（尤其是改 img 的 width）后立刻按新宽度重建预览 span，
                    // 不必等失焦或重进页面。
                    refreshInlineSpans(imagesState.value)
                }
            })

            editText.selectionListener = { start, _ ->
                if (editingState.value && !suppress[0]) {
                    selectionState.value(start)
                    ensureCaretVisible()
                }
            }

            scrollView.setOnScrollChangeListener(object : View.OnScrollChangeListener {
                override fun onScrollChange(
                    v: View?,
                    scrollX: Int,
                    scrollY: Int,
                    oldScrollX: Int,
                    oldScrollY: Int
                ) {
                    // 跳转校正期间的滚动都不是用户行为，不能回写会话偏移。
                    if (!editingState.value && !suppress[0] && scrollRequest.offset < 0) {
                        updateReadOffset()
                    }
                }
            })

            scrollView.setOnTouchListener { _, event ->
                // ScrollView 会先收到落在正文上的事件，只需在这里观察一次；
                // 再挂到 EditText 上会让 MOVE/UP 被重复投递，破坏双击与点按判定。
                deliver(event, fromChild = false)
                false
            }
            scrollView
        }
    )

    LaunchedEffect(bottomInset, density) {
        val horizontal = with(density) { 20.dp.toPx().toInt() }
        val vertical = with(density) { 14.dp.toPx().toInt() }
        val bottom = with(density) { bottomInset.toPx().toInt() }
        editText.setPadding(horizontal, vertical, horizontal, vertical + bottom)
    }

    LaunchedEffect(settings.flipMode, editing) {
        // 编辑模式只保留下滑滚动；整页翻动仅在阅读态生效。
        scrollView.pageMode = settings.flipMode == FlipMode.PAGE && !editing
    }

    // 模式切换 / 文本变化
    LaunchedEffect(editing, display.text, images.toString(), settings.fontFamily, settings.textColor, settings.bold, settings.underline, settings.lineSpacing, colors) {
        if (!editing) {
            if (!editable) return@LaunchedEffect
            renderRead(display.text, preserveScroll = false)
            // 按会话记住的阅读偏移恢复；renderRead 不再把偏移写回 0。
            // 已有跳转请求时以跳转为准，避免恢复动作把刚点开的章节顶掉。
            editText.post {
                if (scrollRequest.offset < 0) {
                    requestScroll(session.currentOffset.value, 800L)
                } else {
                    settleScroll()
                }
            }
        }
    }

    LaunchedEffect(editing, editBody, settings.fontFamily, settings.textColor, settings.bold, settings.underline, settings.lineSpacing, colors) {
        if (editing) {
            applyEditMode()
            if (editText.text?.toString() != editBody) {
                renderEdit(editBody, keepSelection = true)
            }
        }
    }

    // 图片位图异步加载完成后就地重建内联 span：插图后不用重开小说就能看到图片。
    LaunchedEffect(editing, images) {
        if (editing) refreshInlineSpans(images)
    }

    LaunchedEffect(editing) {
        if (editing) {
            editText.post {
                imm().hideSoftInputFromWindow(editText.windowToken, 0)
            }
            // 进入编辑态的瞬间 Compose 焦点系统可能把焦点收回，普通 requestFocus 会静默失败；
            // 延迟后用触摸态聚焦，确保光标真正可见（不弹输入法）。
            editText.postDelayed({
                if (editingState.value) {
                    editText.requestFocusFromTouch()
                    editText.isCursorVisible = true
                }
            }, 150L)
        }
    }

    LaunchedEffect(jumpTarget, editing) {
        val target = jumpTarget ?: return@LaunchedEffect
        if (editing) {
            // 编辑态的章节切换走 editBody 重排，这里只清理过期的阅读跳转。
            session.consumeJump()
            return@LaunchedEffect
        }
        // 目录点击后按字符偏移定位章节首行，并在图片加载等重排后继续校正一段时间。
        requestScroll(target, 1200L)
        session.consumeJump()
    }

    // 编辑态切换章节：正文重排后光标必须回到开头，否则 ensureCaretVisible 会把
    // 上一章残留的光标位置滚进来，看起来就像「跳到了章节中间」。
    LaunchedEffect(editing, editChapterIndex) {
        if (!editing) return@LaunchedEffect
        editText.setSelection(0)
        editText.post {
            editText.setSelection(0)
            requestScroll(0, 600L)
        }
    }

    LaunchedEffect(caretTarget, editing) {
        val target = caretTarget ?: return@LaunchedEffect
        if (editing) {
            val safe = target.coerceIn(0, editBodyState.value.length)
            editText.setSelection(safe)
            editText.post { ensureCaretVisible() }
            session.consumeCaret()
        } else {
            session.consumeCaret()
        }
    }

    LaunchedEffect(settings.fontFamily, settings.fontSizeSp, settings.textColor, settings.bold, settings.underline, settings.lineSpacing, colors) {
        applyStyle(settings, colors)
    }
}

/**
 * 显式指定光标 drawable：系统主题的 cursor 可能被 tint 成与背景同色，
 * 这里用主题强调色画一条 2dp 竖线，保证浅色/深色背景上都能肉眼看到。
 */
private fun applyEditorCursor(editText: TextView, color: Int) {
    val width = (2f * editText.resources.displayMetrics.density).toInt().coerceAtLeast(2)
    val height = (editText.textSize * 1.35f).toInt().coerceAtLeast(1)
    val drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        setSize(width, height)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        editText.textCursorDrawable = drawable
    } else {
        // API 29 以下只能通过 TextView 的私有字段替换光标 drawable。
        runCatching {
            val field = TextView::class.java.getDeclaredField("mCursorDrawable")
            field.isAccessible = true
            field.set(editText, arrayOf(drawable, drawable))
        }
    }
}

private class ScaledImageSpan(
    context: Context,
    private val bitmap: Bitmap,
    private val targetWidthPx: Int
) : ImageSpan(context, bitmap, ALIGN_BOTTOM) {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val drawable = drawable ?: return 0
        val width = targetWidthPx.coerceAtLeast(1)
        val height = (width.toFloat() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
        drawable.setBounds(0, 0, width, height)
        if (fm != null) {
            fm.ascent = -height
            fm.descent = 0
            fm.top = -height
            fm.bottom = 0
        }
        return height
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val drawable = drawable ?: return
        canvas.save()
        val transY = bottom - drawable.bounds.bottom
        canvas.translate(x, transY.toFloat())
        drawable.draw(canvas)
        canvas.restore()
    }
}

/**
 * 编辑态的插图预览：标记文本保持原样可见、可选、可编辑。
 * 预览画在标记所在行的**上方**：通过增加行的 ascent 预留空间，文字自然下移，
 * 再由 [NovelEditText.onDraw] 把图片画进这块空白。
 */
private class ImagePreviewSpan(
    val bitmap: Bitmap,
    val widthPx: Int,
    val heightPx: Int,
    val gapPx: Int
) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence?,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt
    ) {
        // 只在行顶追加空白，基线随之下移：图片在上、标记文本在下。
        fm.ascent -= heightPx + gapPx
        fm.top -= heightPx + gapPx
    }
}

private fun buildSpanned(
    context: Context,
    raw: String,
    settings: AppSettings,
    colors: ReadingColors,
    images: Map<String, Bitmap?>,
    editMode: Boolean,
    maxWidthPx: Int
): Spannable {
    val spannable = SpannableString(raw)

    val imageTokens = InlineParser.imageTokens(raw)
    val quoteTokens = InlineParser.quoteTokens(raw, imageTokens.map { it.start until it.end })

    for (token in quoteTokens) {
        spannable.setSpan(
            ForegroundColorSpan(colors.quote.toArgb()),
            token.start,
            token.end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    for (token in imageTokens) {
        val node = token.node as? InlineNode.Image ?: continue
        val bitmap = images[node.fileName] ?: continue
        // 标记里的 width 只是期望宽度：无论填 600 还是 9999，都不允许超出正文可用宽度。
        val width = node.width.coerceIn(1, maxWidthPx.coerceAtLeast(1))
        if (editMode) {
            // 编辑态不覆盖标记文本，只在标记行上方预留预览空间。
            val height = (width.toFloat() * bitmap.height / bitmap.width)
                .toInt()
                .coerceAtLeast(1)
            spannable.setSpan(
                ImagePreviewSpan(bitmap, width, height, (6f * context.resources.displayMetrics.density).toInt()),
                token.start,
                token.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } else {
            spannable.setSpan(
                ScaledImageSpan(context, bitmap, width),
                token.start,
                token.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    return spannable
}
