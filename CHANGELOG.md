# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.4] - 2026-07-03

### Changed

- README: add an animated demo (render, pinch-zoom, page navigation) with a live
  FPS overlay, referenced via a raw GitHub URL so it renders on npm and GitHub.

## [1.0.3] - 2026-07-03

### Fixed

- Packaging: exclude `android/build`, `android/.cxx`, `android/.gradle`, and
  `ios/build` from the published package (1.0.2 accidentally shipped generated
  native build artifacts).

## [1.0.2] - 2026-07-03

### Added

- Full New Architecture (Fabric) support, verified end-to-end on iOS and Android
  (render, props, events, page navigation) on React Native 0.76.
  - iOS: `RCTViewComponentView` Fabric component wrapping the native PDF view.
  - Android: React Native Gradle plugin codegen (JNI spec) and events dispatched
    through the modern `EventDispatcher`.

### Changed

- iOS: podspec uses `install_modules_dependencies` so Fabric/codegen dependencies
  are linked when the New Architecture is enabled (falls back to `React-Core`).
- iOS: the native `PdfTurboView` class and its React-facing members are now `public`.
- The old architecture remains fully supported and unchanged.

## [1.0.1] - 2026-07-03

### Changed

- Package metadata: set `author`, podspec author, and LICENSE copyright holder
- podspec: use `v`-prefixed git tag to match release tags
- Tightened peer dependencies (`react >=18`, `react-native >=0.73`)

### Fixed

- Removed the stale `@types/react-native` dev dependency that conflicted with RN 0.73 types
- Downgraded `eslint-plugin-prettier` to a Prettier-2-compatible range so a clean install no longer needs `--legacy-peer-deps`

## [1.0.0] - 2026-07-03

First release under the name `react-native-pdf-turbo` (renamed from
`react-native-optimized-pdf`).

### Added

- Annotation (annots) rendering support on Android and iOS
- Tile-based PDF rendering for high-quality zoom (Android)
- `PdfCacheService.prefetch()` to warm the cache ahead of time
- `PdfCacheService.maxCacheSizeBytes` LRU disk-cache size cap with automatic eviction
- iOS: pre-flatten neighbor pages (±1) for instant paging of annotated documents
- New Architecture: codegen spec (`PdfTurboViewNativeComponent`) and `codegenConfig`

### Changed

- Renamed package and native module to `react-native-pdf-turbo`
  (native component `PdfTurboView`, Android package `com.reactnativepdfturbo`)
- Android: migrated to `pdfiumandroid` and replaced previous rendering pipeline with tiled rendering
- iOS: introduced annotation flattening using PDFKit when needed, while keeping CGPDF rendering for performance
- iOS: load documents off the main thread and lower the `CATiledLayer` LOD bias to match max zoom
- Android: debounce tile rendering during gestures and use `RGB_565` tiles to halve memory
- JS: use `codegenNativeComponent` (old architecture unaffected via fallback)
- Updated dependencies and improved TypeScript configuration

### Improved

- Better zoom quality and rendering performance
- More efficient memory usage across large documents
- Minor UI and typing improvements

### Fixed

- Annotations not being rendered
- Re-download loop when the parent re-rendered with inline `source` / callbacks
- In-flight downloads now cancel on unmount / source change instead of leaking
