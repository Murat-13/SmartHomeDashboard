// Образец H.264 для поддельной камеры тестов на эмуляторе (app/src/androidTest/assets/recording/sample.h264): 160×120, 10 кадров/с,
// 3 с, ключевой кадр раз в секунду, Baseline 4:2:0 — его понимает декодер любого Android. Перед каждым кадром — AUD.
//   swift tools/camera-stand/sample.swift app/src/androidTest/assets/recording/sample.h264
import CoreMedia
import CoreVideo
import Foundation
import VideoToolbox

let width = 160, height = 120, fps = 10, frames = 30
var out = Data()
let start = Data([0, 0, 0, 1])

var session: VTCompressionSession?
guard VTCompressionSessionCreate(allocator: nil, width: Int32(width), height: Int32(height), codecType: kCMVideoCodecType_H264,
    encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil, outputCallback: nil, refcon: nil,
    compressionSessionOut: &session) == noErr, let enc = session else { fatalError("кодировщик") }
func set(_ key: CFString, _ value: CFTypeRef) { VTSessionSetProperty(enc, key: key, value: value) }
set(kVTCompressionPropertyKey_RealTime, kCFBooleanFalse)
set(kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
set(kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_Baseline_3_0)
set(kVTCompressionPropertyKey_MaxKeyFrameInterval, fps as CFNumber)
set(kVTCompressionPropertyKey_ExpectedFrameRate, fps as CFNumber)
set(kVTCompressionPropertyKey_AverageBitRate, 120_000 as CFNumber)

func append(_ sample: CMSampleBuffer) {
    out += start + Data([0x09, 0xF0])
    let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]]
    let key = (attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool) != true
    if key, let format = CMSampleBufferGetFormatDescription(sample) {
        for i in 0..<2 {
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: i, parameterSetPointerOut: &pointer,
                parameterSetSizeOut: &size, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
            out += start + Data(bytes: pointer!, count: size)
        }
    }
    let block = CMSampleBufferGetDataBuffer(sample)!
    var bytes = Data(count: CMBlockBufferGetDataLength(block))
    let total = bytes.count
    bytes.withUnsafeMutableBytes { _ = CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: total, destination: $0.baseAddress!) }
    var pos = 0
    while pos + 4 <= bytes.count {
        let n = bytes[pos..<pos + 4].reduce(0) { $0 << 8 | Int($1) }
        let nal = bytes[pos + 4..<pos + 4 + n]
        // Служебные сообщения кодировщика (SEI) не нужны.
        if nal.first! & 0x1F != 6 { out += start + nal }
        pos += 4 + n
    }
}

for f in 0..<frames {
    var pb: CVPixelBuffer?
    CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, nil, &pb)
    let buffer = pb!
    CVPixelBufferLockBaseAddress(buffer, [])
    let y = CVPixelBufferGetBaseAddressOfPlane(buffer, 0)!.assumingMemoryBound(to: UInt8.self)
    let ys = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)
    for row in 0..<height { for col in 0..<width { y[row * ys + col] = UInt8(16 + (col + row + f * 8) % 200) } }
    let c = CVPixelBufferGetBaseAddressOfPlane(buffer, 1)!.assumingMemoryBound(to: UInt8.self)
    let cs = CVPixelBufferGetBytesPerRowOfPlane(buffer, 1)
    for row in 0..<height / 2 { for col in 0..<width { c[row * cs + col] = UInt8(96 + (col + f * 4) % 64) } }
    CVPixelBufferUnlockBaseAddress(buffer, [])
    VTCompressionSessionEncodeFrame(enc, imageBuffer: buffer, presentationTimeStamp: CMTime(value: Int64(f), timescale: Int32(fps)),
        duration: .invalid, frameProperties: nil, infoFlagsOut: nil) { status, _, sample in
        if status == noErr, let sample { append(sample) }
    }
    VTCompressionSessionCompleteFrames(enc, untilPresentationTimeStamp: .invalid)
}
try! out.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
print("\(out.count) байт")
