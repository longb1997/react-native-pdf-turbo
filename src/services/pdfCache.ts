import RNFS from 'react-native-fs';
import md5 from 'crypto-js/md5';
import type { PdfSource } from '../types';

/**
 * Service for handling PDF caching and downloads
 */
export class PdfCacheService {
  /**
   * Maximum total size (bytes) of cached PDFs. When a download pushes the
   * cache above this, the least-recently-modified PDFs are evicted until the
   * cache is back under the limit. Set to `0` (or negative) to disable.
   * Default: 200 MB.
   */
  static maxCacheSizeBytes = 200 * 1024 * 1024;

  /**
   * Get the local file path for a cached PDF
   */
  static getCacheFilePath(source: PdfSource): string {
    const fileName = source.cacheFileName || `${md5(source.uri).toString()}.pdf`;
    return `${RNFS.CachesDirectoryPath}/${fileName}`;
  }

  /**
   * Check if a cached PDF exists and is still valid
   */
  static async isCacheValid(source: PdfSource): Promise<boolean> {
    const localPath = this.getCacheFilePath(source);
    const exists = await RNFS.exists(localPath);

    if (!exists) {
      return false;
    }

    // If expiration is not set, cache is always valid
    if (!source.expiration || source.expiration <= 0) {
      return true;
    }

    // Check if cache has expired
    const stat = await RNFS.stat(localPath);
    const now = Date.now() / 1000;
    return now - stat.mtime < source.expiration;
  }

  /**
   * Download a PDF file and cache it locally
   * @param source PDF source configuration
   * @param onProgress Optional callback for download progress
   * @param onStart Optional callback receiving the RNFS job id, so the caller
   *                can cancel the in-flight download via {@link stopDownload}
   * @returns Local file path
   */
  static async downloadPdf(
    source: PdfSource,
    onProgress?: (percent: number) => void,
    onStart?: (jobId: number) => void,
  ): Promise<string> {
    // A local file needs no download — render it in place. This also avoids a
    // corruption race when several views load the SAME local file at once (each
    // would otherwise RNFS.downloadFile into the identical MD5-named cache path,
    // clobbering each other). Return a bare path; the caller re-adds file://.
    if (source.uri.startsWith('file://')) {
      onProgress?.(100);
      return source.uri.replace(/^file:\/\//, '');
    }
    if (source.uri.startsWith('/')) {
      onProgress?.(100);
      return source.uri;
    }

    const localPath = this.getCacheFilePath(source);

    // Check if we can use cached version
    if (source.cache !== false && (await this.isCacheValid(source))) {
      return localPath;
    }

    // Download the file
    const { jobId, promise } = RNFS.downloadFile({
      fromUrl: source.uri,
      toFile: localPath,
      background: false,
      headers: source.headers,
      progressDivider: 1,
      progress: (res) => {
        if (res.contentLength > 0 && onProgress) {
          const percent = Math.floor((res.bytesWritten / res.contentLength) * 100);
          onProgress(percent);
        }
      },
      begin: () => {
        // This callback is required for progress to update properly
      },
    });

    onStart?.(jobId);

    await promise;

    // Keep the disk cache bounded. Fire-and-forget: never block or fail the
    // download because of eviction bookkeeping.
    this.enforceCacheLimit().catch(() => {});

    return localPath;
  }

  /**
   * Warm the disk cache for a source ahead of time (e.g. the next document the
   * user is likely to open) without rendering it. Resolves to the local path.
   * Reuses a valid cached copy when present.
   */
  static async prefetch(source: PdfSource): Promise<string> {
    return this.downloadPdf(source);
  }

  /**
   * Evict least-recently-modified cached PDFs until the total cache size is
   * within {@link maxCacheSizeBytes}. No-op when the limit is disabled.
   */
  static async enforceCacheLimit(): Promise<void> {
    if (this.maxCacheSizeBytes <= 0) {
      return;
    }

    try {
      const files = await RNFS.readDir(RNFS.CachesDirectoryPath);
      const pdfFiles = files.filter((file) => file.isFile() && file.name.endsWith('.pdf'));

      let total = pdfFiles.reduce((sum, file) => sum + file.size, 0);
      if (total <= this.maxCacheSizeBytes) {
        return;
      }

      // Oldest first (LRU by modification time).
      const ordered = pdfFiles
        .slice()
        .sort((a, b) => (a.mtime?.getTime() ?? 0) - (b.mtime?.getTime() ?? 0));

      for (const file of ordered) {
        if (total <= this.maxCacheSizeBytes) {
          break;
        }
        try {
          await RNFS.unlink(file.path);
          total -= file.size;
        } catch {
          // File may be in use / already gone — skip and continue.
        }
      }
    } catch {
      // Reading the cache dir failed — nothing to evict.
    }
  }

  /**
   * Cancel an in-flight download started by {@link downloadPdf}.
   * Safe to call with a stale/finished job id.
   */
  static stopDownload(jobId: number): void {
    try {
      RNFS.stopDownload(jobId);
    } catch {
      // job already finished / invalid id — nothing to cancel
    }
  }

  /**
   * Clear a specific cached PDF
   */
  static async clearCache(source: PdfSource): Promise<void> {
    const localPath = this.getCacheFilePath(source);
    const exists = await RNFS.exists(localPath);
    if (exists) {
      await RNFS.unlink(localPath);
    }
  }

  /**
   * Clear all cached PDFs
   */
  static async clearAllCache(): Promise<void> {
    const files = await RNFS.readDir(RNFS.CachesDirectoryPath);
    const pdfFiles = files.filter((file) => file.name.endsWith('.pdf'));
    await Promise.all(pdfFiles.map((file) => RNFS.unlink(file.path)));
  }

  /**
   * Get total size of cached PDFs in bytes
   */
  static async getCacheSize(): Promise<number> {
    const files = await RNFS.readDir(RNFS.CachesDirectoryPath);
    const pdfFiles = files.filter((file) => file.name.endsWith('.pdf'));
    return pdfFiles.reduce((total, file) => total + file.size, 0);
  }

  /**
   * Ensure the file path has the file:// protocol
   */
  static normalizeFilePath(path: string): string {
    return path.startsWith('file://') ? path : `file://${path}`;
  }
}
