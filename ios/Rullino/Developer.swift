import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Sviluppo di un fotogramma: fonde le esposizioni come la pellicola (fusione "screen"),
/// taglia in 3:2, passa nello shader dell'emulsione e salva il positivo in JPEG.
enum Developer {
    static let maxSide = 3000

    static func develop(files: [URL], params: FilmParams, iso: Int, out: URL, seed: Float) -> Bool {
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
        let u = FilmRenderer.uniforms(params, iso: iso, w: W, h: H, seed: seed)
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
