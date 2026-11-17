import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Scrittore ZIP minimale, solo metodo "stored": basta per i .rullino e per i .emulsion ricostruiti.
struct ZipWriter {
    private var data = Data()
    private var central = Data()
    private var count = 0

    private static let crcTable: [UInt32] = (0..<256).map { n -> UInt32 in
        var c = UInt32(n)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    private static func crc32(_ d: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for b in d { c = crcTable[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFF_FFFF
    }

    private static func le16(_ v: Int) -> Data { withUnsafeBytes(of: UInt16(truncatingIfNeeded: v).littleEndian) { Data($0) } }
    private static func le32(_ v: UInt32) -> Data { withUnsafeBytes(of: v.littleEndian) { Data($0) } }

    mutating func add(_ name: String, _ bytes: Data) {
        let n = Data(name.utf8), crc = Self.crc32(bytes), size = UInt32(bytes.count), offset = UInt32(data.count)
        var local = Self.le32(0x0403_4b50)
        local += Self.le16(20) + Self.le16(0x0800) + Self.le16(0) + Self.le16(0) + Self.le16(0x21)
        local += Self.le32(crc) + Self.le32(size) + Self.le32(size) + Self.le16(n.count) + Self.le16(0)
        data += local + n + bytes
        var c = Self.le32(0x0201_4b50)
        c += Self.le16(20) + Self.le16(20) + Self.le16(0x0800) + Self.le16(0) + Self.le16(0) + Self.le16(0x21)
        c += Self.le32(crc) + Self.le32(size) + Self.le32(size) + Self.le16(n.count) + Self.le16(0) + Self.le16(0)
        c += Self.le16(0) + Self.le16(0) + Self.le32(0) + Self.le32(offset)
        central += c + n
        count += 1
    }

    func finish() -> Data {
        var out = data
        let start = UInt32(out.count)
        out += central
        out += Self.le32(0x0605_4b50) + Self.le16(0) + Self.le16(0) + Self.le16(count) + Self.le16(count)
        out += Self.le32(UInt32(central.count)) + Self.le32(start) + Self.le16(0)
        return out
    }
}

enum RollShareError: Error { case invalid, newer, noEmulsion, failed }

/// Un rullino da passare a qualcuno: file .rullino (ZIP) con roll.json, l'emulsione e le pose da sviluppare.
/// Stesso formato dell'app Android: si scambiano tra i due telefoni.
enum RollShare {
    static let mimetype = "application/x-rullino"
    static let maxSide = 2400

    /// Prepara il file nella cartella temporanea e ne restituisce l'indirizzo.
    @MainActor
    static func export(_ roll: Roll, store: Store, from: String?) throws -> URL {
        guard let emulsion = store.emulsionJSON(roll.emulsionId) else { throw RollShareError.noEmulsion }
        var zip = ZipWriter()
        zip.add("mimetype", Data(mimetype.utf8))
        var shots: [[String: Any]] = []
        for (i, e) in store.data.exposures.filter({ $0.rollId == roll.id }).enumerated() {
            let name = String(format: "shots/%02d_%02d.jpg", e.frame, i)
            guard let bytes = shrink(store.root.appendingPathComponent(e.file)) else { continue }
            zip.add(name, bytes)
            shots.append(["frame": e.frame, "file": name, "takenAt": Int(Date().timeIntervalSince1970 * 1000),
                          "shutter": e.shutter.map { $0 as Any } ?? NSNull()])
        }
        let manifest: [String: Any] = [
            "format": "rullino", "version": 1, "from": from.map { $0 as Any } ?? NSNull(),
            "emulsion": emulsion,
            "roll": [
                "emulsionId": roll.emulsionId, "emulsionName": roll.emulsionName, "iso": roll.iso,
                "exposures": roll.exposures, "isBw": roll.isBw, "cursor": roll.cursor, "shotCount": roll.shotCount,
                "push": roll.push, "loadedAt": Int(roll.createdAt.timeIntervalSince1970 * 1000),
            ],
            "shots": shots,
        ]
        zip.add("roll.json", try JSONSerialization.data(withJSONObject: manifest, options: [.prettyPrinted]))
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let safe = roll.emulsionName.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }.joined(separator: "-")
        let url = dir.appendingPathComponent("\(safe.isEmpty ? "rullino" : safe)-\(roll.shotCount)-\(roll.exposures).rullino")
        try zip.finish().write(to: url)
        return url
    }

    /// Apre un .rullino: installa l'emulsione se manca e mette il rullino in Rullini, scaricato.
    @MainActor
    static func importRoll(_ data: Data, store: Store) throws -> Roll {
        let entries: [String: Data]
        do { entries = try ZipReader.read(data) } catch { throw RollShareError.invalid }
        guard let mBytes = entries["roll.json"],
              let m = (try? JSONSerialization.jsonObject(with: mBytes)) as? [String: Any],
              m["format"] as? String == "rullino" else { throw RollShareError.invalid }
        if let v = m["version"] as? Int, v > 1 { throw RollShareError.newer }
        guard let em = m["emulsion"] as? [String: Any], let ro = m["roll"] as? [String: Any],
              let emulsionId = ro["emulsionId"] as? String, !emulsionId.isEmpty else { throw RollShareError.invalid }

        if store.emulsion(emulsionId) == nil {
            var preset = em; preset["id"] = emulsionId
            guard store.install(emulsionFile(id: emulsionId, preset: preset)) != nil else { throw RollShareError.noEmulsion }
        }

        var roll = Roll(id: store.data.nextId, emulsionId: emulsionId,
                        emulsionName: ro["emulsionName"] as? String ?? (em["name"] as? String ?? ""),
                        iso: ro["iso"] as? Int ?? 400, exposures: ro["exposures"] as? Int ?? 36, isBw: ro["isBw"] as? Bool ?? false)
        roll.cursor = ro["cursor"] as? Int ?? 1
        roll.shotCount = ro["shotCount"] as? Int ?? 0
        roll.push = ro["push"] as? Int ?? 0
        roll.state = .shelved
        if let ms = ro["loadedAt"] as? Double { roll.createdAt = Date(timeIntervalSince1970: ms / 1000) }
        let dir = store.rollDir(roll.id)
        var exposures: [Exposure] = []
        for s in (m["shots"] as? [[String: Any]]) ?? [] {
            guard let file = s["file"] as? String, let bytes = entries[file] else { continue }
            let rel = "rolls/\(roll.id)/raw/" + (file as NSString).lastPathComponent
            try bytes.write(to: store.root.appendingPathComponent(rel))
            exposures.append(Exposure(rollId: roll.id, frame: s["frame"] as? Int ?? 1, file: rel, shutter: s["shutter"] as? Double))
        }
        _ = dir
        store.data.nextId += 1
        store.data.exposures += exposures
        store.data.rolls.append(roll)
        return roll
    }

    /// Un .emulsion minimo (mimetype, manifest, preset) costruito dai parametri arrivati col rullino.
    static func emulsionFile(id: String, preset: [String: Any]) -> Data {
        var zip = ZipWriter()
        zip.add("mimetype", Data(EmulsionInstaller.mimetype.utf8))
        let manifest: [String: Any] = [
            "format": "emulsion", "formatVersion": 1, "id": id,
            "name": preset["name"] as? String ?? "", "iso": preset["iso"] as? Int ?? 400,
            "exposures": preset["exposures"] as? Int ?? 36, "type": preset["type"] as? String ?? "color",
            "note": preset["note"] as? String ?? "", "files": ["preset": "preset.json"],
        ]
        zip.add("manifest.json", (try? JSONSerialization.data(withJSONObject: manifest)) ?? Data())
        zip.add("preset.json", (try? JSONSerialization.data(withJSONObject: preset)) ?? Data())
        return zip.finish()
    }

    /// La posa ridotta per il viaggio, già raddrizzata, con i dati EXIF che servono allo sviluppo.
    private static func shrink(_ url: URL) -> Data? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let img = Developer.thumbnail(url, maxPixel: maxSide) else { return nil }
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]
        var exif: [CFString: Any] = [:]
        if let e = props?[kCGImagePropertyExifDictionary] as? [CFString: Any] {
            for k in [kCGImagePropertyExifExposureTime, kCGImagePropertyExifFNumber, kCGImagePropertyExifISOSpeedRatings] { exif[k] = e[k] }
        }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, img, [
            kCGImageDestinationLossyCompressionQuality: 0.9,
            kCGImagePropertyExifDictionary: exif,
            kCGImagePropertyOrientation: 1,
        ] as CFDictionary)
        return CGImageDestinationFinalize(dest) ? out as Data : nil
    }
}
