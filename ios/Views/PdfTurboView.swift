import UIKit
import React
import PDFKit

/// Protocol for receiving PDF view events.
@objc protocol PdfTurboViewDelegate: AnyObject {
    @objc optional func pdfView(_ pdfView: PdfTurboView, didFailWithError error: Error)
    @objc optional func pdfView(_ pdfView: PdfTurboView, didLoadWithPageCount pageCount: Int)
    @objc optional func pdfView(_ pdfView: PdfTurboView, didDisplayPage page: Int, size: CGSize)
    @objc optional func pdfViewDidRequestPassword(_ pdfView: PdfTurboView)
}

/// A high-performance PDF view using CATiledLayer for smooth zooming and scrolling.
///
/// This view is optimized for React Native integration, providing callbacks for
/// load completion, errors, and page navigation events.
@objc(PdfTurboView)
public final class PdfTurboView: UIScrollView {

    // MARK: - React Native Properties

    /// Path to the PDF file to display.
    @objc public var source: String = "" {
        didSet {
            handleSourceChange()
        }
    }

    /// Current page index (0-based).
    @objc public var page: NSNumber = 0 {
        didSet {
            handlePageChange(to: page.intValue)
        }
    }

    /// Maximum zoom scale.
    @objc public var maximumZoom: NSNumber = 5.0 {
        didSet {
            configuration.maximumZoom = CGFloat(truncating: maximumZoom)
            applyZoomConfiguration()
        }
    }

    /// Whether antialiasing is enabled.
    @objc public var enableAntialiasing: Bool = true {
        didSet {
            configuration.enableAntialiasing = enableAntialiasing
            tiledPageView?.enableAntialiasing = enableAntialiasing
        }
    }

    /// Password for encrypted PDFs.
    @objc public var password: String = "" {
        didSet {
            configuration.password = password.isEmpty ? nil : password
            retryLoadIfNeeded()
        }
    }

    /// When false, the view stops handling its own pan/zoom so touches fall
    /// through to a parent scroll container. Used by the continuous-scroll mode
    /// where an RN ScrollView drives vertical scrolling and each page is a
    /// non-interactive turbo tile.
    @objc public var gesturesEnabled: Bool = true {
        didSet {
            isScrollEnabled = gesturesEnabled
            pinchGestureRecognizer?.isEnabled = gesturesEnabled
            panGestureRecognizer.isEnabled = gesturesEnabled
        }
    }

    /// "paged" (one page + pinch-zoom) or "continuous" (all pages stacked
    /// vertically, fit-to-width, with native scroll + pinch-zoom).
    @objc public var scrollMode: String = "continuous" {
        didSet {
            guard scrollMode != oldValue, pdfDocument != nil else { return }
            rebuildForMode()
        }
    }

    /// Continuous mode: top/bottom content inset (px) so the document clears a
    /// floating header / toolbar. First page starts below `contentInsetTop`.
    @objc public var contentInsetTop: NSNumber = 0 { didSet { applyContinuousInset() } }
    @objc public var contentInsetBottom: NSNumber = 0 { didSet { applyContinuousInset() } }

    // MARK: - React Native Event Callbacks

    @objc public var onError: RCTDirectEventBlock?
    @objc public var onLoadComplete: RCTDirectEventBlock?
    @objc public var onPageCount: RCTDirectEventBlock?
    @objc public var onPasswordRequired: RCTDirectEventBlock?
    /// Emitted continuously with the current page's on-screen geometry so a JS
    /// overlay (annotation layer) can stay glued to the page during scroll/zoom.
    @objc public var onTransform: RCTDirectEventBlock?
    /// Continuous mode: emitted with every visible page's on-screen rect (view px)
    /// so a JS overlay can position per-page annotations while scrolling/zooming.
    @objc public var onPagesLayout: RCTDirectEventBlock?

    // MARK: - Public Properties

    weak var pdfDelegate: PdfTurboViewDelegate?

    /// Current number of pages in the document.
    private(set) var pageCount: Int = 0

    // MARK: - Private Properties

    private var pdfDocument: CGPDFDocument?
    private var tiledPageView: TiledPDFPageView!
    private var pdfURL: URL?
    private var configuration = PDFConfiguration.default
    private let documentLoader: PDFDocumentLoading

    private var pendingPageIndex: Int?
    private var needsLoad = false
    private var lastBoundsSize: CGSize = .zero
    private var currentPageRect: CGRect?
    private var initialZoomScale: CGFloat = 1.0
    /// 0-based index of the page currently on screen (for onTransform payloads).
    private var currentIndex: Int = 0

    // ── continuous-mode state ────────────────────────────────────────────────
    private var isContinuous: Bool { scrollMode == "continuous" }
    private var contentContainer: UIView?
    private var continuousPageViews: [TiledPDFPageView] = []
    private var continuousFrames: [CGRect] = []     // fit-width frames (zoom=1)
    private var continuousPts: [CGSize] = []         // each page's point size
    private var continuousLaidOut = false
    private var continuousLayoutWidth: CGFloat = 0
    private let continuousGap: CGFloat = 12

    // MARK: - Initialization

    public override init(frame: CGRect) {
        self.tiledPageView = TiledPDFPageView(frame: frame)
        self.documentLoader = PDFDocumentLoader.shared
        super.init(frame: frame)
        setupView()
    }

    public required init?(coder: NSCoder) {
        self.tiledPageView = TiledPDFPageView(frame: .zero)
        self.documentLoader = PDFDocumentLoader.shared
        super.init(coder: coder)
        setupView()
    }

    /// Initializes with a custom document loader (useful for testing).
    init(frame: CGRect, documentLoader: PDFDocumentLoading) {
        self.tiledPageView = TiledPDFPageView(frame: frame)
        self.documentLoader = documentLoader
        super.init(frame: frame)
        setupView()
    }

    // MARK: - Setup

    private func setupView() {
        setupScrollView()
        setupTiledPageView()
    }

    private func setupScrollView() {
        delegate = self
        showsHorizontalScrollIndicator = false
        showsVerticalScrollIndicator = false
        bouncesZoom = true
        applyZoomConfiguration()
    }

    private func setupTiledPageView() {
        tiledPageView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        tiledPageView.configuration = configuration
        addSubview(tiledPageView)
    }

    private func applyZoomConfiguration() {
        maximumZoomScale = configuration.maximumZoom

        guard let pageRect = currentPageRect else { return }

        let fitScale = calculateFitScale(for: pageRect)
        minimumZoomScale = fitScale
        initialZoomScale = fitScale
    }
}

// MARK: - Document Loading

private extension PdfTurboView {

    func handleSourceChange() {
        resetDocument()
        pdfURL = nil
        FlattenedPageCache.shared.removeAll()
        teardownContinuous()
        pendingPageIndex = page.intValue
        needsLoad = true
        setNeedsLayout()
    }

    func handlePageChange(to index: Int) {
        guard let document = pdfDocument else {
            pendingPageIndex = index
            return
        }

        let validIndex = clampPageIndex(index, for: document)
        displayPage(at: validIndex)
    }

    func retryLoadIfNeeded() {
        guard !password.isEmpty, pdfDocument == nil, needsLoad else { return }
        setNeedsLayout()
    }

    func resetDocument() {
        pdfDocument = nil
        pageCount = 0
    }

    func loadDocument() {
        guard !source.isEmpty else { return }

        let path = source.hasPrefix("file://") ? String(source.dropFirst(7)) : source
        pdfURL = URL(fileURLWithPath: path)

        // Loading + unlocking a CGPDFDocument is synchronous and can block for
        // large or encrypted files. Do it off the main thread, then hop back to
        // the main queue for all UIKit work (display / event emission).
        let src = source
        let pwd = configuration.password
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            let result = self.documentLoader.loadDocument(from: src, password: pwd)

            DispatchQueue.main.async {
                // Bail out if the source changed while we were loading.
                guard self.source == src else { return }
                switch result {
                case .success(let document):
                    self.handleLoadSuccess(document: document)
                case .failure(let error):
                    self.handleLoadFailure(error: error)
                }
            }
        }
    }

    func handleLoadSuccess(document: CGPDFDocument) {
        pdfDocument = document
        pageCount = document.numberOfPages

        // Emit page count event on next run loop to ensure listeners are connected
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.onPageCount?(["numberOfPages": NSNumber(value: self.pageCount)])
            self.pdfDelegate?.pdfView?(self, didLoadWithPageCount: self.pageCount)
        }

        if isContinuous {
            continuousLaidOut = false
            setNeedsLayout()
            return
        }

        // Display pending or first page
        let targetIndex = pendingPageIndex ?? 0
        pendingPageIndex = nil
        let validIndex = clampPageIndex(targetIndex, for: document)
        displayPage(at: validIndex)
    }

    func handleLoadFailure(error: PDFError) {
        if case .passwordRequired = error {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.onPasswordRequired?([:])
                self.pdfDelegate?.pdfViewDidRequestPassword?(self)
            }
        }

        onError?(error.eventPayload)
        pdfDelegate?.pdfView?(self, didFailWithError: error)
    }

    func clampPageIndex(_ index: Int, for document: CGPDFDocument) -> Int {
        max(0, min(index, document.numberOfPages - 1))
    }
}

// MARK: - Page Display

private extension PdfTurboView {

    func displayPage(at index: Int) {
        guard let document = pdfDocument,
              let page = document.page(at: index + 1) else {
            return
        }

        let pageRect = page.getBoxRect(.cropBox)
        currentPageRect = pageRect
        currentIndex = index

        prepareForPageDisplay()

        tiledPageView?.cancel()
        tiledPageView?.removeFromSuperview()

        let newView = TiledPDFPageView(frame: CGRect(origin: .zero, size: pageRect.size))
        newView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        newView.configuration = configuration
        newView.pdfURL = pdfURL
        newView.pageIndex = index
        newView.pdfPage = page
        addSubview(newView)
        tiledPageView = newView

        contentSize = pageRect.size
        tiledPageView.preparePage()

        applyFitScale(for: pageRect)
        centerContent()
        emitTransform()
        notifyPageDisplayed(index: index, size: pageRect.size)
        prewarmNeighbors(around: index)
    }

    /// Pre-flattens the adjacent pages so a subsequent page change is instant.
    func prewarmNeighbors(around index: Int) {
        guard let url = pdfURL, let document = pdfDocument else { return }
        let lastIndex = document.numberOfPages - 1

        if index - 1 >= 0 {
            TiledPDFPageView.prewarm(url: url, pageIndex: index - 1)
        }
        if index + 1 <= lastIndex {
            TiledPDFPageView.prewarm(url: url, pageIndex: index + 1)
        }
    }

    func prepareForPageDisplay() {
        zoomScale = 1.0
        contentOffset = .zero
    }

    func applyFitScale(for pageRect: CGRect) {
        let fitScale = calculateFitScale(for: pageRect)
        minimumZoomScale = fitScale
        initialZoomScale = fitScale
        setZoomScale(fitScale, animated: false)
    }

    func notifyPageDisplayed(index: Int, size: CGSize) {
        let payload: [String: Any] = [
            "currentPage": index + 1,
            "width": size.width,
            "height": size.height
        ]

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.onLoadComplete?(payload)
            self.pdfDelegate?.pdfView?(self, didDisplayPage: index + 1, size: size)
        }
    }
}

// MARK: - Layout & Scaling

private extension PdfTurboView {

    func calculateFitScale(for pageRect: CGRect) -> CGFloat {
        guard bounds.width > 0,
              bounds.height > 0,
              pageRect.width > 0,
              pageRect.height > 0 else {
            return 1.0
        }

        let scaleX = bounds.width / pageRect.width
        let scaleY = bounds.height / pageRect.height
        let scale = min(scaleX, scaleY)

        guard scale.isFinite, scale > 0 else {
            return 1.0
        }

        return scale
    }

    func centerContent() {
        let boundsSize = bounds.size
        var frame = tiledPageView.frame

        frame.origin.x = frame.width < boundsSize.width
            ? (boundsSize.width - frame.width) * 0.5
            : 0

        frame.origin.y = frame.height < boundsSize.height
            ? (boundsSize.height - frame.height) * 0.5
            : 0

        tiledPageView.frame = frame
    }

    /// Emits the current page's on-screen rect (view px) so a JS overlay can
    /// track it. The tiled page view is the scroll view's zooming content view,
    /// so its frame is in scaled content space; subtracting contentOffset gives
    /// the page's position within this view's bounds.
    func emitTransform() {
        guard let onTransform = onTransform, currentPageRect != nil else { return }
        let frame = tiledPageView.frame
        onTransform([
            "page": currentIndex,
            "x": frame.origin.x - contentOffset.x,
            "y": frame.origin.y - contentOffset.y,
            "width": frame.width,
            "height": frame.height
        ])
    }
}

// MARK: - Continuous Mode

private extension PdfTurboView {

    func teardownContinuous() {
        continuousPageViews.forEach { $0.cancel(); $0.removeFromSuperview() }
        continuousPageViews.removeAll()
        continuousFrames.removeAll()
        contentContainer?.removeFromSuperview()
        contentContainer = nil
        continuousLaidOut = false
        continuousLayoutWidth = 0
    }

    /// Rebuild after a live scrollMode switch (rare — usually set once at mount).
    func rebuildForMode() {
        teardownContinuous()
        tiledPageView?.cancel()
        tiledPageView?.removeFromSuperview()
        let fresh = TiledPDFPageView(frame: bounds)
        fresh.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        fresh.configuration = configuration
        addSubview(fresh)
        tiledPageView = fresh
        currentPageRect = nil
        zoomScale = 1
        contentOffset = .zero
        if let doc = pdfDocument {
            if isContinuous {
                setNeedsLayout()
            } else {
                displayPage(at: clampPageIndex(currentIndex, for: doc))
            }
        }
    }

    /// Stack every page vertically, fit-to-width, inside a single zooming
    /// container. Each page is a CATiledLayer view that renders lazily.
    func layoutContinuous() {
        guard let document = pdfDocument, bounds.width > 0 else { return }

        contentContainer?.removeFromSuperview()
        continuousPageViews.removeAll()
        continuousFrames.removeAll()
        continuousPts.removeAll()

        zoomScale = 1
        let width = bounds.width
        let container = UIView(frame: CGRect(x: 0, y: 0, width: width, height: 0))
        var y: CGFloat = 0
        for i in 0..<document.numberOfPages {
            guard let pdfPage = document.page(at: i + 1) else { continue }
            let box = pdfPage.getBoxRect(.cropBox)
            let aspect = box.width > 0 ? box.height / box.width : 1.414
            let h = width * aspect
            let frame = CGRect(x: 0, y: y, width: width, height: h)
            let pv = TiledPDFPageView(frame: frame)
            pv.configuration = configuration
            pv.enableAntialiasing = configuration.enableAntialiasing
            pv.pdfURL = pdfURL
            pv.pageIndex = i
            pv.pdfPage = pdfPage
            container.addSubview(pv)
            pv.preparePage()
            continuousPageViews.append(pv)
            continuousFrames.append(frame)
            continuousPts.append(CGSize(width: box.width, height: box.height))
            y += h + continuousGap
        }
        let totalHeight = max(0, y - continuousGap)
        container.frame = CGRect(x: 0, y: 0, width: width, height: totalHeight)
        addSubview(container)
        contentContainer = container
        continuousLaidOut = true
        continuousLayoutWidth = width

        minimumZoomScale = 1
        maximumZoomScale = configuration.maximumZoom
        contentSize = container.frame.size
        applyContinuousInset()
        // Start scrolled to the very top, accounting for the top inset.
        contentOffset = CGPoint(x: 0, y: -contentInset.top)
    }

    func applyContinuousInset() {
        guard isContinuous else {
            contentInset = .zero
            return
        }
        contentInset = UIEdgeInsets(
            top: CGFloat(truncating: contentInsetTop),
            left: 0,
            bottom: CGFloat(truncating: contentInsetBottom),
            right: 0
        )
    }

    /// Emit every visible page's on-screen rect (viewport-relative px) so the JS
    /// overlay can position per-page annotations.
    func emitPagesLayout() {
        guard isContinuous, let onPagesLayout = onPagesLayout, contentContainer != nil else { return }
        var pages: [[String: Any]] = []
        let viewH = bounds.height
        for (i, pv) in continuousPageViews.enumerated() {
            // convert() accounts for the container's zoom transform; subtract
            // contentOffset to get a rect relative to the visible viewport.
            let contentRect = self.convert(pv.bounds, from: pv)
            let rect = CGRect(
                x: contentRect.origin.x - contentOffset.x,
                y: contentRect.origin.y - contentOffset.y,
                width: contentRect.width,
                height: contentRect.height
            )
            // Cull pages fully outside the viewport (± one page margin).
            if rect.maxY < -rect.height || rect.minY > viewH + rect.height { continue }
            let pts = i < continuousPts.count ? continuousPts[i] : CGSize(width: rect.width, height: rect.height)
            pages.append([
                "page": i,
                "x": rect.origin.x,
                "y": rect.origin.y,
                "width": rect.width,
                "height": rect.height,
                "ptsW": pts.width,
                "ptsH": pts.height
            ])
        }
        // Payload as a JSON string — avoids array-in-event codegen fragility and
        // works identically over the legacy-interop and Fabric event paths.
        if let data = try? JSONSerialization.data(withJSONObject: pages),
           let json = String(data: data, encoding: .utf8) {
            onPagesLayout(["pages": json])
        }
    }
}

// MARK: - UIScrollViewDelegate

extension PdfTurboView: UIScrollViewDelegate {

    public func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        isContinuous ? contentContainer : tiledPageView
    }

    public func scrollViewDidZoom(_ scrollView: UIScrollView) {
        if isContinuous {
            emitPagesLayout()
            return
        }
        centerContent()
        emitTransform()
    }

    public func scrollViewDidScroll(_ scrollView: UIScrollView) {
        if isContinuous {
            emitPagesLayout()
            return
        }
        emitTransform()
    }
}

// MARK: - Lifecycle

extension PdfTurboView {

    public override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            setNeedsLayout()
        }
    }

    public override func layoutSubviews() {
        super.layoutSubviews()

        guard bounds.width > 0, bounds.height > 0 else { return }

        if needsLoad {
            needsLoad = false
            loadDocument()
            lastBoundsSize = bounds.size
            return
        }

        if isContinuous {
            // (Re)build the stacked page layout once bounds are known, or when the
            // available width changes (rotation / resize) so pages re-fit width.
            if pdfDocument != nil && (!continuousLaidOut || abs(continuousLayoutWidth - bounds.width) > 0.5) {
                layoutContinuous()
            }
            emitPagesLayout()
            return
        }

        handleBoundsChange()

        // Recovery: if the page was fitted while our bounds were still zero/stale
        // (common when embedded in a parent scroll container that sizes us after
        // the async document load completes), the tiled layer ends up 0-height and
        // nothing renders. Re-fit once bounds are valid again.
        if let pageRect = currentPageRect, tiledPageView.frame.height <= 0 {
            applyFitScale(for: pageRect)
            centerContent()
            emitTransform()
        }
    }

    private func handleBoundsChange() {
        guard lastBoundsSize != bounds.size,
              let pageRect = currentPageRect else {
            centerContent()
            emitTransform()
            return
        }

        lastBoundsSize = bounds.size
        applyFitScale(for: pageRect)
        centerContent()
        emitTransform()
    }
}

// MARK: - Public Methods

extension PdfTurboView {

    /// Resets the zoom to fit the page.
    @objc func resetZoom() {
        UIView.animate(withDuration: configuration.zoomResetAnimationDuration) { [weak self] in
            guard let self = self else { return }
            self.setZoomScale(self.initialZoomScale, animated: false)
            self.centerContent()
        }
    }

    /// Navigates to a specific page.
    /// - Parameter index: The page index (0-based).
    @objc func goToPage(_ index: Int) {
        page = NSNumber(value: index)
    }
}
