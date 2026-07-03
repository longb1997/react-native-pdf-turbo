import type { HostComponent, ViewProps } from 'react-native';
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';
import type {
  Double,
  Float,
  Int32,
  WithDefault,
  DirectEventHandler,
} from 'react-native/Libraries/Types/CodegenTypes';

/**
 * Fabric (New Architecture) codegen spec for the native PDF view.
 *
 * React Native's codegen scans `*NativeComponent.ts` files under the
 * `jsSrcsDir` configured in `package.json > codegenConfig` and generates the
 * native Fabric component interfaces from this spec. On the old architecture
 * `codegenNativeComponent` transparently falls back to `requireNativeComponent`,
 * so the same component name (`PdfTurboView`) resolves to the existing view
 * managers — the library keeps working on both architectures.
 */

type LoadCompleteEvent = Readonly<{
  currentPage: Int32;
  width: Double;
  height: Double;
}>;

type ErrorEvent = Readonly<{
  message: string;
}>;

type PageCountEvent = Readonly<{
  numberOfPages: Int32;
}>;

// Empty payload — fired when the document needs a password.
type PasswordRequiredEvent = Readonly<{}>;

export interface NativeProps extends ViewProps {
  /** Local file path (file://) of the PDF to render. */
  source?: string;
  /** Current page index (0-based). */
  page?: WithDefault<Int32, 0>;
  /** Maximum zoom scale. */
  maximumZoom?: WithDefault<Float, 5.0>;
  /** Whether antialiasing is enabled. */
  enableAntialiasing?: WithDefault<boolean, true>;
  /** Password for encrypted PDFs. */
  password?: string;

  onLoadComplete?: DirectEventHandler<LoadCompleteEvent>;
  onError?: DirectEventHandler<ErrorEvent>;
  onPageCount?: DirectEventHandler<PageCountEvent>;
  onPasswordRequired?: DirectEventHandler<PasswordRequiredEvent>;
}

export default codegenNativeComponent<NativeProps>('PdfTurboView') as HostComponent<NativeProps>;
