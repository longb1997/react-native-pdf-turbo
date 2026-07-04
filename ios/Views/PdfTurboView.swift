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

    // MARK: - React Native Event Callbacks

    @objc public var onError: RCTDirectEventBlock?
    @objc public var onLoadComplete: RCTDirectEventBlock?
    @objc public var onPageCount: RCTDirectEventBlock?
    @objc public var onPasswordRequired: RCTDirectEventBlock?
    /// Emitted continuously with the current page's on-screen geometry so a JS
    /// overlay (annotation layer) can stay glued to the page during scroll/zoom.
    @objc public var onTransform: RCTDirectEventBlock?

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

// MARK: - UIScrollViewDelegate

extension PdfTurboView: UIScrollViewDelegate {

    public func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        tiledPageView
    }

    public func scrollViewDidZoom(_ scrollView: UIScrollView) {
        centerContent()
        emitTransform()
    }

    public func scrollViewDidScroll(_ scrollView: UIScrollView) {
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

        handleBoundsChange()
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
