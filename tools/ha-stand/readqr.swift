// Чтение QR распознавателем macOS: swift -sdk <SDK той же версии, что компилятор> readqr.swift <папка> — сверяет qrN.pgm с текстом qrN.txt (QrCodeTest.dumpForScanner).
import AppKit
import Vision
let dir = CommandLine.arguments[1]
var ok = 0, bad = 0
for i in 0..<100 {
    guard let pgm = try? String(contentsOfFile: "\(dir)/qr\(i).pgm", encoding: .utf8),
          let text = try? String(contentsOfFile: "\(dir)/qr\(i).txt", encoding: .utf8) else { break }
    let parts = pgm.split(whereSeparator: { $0 == " " || $0 == "\n" })
    let w = Int(parts[1])!, h = Int(parts[2])!
    let bytes = parts[4...].map { UInt8($0)! }
    let provider = CGDataProvider(data: Data(bytes) as CFData)!
    let image = CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: w, space: CGColorSpaceCreateDeviceGray(),
                        bitmapInfo: CGBitmapInfo(rawValue: 0), provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
    let request = VNDetectBarcodesRequest()
    request.symbologies = [.qr]
    try? VNImageRequestHandler(cgImage: image).perform([request])
    let read = (request.results ?? []).first?.payloadStringValue
    if read == text { ok += 1 } else { bad += 1; print("qr\(i): не совпало (прочитано: \(read?.count ?? -1) знаков)") }
}
print("прочитано верно: \(ok), ошибок: \(bad)")
