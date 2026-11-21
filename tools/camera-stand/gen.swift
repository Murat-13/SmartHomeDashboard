// Тестовое видео для стенда камер: кодировщик macOS (VideoToolbox), без сторонних программ.
// gen <h264|hevc> <ширина> <высота> <к/с> <кбит/с> <ключевой кадр раз в N кадров> <секунд> <подпись> <файл>
// Файл: "CAMS", кодек (4|5), к/с, ширина, высота (по 2 байта), число кадров (4 байта); дальше кадры:
// длина (4 байта), признак ключевого (1 байт), NAL-единицы с 4-байтной длиной (у ключевого — с VPS/SPS/PPS впереди).
import Foundation
import CoreMedia
import CoreVideo
import VideoToolbox
import CoreGraphics
import CoreText

let args = CommandLine.arguments
guard args.count == 10 else {
    print("usage: gen h264|hevc width height fps kbps gop seconds label out")
    exit(2)
}
let hevc = args[1] == "hevc"
let width = Int(args[2])!, height = Int(args[3])!, fps = Int(args[4])!, kbps = Int(args[5])!
let gop = Int(args[6])!, seconds = Int(args[7])!
let label = args[8], outPath = args[9]
let total = fps * seconds

func be16(_ v: Int) -> [UInt8] { [UInt8((v >> 8) & 255), UInt8(v & 255)] }
func be32(_ v: Int) -> [UInt8] { [UInt8((v >> 24) & 255), UInt8((v >> 16) & 255), UInt8((v >> 8) & 255), UInt8(v & 255)] }

FileManager.default.createFile(atPath: outPath, contents: nil)
let out = FileHandle(forWritingAtPath: outPath)!
var header = Data("CAMS".utf8)
header.append(UInt8(hevc ? 5 : 4))
header.append(contentsOf: be16(fps) + be16(width) + be16(height) + be32(total))
out.write(header)

var session: VTCompressionSession?
let created = VTCompressionSessionCreate(
    allocator: nil, width: Int32(width), height: Int32(height),
    codecType: hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264,
    encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil,
    outputCallback: nil, refcon: nil, compressionSessionOut: &session)
guard created == noErr, let enc = session else { print("кодировщик не создан: \(created)"); exit(1) }

func set(_ key: CFString, _ value: CFTypeRef) {
    let s = VTSessionSetProperty(enc, key: key, value: value)
    if s != noErr { print("свойство \(key) не принято: \(s)") }
}
set(kVTCompressionPropertyKey_RealTime, kCFBooleanFalse)
set(kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
set(kVTCompressionPropertyKey_ProfileLevel, hevc ? kVTProfileLevel_HEVC_Main_AutoLevel : kVTProfileLevel_H264_High_AutoLevel)
set(kVTCompressionPropertyKey_MaxKeyFrameInterval, gop as CFNumber)
set(kVTCompressionPropertyKey_ExpectedFrameRate, fps as CFNumber)
set(kVTCompressionPropertyKey_AverageBitRate, (kbps * 1000) as CFNumber)
VTCompressionSessionPrepareToEncodeFrames(enc)

let lock = NSLock()
var written = 0, keys = 0, bytes = 0, failed = false

func parameterSets(_ fmt: CMFormatDescription) -> Data {
    var data = Data()
    var count = 0
    var index = 0
    repeat {
        var pointer: UnsafePointer<UInt8>?
        var size = 0
        let status = hevc
            ? CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(fmt, parameterSetIndex: index, parameterSetPointerOut: &pointer,
                                                                 parameterSetSizeOut: &size, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
            : CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: index, parameterSetPointerOut: &pointer,
                                                                 parameterSetSizeOut: &size, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
        guard status == noErr, let p = pointer else { break }
        data.append(contentsOf: be32(size))
        data.append(p, count: size)
        index += 1
    } while index < count
    return data
}

let handler: VTCompressionOutputHandler = { status, _, sample in
    guard status == noErr, let sample = sample, let block = CMSampleBufferGetDataBuffer(sample) else { failed = true; return }
    let length = CMBlockBufferGetDataLength(block)
    var payload = Data(count: length)
    payload.withUnsafeMutableBytes { raw in
        _ = CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: raw.baseAddress!)
    }
    let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]]
    let notSync = attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false
    var record = Data()
    if !notSync, let fmt = CMSampleBufferGetFormatDescription(sample) { record.append(parameterSets(fmt)) }
    record.append(payload)
    lock.lock()
    out.write(Data(be32(record.count) + [UInt8(notSync ? 0 : 1)]))
    out.write(record)
    written += 1
    if !notSync { keys += 1 }
    bytes += record.count
    lock.unlock()
}

let space = CGColorSpace(name: CGColorSpace.sRGB)!
let font = CTFontCreateWithName("Menlo-Bold" as CFString, CGFloat(height) / 7, nil)
let small = CTFontCreateWithName("Menlo-Bold" as CFString, CGFloat(height) / 14, nil)
func text(_ ctx: CGContext, _ s: String, _ f: CTFont, x: CGFloat, y: CGFloat) {
    let attributes: [CFString: Any] = [kCTFontAttributeName: f, kCTForegroundColorAttributeName: CGColor(red: 1, green: 1, blue: 1, alpha: 1)]
    let line = CTLineCreateWithAttributedString(CFAttributedStringCreate(nil, s as CFString, attributes as CFDictionary)!)
    ctx.textPosition = CGPoint(x: x, y: y)
    CTLineDraw(line, ctx)
}

// Два генератора: неподвижный «рисунок сцены» (одинаков в каждом кадре) и «шум датчика», меняющий малую часть блоков.
// Так ключевой кадр выходит в разы больше обычного — как у камеры, снимающей неподвижный двор.
var still: UInt64 = 1
var seed: UInt64 = 0x9E3779B97F4A7C15
@inline(__always) func nextStill() -> UInt64 { still ^= still << 13; still ^= still >> 7; still ^= still << 17; return still }
@inline(__always) func next() -> UInt64 { seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17; return seed }

for i in 0..<total {
    var buffer: CVPixelBuffer?
    CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32BGRA, [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &buffer)
    guard let pb = buffer else { print("нет буфера кадра"); exit(1) }
    CVPixelBufferLockBaseAddress(pb, [])
    let base = CVPixelBufferGetBaseAddress(pb)!
    let stride = CVPixelBufferGetBytesPerRow(pb)
    let ctx = CGContext(data: base, width: width, height: height, bitsPerComponent: 8, bytesPerRow: stride, space: space,
                        bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)!
    // Фон плавно меняется, по нему едет полоса: видно, что картинка живая.
    let phase = Double(i) / Double(total)
    ctx.setFillColor(CGColor(red: 0.10 + 0.08 * CGFloat(sin(phase * 2 * .pi)), green: 0.16, blue: 0.22 + 0.08 * CGFloat(cos(phase * 2 * .pi)), alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
    ctx.setFillColor(CGColor(red: 0.95, green: 0.75, blue: 0.2, alpha: 1))
    let barX = CGFloat((i * max(2, width / (fps * 4))) % width)
    ctx.fill(CGRect(x: barX, y: CGFloat(height) * 0.36, width: CGFloat(width) / 40, height: CGFloat(height) * 0.12))
    text(ctx, label, small, x: CGFloat(width) * 0.04, y: CGFloat(height) * 0.86)
    text(ctx, String(format: "%02d.%02d", i / fps, i % fps), font, x: CGFloat(width) * 0.04, y: CGFloat(height) * 0.62)
    // Нижняя треть — мелкий рисунок с «шумом датчика»: кодировщику есть на что тратить заданный битрейт.
    let pixels = base.assumingMemoryBound(to: UInt8.self)
    still = 0x2545F4914F6CDD1D
    let block = max(4, width / 320)
    var y = height * 2 / 3
    while y < height {
        var x = 0
        while x < width {
            var v = UInt8(60 + (nextStill() & 0x7F))
            if next() & 31 == 0 { v = UInt8(60 + (next() & 0x7F)) }
            var yy = y
            while yy < min(y + block, height) {
                var xx = x
                let row = pixels + yy * stride
                while xx < min(x + block, width) {
                    row[xx * 4] = v; row[xx * 4 + 1] = v; row[xx * 4 + 2] = v; row[xx * 4 + 3] = 255
                    xx += 1
                }
                yy += 1
            }
            x += block
        }
        y += block
    }
    CVPixelBufferUnlockBaseAddress(pb, [])
    let pts = CMTime(value: CMTimeValue(i), timescale: CMTimeScale(fps))
    let properties: CFDictionary? = i % gop == 0 ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary : nil
    let status = VTCompressionSessionEncodeFrame(enc, imageBuffer: pb, presentationTimeStamp: pts,
                                                 duration: CMTime(value: 1, timescale: CMTimeScale(fps)),
                                                 frameProperties: properties, infoFlagsOut: nil, outputHandler: handler)
    if status != noErr { print("кадр \(i) не принят: \(status)"); exit(1) }
}
VTCompressionSessionCompleteFrames(enc, untilPresentationTimeStamp: .invalid)
VTCompressionSessionInvalidate(enc)
out.closeFile()
if failed || written != total { print("ошибка: записано \(written) из \(total)"); exit(1) }
print(String(format: "%@: %d кадров, ключевых %d, %.0f кбит/с", label, written, keys, Double(bytes) * 8 / Double(seconds) / 1000))
