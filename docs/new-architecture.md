# New Architecture (Fabric) migration

## Status

| Layer | State |
| ----- | ----- |
| JS codegen spec (`src/PdfTurboViewNativeComponent.ts`) | ✅ Done |
| `codegenConfig` in `package.json` | ✅ Done |
| JS runtime uses `codegenNativeComponent` | ✅ Done (works on old arch via `requireNativeComponent` fallback) |
| iOS Fabric component (`RCTViewComponentView`) | ✅ Done — `ios/Fabric/RNPdfTurboComponentView.mm`, verified on RN 0.76 |
| Android Fabric (codegen JNI + `EventDispatcher`) | ✅ Done — verified on RN 0.76 |

Both architectures are fully supported. Verified end-to-end on React Native 0.76
(render, prop updates, events, page navigation) on an iOS simulator and an Android
emulator. The old-architecture path is unchanged.

### iOS implementation notes

- `ios/Fabric/RNPdfTurboComponentView.mm` — an `RCTViewComponentView` subclass
  (guarded by `#ifdef RCT_NEW_ARCH_ENABLED`) that hosts the Swift `PdfTurboView`,
  maps `PdfTurboViewProps`, and forwards events through `PdfTurboViewEventEmitter`.
  It exports `PdfTurboViewCls()` (C linkage) for the codegen provider.
- The Swift `PdfTurboView` and its React-facing members are `public` so the
  generated `-Swift.h` exposes them to the component view.
- The podspec uses `install_modules_dependencies` and compiles `.mm` sources.

### Android implementation notes

- `android/build.gradle` applies `com.facebook.react` when `newArchEnabled`, so
  codegen generates the JNI spec (`react_codegen_RNPdfTurboViewSpec`) required by
  the app's native build.
- Events are dispatched via `UIManagerHelper.getEventDispatcherForReactTag(...)`
  instead of the legacy `RCTEventEmitter`, which works on both architectures.
- Props flow through the existing `@ReactProp` setters (reflection); no separate
  Fabric delegate is required.

## Codegen names (from `codegenConfig`)

- Spec library: `RNPdfTurboViewSpec`
- Component name: `PdfTurboView`
- Generated C++ types (namespace `facebook::react`): `PdfTurboViewComponentDescriptor`,
  `PdfTurboViewProps`, `PdfTurboViewEventEmitter`
- Android Java package: `com.reactnativepdfturbo`; generated
  `PdfTurboViewManagerInterface`, `PdfTurboViewManagerDelegate` under
  `com.facebook.react.viewmanagers`.

## Verifying against an example app

To re-verify after changes, create an RN 0.76+ app with the New Architecture
enabled, add the library, and build:

- iOS: `RCT_NEW_ARCH_ENABLED=1 pod install`, then build/run. The generated spec
  headers live under `react/renderer/components/RNPdfTurboViewSpec/`.
- Android: set `newArchEnabled=true` in `gradle.properties`, then build/run. The
  library's `com.facebook.react` plugin generates the JNI spec into
  `android/build/generated/source/codegen/`.
