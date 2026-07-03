# Android Setup for react-native-pdf-turbo

## Automatic Linking (React Native 0.60+)

The package will be automatically linked when you run your project. No manual linking required.

## Manual Setup (if needed)

If automatic linking doesn't work, follow these steps:

### 1. Add to `settings.gradle`

```gradle
include ':react-native-pdf-turbo'
project(':react-native-pdf-turbo').projectDir = new File(rootProject.projectDir, '../node_modules/react-native-pdf-turbo/android')
```

### 2. Add to `app/build.gradle`

```gradle
dependencies {
    implementation project(':react-native-pdf-turbo')
}
```

### 3. Add to `MainApplication.java` or `MainApplication.kt`

**Java:**

```java
import com.reactnativepdfturbo.PdfTurboPackage;

@Override
protected List<ReactPackage> getPackages() {
    List<ReactPackage> packages = new PackageList(this).getPackages();
    packages.add(new PdfTurboPackage());
    return packages;
}
```

**Kotlin:**

```kotlin
import com.reactnativepdfturbo.PdfTurboPackage

override fun getPackages(): List<ReactPackage> =
    PackageList(this).packages.apply {
        add(PdfTurboPackage())
    }
```

## Requirements

- Android SDK 24+
- Kotlin 1.9+

## Features

- High-performance PDF rendering using pdfium
- Smooth zoom and pan with gesture support
- Double-tap to zoom/reset
- Memory-efficient rendering with proper bitmap recycling
- Antialiasing support for crisp text
