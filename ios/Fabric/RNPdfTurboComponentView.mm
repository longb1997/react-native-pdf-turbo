#ifdef RCT_NEW_ARCH_ENABLED

#import <UIKit/UIKit.h>
#import <React/RCTViewComponentView.h>
#import <React/RCTConversions.h>
// Defines RCTDirectEventBlock, which the generated Swift header references for the
// view's event-callback properties. Must precede the "-Swift.h" import.
#import <React/RCTComponent.h>

#import <react/renderer/components/RNPdfTurboViewSpec/ComponentDescriptors.h>
#import <react/renderer/components/RNPdfTurboViewSpec/EventEmitters.h>
#import <react/renderer/components/RNPdfTurboViewSpec/Props.h>
#import <react/renderer/components/RNPdfTurboViewSpec/RCTComponentViewHelpers.h>

// Swift view (UIScrollView + CATiledLayer renderer). The pod module name is the
// podspec `s.name`; its generated ObjC interface header is imported by name
// (static-library pod, so the "<Module>-Swift.h" double-quote form is used).
#import "ReactNativePdfTurbo-Swift.h"

using namespace facebook::react;

@interface RNPdfTurboComponentView : RCTViewComponentView <RCTPdfTurboViewViewProtocol>
@end

@implementation RNPdfTurboComponentView {
  PdfTurboView *_pdfView;
}

+ (ComponentDescriptorProvider)componentDescriptorProvider
{
  return concreteComponentDescriptorProvider<PdfTurboViewComponentDescriptor>();
}

- (instancetype)initWithFrame:(CGRect)frame
{
  if (self = [super initWithFrame:frame]) {
    static const auto defaultProps = std::make_shared<const PdfTurboViewProps>();
    _props = defaultProps;

    _pdfView = [[PdfTurboView alloc] initWithFrame:self.bounds];

    __weak __typeof(self) weakSelf = self;

    _pdfView.onLoadComplete = ^(NSDictionary *body) {
      __typeof(self) strongSelf = weakSelf;
      if (!strongSelf) return;
      const auto emitter = [strongSelf pdfEventEmitter];
      if (!emitter) return;
      emitter->onLoadComplete({
        .currentPage = [body[@"currentPage"] intValue],
        .width = [body[@"width"] doubleValue],
        .height = [body[@"height"] doubleValue],
      });
    };

    _pdfView.onError = ^(NSDictionary *body) {
      __typeof(self) strongSelf = weakSelf;
      if (!strongSelf) return;
      const auto emitter = [strongSelf pdfEventEmitter];
      if (!emitter) return;
      NSString *message = body[@"message"] ?: @"";
      emitter->onError({.message = std::string([message UTF8String])});
    };

    _pdfView.onPageCount = ^(NSDictionary *body) {
      __typeof(self) strongSelf = weakSelf;
      if (!strongSelf) return;
      const auto emitter = [strongSelf pdfEventEmitter];
      if (!emitter) return;
      emitter->onPageCount({.numberOfPages = [body[@"numberOfPages"] intValue]});
    };

    _pdfView.onPasswordRequired = ^(NSDictionary *body) {
      __typeof(self) strongSelf = weakSelf;
      if (!strongSelf) return;
      const auto emitter = [strongSelf pdfEventEmitter];
      if (!emitter) return;
      emitter->onPasswordRequired({});
    };

    _pdfView.onTransform = ^(NSDictionary *body) {
      __typeof(self) strongSelf = weakSelf;
      if (!strongSelf) return;
      const auto emitter = [strongSelf pdfEventEmitter];
      if (!emitter) return;
      emitter->onTransform({
        .page = [body[@"page"] intValue],
        .x = [body[@"x"] doubleValue],
        .y = [body[@"y"] doubleValue],
        .width = [body[@"width"] doubleValue],
        .height = [body[@"height"] doubleValue],
      });
    };

    _pdfView.onPagesLayout = ^(NSDictionary *body) {
      __typeof(self) strongSelf = weakSelf;
      if (!strongSelf) return;
      const auto emitter = [strongSelf pdfEventEmitter];
      if (!emitter) return;
      NSString *pages = body[@"pages"] ?: @"[]";
      emitter->onPagesLayout({.pages = std::string([pages UTF8String])});
    };

    self.contentView = _pdfView;
  }
  return self;
}

- (std::shared_ptr<const PdfTurboViewEventEmitter>)pdfEventEmitter
{
  if (!_eventEmitter) {
    return nullptr;
  }
  return std::static_pointer_cast<const PdfTurboViewEventEmitter>(_eventEmitter);
}

- (void)updateProps:(const Props::Shared &)props oldProps:(const Props::Shared &)oldProps
{
  const auto &oldViewProps = *std::static_pointer_cast<const PdfTurboViewProps>(_props);
  const auto &newViewProps = *std::static_pointer_cast<const PdfTurboViewProps>(props);

  if (oldViewProps.source != newViewProps.source) {
    _pdfView.source = RCTNSStringFromString(newViewProps.source);
  }
  if (oldViewProps.maximumZoom != newViewProps.maximumZoom) {
    _pdfView.maximumZoom = @(newViewProps.maximumZoom);
  }
  if (oldViewProps.enableAntialiasing != newViewProps.enableAntialiasing) {
    _pdfView.enableAntialiasing = newViewProps.enableAntialiasing;
  }
  if (oldViewProps.password != newViewProps.password) {
    _pdfView.password = RCTNSStringFromString(newViewProps.password);
  }
  if (oldViewProps.gesturesEnabled != newViewProps.gesturesEnabled) {
    _pdfView.gesturesEnabled = newViewProps.gesturesEnabled;
  }
  if (oldViewProps.scrollMode != newViewProps.scrollMode) {
    _pdfView.scrollMode = RCTNSStringFromString(newViewProps.scrollMode);
  }
  if (oldViewProps.contentInsetTop != newViewProps.contentInsetTop) {
    _pdfView.contentInsetTop = @(newViewProps.contentInsetTop);
  }
  if (oldViewProps.contentInsetBottom != newViewProps.contentInsetBottom) {
    _pdfView.contentInsetBottom = @(newViewProps.contentInsetBottom);
  }
  // Set page last so it applies against the (possibly new) document.
  if (oldViewProps.page != newViewProps.page) {
    _pdfView.page = @(newViewProps.page);
  }

  [super updateProps:props oldProps:oldProps];
}

- (void)prepareForRecycle
{
  [super prepareForRecycle];
  _pdfView.source = @"";
  _pdfView.password = @"";
}

@end

// The codegen-generated RCTThirdPartyFabricComponentsProvider maps the component
// name "PdfTurboView" to this factory function. It must have C linkage so the
// generated provider (which references `_PdfTurboViewCls`) links against it.
extern "C" Class<RCTComponentViewProtocol> PdfTurboViewCls(void)
{
  return RNPdfTurboComponentView.class;
}

#endif // RCT_NEW_ARCH_ENABLED
