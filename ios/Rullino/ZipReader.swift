import Compression
import Foundation

/// Lettore ZIP minimale (metodi "stored" e "deflate", niente ZIP64): basta per i file .emulsion.
enum ZipReader {
    struct Failure: Error {}

    static let maxTotal = 64 * 1024 * 1024
    static let maxEntries = 256

    /// Nome del file → contenuto. Rifiuta percorsi assoluti o con "..".
    static func read(_ data: Data) throws -> [String: Data] {
        let b = [UInt8](data)
        func u16(_ o: Int) throws -> Int {
            guard o >= 0, o + 2 <= b.count else { throw Failure() }
            return Int(b[o]) | Int(b[o + 1]) << 8
        }
        func u32(_ o: Int) throws -> Int {
            guard o >= 0, o + 4 <= b.count else { throw Failure() }
            return Int(b[o]) | Int(b[o + 1]) << 8 | Int(b[o + 2]) << 16 | Int(b[o + 3]) << 24
        }
        // Fine della directory centrale, cercata dal fondo (il commento può essere lungo fino a 64 KB).
        var eocd = -1
        var i = b.count - 22
        while i >= max(0, b.count - 22 - 65535) {
            if try u32(i) == 0x06054b50 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw Failure() }
        let count = try u16(eocd + 10)
        var p = try u32(eocd + 16)
        guard count <= maxEntries else { throw EmulsionError.tooBig }

        var out: [String: Data] = [:]
        var total = 0
        for _ in 0..<count {
            guard try u32(p) == 0x02014b50 else { throw Failure() }
            let method = try u16(p + 10)
            let csize = try u32(p + 20)
            let usize = try u32(p + 24)
            let nlen = try u16(p + 28), elen = try u16(p + 30), clen = try u16(p + 32)
            let local = try u32(p + 42)
            guard p + 46 + nlen <= b.count else { throw Failure() }
            let name = String(decoding: b[(p + 46)..<(p + 46 + nlen)], as: UTF8.self)
            p += 46 + nlen + elen + clen
            if name.hasSuffix("/") { continue }
            if name.hasPrefix("/") || name.split(separator: "/").contains("..") || name.contains("\\") { throw EmulsionError.badPaths }
            total += usize
            guard total <= maxTotal else { throw EmulsionError.tooBig }

            guard try u32(local) == 0x04034b50 else { throw Failure() }
            let start = local + 30 + (try u16(local + 26)) + (try u16(local + 28))
            guard start + csize <= b.count else { throw Failure() }
            let raw = Array(b[start..<(start + csize)])
            switch method {
            case 0: out[name] = Data(raw)
            case 8: out[name] = try inflate(raw, size: usize)
            default: throw Failure()
            }
        }
        return out
    }

    /// DEFLATE grezzo (COMPRESSION_ZLIB di Apple è proprio questo, senza intestazione zlib).
    private static func inflate(_ src: [UInt8], size: Int) throws -> Data {
        if size == 0 { return Data() }
        var dst = [UInt8](repeating: 0, count: size)
        let n = src.withUnsafeBufferPointer { s in
            dst.withUnsafeMutableBufferPointer { d in
                compression_decode_buffer(d.baseAddress!, size, s.baseAddress!, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        guard n == size else { throw Failure() }
        return Data(dst)
    }
}
