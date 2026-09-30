import Foundation
import VLCKit
import os

/// Builds libVLC media that reads a `LivestreamPipe`: `libvlc_media_new_callbacks`,
/// whose read callback blocks on the pipe on libVLC's input thread, wrapped
/// as a `VLCMedia` so it plays through the same `VLCMediaPlayer` as RTSP.
enum LivestreamMedia {
    /// Options for a live fMP4 byte stream. Naming the demuxer spares VLC
    /// probing a stream it cannot seek back in; the caching values match the
    /// RTSP path's, whichever of them the callback input consults.
    static let options = [
        ":demux=mp4",
        ":network-caching=\(VlcRuntime.networkCachingMs)",
        ":live-caching=\(VlcRuntime.networkCachingMs)",
        ":file-caching=\(VlcRuntime.networkCachingMs)",
    ]

    /// A media that reads `pipe` once. Close the pipe before stopping or
    /// releasing the player: libVLC waits for its input thread, which may be
    /// blocked in a read that only the pipe can end.
    static func make(reading pipe: LivestreamPipe) -> VLCMedia? {
        let source = Unmanaged.passRetained(LivestreamMediaSource(pipe: pipe)).toOpaque()
        guard let descriptor = libvlc_media_new_callbacks(livestreamCallbacks, source) else {
            Unmanaged<LivestreamMediaSource>.fromOpaque(source).release()
            return nil
        }
        // VLCMedia takes its own reference.
        let media = VLCMedia(libVLCMediaDescriptor: UnsafeMutableRawPointer(descriptor))
        libvlc_media_release(descriptor)
        for option in options { media?.addOption(option) }
        return media
    }
}

/// The context libVLC's callbacks get. It owns one reference, taken when the
/// media is made and given back by the close callback. A media that is
/// never opened (stopped before its input started) keeps it: one small,
/// closed pipe, which beats freeing something libVLC may still open.
final class LivestreamMediaSource: @unchecked Sendable {
    let pipe: LivestreamPipe
    private let opened = OSAllocatedUnfairLock(initialState: false)

    init(pipe: LivestreamPipe) {
        self.pipe = pipe
    }

    /// True the first time only: a live stream cannot be read twice, and the
    /// single reference cannot be given back twice.
    func claimOpen() -> Bool {
        opened.withLock { opened in
            defer { opened = true }
            return !opened
        }
    }
}

// libVLC calls these on its input thread: file-scope functions, so they carry
// no actor isolation (#58).

private func livestreamOpen(
    _ opaque: UnsafeMutableRawPointer?, _ datap: UnsafeMutablePointer<UnsafeMutableRawPointer?>?,
    _ sizep: UnsafeMutablePointer<UInt64>?
) -> Int32 {
    guard let opaque, let datap else { return -1 }
    let source = Unmanaged<LivestreamMediaSource>.fromOpaque(opaque).takeUnretainedValue()
    guard source.claimOpen() else { return -1 }
    datap.pointee = opaque
    sizep?.pointee = UInt64.max  // unknown: a live stream has no length
    return 0
}

private func livestreamRead(_ data: UnsafeMutableRawPointer?, _ buffer: UnsafeMutablePointer<UInt8>?, _ length: Int)
    -> Int
{
    guard let data, let buffer else { return -1 }
    let source = Unmanaged<LivestreamMediaSource>.fromOpaque(data).takeUnretainedValue()
    switch source.pipe.read(into: UnsafeMutableRawBufferPointer(start: buffer, count: length)) {
    case .bytes(let count): return count
    case .endOfStream: return 0
    case .failed: return -1
    }
}

private func livestreamClose(_ data: UnsafeMutableRawPointer?) {
    guard let data else { return }
    let source = Unmanaged<LivestreamMediaSource>.fromOpaque(data)
    source.takeUnretainedValue().pipe.close()
    source.release()
}

/// libVLC keeps the pointer for as long as any media made with it lives, so
/// it is allocated once and never freed.
nonisolated(unsafe) private let livestreamCallbacks: UnsafeMutablePointer<libvlc_media_open_cbs> = {
    let callbacks = UnsafeMutablePointer<libvlc_media_open_cbs>.allocate(capacity: 1)
    callbacks.initialize(
        to: libvlc_media_open_cbs(
            version: 0,
            open: livestreamOpen,
            read: livestreamRead,
            seek: nil,  // not seekable
            close: livestreamClose
        )
    )
    return callbacks
}()
