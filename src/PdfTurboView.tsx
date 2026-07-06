import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { View, Platform } from 'react-native';
import PdfTurboViewNativeComponent from './PdfTurboViewNativeComponent';
import { PdfCacheService } from './services/pdfCache';
import { PdfNavigationControls } from './components/PdfNavigationControls';
import { PdfLoadingOverlay, PdfErrorOverlay } from './components/PdfOverlays';
import {
  DEFAULT_MAXIMUM_ZOOM,
  DEFAULT_ENABLE_ANTIALIASING,
  DEFAULT_SHOW_NAVIGATION_CONTROLS,
  ERROR_MESSAGES,
} from './constants';
import type {
  PdfTurboViewProps,
  NativeLoadCompleteEvent,
  NativePageCountEvent,
  NativeTransformEvent,
  NativePdfTurboViewProps,
} from './types';

// codegenNativeComponent resolves to the Fabric component on the New
// Architecture and falls back to requireNativeComponent('PdfTurboView') on the
// old architecture, so this works on both.
const NativePdfTurboView =
  Platform.OS === 'ios' || Platform.OS === 'android' ? PdfTurboViewNativeComponent : () => null;

/**
 * PdfTurboView - High-performance PDF viewer for React Native
 *
 * Features:
 * - Automatic PDF caching with configurable expiration
 * - Progress tracking for downloads
 * - Page navigation with built-in controls
 * - Zoom support with configurable maximum
 * - High-quality rendering with antialiasing
 *
 * @example
 * ```tsx
 * <PdfTurboView
 *   source={{ uri: 'https://example.com/file.pdf', cache: true }}
 *   maximumZoom={5}
 *   onLoadComplete={(page, dimensions) => console.log('Loaded', page, dimensions)}
 * />
 * ```
 */
function PdfTurboView({
  source,
  page,
  password,
  maximumZoom = DEFAULT_MAXIMUM_ZOOM,
  enableAntialiasing = DEFAULT_ENABLE_ANTIALIASING,
  gesturesEnabled = true,
  scrollMode = 'paged',
  contentInsetTop = 0,
  contentInsetBottom = 0,
  showNavigationControls = DEFAULT_SHOW_NAVIGATION_CONTROLS,
  style,
  onLoadComplete,
  onError,
  onPageCount,
  onPageChange,
  onPasswordRequired,
  onTransform,
  onPagesLayout,
}: PdfTurboViewProps) {
  const [localPath, setLocalPath] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [internalPage, setInternalPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const lastSource = useRef<string | null>(null);

  // Keep the freshest source object and callbacks in refs so the download
  // effect does not re-run (and re-download) just because the parent passed a
  // new inline object / handler reference on its own re-render.
  const sourceRef = useRef(source);
  sourceRef.current = source;

  const callbacksRef = useRef({
    onError,
    onLoadComplete,
    onPageCount,
    onPageChange,
    onPasswordRequired,
    onTransform,
    onPagesLayout,
  });
  callbacksRef.current = {
    onError,
    onLoadComplete,
    onPageCount,
    onPageChange,
    onPasswordRequired,
    onTransform,
    onPagesLayout,
  };

  useEffect(() => {
    let cancelled = false;
    let activeJobId: number | null = null;

    const loadPdf = async () => {
      const currentSource = sourceRef.current;
      setLoading(true);
      setProgress(0);
      setError(null);
      setLocalPath(null);
      setInternalPage(0);
      setTotalPages(1);
      lastSource.current = currentSource.uri;

      try {
        const path = await PdfCacheService.downloadPdf(
          currentSource,
          (p) => {
            if (!cancelled) {
              setProgress(p);
            }
          },
          (jobId) => {
            activeJobId = jobId;
          },
        );

        if (!cancelled && lastSource.current === currentSource.uri) {
          setLocalPath(PdfCacheService.normalizeFilePath(path));
          setLoading(false);
        }
      } catch (e: any) {
        if (!cancelled) {
          const message = e?.message || ERROR_MESSAGES.DOWNLOAD_FAILED;
          setError(message);
          setLoading(false);
          callbacksRef.current.onError?.({ nativeEvent: { message } });
        }
      }
    };

    loadPdf();

    return () => {
      cancelled = true;
      // Abort the in-flight native download instead of letting it finish in the
      // background after the component/source changed.
      if (activeJobId !== null) {
        PdfCacheService.stopDownload(activeJobId);
      }
    };
    // Only re-download when the identity of the source actually changes — not on
    // every parent render. `headers`/`method` are read live from sourceRef.
  }, [source.uri, source.cache, source.cacheFileName, source.expiration]);

  const handleNextPage = useCallback(() => {
    setInternalPage((prev) => {
      if (prev < totalPages - 1) {
        const next = prev + 1;
        callbacksRef.current.onPageChange?.(next);
        return next;
      }
      return prev;
    });
  }, [totalPages]);

  const handlePrevPage = useCallback(() => {
    setInternalPage((prev) => {
      if (prev > 0) {
        const next = prev - 1;
        callbacksRef.current.onPageChange?.(next);
        return next;
      }
      return prev;
    });
  }, []);

  const handlePageChange = useCallback((newPage: number) => {
    setInternalPage(newPage);
    callbacksRef.current.onPageChange?.(newPage);
  }, []);

  const handleLoadComplete = useCallback((event: NativeLoadCompleteEvent) => {
    const { currentPage, width, height } = event.nativeEvent;
    callbacksRef.current.onLoadComplete?.(currentPage, { width, height });
  }, []);

  const handlePageCount = useCallback((event: NativePageCountEvent) => {
    const { numberOfPages } = event.nativeEvent;
    setTotalPages(numberOfPages);
    callbacksRef.current.onPageCount?.(numberOfPages);
  }, []);

  const handleTransform = useCallback((event: NativeTransformEvent) => {
    callbacksRef.current.onTransform?.(event.nativeEvent);
  }, []);

  const handlePagesLayout = useCallback((event: {nativeEvent: {pages: string}}) => {
    const cb = callbacksRef.current.onPagesLayout;
    if (!cb) return;
    try {
      cb(JSON.parse(event.nativeEvent.pages || '[]'));
    } catch {
      // malformed payload — ignore this frame
    }
  }, []);

  const handleError = useCallback<NonNullable<NativePdfTurboViewProps['onError']>>((event) => {
    callbacksRef.current.onError?.(event);
  }, []);

  const handlePasswordRequired = useCallback(() => {
    callbacksRef.current.onPasswordRequired?.();
  }, []);

  const containerStyle = useMemo(() => [{ flex: 1 }, style], [style]);

  if (loading) {
    return <PdfLoadingOverlay progress={progress} style={style} />;
  }

  if (error) {
    return <PdfErrorOverlay error={error} style={style} />;
  }

  if (!localPath) {
    return null;
  }

  return (
    <View style={containerStyle}>
      <NativePdfTurboView
        source={localPath}
        page={showNavigationControls ? internalPage : page || 0}
        enableAntialiasing={enableAntialiasing}
        gesturesEnabled={gesturesEnabled}
        scrollMode={scrollMode}
        contentInsetTop={contentInsetTop}
        contentInsetBottom={contentInsetBottom}
        maximumZoom={maximumZoom}
        password={password || ''}
        style={{ flex: 1 }}
        onLoadComplete={handleLoadComplete}
        onError={handleError}
        onPageCount={handlePageCount}
        onPasswordRequired={handlePasswordRequired}
        onTransform={handleTransform}
        onPagesLayout={handlePagesLayout}
      />
      {showNavigationControls && (
        <PdfNavigationControls
          currentPage={internalPage}
          totalPages={totalPages}
          onNextPage={handleNextPage}
          onPrevPage={handlePrevPage}
          onPageChange={handlePageChange}
        />
      )}
    </View>
  );
}

export default React.memo(PdfTurboView);
