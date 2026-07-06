import { ViewStyle } from 'react-native';

/**
 * Configuration for PDF source
 */
export interface PdfSource {
  /** URI of the PDF file (remote URL or local file path) */
  uri: string;
  /** Whether to cache the PDF file locally. Default: true */
  cache?: boolean;
  /** Custom filename for the cached file. If not provided, uses MD5 hash of URI */
  cacheFileName?: string;
  /** Cache expiration time in seconds. If not set, cache never expires */
  expiration?: number;
  /** HTTP method for download. Default: 'GET' */
  method?: string;
  /** HTTP headers for download request */
  headers?: Record<string, string>;
}

/**
 * Page dimensions returned by onLoadComplete event
 */
export interface PdfPageDimensions {
  width: number;
  height: number;
}

/**
 * On-screen geometry of the current page, in view pixels. Emitted continuously
 * while the page is displayed / scrolled / zoomed so a JS overlay (e.g. an
 * annotation layer) can stay glued to the page.
 *  - `page`          current page index (0-based)
 *  - `x` / `y`       page top-left on screen (view px)
 *  - `width`/`height` on-screen (zoom-scaled) page size (view px)
 * Map a point in page space (PDF points) to screen px with:
 *   screenX = x + (pointX / pageWidthPts)  * width
 *   screenY = y + (pointY / pageHeightPts) * height
 */
export interface PdfTransform {
  page: number;
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface PdfTransformEvent {
  nativeEvent: PdfTransform;
}

/** One visible page's on-screen rect (view px) + point size, in continuous mode. */
export interface PdfPageRect {
  page: number;
  x: number;
  y: number;
  width: number;
  height: number;
  /** Page size in PDF points (for mapping annotation geometry). */
  ptsW: number;
  ptsH: number;
}

export type PdfScrollMode = 'paged' | 'continuous';

/**
 * Error event from native module
 */
export interface PdfErrorEvent {
  nativeEvent: {
    message: string;
  };
}

/**
 * Props for PdfTurboView component
 */
export interface PdfTurboViewProps {
  /** PDF source configuration */
  source: PdfSource;
  /** Current page index (0-based) */
  page?: number;
  /** Password for encrypted PDF files */
  password?: string;
  /** Maximum zoom level. Default: 3 */
  maximumZoom?: number;
  /** Enable antialiasing for better rendering quality. Default: true */
  enableAntialiasing?: boolean;
  /**
   * When false, the view yields pan/zoom gestures to a parent scroll container.
   * Used to embed a page as a non-interactive tile inside an RN ScrollView
   * (continuous-scroll mode). Default: true.
   */
  gesturesEnabled?: boolean;
  /**
   * 'paged' (default) shows one page with pinch-zoom; 'continuous' stacks all
   * pages vertically with native scroll + pinch-zoom.
   */
  scrollMode?: PdfScrollMode;
  /** Continuous mode: top content inset in px (clears a floating header). */
  contentInsetTop?: number;
  /** Continuous mode: bottom content inset in px (clears a floating toolbar). */
  contentInsetBottom?: number;
  /** Show built-in navigation controls. Default: true */
  showNavigationControls?: boolean;
  /** Custom style for the container */
  style?: ViewStyle;
  /** Callback when PDF is loaded successfully */
  onLoadComplete?: (currentPage: number, dimensions: PdfPageDimensions) => void;
  /** Callback when an error occurs */
  onError?: (error: PdfErrorEvent) => void;
  /** Callback when page count is available */
  onPageCount?: (numberOfPages: number) => void;
  /** Callback when page changes */
  onPageChange?: (currentPage: number) => void;
  /** Callback when PDF requires a password */
  onPasswordRequired?: () => void;
  /**
   * Fired continuously with the current page's on-screen geometry (view px)
   * while it is displayed / scrolled / zoomed. Use it to keep a JS overlay
   * (annotation layer) aligned with the page. High-frequency during gestures.
   */
  onTransform?: (transform: PdfTransform) => void;
  /**
   * Continuous mode: fired with every visible page's on-screen rect (view px)
   * on scroll/zoom, so a JS overlay can position per-page annotations.
   */
  onPagesLayout?: (pages: PdfPageRect[]) => void;
}

/**
 * Native event for load complete
 */
export interface NativeLoadCompleteEvent {
  nativeEvent: {
    currentPage: number;
    width: number;
    height: number;
  };
}

/**
 * Native event for page count
 */
export interface NativePageCountEvent {
  nativeEvent: {
    numberOfPages: number;
  };
}

/**
 * Native event for the current page's on-screen transform.
 */
export interface NativeTransformEvent {
  nativeEvent: {
    page: number;
    x: number;
    y: number;
    width: number;
    height: number;
  };
}

/**
 * Props for navigation controls component
 */
export interface PdfNavigationControlsProps {
  currentPage: number;
  totalPages: number;
  onNextPage: () => void;
  onPrevPage: () => void;
  onPageChange: (page: number) => void;
  style?: ViewStyle;
}

/**
 * Props for loading overlay component
 */
export interface PdfLoadingOverlayProps {
  progress: number;
  style?: ViewStyle;
}

/**
 * Props for error overlay component
 */
export interface PdfErrorOverlayProps {
  error: string;
  style?: ViewStyle;
}

export interface NativePdfTurboViewProps {
  /** PDF source configuration */
  source: string;
  /** Current page index (0-based) */
  page: number;
  /** Password for encrypted PDF files */
  password?: string;
  /** Maximum zoom level. Default: 3 */
  maximumZoom?: number;
  /** Enable antialiasing for better rendering quality. Default: true */
  enableAntialiasing?: boolean;
  /** When false, yields pan/zoom to a parent scroll container. Default: true. */
  gesturesEnabled?: boolean;
  /** "paged" or "continuous". */
  scrollMode?: string;
  /** Continuous mode content insets (px). */
  contentInsetTop?: number;
  contentInsetBottom?: number;
  /** Callback when PDF is loaded successfully */
  onLoadComplete?: (event: NativeLoadCompleteEvent) => void;
  /** Callback when an error occurs */
  onError?: (error: PdfErrorEvent) => void;
  /** Callback when page count is available */
  onPageCount?: (event: NativePageCountEvent) => void;
  /** Callback when page changes */
  onPasswordRequired?: () => void;
  /** Callback with the current page's on-screen geometry (view px). */
  onTransform?: (event: NativeTransformEvent) => void;
  /** Continuous mode: visible pages' rects as a JSON string. */
  onPagesLayout?: (event: {nativeEvent: {pages: string}}) => void;
  /** Custom style for the container */
  style?: ViewStyle;
}
