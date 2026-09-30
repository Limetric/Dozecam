// libVLC's C API, for what VLCKit's Objective-C API does not expose: the
// livestream's media callbacks (Player/LivestreamVideoPlayerController.swift).
// The headers are vendored from the pinned VLCKit binary (Player/libvlc).
#include <vlc/vlc.h>
#import <VLCKit/VLCKit.h>

NS_ASSUME_NONNULL_BEGIN

/// Declared by VLCKit's private LibVLCBridging header, which the framework
/// ships (PrivateHeaders/VLCLibVLCBridging.h) but does not export: wraps a
/// libvlc_media_t, taking its own reference. VLCKit uses it itself for every
/// media libVLC hands back.
@interface VLCMedia (DozecamLibVLCBridging)
- (nullable instancetype)initWithLibVLCMediaDescriptor:(void *)md;
@end

NS_ASSUME_NONNULL_END
