import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Sviluppo di un fotogramma: fonde le esposizioni come la pellicola (fusione "screen"),
/// taglia in 3:2, passa nello shader dell'emulsione e salva il positivo in JPEG.
enum Developer {
    static let maxSide = 3000

    static func develop(files: [URL], params: FilmParams, iso: Int, out: URL, seed: Float,
                        push: Int = 0, slide: Bool = false, shutters: [Double?] = [], meters: [Double?] = []) -> Bool {
        let layers = files.compactMap { load($0) }
        guard let first = layers.first else { return false }
        let ratio: CGFloat = first.width >= first.height ? 1.5 : 1 / 1.5
        var w = CGFloat(first.width), h = (w / ratio).rounded(.down)
        if h > CGFloat(first.height) { h = CGFloat(first.height); w = (h * ratio).rounded(.down) }
        let W = Int(w), H = Int(h)

        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: W * 4,
                                  space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
        ctx.interpolationQuality = .high
        ctx.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))
        for (i, img) in layers.enumerated() {
            ctx.setBlendMode(i == 0 ? .normal : .screen)
            // Riempie il fotogramma tagliando l'eccesso al centro.
            let s = max(CGFloat(W) / CGFloat(img.width), CGFloat(H) / CGFloat(img.height))
            let dw = CGFloat(img.width) * s, dh = CGFloat(img.height) * s
            ctx.draw(img, in: CGRect(x: (CGFloat(W) - dw) / 2, y: (CGFloat(H) - dh) / 2, width: dw, height: dh))
        }
        guard let px = ctx.data else { return false }
        // La luce vera della scena: dati EXIF corretti da quanto è chiara davvero la foto del telefono.
        let offsets = files.map { VirtualCamera.lumaOffset($0) }
        // Prima scelta: quello che diceva l'esposimetro allo scatto (è quello che hai visto).
        // Per gli scatti vecchi: dati EXIF corretti dalla luminosità vera della foto.
        let errors = files.enumerated().compactMap { i, u -> Float? in
            let shutter = i < shutters.count ? shutters[i] : nil
            if i < meters.count, let ev = meters[i] {
                if let shutter { return VirtualCamera.manualError(ev100: ev, filmIso: iso, shutter: shutter) }
                return VirtualCamera.errorFor(ev100: ev, filmIso: iso)
            }
            return VirtualCamera.errorStops(u, filmIso: iso, shutter: shutter, offset: offsets[i])
        }
        let err = errors.isEmpty ? 0 : errors.reduce(0, +) / Float(errors.count)
        // La foto del telefono si riporta prima a un'esposizione media, poi la pellicola fa il resto.
        let base = offsets.isEmpty ? 0 : -offsets.reduce(0, +) / Float(offsets.count)
        let u = FilmRenderer.uniforms(params, iso: iso, w: W, h: H, seed: seed, exposureError: err, push: push, slide: slide, base: base)
        guard FilmRenderer.shared.render(pixels: px, width: W, height: H, uniforms: u),
              let result = ctx.makeImage() else { return false }

        guard let dest = CGImageDestinationCreateWithURL(out as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else { return false }
        CGImageDestinationAddImage(dest, result, [kCGImageDestinationLossyCompressionQuality: 0.92] as CFDictionary)
        return CGImageDestinationFinalize(dest)
    }

    /// Carica lo scatto già ruotato secondo l'EXIF e ridotto al lato massimo.
    static func load(_ url: URL) -> CGImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
        ]
        return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary)
    }

    /// Miniatura veloce per il tavolo luminoso.
    static func thumbnail(_ url: URL, maxPixel: Int) -> CGImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary)
    }
}

/// La compatta virtuale: esposizione automatica con limiti veri (f/2.8–f/16, 1/30–1/500).
/// Il telefono fa da esposimetro: dai dati EXIF ricava la luce della scena e calcola di quanti
/// stop sbaglia la compatta con la sensibilità della pellicola caricata.
enum VirtualCamera {
    /// offset = quanto la foto del telefono è più chiara (+) o più scura (−) di un'esposizione media, in stop.
    /// Di notte il telefono arriva al limite e la foto resta scura anche se l'EXIF "sembra" giusto.
    static func errorStops(_ url: URL, filmIso: Int, shutter: Double? = nil, offset: Float = 0) -> Float? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any],
              let t = exif[kCGImagePropertyExifExposureTime] as? Double, t > 0,
              let n = exif[kCGImagePropertyExifFNumber] as? Double, n > 0,
              let isos = exif[kCGImagePropertyExifISOSpeedRatings] as? [Double], let iso = isos.first, iso > 0
        else { return nil }
        let ev100 = log2(n * n / t) - log2(iso / 100) + Double(offset)
        if let shutter { return manualError(ev100: ev100, filmIso: filmIso, shutter: shutter) }
        return errorFor(ev100: ev100, filmIso: filmIso)
    }

    /// Mezzo stop di tolleranza (le foto normali non cambiano), poi limitato a −4…+1,5 stop.
    static func deadZone(_ k: Double) -> Float {
        let v = k > 0 ? max(k - 0.5, 0) : min(k + 0.5, 0)
        return Float(min(max(v, -4), 1.5))
    }

    /// Di quanti stop la foto è più chiara o più scura del grigio medio (media pesata al centro).
    static func lumaOffset(_ url: URL) -> Float {
        guard let img = Developer.thumbnail(url, maxPixel: 128), let space = CGColorSpace(name: CGColorSpace.sRGB) else { return 0 }
        let w = img.width, h = img.height
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return 0 }
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let data = ctx.data else { return 0 }
        let p = data.bindMemory(to: UInt8.self, capacity: w * h * 4)
        func lin(_ c: UInt8) -> Double { let v = Double(c) / 255; return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        var sum = 0.0, weights = 0.0
        for y in stride(from: 0, to: h, by: 2) {
            for x in stride(from: 0, to: w, by: 2) {
                let i = (y * w + x) * 4
                let fx = (Double(x) + 0.5) / Double(w) - 0.5, fy = (Double(y) + 0.5) / Double(h) - 0.5
                let wgt = 1 + 1.5 * exp(-(fx * fx + fy * fy) / 0.05)
                sum += wgt * (0.2126 * lin(p[i]) + 0.7152 * lin(p[i + 1]) + 0.0722 * lin(p[i + 2]))
                weights += wgt
            }
        }
        let mean = sum / max(weights, 1)
        return mean <= 0 ? -4 : deadZone(log2(mean / 0.18))
    }

    /// Tempo giusto (secondi) a f/4 per una scena di luminosità ev100 con la pellicola caricata.
    static func neededShutter(ev100: Double, filmIso: Int) -> Double {
        16 / (pow(2, ev100) * Double(max(filmIso, 1)) / 100)
    }

    static func ev100(seconds t: Double, fNumber n: Double, iso: Double) -> Double {
        log2(n * n / t) - log2(iso / 100)
    }

    /// Tempo scelto sulla ghiera, diaframma fisso a f/4.
    static func manualError(ev100: Double, filmIso: Int, shutter: Double) -> Float {
        let needed = 16 / (pow(2, ev100) * Double(max(filmIso, 1)) / 100)
        return Float(min(max(log2(shutter / needed), -5), 5))
    }

    /// Il tempo che usa davvero la compatta in automatico: f/8 se basta, altrimenti apre fino a f/2.8;
    /// sempre tra 1/500 e 1/30.
    static func autoShutter(ev100: Double, filmIso: Int) -> Double {
        let needed = 1 / (pow(2, ev100) * Double(max(filmIso, 1)) / 100)   // t/N²
        let atF8 = needed * 64
        if atF8 >= 1.0 / 500 && atF8 <= 1.0 / 30 { return atF8 }
        if atF8 < 1.0 / 500 { return 1.0 / 500 }
        return min(max(needed * 2.8 * 2.8, 1.0 / 500), 1.0 / 30)
    }

    static func errorFor(ev100: Double, filmIso: Int) -> Float {
        let needed = 1 / (pow(2, ev100) * Double(max(filmIso, 1)) / 100)
        let most = (1.0 / 30) / (2.8 * 2.8)
        let least = (1.0 / 500) / (16 * 16)
        let used = min(max(needed, least), most)
        return Float(min(max(log2(used / needed), -5), 5))
    }
}
