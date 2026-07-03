<div align="center">

# react-native-pdf-turbo

**A blazing-fast, memory-lean PDF viewer for React Native.**
Built for documents that break other viewers — thousands of pages, heavy annotations, aggressive zoom.

[![npm](https://img.shields.io/npm/v/react-native-pdf-turbo.svg)](https://www.npmjs.com/package/react-native-pdf-turbo)
[![platforms](https://img.shields.io/badge/platforms-iOS%20%7C%20Android-lightgrey.svg)](#requirements)
[![arch](https://img.shields.io/badge/New%20Architecture-ready-brightgreen.svg)](./docs/new-architecture.md)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](#license)

<br />

<img src="https://raw.githubusercontent.com/longb1997/react-native-pdf-turbo/main/assets/demo.gif" alt="react-native-pdf-turbo demo" width="300" />

</div>

---

## Why this library?

Most RN PDF viewers rasterize whole pages up front. That falls over on large or
annotated files — memory spikes, dropped frames, hard crashes. `react-native-pdf-turbo`
renders **only the tiles you can actually see, at the zoom you're actually at**, on a
background thread, and reuses everything it can.

|              | react-native-pdf-turbo                                                      |
| ------------ | --------------------------------------------------------------------------- |
| Rendering    | Tiled — `CATiledLayer` (iOS) / custom 512px tiles on `PdfiumCore` (Android) |
| Large files  | 1000+ pages without preloading the whole document                           |
| Annotations  | Flattened & cached per page, off the main thread                            |
| Zoom / pan   | GPU-friendly, tiles rendered on demand                                      |
| Caching      | On-disk cache with expiration **and** an LRU size cap                       |
| Prefetch     | Warm the disk cache _and_ pre-flatten neighbor pages                        |
| Architecture | Codegen spec included — runs on Paper and New Architecture                  |

## Contents

- [Install](#install)
- [Quick start](#quick-start)
- [Recipes](#recipes)
- [Caching & prefetch](#caching--prefetch)
- [API](#api)
- [How it works](#how-it-works)
- [New Architecture](#new-architecture)
- [Requirements](#requirements)
- [FAQ](#faq)
- [License](#license)

## Install

```bash
yarn add react-native-pdf-turbo
# peer deps used internally for download + hashing
yarn add react-native-fs crypto-js
```

**iOS**

```bash
cd ios && pod install
```

**Android** — autolinked, nothing to do.

## Quick start

```tsx
import PdfTurboView from 'react-native-pdf-turbo';

export default function Viewer() {
  return <PdfTurboView source={{ uri: 'https://example.com/report.pdf' }} style={{ flex: 1 }} />;
}
```

That single line gives you: download + on-disk cache, a progress spinner, tiled
rendering, pinch-zoom, and the built-in pager.

## Recipes

<details open>
<summary><b>Remote file with caching, auth headers & callbacks</b></summary>

```tsx
<PdfTurboView
  source={{
    uri: 'https://example.com/report.pdf',
    cache: true,
    cacheFileName: 'report-2026.pdf',
    expiration: 60 * 60 * 24, // seconds; omit for "never expires"
    headers: { Authorization: 'Bearer <token>' },
  }}
  maximumZoom={5}
  onPageCount={(count) => console.log('pages:', count)}
  onLoadComplete={(page, { width, height }) => console.log('shown', page, width, height)}
  onError={(e) => console.warn(e.nativeEvent.message)}
  style={{ flex: 1 }}
/>
```

</details>

<details>
<summary><b>Password-protected document</b></summary>

```tsx
const [password, setPassword] = useState('');

<PdfTurboView
  source={{ uri: 'https://example.com/secret.pdf' }}
  password={password}
  onPasswordRequired={() => promptForPassword()}
  onError={(e) => {
    if (e.nativeEvent.message.includes('Invalid password')) retry();
  }}
  style={{ flex: 1 }}
/>;
```

The native side first tries an empty password (many PDFs use that for user
access); `onPasswordRequired` fires only when a real one is needed.

</details>

<details>
<summary><b>Bring your own pager (headless controls)</b></summary>

```tsx
const [page, setPage] = useState(0);
const [total, setTotal] = useState(1);

<>
  <PdfTurboView
    source={{ uri }}
    page={page}
    showNavigationControls={false}
    onPageCount={setTotal}
    style={{ flex: 1 }}
  />
  <MyPager current={page} total={total} onChange={setPage} />
</>;
```

Set `showNavigationControls={false}` and drive the `page` prop yourself.

</details>

## Caching & prefetch

Downloads are hashed by URI (or your `cacheFileName`) into the app cache dir.
Two knobs keep it fast and bounded:

```tsx
import { PdfCacheService } from 'react-native-pdf-turbo';

// Warm the next document before the user taps it — no flicker on open.
await PdfCacheService.prefetch({ uri: 'https://example.com/next.pdf' });

// Bound the cache. When a download pushes total size over the cap, the
// least-recently-modified PDFs are evicted. Default 200 MB; 0 disables it.
PdfCacheService.maxCacheSizeBytes = 150 * 1024 * 1024;

// Manual controls
await PdfCacheService.clearCache({ uri }); // one file
await PdfCacheService.clearAllCache(); // everything
const bytes = await PdfCacheService.getCacheSize();
```

On top of disk caching, opening a page **pre-flattens its neighbors** (±1) on iOS,
so paging through an annotated document is instant instead of flashing.

Cancellation is automatic: unmounting or swapping `source` aborts the in-flight
download instead of leaking it in the background.

## API

### `<PdfTurboView />`

| Prop                     | Type                                | Default | Notes                                                                     |
| ------------------------ | ----------------------------------- | ------- | ------------------------------------------------------------------------- |
| `source`                 | `PdfSource`                         | —       | **Required.** See below.                                                  |
| `page`                   | `number`                            | `0`     | Controlled page index (0-based) when `showNavigationControls` is `false`. |
| `password`               | `string`                            | —       | For encrypted files.                                                      |
| `maximumZoom`            | `number`                            | `3`     | Upper zoom bound.                                                         |
| `enableAntialiasing`     | `boolean`                           | `true`  | Smoother text at a small cost.                                            |
| `showNavigationControls` | `boolean`                           | `true`  | Built-in pager; turn off for custom UI.                                   |
| `style`                  | `ViewStyle`                         | —       | Container style.                                                          |
| `onLoadComplete`         | `(page, { width, height }) => void` | —       | A page finished rendering.                                                |
| `onPageCount`            | `(count) => void`                   | —       | Document opened; total pages known.                                       |
| `onPageChange`           | `(page) => void`                    | —       | Current page changed.                                                     |
| `onError`                | `(e: PdfErrorEvent) => void`        | —       | Load/render failure.                                                      |
| `onPasswordRequired`     | `() => void`                        | —       | Document needs a password.                                                |

### `PdfSource`

| Field           | Type                     | Default      | Notes                                             |
| --------------- | ------------------------ | ------------ | ------------------------------------------------- |
| `uri`           | `string`                 | —            | **Required.** Remote URL or local `file://` path. |
| `cache`         | `boolean`                | `true`       | Set `false` to always re-download.                |
| `cacheFileName` | `string`                 | MD5 of `uri` | Stable name for the cached copy.                  |
| `expiration`    | `number`                 | —            | Seconds; unset / `0` = never expires.             |
| `headers`       | `Record<string, string>` | —            | Sent with the download request.                   |

### `PdfCacheService`

| Member                         | Signature                                            | Purpose                                        |
| ------------------------------ | ---------------------------------------------------- | ---------------------------------------------- |
| `prefetch`                     | `(source) => Promise<string>`                        | Warm the cache without rendering.              |
| `downloadPdf`                  | `(source, onProgress?, onStart?) => Promise<string>` | Download + cache; `onStart` yields the job id. |
| `stopDownload`                 | `(jobId) => void`                                    | Cancel an in-flight download.                  |
| `isCacheValid`                 | `(source) => Promise<boolean>`                       | Cached & not expired?                          |
| `getCacheFilePath`             | `(source) => string`                                 | Resolved local path.                           |
| `getCacheSize`                 | `() => Promise<number>`                              | Total cached bytes.                            |
| `enforceCacheLimit`            | `() => Promise<void>`                                | Evict until under the cap.                     |
| `clearCache` / `clearAllCache` | `=> Promise<void>`                                   | Remove one / all cached PDFs.                  |
| `maxCacheSizeBytes`            | `number`                                             | Cache size cap (default 200 MB; `0` disables). |

`PdfNavigationControls` is also exported if you want the default pager UI standalone.

## How it works

```
 source={{ uri }}
        │  JS: download → hash → disk cache (LRU capped) → file://
        ▼
 native view receives a local path
        │
        ├─ iOS      CGPDFDocument (off-main) → PDFKit flatten annots (cached)
        │           → CATiledLayer renders visible tiles per zoom level
        │
        └─ Android  PdfiumCore → base bitmap + on-demand 512px RGB_565 tiles
                    (tile refresh debounced during pan/zoom)
        ▼
 events → onPageCount · onLoadComplete · onPageChange · onError · onPasswordRequired
```

Key ideas that keep it fast:

- **Tiled rendering** — never rasterize a full page at full zoom; render the
  handful of tiles on screen.
- **Off-main document load** — parsing/unlocking never blocks the UI thread.
- **Annotation flattening with a cache** — annotated pages draw correctly without
  re-flattening on every display.
- **Debounced tiling (Android)** — gestures scale the base bitmap; sharp tiles land
  once movement settles, avoiding an allocation storm.
- **Neighbor pre-flatten (iOS)** — ±1 pages are ready before you swipe.

## New Architecture

The package ships a codegen spec (`src/PdfTurboViewNativeComponent.ts`) and
`codegenConfig`, and the JS layer uses `codegenNativeComponent`. On the **old
architecture** it transparently resolves to the existing view managers, so nothing
changes. Full Fabric native components are tracked in
[`docs/new-architecture.md`](./docs/new-architecture.md).

## Requirements

|              | Minimum |
| ------------ | ------- |
| iOS          | 12.0    |
| Android      | API 24  |
| React Native | 0.73+   |

## FAQ

**Blank page or nothing renders.** Confirm the `uri` is reachable and returns a
valid PDF; check the `onError` message and that network permissions allow the host.

**Memory still high on huge files.** Lower `maximumZoom`, and cap the disk cache
via `PdfCacheService.maxCacheSizeBytes`.

**Colors look slightly banded when zoomed way in (Android).** Tiles use `RGB_565`
to halve memory. If you need perfect gradients, that's the tradeoff to know about.

**Cache seems ignored.** Ensure `cache` isn't `false`, there's free storage, and
the app has write access to the cache directory.

## Contributing

Issues and PRs welcome — see [CONTRIBUTING](./CONTRIBUTING.md).

## License

MIT
