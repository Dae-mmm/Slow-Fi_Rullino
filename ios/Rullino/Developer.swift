import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Sviluppo di un fotogramma: fonde le esposizioni come la pellicola (fusione "screen"),
/// taglia in 3:2, passa nello shader dell'emulsione e salva il positivo in JPEG.
enum Developer {
    static let maxSide = 3000

    static func develop(files: [URL], params: FilmParams, iso: Int, out: URL, seed: Float,
                        push: Int = 0, slide: Bool = false) -> Bool {
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
        let errors = files.compactMap { VirtualCamera.errorStops($0, filmIso: iso) }
        let err = errors.isEmpty ? 0 : errors.reduce(0, +) / Float(errors.count)
        let u = FilmRenderer.uniforms(params, iso: iso, w: W, h: H, seed: seed, exposureError: err, push: push, slide: slide)
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
    static func errorStops(_ url: URL, filmIso: Int) -> Float? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any],
              let t = exif[kCGImagePropertyExifExposureTime] as? Double, t > 0,
              let n = exif[kCGImagePropertyExifFNumber] as? Double, n > 0,
              let isos = exif[kCGImagePropertyExifISOSpeedRatings] as? [Double], let iso = isos.first, iso > 0
        else { return nil }
        let ev100 = log2(n * n / t) - log2(iso / 100)
        return errorFor(ev100: ev100, filmIso: filmIso)
    }

    static func errorFor(ev100: Double, filmIso: Int) -> Float {
        let needed = 1 / (pow(2, ev100) * Double(max(filmIso, 1)) / 100)
        let most = (1.0 / 30) / (2.8 * 2.8)
        let least = (1.0 / 500) / (16 * 16)
        let used = min(max(needed, least), most)
        return Float(min(max(log2(used / needed), -5), 5))
    }
}
