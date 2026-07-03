import UIKit
import CoreGraphics
import PDFKit


import UIKit
import CoreGraphics

/// A view that renders a PDF page using CATiledLayer for efficient tiled rendering.
///
/// CATiledLayer divides the content into tiles, allowing smooth zooming and scrolling
/// without rendering the entire PDF at once. This is essential for large PDF documents.

// MARK: - Global cache of flattened pages
final class FlattenedPageCache {
    static let shared = FlattenedPageCache()

    private let cache = NSCache<NSString, FlattenedPageEntry>()

    private init() {
        cache.countLimit = 10
    }

    func entry(forKey key: String) -> FlattenedPageEntry? {
        cache.object(forKey: key as NSString)
    }

    func setEntry(_ entry: FlattenedPageEntry, forKey key: String) {
        cache.setObject(entry, forKey: key as NSString)
    }

    func removeEntry(forKey key: String) {
        cache.removeObject(forKey: key as NSString)
    }

    func removeAll() {
        cache.removeAllObjects()
    }
}

final class FlattenedPageEntry: NSObject {
    let document: CGPDFDocument
    let page: CGPDFPage

    init(document: CGPDFDocument, page: CGPDFPage) {
        self.document = document
        self.page = page
    }
}

// MARK: - Atomic flag for thread-safe cancellation

final class OSAtomicFlag {
    private var _value: Bool = false
    private var _lock = os_unfair_lock()

    func set() {
        os_unfair_lock_lock(&_lock)
        _value = true
        os_unfair_lock_unlock(&_lock)
    }

    func isSet() -> Bool {
        os_unfair_lock_lock(&_lock)
        let v = _value
        os_unfair_lock_unlock(&_lock)
        return v
    }
}

// MARK: - TiledPDFPageView

final class TiledPDFPageView: UIView {

    // MARK: - Properties

    var pdfPage: CGPDFPage? {
        didSet { setNeedsDisplay() }
    }

    var pdfURL: URL?
    var pageIndex: Int = 0

    var enableAntialiasing: Bool = true {
        didSet { setNeedsDisplay() }
    }

    var configuration: PDFConfiguration = .default {
        didSet { applyConfiguration() }
    }

    override class var layerClass: AnyClass { CATiledLayer.self }

    private var tiledLayer: CATiledLayer { layer as! CATiledLayer }

    private var _activePage: CGPDFPage?
    private var _pdfKitDocument: PDFDocument?
    private var _generation: UInt = 0
    private let _cancelled = OSAtomicFlag()

    // MARK: - Initialization

    override init(frame: CGRect) {
        super.init(frame: frame)
        commonInit()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        commonInit()
    }

    convenience init(frame: CGRect, configuration: PDFConfiguration) {
        self.init(frame: frame)
        self.configuration = configuration
        applyConfiguration()
    }

    private func commonInit() {
        applyConfiguration()
        contentScaleFactor = UIScreen.main.scale
        backgroundColor = .white
    }

    // MARK: - Configuration

    private func applyConfiguration() {
        tiledLayer.tileSize = configuration.tileSize
        tiledLayer.levelsOfDetail = configuration.levelsOfDetail
        tiledLayer.levelsOfDetailBias = configuration.levelsOfDetailBias
        enableAntialiasing = configuration.enableAntialiasing
    }

    // MARK: - Cancellation

    func cancel() {
        _cancelled.set()
    }

    private var currentCacheKey: String {
        guard let url = pdfURL else { return "" }
        return "\(url.absoluteString)_page\(pageIndex)"
    }

    // MARK: - Prepare Page (Flatten Pipeline)

    func preparePage() {
        _generation &+= 1
        let currentGen = _generation

        let cacheKey = currentCacheKey
        if let cached = FlattenedPageCache.shared.entry(forKey: cacheKey) {
            _activePage = cached.page
            setNeedsDisplay()
            return
        }

        _activePage = pdfPage

        guard let url = pdfURL,
              let originalPage = pdfPage else { return }

        let idx = pageIndex
        let originalCropBox = originalPage.getBoxRect(.cropBox)
        let rotation = originalPage.rotationAngle

        let pdfKitDoc: PDFDocument
        if let existing = _pdfKitDocument {
            pdfKitDoc = existing
        } else if let newDoc = PDFDocument(url: url) {
            _pdfKitDocument = newDoc
            pdfKitDoc = newDoc
        } else {
            return
        }

        guard let pdfKitPage = pdfKitDoc.page(at: idx) else { return }
        let annotations = pdfKitPage.annotations
        if annotations.isEmpty { return }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }

            let entry = Self.createFlattenedEntry(
                pdfKitPage: pdfKitPage,
                originalCropBox: originalCropBox,
                rotation: Int(rotation)
            )

            guard let entry = entry else { return }
            FlattenedPageCache.shared.setEntry(entry, forKey: cacheKey)

            DispatchQueue.main.async { [weak self] in
                guard let self = self, self._generation == currentGen else { return }
                self._activePage = entry.page
                self.setNeedsDisplay()
            }
        }
    }

    // MARK: - Prefetch

    /// Warms the flattened-page cache for a neighboring page off the main
    /// thread, so switching to it does not incur the flatten pipeline on
    /// display. No-op if the page has no annotations or is already cached.
    static func prewarm(url: URL, pageIndex: Int) {
        guard pageIndex >= 0 else { return }

        let cacheKey = "\(url.absoluteString)_page\(pageIndex)"
        if FlattenedPageCache.shared.entry(forKey: cacheKey) != nil { return }

        DispatchQueue.global(qos: .utility).async {
            guard let document = PDFDocument(url: url),
                  let page = document.page(at: pageIndex) else { return }

            let annotations = page.annotations
            if annotations.isEmpty { return }

            let cropBox = page.bounds(for: .cropBox)
            let rotation = page.rotation

            guard FlattenedPageCache.shared.entry(forKey: cacheKey) == nil,
                  let entry = createFlattenedEntry(
                      pdfKitPage: page,
                      originalCropBox: cropBox,
                      rotation: rotation
                  ) else { return }

            FlattenedPageCache.shared.setEntry(entry, forKey: cacheKey)
        }
    }

    // MARK: - Flattening Logic
    private static func createFlattenedEntry(
        pdfKitPage: PDFPage,
        originalCropBox: CGRect,
        rotation: Int
    ) -> FlattenedPageEntry? {
        let finalSize: CGSize
        if rotation == 90 || rotation == 270 {
            finalSize = CGSize(width: originalCropBox.height, height: originalCropBox.width)
        } else {
            finalSize = originalCropBox.size
        }

        var mediaBox = CGRect(origin: .zero, size: finalSize)

        let pdfData = NSMutableData()
        guard let consumer = CGDataConsumer(data: pdfData as CFMutableData),
              let pdfContext = CGContext(consumer: consumer, mediaBox: &mediaBox, nil) else {
            return nil
        }

        pdfContext.beginPage(mediaBox: &mediaBox)

        if originalCropBox.origin.x != 0 || originalCropBox.origin.y != 0 {
            pdfContext.translateBy(x: -originalCropBox.origin.x, y: -originalCropBox.origin.y)
        }

        pdfKitPage.draw(with: .cropBox, to: pdfContext)

        pdfContext.endPage()
        pdfContext.closePDF()

        guard let provider = CGDataProvider(data: pdfData as CFData),
              let flattenedDoc = CGPDFDocument(provider),
              let flattenedCGPage = flattenedDoc.page(at: 1) else {
            return nil
        }

        return FlattenedPageEntry(document: flattenedDoc, page: flattenedCGPage)
    }

    // MARK: - Rendering

    override func draw(_ rect: CGRect) {
        guard !_cancelled.isSet() else { return }

        guard let context = UIGraphicsGetCurrentContext(),
              let page = _activePage else { return }

        renderPage(page, in: context, rect: rect)
    }

    private func renderPage(_ page: CGPDFPage, in context: CGContext, rect: CGRect) {
        context.saveGState()
        defer { context.restoreGState() }

        context.setShouldAntialias(enableAntialiasing)
        context.interpolationQuality = determineInterpolationQuality()

        context.setFillColor(UIColor.white.cgColor)
        context.fill(rect)

        context.translateBy(x: 0, y: bounds.height)
        context.scaleBy(x: 1.0, y: -1.0)

        let pdfTransform = page.getDrawingTransform(
            .cropBox,
            rect: bounds,
            rotate: 0,
            preserveAspectRatio: true
        )
        context.concatenate(pdfTransform)
        // Draw the page
        context.drawPDFPage(page)
    }

    private func determineInterpolationQuality() -> CGInterpolationQuality {
        tiledLayer.levelsOfDetail == 1 ? .low : .high
    }
}

// MARK: - Public Methods

extension TiledPDFPageView {

    func prepareForReuse() {
        pdfPage = nil
        _activePage = nil
        layer.contents = nil
    }

    /// Updates the view size to match the page dimensions.
    func updateFrame(for page: CGPDFPage) {
        let pageRect = page.getBoxRect(.cropBox)
        frame = CGRect(origin: .zero, size: pageRect.size)
        pdfPage = page
    }

    func invalidateCache() {
        FlattenedPageCache.shared.removeEntry(forKey: currentCacheKey)
        _activePage = pdfPage
        _pdfKitDocument = nil
        setNeedsDisplay()
    }

    func invalidateAllCache() {
        FlattenedPageCache.shared.removeAll()
        _pdfKitDocument = nil
        _activePage = pdfPage
        setNeedsDisplay()
    }
}
