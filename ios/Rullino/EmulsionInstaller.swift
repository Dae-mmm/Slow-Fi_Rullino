import CryptoKit
import Foundation

enum EmulsionError: Error {
    case notEmulsion, noManifest, badManifest, newer, missing(String), damaged(String)
    case noPreset, badPreset, noId, noSpace, badPaths, tooBig

    var message: String {
        switch self {
        case .notEmulsion: L("inst_not_emulsion")
        case .noManifest: L("inst_no_manifest")
        case .badManifest: L("inst_bad_manifest")
        case .newer: L("inst_newer")
        case .missing(let n): L("inst_missing", n)
        case .damaged(let n): L("inst_damaged", n)
        case .noPreset: L("inst_no_preset")
        case .badPreset: L("inst_bad_preset")
        case .noId: L("inst_no_id")
        case .noSpace: L("inst_no_space")
        case .badPaths: L("inst_bad_paths")
        case .tooBig: L("inst_too_big")
        }
    }
}

/// Installa un file .emulsion (ZIP con mimetype, manifest.json, preset.json, immagini, lut).
/// Sul telefono resta in emulsions/<id>/: emulsion.json (quello che usa l'app) più le immagini.
enum EmulsionInstaller {
    static let mimetype = "application/x-emulsion"
    static let formatVersion = 1

    static func install(_ data: Data, into root: URL) throws -> Emulsion {
        let entries: [String: Data]
        do { entries = try ZipReader.read(data) }
        catch let e as EmulsionError { throw e }
        catch { throw EmulsionError.notEmulsion }

        guard let mt = entries["mimetype"], String(decoding: mt, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines) == mimetype
        else { throw EmulsionError.notEmulsion }
        guard let mBytes = entries["manifest.json"] else { throw EmulsionError.noManifest }
        guard let manifest = (try? JSONSerialization.jsonObject(with: mBytes)) as? [String: Any] else { throw EmulsionError.badManifest }
        guard manifest["format"] as? String == "emulsion" else { throw EmulsionError.notEmulsion }
        if let v = manifest["formatVersion"] as? Int, v > formatVersion { throw EmulsionError.newer }

        // Integrità: ogni file elencato in sha256 deve esserci e combaciare.
        for (name, value) in (manifest["sha256"] as? [String: Any]) ?? [:] {
            guard let expected = value as? String else { continue }
            guard let bytes = entries[name] else { throw EmulsionError.missing(name) }
            let hex = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
            guard hex.caseInsensitiveCompare(expected) == .orderedSame else { throw EmulsionError.damaged(name) }
        }

        let files = (manifest["files"] as? [String: Any]) ?? [:]
        let presetName = (files["preset"] as? String) ?? "preset.json"
        guard let pBytes = entries[presetName] else { throw EmulsionError.noPreset }
        guard let preset = (try? JSONSerialization.jsonObject(with: pBytes)) as? [String: Any] else { throw EmulsionError.badPreset }
        let params = (preset["params"] as? [String: Any]) ?? [:]

        let rawId = ((manifest["id"] as? String).flatMap { $0.isEmpty ? nil : $0 }) ?? (preset["id"] as? String) ?? ""
        let id = String(rawId.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) || "-_.".unicodeScalars.contains($0) })
        guard !id.isEmpty, id != ".", id != ".." else { throw EmulsionError.noId }

        var name = (manifest["name"] as? String) ?? ""
        if name.isEmpty { name = (preset["name"] as? String) ?? "" }
        if name.isEmpty { name = L("inst_untitled") }
        let mono = ((params["mono"] as? [String: Any])?["enabled"] as? Bool) ?? false
        let combined: [String: Any] = [
            "id": id,
            "name": name,
            "iso": (manifest["iso"] as? Int) ?? (preset["iso"] as? Int) ?? 400,
            "exposures": (manifest["exposures"] as? Int) ?? (preset["exposures"] as? Int) ?? 36,
            "type": (mono || manifest["type"] as? String == "bw") ? "bw" : "color",
            "note": (manifest["note"] as? String) ?? (preset["note"] as? String) ?? "",
            "params": params,
        ]
        let json = try JSONSerialization.data(withJSONObject: combined)
        let emulsion = try JSONDecoder().decode(Emulsion.self, from: json)

        // Scrive in una cartella temporanea e poi la sostituisce a quella vecchia.
        let fm = FileManager.default
        let base = root.appendingPathComponent("emulsions", isDirectory: true)
        let tmp = base.appendingPathComponent(".tmp-\(UUID().uuidString)", isDirectory: true)
        let dir = base.appendingPathComponent(id, isDirectory: true)
        do {
            try fm.createDirectory(at: tmp, withIntermediateDirectories: true)
            try json.write(to: tmp.appendingPathComponent("emulsion.json"))
            for key in ["box", "canister"] {
                if let f = files[key] as? String, let d = entries[f] {
                    try d.write(to: tmp.appendingPathComponent(key + "." + (f as NSString).pathExtension.lowercased()))
                }
            }
            if fm.fileExists(atPath: dir.path) { try fm.removeItem(at: dir) }
            try fm.moveItem(at: tmp, to: dir)
        } catch {
            try? fm.removeItem(at: tmp)
            throw EmulsionError.noSpace
        }
        return emulsion
    }

    /// Le emulsioni importate in precedenza.
    static func installed(in root: URL) -> [Emulsion] {
        let base = root.appendingPathComponent("emulsions", isDirectory: true)
        let dirs = (try? FileManager.default.contentsOfDirectory(at: base, includingPropertiesForKeys: nil)) ?? []
        return dirs.compactMap { d in
            guard !d.lastPathComponent.hasPrefix("."),
                  let data = try? Data(contentsOf: d.appendingPathComponent("emulsion.json")) else { return nil }
            return try? JSONDecoder().decode(Emulsion.self, from: data)
        }
        .sorted { $0.name < $1.name }
    }
}
