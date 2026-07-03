package com.reactnativepdfturbo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * View otimizada para renderização de PDF no Android
 * 
 * Usa PdfiumCore (pdfiumandroid) para renderização eficiente com suporte a Annots
 * com suporte a zoom via ScaleGestureDetector e pan via GestureDetector
 */
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
    }

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
    
    private val activeTiles = ConcurrentHashMap<String, Tile>()
    private val tileFutures = ConcurrentHashMap<String, Future<*>>()
    private var pendingBaseRenderFuture: Future<*>? = null
    private val renderExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    
    private var source: String = ""
    private var pageIndex: Int = 0
    private var maximumZoom: Float = 5.0f
    private var enableAntialiasing: Boolean = true
    private var password: String = ""
    
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
            pendingPageIndex = pageIndex
            requestLayout()
            invalidate()
        }
    }

    fun setPage(page: Int) {
        if (this.pageIndex != page) {
            this.pageIndex = page
            if (pdfDocument != null) {
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

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        
        if (width > 0 && height > 0 && needsLoad) {
            needsLoad = false
            loadPdf()
        } else if (changed && baseBitmap != null) {
            calculateFitScale()
            constrainTranslation()
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
            
            val targetPage = pendingPageIndex ?: 0
            pendingPageIndex = null
            displayPage(targetPage.coerceIn(0, pageCount - 1))
            
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
        val maxPixels = 4000000.0
        val bitmapPixels = (pageWidth.toDouble() * scale) * (pageHeight.toDouble() * scale)
        if (bitmapPixels > maxPixels) {
            scale = Math.sqrt(maxPixels / (pageWidth.toDouble() * pageHeight.toDouble())).toFloat()
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

    /**
     * Dispatch a direct event through the modern EventDispatcher. This works on
     * both the New Architecture (Fabric) and the old architecture, unlike the
     * legacy RCTEventEmitter.receiveEvent path.
     */
    private fun emitEvent(name: String, payload: WritableMap) {
        val reactContext = context as? ReactContext ?: return
        val surfaceId = UIManagerHelper.getSurfaceId(reactContext)
        val dispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, id)
        dispatcher?.dispatchEvent(object : Event<Event<*>>(surfaceId, id) {
            override fun getEventName(): String = name
            override fun getEventData(): WritableMap = payload
        })
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

    fun cleanup() {
        tileHandler.removeCallbacks(tileUpdateRunnable)
        pendingBaseRenderFuture?.cancel(true)
        clearTiles()
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