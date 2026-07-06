# react-native-pdf-turbo — Example App

A runnable React Native app (RN 0.86, New Architecture) that demonstrates
`react-native-pdf-turbo`. It consumes the library **directly from the parent
directory** (`link:..`) so edits to the library source hot-reload here — no
publish/build step.

See [`App.tsx`](./App.tsx) for the full demo.

## What it shows

- Remote PDF loading with automatic caching + expiration
- **Continuous scroll vs. paged** viewing — toggle `scrollMode`
  (`'continuous'` stacks all pages in one vertical scroll; `'paged'` shows one
  page at a time with the built-in next/prev pill)
- Tracking the current page in continuous mode via the `onPagesLayout` stream
- `onLoadComplete` / `onPageCount` / `onPageChange` / `onError` callbacks
- Cache inspection & clearing via `PdfCacheService`

## Prerequisites

Set up your machine per the RN docs first:
https://reactnative.dev/docs/environment-setup ("React Native CLI Quickstart").

## Install

Run from **this** directory:

```bash
cd example
npm install        # or: yarn
```

iOS also needs pods (native module is autolinked from the parent package):

```bash
cd ios && pod install && cd ..
```

## Run

```bash
# terminal 1 — Metro (from example/)
npm start

# terminal 2
npm run ios        # or: npm run android
```

## Notes

- The library is a Fabric/codegen native component; this app has the **New
  Architecture enabled** (`newArchEnabled=true` in `android/gradle.properties`,
  `RCT_NEW_ARCH_ENABLED` for iOS pods).
- `metro.config.js` watches the parent folder and forces a single copy of
  `react` / `react-native` / `react-native-fs` to avoid duplicate-module and
  "Invalid hook call" crashes.
- If Metro serves stale modules after changing native code, run
  `npm start -- --reset-cache`.
