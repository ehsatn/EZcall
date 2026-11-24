@main
struct AudioJitterCheck {
    static let frame = CallAudio.frameLength

    static func push(_ buffer: inout Jitter, frames: Int, level: Float = 0.5) {
        Array(repeating: level, count: frames * frame).withUnsafeBufferPointer { buffer.push($0) }
    }

    static func pull(_ buffer: inout Jitter, frames: Int = 1) -> [Float] {
        var output = Array(repeating: Float.zero, count: frames * frame)
        output.withUnsafeMutableBufferPointer { buffer.pull($0.baseAddress!, $0.count) }
        return output
    }

    static func main() {
        precondition(CallAudio.frequencies("5")?.0 == 770 && CallAudio.frequencies("5")?.1 == 1336)
        precondition(CallAudio.toneSamples("A") == nil)
        for digit in "123456789*0#" {
            let samples = CallAudio.toneSamples(digit)!
            precondition(samples.count == frame * 12 && samples.prefix(frame * 8).contains { abs($0) > 0.2 })
            precondition(samples.suffix(frame * 4).allSatisfy { $0 == 0 })
        }
        var buffer = Jitter()
        push(&buffer, frames: 1)
        precondition(pull(&buffer).allSatisfy { $0 == 0 })
        push(&buffer, frames: 1)
        let first = pull(&buffer, frames: 2)
        precondition(first[0] < 0.01 && first.last == 0.5 && buffer.underruns == 0)

        let gap = pull(&buffer)
        precondition(gap[0] > 0.49 && gap[239] == 0 && gap.last == 0)
        push(&buffer, frames: 2)
        precondition(pull(&buffer).allSatisfy { $0 == 0 })
        push(&buffer, frames: 1)
        precondition(pull(&buffer, frames: 3).last == 0.5 && buffer.underruns == 1)

        _ = pull(&buffer)
        for _ in 0..<2 {
            push(&buffer, frames: 3)
            precondition(pull(&buffer).allSatisfy { $0 == 0 })
            push(&buffer, frames: 1)
            precondition(pull(&buffer, frames: 4).last == 0.5)
            _ = pull(&buffer)
        }
        precondition(buffer.underruns == 4)

        var burst = Jitter()
        push(&burst, frames: 2)
        _ = pull(&burst)
        push(&burst, frames: 6, level: -0.5)
        let trimmed = pull(&burst, frames: 3)
        precondition(burst.trims == 1 && trimmed[0] > 0.49 && trimmed.last == -0.5)
        _ = pull(&burst)
        precondition(burst.underruns == 1)

        print("Audio jitter checks passed")
    }
}
