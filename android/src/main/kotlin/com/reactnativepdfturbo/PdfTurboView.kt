package com.reactnativepdfturbo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfPasswordException
import io.legere.pdfiumandroid.PdfiumCore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class PdfTurboView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TILE_SIZE = 512
        // Coalesce high-frequency gesture updates: re-tile only after the
        // pan/zoom settles instead of on every touch/animation frame.
        private const val TILE_UPDATE_DELAY_MS = 90L
        // Max pixels per rendered bitmap (~16MB ARGB) to stay under Canvas limits.
        private const val MAX_BITMAP_PIXELS = 4_000_000.0
        // Continuous mode: gap between stacked pages, in dp.
        private const val CONTINUOUS_GAP_DP = 12f
        // Continuous mode: how many rendered page bitmaps to keep cached.
        private const val MAX_CACHED_PAGES = 8
        private const val CONTINUOUS_RENDER_DELAY_MS = 80L
    }

    private val density: Float = context.resources.displayMetrics.density

    private val tileHandler = Handler(Looper.getMainLooper())
    private val tileUpdateRunnable = Runnable { updateTiles() }

    /**
     * Debounced tile refresh. During an active gesture the base bitmap is
     * scaled to fill the viewport; sharp tiles are only rendered once movement
     * pauses, avoiding a storm of Bitmap allocations / renderPageBitmap calls.
     */
    private fun scheduleTileUpdate() {
        tileHandler.removeCallbacks(tileUpdateRunnable)
        tileHandler.postDelayed(tileUpdateRunnable, TILE_UPDATE_DELAY_MS)
    }

    private class Tile(val x: Int, val y: Int, val size: Int, val scale: Float, val bitmap: Bitmap)

    private val pdfiumCore: PdfiumCore = PdfiumCore()
    private var pdfDocument: PdfDocument? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var currentPage: PdfPage? = null
    private var baseBitmap: Bitmap? = null

    // Serialize every native pdfium document/page call — pdfium is not
    // thread-safe per-document and continuous mode touches it from a background
    // measurement/render sweep.
    private val docLock = Any()

    private val activeTiles = ConcurrentHashMap<String, Tile>()
    private val tileFutures = ConcurrentHashMap<String, Future<*>>()
    private var pendingBaseRenderFuture: Future<*>? = null
    private val renderExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    private var source: String = ""
    private var pageIndex: Int = 0
    private var maximumZoom: Float = 5.0f
    private var enableAntialiasing: Boolean = true
    private var password: String = ""
    // When false the view ignores its own pan/zoom so a parent scroll container
    // (embedded-tile mode) receives the touches instead.
    private var gesturesEnabled: Boolean = true

    // "paged" (one page at a time) or "continuous" (all pages stacked vertically).
    private var scrollMode: String = "continuous"
    private val isContinuous: Boolean get() = scrollMode == "continuous"
    private var contentInsetTopPx: Float = 0f
    private var contentInsetBottomPx: Float = 0f

    private var scaleFactor: Float = 1.0f
    private var minScaleFactor: Float = 1.0f
    private var translateX: Float = 0f
    private var translateY: Float = 0f

    private var pageWidth: Int = 0
    private var pageHeight: Int = 0

    private var needsLoad: Boolean = false
    private var pendingPageIndex: Int? = null

    private val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        isDither = true
    }

    private val scaleGestureDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var activePointerId: Int = -1

    // ── continuous-mode state ────────────────────────────────────────────────
    private val gapPx: Float = CONTINUOUS_GAP_DP * density
    // Per-page geometry, filled progressively by a background measurement sweep.
    private var cPtsW = FloatArray(0)
    private var cPtsH = FloatArray(0)
    private var cTop = FloatArray(0)   // fit-width (zoom=1) y offset, px
    private var cH = FloatArray(0)     // fit-width (zoom=1) page height, px
    @Volatile private var cMeasured = 0
    @Volatile private var cContentH1 = 0f   // fit-width total height of measured pages, px
    private var cLayoutWidth = 0
    private var measureFuture: Future<*>? = null

    private var cZoom = 1.0f
    private var cScrollY = 0f
    private var cTransX = 0f
    private var pendingScrollPage: Int? = null

    // Rendered page bitmaps for the currently visible pages (main-thread owned).
    private val pageBitmaps = HashMap<Int, Bitmap>()
    private val pageBitmapWidth = HashMap<Int, Int>()
    private val pageRenderFutures = ConcurrentHashMap<Int, Future<*>>()
    private val continuousRenderRunnable = Runnable { renderVisiblePages() }

    private val scaleDetectorContinuous: ScaleGestureDetector
    private val gestureDetectorContinuous: GestureDetector
    private var flingAnimator: android.animation.ValueAnimator? = null

    init {
        setBackgroundColor(Color.WHITE)
        setWillNotDraw(false)

        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldScale = scaleFactor
                scaleFactor *= detector.scaleFactor
                scaleFactor = max(minScaleFactor, min(scaleFactor, maximumZoom))

                if (oldScale != scaleFactor) {
                    val focusX = detector.focusX
                    val focusY = detector.focusY

                    val oldScaledWidth = pageWidth * oldScale
                    val oldScaledHeight = pageHeight * oldScale
                    val oldContentX = (width - oldScaledWidth) / 2 + translateX
                    val oldContentY = (height - oldScaledHeight) / 2 + translateY

                    val pdfX = (focusX - oldContentX) / oldScale
                    val pdfY = (focusY - oldContentY) / oldScale

                    val newScaledWidth = pageWidth * scaleFactor
                    val newScaledHeight = pageHeight * scaleFactor
                    val newContentX = (width - newScaledWidth) / 2
                    val newContentY = (height - newScaledHeight) / 2

                    translateX = focusX - newContentX - (pdfX * scaleFactor)
                    translateY = focusY - newContentY - (pdfY * scaleFactor)

                    constrainTranslation()
                    scheduleTileUpdate()
                    invalidate()
                    emitTransform()
                }
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > minScaleFactor) {
                    animateToScale(minScaleFactor, e.x, e.y)
                } else {
                    animateToScale(min(2.5f, maximumZoom), e.x, e.y)
                }
                return true
            }
        })

        // ── continuous-mode gesture detectors ────────────────────────────────
        scaleDetectorContinuous = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldZoom = cZoom
                var newZoom = cZoom * detector.scaleFactor
                newZoom = max(1.0f, min(newZoom, maximumZoom))
                if (newZoom == oldZoom) return true
                zoomContinuousAbout(detector.focusX, detector.focusY, oldZoom, newZoom)
                return true
            }
        })
        gestureDetectorContinuous = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (cZoom > 1.01f) 1.0f else min(2.5f, maximumZoom)
                animateContinuousZoom(target, e.x, e.y)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                startContinuousFling(velocityY)
                return true
            }
        })
    }

    private fun animateToScale(targetScale: Float, focusX: Float, focusY: Float) {
        val startScale = scaleFactor
        val startTranslateX = translateX
        val startTranslateY = translateY

        val oldScaledWidth = pageWidth * startScale
        val oldScaledHeight = pageHeight * startScale
        val oldContentX = (width - oldScaledWidth) / 2 + startTranslateX
        val oldContentY = (height - oldScaledHeight) / 2 + startTranslateY
        val pdfX = (focusX - oldContentX) / startScale
        val pdfY = (focusY - oldContentY) / startScale

        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val currentScale = startScale + (targetScale - startScale) * fraction
                scaleFactor = currentScale

                if (targetScale <= minScaleFactor) {
                    translateX = startTranslateX * (1 - fraction)
                    translateY = startTranslateY * (1 - fraction)
                } else {
                    val newScaledWidth = pageWidth * currentScale
                    val newScaledHeight = pageHeight * currentScale
                    val newContentX = (width - newScaledWidth) / 2
                    val newContentY = (height - newScaledHeight) / 2

                    translateX = focusX - newContentX - (pdfX * currentScale)
                    translateY = focusY - newContentY - (pdfY * currentScale)
                }
                constrainTranslation()
                    scheduleTileUpdate()
                    invalidate()
                    emitTransform()
            }
            start()
        }
    }

    private fun constrainTranslation() {
        val scaledWidth = pageWidth * scaleFactor
        val scaledHeight = pageHeight * scaleFactor

        val maxTranslateX = max(0f, (scaledWidth - width) / 2)
        val maxTranslateY = max(0f, (scaledHeight - height) / 2)

        if (scaledWidth <= width) {
            translateX = 0f
        } else {
            translateX = translateX.coerceIn(-maxTranslateX, maxTranslateX)
        }

        if (scaledHeight <= height) {
            translateY = 0f
        } else {
            translateY = translateY.coerceIn(-maxTranslateY, maxTranslateY)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Embedded-tile mode: let the parent scroll container own the gesture.
        if (!gesturesEnabled) return false

        if (isContinuous) return onTouchContinuous(event)

        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                if (scaleFactor > minScaleFactor + 0.01f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleGestureDetector.isInProgress) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)

                        translateX += (x - lastTouchX)
                        translateY += (y - lastTouchY)
                        constrainTranslation()
                    scheduleTileUpdate()
                    invalidate()
                    emitTransform()

                        lastTouchX = x
                        lastTouchY = y
                    }
                } else {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        lastTouchX = event.getX(pointerIndex)
                        lastTouchY = event.getY(pointerIndex)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {

                    val newIndex = if (pointerIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newIndex)
                        lastTouchX = event.getX(newIndex)
                        lastTouchY = event.getY(newIndex)
                    }
                }

                if (event.pointerCount == 2) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        lastTouchX = event.getX(idx)
                        lastTouchY = event.getY(idx)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    fun setSource(source: String) {
        if (this.source != source) {
            this.source = source
            needsLoad = true
            closePdfDocument()
            cancelContinuous()
            pendingPageIndex = pageIndex
            requestLayout()
            invalidate()
        }
    }

    fun setPage(page: Int) {
        if (this.pageIndex != page) {
            this.pageIndex = page
            if (isContinuous) {
                scrollToPageContinuous(page)
            } else if (pdfDocument != null) {
                displayPage(page)
            } else {
                pendingPageIndex = page
            }
        }
    }

    fun setMaximumZoom(zoom: Float) {
        this.maximumZoom = zoom
        if (scaleFactor > zoom) {
            scaleFactor = zoom
            invalidate()
        }
        if (isContinuous && cZoom > zoom) {
            cZoom = zoom
            clampContinuousScroll()
            invalidate()
        }
    }

    fun setEnableAntialiasing(enable: Boolean) {
        this.enableAntialiasing = enable
        paint.isAntiAlias = enable
        paint.isFilterBitmap = enable
        invalidate()
    }

    fun setPassword(pwd: String) {
        if (this.password != pwd) {
            this.password = pwd
            if (source.isNotEmpty() && pdfDocument == null) {
                needsLoad = true
                requestLayout()
            }
        }
    }

    fun setGesturesEnabled(enabled: Boolean) {
        this.gesturesEnabled = enabled
    }

    fun setScrollMode(mode: String) {
        val m = if (mode == "paged") "paged" else "continuous"
        if (m == scrollMode) return
        scrollMode = m
        if (pdfDocument == null || width == 0 || height == 0) return
        if (isContinuous) {
            // Leaving paged: drop single-page render state, build continuous layout.
            pendingBaseRenderFuture?.cancel(true)
            baseBitmap?.recycle(); baseBitmap = null
            clearTiles()
            try { currentPage?.close() } catch (_: Exception) {}
            currentPage = null
            startContinuousMeasurement()
        } else {
            cancelContinuous()
            displayPage(pageIndex.coerceAtLeast(0))
        }
    }

    fun setContentInsetTop(value: Float) {
        val px = value * density
        if (px != contentInsetTopPx) {
            contentInsetTopPx = px
            if (isContinuous) { clampContinuousScroll(); invalidate() }
        }
    }

    fun setContentInsetBottom(value: Float) {
        val px = value * density
        if (px != contentInsetBottomPx) {
            contentInsetBottomPx = px
            if (isContinuous) { clampContinuousScroll(); invalidate() }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        if (width > 0 && height > 0 && needsLoad) {
            needsLoad = false
            loadPdf()
        } else if (changed && isContinuous && pdfDocument != null && cLayoutWidth != width) {
            // Width changed: fit-width heights are stale — re-measure.
            startContinuousMeasurement()
        } else if (changed && baseBitmap != null) {
            calculateFitScale()
            constrainTranslation()
            emitTransform()
        }
    }

    private fun closePdfDocument() {
        pendingBaseRenderFuture?.cancel(true)
        pendingBaseRenderFuture = null
        try { currentPage?.close() } catch (_: Exception) {}
        currentPage = null

        if (pdfDocument != null) {
            try { pdfDocument?.close() } catch (_: Exception) {}
            pdfDocument = null
            fileDescriptor = null
        } else {
            try { fileDescriptor?.close() } catch (_: Exception) {}
            fileDescriptor = null
        }
    }

    private fun loadPdf() {
        if (source.isEmpty()) return

        try {
            val path = if (source.startsWith("file://")) {
                source.substring(7)
            } else {
                source
            }

            val file = File(path)
            if (!file.exists()) {
                sendError("PDF file not found: $path")
                return
            }

            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

            try {
                val pwd = if (password.isNotEmpty()) password else null
                pdfDocument = pdfiumCore.newDocument(fileDescriptor!!, pwd)
            } catch (e: PdfPasswordException) {
                fileDescriptor?.close()
                fileDescriptor = null
                if (password.isEmpty()) {
                    sendPasswordRequired()
                    sendError("PDF is password protected")
                } else {
                    sendError("Invalid password for PDF")
                }
                return
            } catch (e: Exception) {
                fileDescriptor?.close()
                fileDescriptor = null
                sendError("Failed to load PDF: ${e.message}")
                return
            }

            val pageCount = pdfDocument!!.getPageCount()

            sendPageCount(pageCount)

            if (isContinuous) {
                pendingScrollPage = pendingPageIndex
                pendingPageIndex = null
                startContinuousMeasurement()
            } else {
                val targetPage = pendingPageIndex ?: 0
                pendingPageIndex = null
                displayPage(targetPage.coerceIn(0, pageCount - 1))
            }

        } catch (e: Exception) {
            sendError("Failed to load PDF: ${e.message}")
        }
    }

    private fun displayPage(index: Int) {
        val doc = pdfDocument ?: return

        val pageCount = doc.getPageCount()
        if (index < 0 || index >= pageCount) {
            sendError("Invalid page index: $index")
            return
        }

        pendingBaseRenderFuture?.cancel(true)
        pendingBaseRenderFuture = null

        baseBitmap?.recycle()
        baseBitmap = null

        currentPage?.close()
        currentPage = null

        val page = doc.openPage(index)
        currentPage = page

        pageWidth = page.getPageWidthPoint()
        pageHeight = page.getPageHeightPoint()

        pageIndex = index
        calculateFitScale()

        scaleFactor = minScaleFactor
        translateX = 0f
        translateY = 0f

        clearTiles()
        updateTiles()
        invalidate()
        emitTransform()

        val scale = calculateBaseScale()
        val bmpWidth = max(1, (pageWidth * scale).toInt())
        val bmpHeight = max(1, (pageHeight * scale).toInt())

        pendingBaseRenderFuture = renderExecutor.submit {
            try {
                val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)

                synchronized(page) {
                    if (currentPage == page) {
                        page.renderPageBitmap(
                            bitmap, 0, 0, bmpWidth, bmpHeight,
                            renderAnnot = true
                        )
                    }
                }

                post {
                    if (currentPage == page) {
                        baseBitmap?.recycle()
                        baseBitmap = bitmap
                        invalidate()
                    } else {
                        bitmap.recycle()
                    }
                }
            } catch (_: Exception) { }
        }

        sendLoadComplete(index, pageWidth, pageHeight)
    }

    private fun clearTiles() {
        tileFutures.values.forEach { it.cancel(true) }
        tileFutures.clear()
        activeTiles.values.forEach { it.bitmap.recycle() }
        activeTiles.clear()
    }

    private fun updateTiles() {
        if (width == 0 || height == 0 || pageWidth == 0 || pageHeight == 0) return
        val page = currentPage ?: return

        val scaledWidth = pageWidth * scaleFactor
        val scaledHeight = pageHeight * scaleFactor

        val contentX = (width - scaledWidth) / 2 + translateX
        val contentY = (height - scaledHeight) / 2 + translateY

        val visibleLeft = max(0f, -contentX)
        val visibleTop = max(0f, -contentY)
        val visibleRight = min(scaledWidth, width - contentX)
        val visibleBottom = min(scaledHeight, height - contentY)

        if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return

        val cols = Math.ceil((scaledWidth / TILE_SIZE).toDouble()).toInt()
        val rows = Math.ceil((scaledHeight / TILE_SIZE).toDouble()).toInt()

        val startCol = Math.floor((visibleLeft / TILE_SIZE).toDouble()).toInt().coerceIn(0, cols - 1)
        val endCol = Math.floor((visibleRight / TILE_SIZE).toDouble()).toInt().coerceIn(0, cols - 1)
        val startRow = Math.floor((visibleTop / TILE_SIZE).toDouble()).toInt().coerceIn(0, rows - 1)
        val endRow = Math.floor((visibleBottom / TILE_SIZE).toDouble()).toInt().coerceIn(0, rows - 1)

        val neededKeys = mutableSetOf<String>()
        val currentZoomFactor = scaleFactor

        for (r in startRow..endRow) {
            for (c in startCol..endCol) {
                val key = "${currentZoomFactor}_${c}_${r}"
                neededKeys.add(key)

                if (!activeTiles.containsKey(key) && !tileFutures.containsKey(key)) {
                    val tileX = c * TILE_SIZE
                    val tileY = r * TILE_SIZE

                    val future = renderExecutor.submit {
                        try {
                            // RGB_565 halves per-tile memory vs ARGB_8888; tiles
                            // are opaque (white-erased before rendering) so no alpha
                            // channel is needed.
                            val bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.RGB_565)
                            bitmap.eraseColor(Color.WHITE)

                            synchronized(page) {
                                if (currentPage == page) {
                                    page.renderPageBitmap(
                                        bitmap,
                                        -tileX, -tileY,
                                        scaledWidth.toInt(), scaledHeight.toInt(),
                                        renderAnnot = true
                                    )
                                }
                            }

                            val tile = Tile(tileX, tileY, TILE_SIZE, currentZoomFactor, bitmap)
                            if (scaleFactor == currentZoomFactor) {
                                activeTiles[key] = tile
                                postInvalidate()
                            } else {
                                bitmap.recycle()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            tileFutures.remove(key)
                        }
                    }
                    tileFutures[key] = future
                }
            }
        }

        val keysToRemove = activeTiles.keys.filter { it !in neededKeys }
        for (key in keysToRemove) {
            activeTiles.remove(key)?.bitmap?.recycle()
        }
        val futuresToRemove = tileFutures.keys.filter { it !in neededKeys }
        for (key in futuresToRemove) {
            tileFutures.remove(key)?.cancel(true)
        }
    }

    private fun calculateBaseScale(): Float {
        if (pageWidth == 0 || pageHeight == 0) return 1.0f

        var scale = if (width > 0 && height > 0) {
            val scaleX = width.toFloat() / pageWidth
            val scaleY = height.toFloat() / pageHeight
            min(scaleX, scaleY)
        } else {
            1.0f
        }

        // Base image limit: ~4M pixels (approx. 16MB) to avoid exceeding the Canvas size limit
        val bitmapPixels = (pageWidth.toDouble() * scale) * (pageHeight.toDouble() * scale)
        if (bitmapPixels > MAX_BITMAP_PIXELS) {
            scale = Math.sqrt(MAX_BITMAP_PIXELS / (pageWidth.toDouble() * pageHeight.toDouble())).toFloat()
        }

        return max(scale, 0.1f)
    }

    private fun calculateFitScale() {
        if (pageWidth == 0 || pageHeight == 0 || width == 0 || height == 0) return

        val scaleX = width.toFloat() / pageWidth
        val scaleY = height.toFloat() / pageHeight
        minScaleFactor = min(scaleX, scaleY)

        if (scaleFactor < minScaleFactor) {
            scaleFactor = minScaleFactor
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isContinuous) {
            drawContinuous(canvas)
            return
        }

        val scaledWidth = pageWidth * scaleFactor
        val scaledHeight = pageHeight * scaleFactor

        val centerX = (width - scaledWidth) / 2 + translateX
        val centerY = (height - scaledHeight) / 2 + translateY

        baseBitmap?.let { base ->
            canvas.save()
            canvas.translate(centerX, centerY)
            val baseScaleX = scaledWidth / base.width.toFloat()
            val baseScaleY = scaledHeight / base.height.toFloat()
            canvas.scale(baseScaleX, baseScaleY)
            canvas.drawBitmap(base, 0f, 0f, paint)
            canvas.restore()
        }

        for (tile in activeTiles.values) {
            if (tile.scale != scaleFactor) continue

            canvas.save()
            canvas.translate(centerX + tile.x, centerY + tile.y)
            val drawRect = android.graphics.Rect(0, 0, tile.bitmap.width, tile.bitmap.height)
            canvas.drawBitmap(tile.bitmap, drawRect, drawRect, paint)
            canvas.restore()
        }
    }

    // ── continuous mode ──────────────────────────────────────────────────────

    /**
     * Progressively measure every page's point size on a background thread and
     * build a fit-to-width vertical layout. Pages become visible as soon as they
     * are measured, so the first page paints without waiting for a huge document.
     */
    private fun startContinuousMeasurement() {
        val doc = pdfDocument ?: return
        val w = width
        if (w <= 0) return

        measureFuture?.cancel(true)
        clearPageBitmaps()

        val count = doc.getPageCount()
        cPtsW = FloatArray(count)
        cPtsH = FloatArray(count)
        cTop = FloatArray(count)
        cH = FloatArray(count)
        cMeasured = 0
        cContentH1 = 0f
        cLayoutWidth = w
        cZoom = 1f
        cScrollY = 0f
        cTransX = 0f

        measureFuture = renderExecutor.submit {
            var y = 0f
            for (i in 0 until count) {
                if (Thread.currentThread().isInterrupted) return@submit
                var ptsW = 0
                var ptsH = 0
                synchronized(docLock) {
                    val d = pdfDocument ?: return@submit
                    try {
                        val p = d.openPage(i)
                        ptsW = p.getPageWidthPoint()
                        ptsH = p.getPageHeightPoint()
                        p.close()
                    } catch (_: Exception) { }
                }
                if (ptsW <= 0 || ptsH <= 0) { ptsW = 612; ptsH = 792 }
                val h1 = w.toFloat() * ptsH / ptsW
                cPtsW[i] = ptsW.toFloat()
                cPtsH[i] = ptsH.toFloat()
                cTop[i] = y
                cH[i] = h1
                y += h1 + gapPx
                // Publish measured count after the arrays are written.
                cContentH1 = y - gapPx
                cMeasured = i + 1
                if (i < 4 || i % 48 == 0) {
                    post {
                        if (cLayoutWidth == w) {
                            applyPendingScroll()
                            invalidate()
                            // Render the pages already in view instead of waiting
                            // for the whole (possibly huge) document to measure.
                            scheduleContinuousRender()
                            emitPagesLayout()
                        }
                    }
                }
            }
            post {
                if (cLayoutWidth == w) {
                    applyPendingScroll()
                    scheduleContinuousRender()
                    invalidate()
                    emitPagesLayout()
                }
            }
        }
    }

    private fun cancelContinuous() {
        measureFuture?.cancel(true)
        measureFuture = null
        flingAnimator?.cancel()
        flingAnimator = null
        handler?.removeCallbacks(continuousRenderRunnable)
        removeCallbacks(continuousRenderRunnable)
        clearPageBitmaps()
        cMeasured = 0
        cContentH1 = 0f
        cLayoutWidth = 0
    }

    private fun clearPageBitmaps() {
        pageRenderFutures.values.forEach { it.cancel(true) }
        pageRenderFutures.clear()
        pageBitmaps.values.forEach { it.recycle() }
        pageBitmaps.clear()
        pageBitmapWidth.clear()
    }

    private fun applyPendingScroll() {
        val target = pendingScrollPage ?: return
        if (target < cMeasured) {
            pendingScrollPage = null
            cScrollY = (cTop[target] * cZoom).coerceIn(0f, continuousMaxScroll())
        }
    }

    private fun scrollToPageContinuous(index: Int) {
        if (index in 0 until cMeasured) {
            flingAnimator?.cancel()
            cScrollY = (cTop[index] * cZoom).coerceIn(0f, continuousMaxScroll())
            clampContinuousScroll()
            scheduleContinuousRender()
            invalidate()
            emitPagesLayout()
        } else {
            pendingScrollPage = index
        }
    }

    private fun continuousMaxScroll(): Float {
        val total = cContentH1 * cZoom
        val viewport = height - contentInsetTopPx - contentInsetBottomPx
        return max(0f, total - viewport)
    }

    private fun clampContinuousScroll() {
        cScrollY = cScrollY.coerceIn(0f, continuousMaxScroll())
        val pageScreenW = width * cZoom
        cTransX = if (pageScreenW <= width) 0f
                  else cTransX.coerceIn(-(pageScreenW - width) / 2, (pageScreenW - width) / 2)
    }

    private fun onTouchContinuous(event: MotionEvent): Boolean {
        scaleDetectorContinuous.onTouchEvent(event)
        gestureDetectorContinuous.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flingAnimator?.cancel()
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetectorContinuous.isInProgress) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        cScrollY -= (y - lastTouchY)
                        cTransX += (x - lastTouchX)
                        clampContinuousScroll()
                        invalidate()
                        emitPagesLayout()
                        scheduleContinuousRender()
                        lastTouchX = x
                        lastTouchY = y
                    }
                } else {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        lastTouchX = event.getX(pointerIndex)
                        lastTouchY = event.getY(pointerIndex)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newIndex)
                        lastTouchX = event.getX(newIndex)
                        lastTouchY = event.getY(newIndex)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                scheduleContinuousRender()
            }
        }
        return true
    }

    private fun zoomContinuousAbout(focusX: Float, focusY: Float, oldZoom: Float, newZoom: Float) {
        // Keep the content point under the focus fixed across the zoom change.
        val contentY = (focusY - contentInsetTopPx + cScrollY) / oldZoom
        val baseX = (width - width * oldZoom) / 2 + cTransX
        val contentX = (focusX - baseX) / oldZoom

        cZoom = newZoom
        cScrollY = contentInsetTopPx + contentY * newZoom - focusY
        cTransX = focusX - (width - width * newZoom) / 2 - contentX * newZoom
        clampContinuousScroll()
        invalidate()
        emitPagesLayout()
        scheduleContinuousRender()
    }

    private fun animateContinuousZoom(target: Float, focusX: Float, focusY: Float) {
        flingAnimator?.cancel()
        val start = cZoom
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val z = start + (target - start) * f
                val old = cZoom
                if (z != old) zoomContinuousAbout(focusX, focusY, old, z)
            }
            start()
        }.also { flingAnimator = it }
    }

    private fun startContinuousFling(velocityY: Float) {
        val maxScroll = continuousMaxScroll()
        if (maxScroll <= 0f) return
        flingAnimator?.cancel()
        val start = cScrollY
        // Project a simple decelerating fling distance from the fling velocity.
        val distance = (-velocityY * 0.20f)
        val target = (start + distance).coerceIn(0f, maxScroll)
        if (kotlin.math.abs(target - start) < 1f) return
        android.animation.ValueAnimator.ofFloat(start, target).apply {
            duration = 380
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                cScrollY = (a.animatedValue as Float).coerceIn(0f, continuousMaxScroll())
                invalidate()
                emitPagesLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    scheduleContinuousRender()
                }
            })
            start()
        }.also { flingAnimator = it }
    }

    private fun scheduleContinuousRender() {
        removeCallbacks(continuousRenderRunnable)
        postDelayed(continuousRenderRunnable, CONTINUOUS_RENDER_DELAY_MS)
    }

    private fun visiblePageRange(): IntRange? {
        val measured = cMeasured
        if (measured == 0) return null
        val zoom = cZoom
        var first = -1
        var last = -1
        for (i in 0 until measured) {
            val yTop = cTop[i] * zoom - cScrollY + contentInsetTopPx
            val yBottom = yTop + cH[i] * zoom
            if (yBottom < 0f) continue
            if (yTop > height.toFloat()) break
            if (first == -1) first = i
            last = i
        }
        if (first == -1) return null
        return first..last
    }

    private fun renderVisiblePages() {
        val range = visiblePageRange() ?: return
        val zoom = cZoom
        // Also render one page above/below the viewport so pages are ready
        // before they scroll into view — keeps fast scrolling from flashing white.
        val first = max(0, range.first - 1)
        val last = min(cMeasured - 1, range.last + 1)
        val renderRange = first..last
        val keep = renderRange.toSet()

        for (i in renderRange) {
            val targetW = computeRenderWidth(i, zoom)
            val haveW = pageBitmapWidth[i]
            val needs = pageBitmaps[i] == null ||
                haveW == null ||
                kotlin.math.abs(haveW - targetW) > max(2, targetW / 20)
            if (needs && !pageRenderFutures.containsKey(i)) {
                submitPageRender(i, targetW)
            }
        }

        // Evict cached pages outside the render window when over budget.
        if (pageBitmaps.size > MAX_CACHED_PAGES) {
            val evictable = pageBitmaps.keys.filter { it !in keep }.sortedByDescending {
                min(kotlin.math.abs(it - range.first), kotlin.math.abs(it - range.last))
            }
            for (k in evictable) {
                if (pageBitmaps.size <= MAX_CACHED_PAGES) break
                pageBitmaps.remove(k)?.recycle()
                pageBitmapWidth.remove(k)
                pageRenderFutures.remove(k)?.cancel(true)
            }
        }
    }

    private fun computeRenderWidth(i: Int, zoom: Float): Int {
        val onScreenW = (width * zoom)
        val onScreenH = (cH[i] * zoom)
        var w = onScreenW
        var h = onScreenH
        val px = w.toDouble() * h.toDouble()
        if (px > MAX_BITMAP_PIXELS) {
            val s = sqrt(MAX_BITMAP_PIXELS / px).toFloat()
            w *= s; h *= s
        }
        return max(1, w.toInt())
    }

    private fun submitPageRender(i: Int, targetW: Int) {
        val ptsW = cPtsW.getOrNull(i) ?: return
        val ptsH = cPtsH.getOrNull(i) ?: return
        if (ptsW <= 0f || ptsH <= 0f) return
        val targetH = max(1, (targetW * (ptsH / ptsW)).toInt())
        val future = renderExecutor.submit {
            try {
                var bitmap: Bitmap? = null
                synchronized(docLock) {
                    val d = pdfDocument ?: return@submit
                    val page = d.openPage(i)
                    try {
                        val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565)
                        bmp.eraseColor(Color.WHITE)
                        page.renderPageBitmap(bmp, 0, 0, targetW, targetH, renderAnnot = true)
                        bitmap = bmp
                    } finally {
                        page.close()
                    }
                }
                val out = bitmap ?: return@submit
                post {
                    if (!isContinuous) { out.recycle(); return@post }
                    pageBitmaps.put(i, out)?.recycle()
                    pageBitmapWidth[i] = targetW
                    invalidate()
                }
            } catch (_: Exception) {
            } finally {
                pageRenderFutures.remove(i)
            }
        }
        pageRenderFutures[i] = future
    }

    private fun drawContinuous(canvas: Canvas) {
        val range = visiblePageRange() ?: return
        val zoom = cZoom
        val pageScreenW = width * zoom
        val x = (width - pageScreenW) / 2 + cTransX

        for (i in range) {
            val yTop = cTop[i] * zoom - cScrollY + contentInsetTopPx
            val pageScreenH = cH[i] * zoom
            val dest = RectF(x, yTop, x + pageScreenW, yTop + pageScreenH)
            // White page background (also shown while its bitmap renders).
            canvas.drawRect(dest, whitePagePaint)
            val bmp = pageBitmaps[i]
            if (bmp != null && !bmp.isRecycled) {
                canvas.drawBitmap(bmp, null, dest, paint)
            }
        }
    }

    private val whitePagePaint = Paint().apply { color = Color.WHITE }

    /**
     * Dispatch a direct event through the modern EventDispatcher. This works on
     * both the New Architecture (Fabric) and the old architecture, unlike the
     * legacy RCTEventEmitter.receiveEvent path.
     */
    private class PdfDirectEvent(
        surfaceId: Int,
        viewTag: Int,
        private val evtName: String,
        private val evtData: WritableMap
    ) : Event<PdfDirectEvent>(surfaceId, viewTag) {
        override fun getEventName(): String = evtName
        override fun getEventData(): WritableMap = evtData
    }

    private fun emitEvent(name: String, payload: WritableMap) {
        val reactContext = context as? ReactContext ?: return
        val surfaceId = UIManagerHelper.getSurfaceId(reactContext)
        val dispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, id)
        dispatcher?.dispatchEvent(PdfDirectEvent(surfaceId, id, name, payload))
    }

    private fun sendLoadComplete(page: Int, width: Int, height: Int) {
        emitEvent("onLoadComplete", Arguments.createMap().apply {
            putInt("currentPage", page + 1)
            putInt("width", width)
            putInt("height", height)
        })
    }

    private fun sendError(message: String) {
        emitEvent("onError", Arguments.createMap().apply {
            putString("message", message)
        })
    }

    private fun sendPageCount(count: Int) {
        emitEvent("onPageCount", Arguments.createMap().apply {
            putInt("numberOfPages", count)
        })
    }

    private fun sendPasswordRequired() {
        emitEvent("onPasswordRequired", Arguments.createMap())
    }

    /**
     * Emit the current page's on-screen rect (view px) so a JS overlay
     * (annotation layer) can stay glued to the page during pan/zoom. Mirrors the
     * geometry used by onDraw: content origin is the centered page plus the pan
     * translation, sized by the current zoom scale.
     */
    private fun emitTransform() {
        if (pageWidth == 0 || pageHeight == 0) return
        val scaledWidth = pageWidth * scaleFactor
        val scaledHeight = pageHeight * scaleFactor
        val contentX = (width - scaledWidth) / 2 + translateX
        val contentY = (height - scaledHeight) / 2 + translateY
        emitEvent("onTransform", Arguments.createMap().apply {
            putInt("page", pageIndex)
            putDouble("x", contentX.toDouble())
            putDouble("y", contentY.toDouble())
            putDouble("width", scaledWidth.toDouble())
            putDouble("height", scaledHeight.toDouble())
        })
    }

    /**
     * Continuous mode: emit every visible page's on-screen rect (view px) plus
     * its PDF point size, as a JSON string, so a JS overlay can position per-page
     * annotations. Matches the iOS `onPagesLayout` payload shape.
     */
    private fun emitPagesLayout() {
        val range = visiblePageRange() ?: run {
            emitEvent("onPagesLayout", Arguments.createMap().apply { putString("pages", "[]") })
            return
        }
        val zoom = cZoom
        val pageScreenW = width * zoom
        val x = (width - pageScreenW) / 2 + cTransX
        val arr = JSONArray()
        for (i in range) {
            val yTop = cTop[i] * zoom - cScrollY + contentInsetTopPx
            val pageScreenH = cH[i] * zoom
            arr.put(JSONObject().apply {
                put("page", i)
                put("x", x.toDouble())
                put("y", yTop.toDouble())
                put("width", pageScreenW.toDouble())
                put("height", pageScreenH.toDouble())
                put("ptsW", cPtsW[i].toDouble())
                put("ptsH", cPtsH[i].toDouble())
            })
        }
        emitEvent("onPagesLayout", Arguments.createMap().apply { putString("pages", arr.toString()) })
    }

    fun cleanup() {
        tileHandler.removeCallbacks(tileUpdateRunnable)
        removeCallbacks(continuousRenderRunnable)
        flingAnimator?.cancel()
        pendingBaseRenderFuture?.cancel(true)
        clearTiles()
        cancelContinuous()
        renderExecutor.shutdownNow()

        baseBitmap?.recycle()
        baseBitmap = null

        closePdfDocument()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }
}
