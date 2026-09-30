/// The level of a decoded audio buffer, the port of Android's `PcmRms`: the
/// square root of the mean squared sample over full scale, clamped to 0–1,
/// and 0 for an empty buffer (shared/spec/alerts-and-sound-modes.md, "The
/// detector").
///
/// Android measures 16-bit PCM, where full scale is 32768. libVLC hands iOS
/// 32-bit floats (48 kHz mono, #59), where full scale is ±1.0, so the two
/// measure the same thing on the same scale: the testbed's noise reads 0.35 on
/// both. Nonisolated and allocation-free, because it runs on libVLC's audio
/// thread once per buffer.
enum PcmRms {
    /// The level of `samples`, floats with ±1.0 as full scale.
    static func of(_ samples: UnsafeBufferPointer<Float>) -> Float {
        guard !samples.isEmpty else { return 0 }
        // Summed in Double, as Android does: a second of loud audio is 48,000
        // squares, enough for Float's 24 bits to start dropping the small ones.
        var sumSquares = 0.0
        for sample in samples {
            let value = Double(sample)
            sumSquares += value * value
        }
        return clamp((sumSquares / Double(samples.count)).squareRoot())
    }

    static func of(_ samples: [Float]) -> Float {
        samples.withUnsafeBufferPointer { of($0) }
    }

    /// The level of signed 16-bit samples, each over 32768: the form the
    /// shared fixture (`sound-detector/rms.json`) and Android's input take.
    static func of(int16 samples: [Int16]) -> Float {
        of(samples.map { Float($0) / 32_768 })
    }

    private static func clamp(_ level: Double) -> Float {
        Float(min(max(level, 0), 1))
    }
}
