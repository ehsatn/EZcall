import AVFoundation
import os

enum CallAudioError: Error {
    case format, codec, microphone
}

/// Live audio for one call answered or placed from this iPhone. The far end of the Android SIM call arrives as
/// 20 ms Opus packets and plays through a small jitter buffer. With two-way audio the microphone goes back the
/// same way. Playback buffers until CallKit activates the audio session.
final class CallAudio: @unchecked Sendable {
    static let sampleRate = 48_000.0
    static let frameLength = 960
    private static let logger = Logger(subsystem: "app.xcall.relay", category: "CallAudio")

    let uplink: Bool
    let senderDtmf: Bool
    private let engine = AVAudioEngine()
    private let pcm: AVAudioFormat
    private let opus: AVAudioFormat
    private let decoder: AVAudioConverter
    private let encoder: AVAudioConverter?
    private let maxPacket: Int
    private let payloadLimit: Int
    private let pairFrames: Bool
    private let playback: OSAllocatedUnfairLock<Jitter>
    private let tones = OSAllocatedUnfairLock(initialState: ArraySlice<Float>())
    private let mute = OSAllocatedUnfairLock(initialState: false)
    private let source: AVAudioSourceNode
    private let routeChanged: @Sendable () -> Void
    private let send: @Sendable (Data) -> Void
    private var receiver: AudioSeal
    // Used only on the microphone tap thread.
    private var sender: AudioSeal
    private var resampler: AVAudioConverter?
    private var pending = [Float]()
    private var attached = false
    private var running = false
    private var observer: NSObjectProtocol?

    var muted: Bool {
        get { mute.withLockUnchecked { $0 } }
        set {
            mute.withLockUnchecked { $0 = newValue }
            if newValue { tones.withLockUnchecked { $0 = [] } }
        }
    }

    init(key: Data, uplink: Bool, payloadLimit: Int, pairFrames: Bool, senderDtmf: Bool, routeChanged: @escaping @Sendable () -> Void,
         send: @escaping @Sendable (Data) -> Void) throws {
        guard let pcm = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Self.sampleRate,
                                      channels: 1, interleaved: false) else { throw CallAudioError.format }
        var description = AudioStreamBasicDescription(
            mSampleRate: Self.sampleRate, mFormatID: kAudioFormatOpus, mFormatFlags: 0, mBytesPerPacket: 0,
            mFramesPerPacket: UInt32(Self.frameLength), mBytesPerFrame: 0, mChannelsPerFrame: 1,
            mBitsPerChannel: 0, mReserved: 0)
        guard let opus = AVAudioFormat(streamDescription: &description),
              let decoder = AVAudioConverter(from: opus, to: pcm) else { throw CallAudioError.codec }
        var encoder: AVAudioConverter?
        if uplink {
            guard let made = AVAudioConverter(from: pcm, to: opus) else { throw CallAudioError.codec }
            // Leave room for variable-size frames within one encrypted Bluetooth write.
            let target = min(56_000, payloadLimit * 400 * 2 / 3)
            let rates = made.applicableEncodeBitRates?.map(\.intValue) ?? []
            made.bitRate = rates.filter { $0 <= target }.max() ?? target
            encoder = made
        }
        let playback = OSAllocatedUnfairLock(uncheckedState: Jitter())
        self.pcm = pcm
        self.opus = opus
        self.decoder = decoder
        self.encoder = encoder
        self.maxPacket = max(encoder?.maximumOutputPacketSize ?? 0, 1_500)
        self.payloadLimit = payloadLimit
        self.pairFrames = pairFrames
        self.uplink = uplink
        self.senderDtmf = senderDtmf
        self.playback = playback
        self.routeChanged = routeChanged
        self.send = send
        receiver = try AudioSeal(key: key)
        sender = try AudioSeal(key: key)
        source = AVAudioSourceNode(format: pcm) { _, _, frameCount, bufferList in
            let buffers = UnsafeMutableAudioBufferListPointer(bufferList)
            guard let output = buffers.first?.mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            playback.withLockUnchecked { $0.pull(output, Int(frameCount)) }
            return noErr
        }
    }

    /// Starts playback and capture. CallKit calls this once it activates the audio session.
    func start() throws {
        guard !running else { return }
        if uplink {
            let input = engine.inputNode
            if !input.isVoiceProcessingEnabled {
                // Some routes supply a usable mic even when the voice-processing unit cannot open.
                try? input.setVoiceProcessingEnabled(true)
            }
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0 else { throw CallAudioError.microphone }
            if format == pcm {
                resampler = nil
            } else {
                guard let converter = AVAudioConverter(from: format, to: pcm) else { throw CallAudioError.microphone }
                converter.primeMethod = .none
                resampler = converter
            }
            input.installTap(onBus: 0, bufferSize: 480, format: format) { [weak self] buffer, _ in
                self?.capture(buffer)
            }
        }
        if !attached {
            engine.attach(source)
            attached = true
        }
        engine.connect(source, to: engine.mainMixerNode, format: pcm)
        engine.prepare()
        do {
            try engine.start()
        } catch {
            if uplink { engine.inputNode.removeTap(onBus: 0) }
            throw error
        }
        running = true
        // Route changes stop the engine and can change the microphone format.
        observer = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange,
                                                          object: engine, queue: .main) { [weak self] _ in
            guard let self, self.running else { return }
            self.stop()
            self.routeChanged()
        }
    }

    deinit { stop() }

    func stop() {
        tones.withLockUnchecked { $0 = [] }
        guard running else { return }
        running = false
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = nil
        if uplink { engine.inputNode.removeTap(onBus: 0) }
        engine.stop()
        let (underruns, trims) = playback.withLockUnchecked { buffer in
            let stats = (buffer.underruns, buffer.trims)
            buffer = Jitter()
            return stats
        }
        if underruns > 0 || trims > 0 {
            Self.logger.info("Call playback underruns=\(underruns) buffer trims=\(trims)")
        }
        pending.removeAll(keepingCapacity: true)
    }

    /// Opens one notification from Android and queues its audio. Called on the main thread.
    func receive(_ packet: Data) {
        guard let payload = receiver.open(packet) else { return }
        if pairFrames {
            guard let (first, second) = AudioSeal.splitPair(payload) else { return }
            decode(first)
            decode(second)
        } else {
            decode(payload)
        }
    }

    private func decode(_ payload: Data) {
        let input = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: payload.count)
        payload.withUnsafeBytes { bytes in
            guard let base = bytes.baseAddress else { return }
            input.data.copyMemory(from: base, byteCount: payload.count)
        }
        input.byteLength = UInt32(payload.count)
        input.packetCount = 1
        input.packetDescriptions?.pointee = AudioStreamPacketDescription(
            mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(payload.count))
        guard let output = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: AVAudioFrameCount(Self.frameLength * 6))
        else { return }
        guard Self.convert(decoder, input, into: output), output.frameLength > 0,
              let channel = output.floatChannelData?[0] else { return }
        let frames = UnsafeBufferPointer(start: channel, count: Int(output.frameLength))
        playback.withLockUnchecked { $0.push(frames) }
    }

    private func capture(_ buffer: AVAudioPCMBuffer) {
        guard !muted else {
            pending.removeAll(keepingCapacity: true)
            return
        }
        let samples: AVAudioPCMBuffer
        if let resampler {
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * Self.sampleRate / buffer.format.sampleRate) + 64
            guard let converted = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: capacity),
                  Self.convert(resampler, buffer, into: converted) else { return }
            samples = converted
        } else {
            samples = buffer
        }
        guard let channel = samples.floatChannelData?[0] else { return }
        pending.append(contentsOf: UnsafeBufferPointer(start: channel, count: Int(samples.frameLength)))
        var offset = 0
        while !muted && pending.count - offset >= Self.frameLength {
            if let packet = encode(pending[offset..<(offset + Self.frameLength)]),
               let sealed = try? sender.seal(packet) {
                send(sealed)
            }
            offset += Self.frameLength
        }
        pending.removeFirst(offset)
    }

    func enqueueTone(_ digit: Character) -> Bool {
        guard running, uplink, !muted, let tone = Self.toneSamples(digit) else { return false }
        return tones.withLockUnchecked { queue in
            guard queue.count + tone.count <= tone.count * 12 else { return false }
            queue.append(contentsOf: tone)
            return true
        }
    }

    static func frequencies(_ digit: Character) -> (Double, Double)? {
        let keys = Array("123456789*0#")
        guard let index = keys.firstIndex(of: digit) else { return nil }
        return ([697.0, 770.0, 852.0, 941.0][index / 3], [1209.0, 1336.0, 1477.0][index % 3])
    }

    static func toneSamples(_ digit: Character) -> [Float]? {
        guard let (low, high) = frequencies(digit) else { return nil }
        let count = frameLength * 8
        var samples = [Float](repeating: 0, count: count + frameLength * 4)
        for i in 0..<count {
            let phase = 2 * Double.pi * Double(i) / sampleRate
            let fade = min(1, Double(min(i, count - 1 - i)) / 192)
            samples[i] = Float(0.22 * fade * (sin(low * phase) + sin(high * phase)))
        }
        return samples
    }

    private func encode(_ samples: ArraySlice<Float>) -> Data? {
        guard let encoder,
              let input = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: AVAudioFrameCount(Self.frameLength)),
              let channel = input.floatChannelData?[0] else { return nil }
        input.frameLength = AVAudioFrameCount(Self.frameLength)
        samples.withUnsafeBufferPointer { values in
            guard let base = values.baseAddress else { return }
            channel.update(from: base, count: Self.frameLength)
        }
        tones.withLockUnchecked { queue in
            let count = min(Self.frameLength, queue.count)
            guard count > 0 else { return }
            queue.withUnsafeBufferPointer { channel.update(from: $0.baseAddress!, count: count) }
            queue = count == queue.count ? [] : queue.dropFirst(count)
        }
        let output = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: maxPacket)
        guard Self.convert(encoder, input, into: output), output.packetCount > 0 else { return nil }
        let description = output.packetDescriptions?.pointee
        let size = description.map { Int($0.mDataByteSize) } ?? Int(output.byteLength)
        let start = Int(description?.mStartOffset ?? 0)
        guard size > 0, size <= payloadLimit, start >= 0, start <= Int(output.byteLength) - size else { return nil }
        return Data(bytes: output.data.advanced(by: start), count: size)
    }

    // Feeds one buffer and leaves the converter ready for the next, which keeps codec and resampler state.
    private static func convert(_ converter: AVAudioConverter, _ input: AVAudioBuffer, into output: AVAudioBuffer) -> Bool {
        var supplied = false
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, inputStatus in
            if supplied {
                inputStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            inputStatus.pointee = .haveData
            return input
        }
        return status != .error && error == nil
    }
}

// Jitter buffer between Bluetooth arrivals and the audio render thread.
struct Jitter {
    private static let minimum = CallAudio.frameLength * 3
    private static let maximum = CallAudio.frameLength * 6
    private static let limit = CallAudio.frameLength * 9
    private static let fadeLength = 240

    private var samples = [Float]()
    private var head = 0
    private var start = Jitter.minimum
    private var primed = false
    private var needsFade = false
    private var lastSample: Float = 0
    private var fadeFrom: Float = 0
    private var fadeRemaining = 0
    private(set) var underruns = 0
    private(set) var trims = 0

    private var count: Int { samples.count - head }

    mutating func push(_ frames: UnsafeBufferPointer<Float>) {
        if head >= 48_000 {
            samples.removeFirst(head)
            head = 0
        }
        samples.append(contentsOf: frames)
        // A burst after a Bluetooth stall or clock drift would otherwise grow the delay.
        if count > Self.limit {
            head = samples.count - start
            needsFade = true
            trims += 1
        }
    }

    mutating func pull(_ output: UnsafeMutablePointer<Float>, _ frames: Int) {
        guard frames > 0 else { return }
        let playing = primed || count >= start
        var written = 0
        if playing {
            if !primed || needsFade { beginFade() }
            needsFade = false
            written = min(frames, count)
            if written > 0 {
                samples.withUnsafeBufferPointer { values in
                    if let base = values.baseAddress { output.update(from: base + head, count: written) }
                }
                head += written
                applyFade(to: output, count: written)
            }
        }
        if written < frames {
            let tail = output + written
            let remaining = frames - written
            tail.update(repeating: 0, count: remaining)
            if playing {
                start = min(start + CallAudio.frameLength, Self.maximum)
                underruns += 1
                beginFade()
            }
            applyFade(to: tail, count: remaining)
        }
        primed = playing && written == frames
    }

    private mutating func beginFade() {
        fadeFrom = lastSample
        fadeRemaining = Self.fadeLength
    }

    private mutating func applyFade(to output: UnsafeMutablePointer<Float>, count: Int) {
        for index in 0..<min(count, fadeRemaining) {
            fadeRemaining -= 1
            let weight = Float(fadeRemaining) / Float(Self.fadeLength)
            output[index] += (fadeFrom - output[index]) * weight
        }
        lastSample = output[count - 1]
    }
}
