import Foundation
import MetricKit
import UIKit

/// Crash e segnalazioni, senza servizi esterni: vanno nel nostro Supabase.
/// - I crash li raccoglie iOS (MetricKit) e ce li consegna al prossimo avvio; le eccezioni Objective-C
///   le scriviamo noi su file nel momento in cui succedono.
/// - Gli errori gravi ma non fatali (es. uno sviluppo fallito) partono subito.
/// - "Segnala un problema": testo, contatto facoltativo e uno screenshot dalla libreria.
/// Nessun dato personale: un codice casuale del dispositivo, modello, versione di iOS e dell'app.
final class Reporter: NSObject, MXMetricManagerSubscriber {
    static let shared = Reporter()
    weak var account: Account?

    private let dir: URL = {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("crashes", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    /// Codice casuale del dispositivo: serve solo a non farsi inondare da un telefono impazzito.
    let deviceId: String = {
        if let id = UserDefaults.standard.string(forKey: "deviceId") { return id }
        let id = UUID().uuidString
        UserDefaults.standard.set(id, forKey: "deviceId")
        return id
    }()

    func install() {
        MXMetricManager.shared.add(self)
        NSSetUncaughtExceptionHandler { e in
            let j: [String: Any] = ["kind": "crash", "message": "\(e.name.rawValue): \(e.reason ?? "")",
                                    "stack": e.callStackSymbols.joined(separator: "\n"), "version": Reporter.appVersion]
            if let d = try? JSONSerialization.data(withJSONObject: j) {
                try? d.write(to: Reporter.shared.dir.appendingPathComponent("crash-\(Int(Date().timeIntervalSince1970 * 1000)).json"))
            }
        }
        Task { await flush() }
    }

    /// iOS consegna i rapporti dei crash (anche quelli Swift) al prossimo avvio.
    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for p in payloads {
            for c in p.crashDiagnostics ?? [] {
                let meta = c.metaData
                let msg = "\(c.exceptionType.map { "exception \($0)" } ?? "") \(c.signal.map { "signal \($0)" } ?? "") \(c.terminationReason ?? "")"
                    .trimmingCharacters(in: .whitespaces)
                let stack = String(data: c.callStackTree.jsonRepresentation(), encoding: .utf8) ?? ""
                Task { _ = try? await send(kind: "crash", message: msg.isEmpty ? "crash" : msg, stack: stack, version: meta.applicationBuildVersion) }
            }
            for h in p.hangDiagnostics ?? [] {
                let stack = String(data: h.callStackTree.jsonRepresentation(), encoding: .utf8) ?? ""
                Task { _ = try? await send(kind: "hang", message: "hang \(h.hangDuration)", stack: stack, version: h.metaData.applicationBuildVersion) }
            }
        }
    }

    /// Spedisce i crash rimasti in coda; senza rete si riprova al prossimo avvio.
    func flush() async {
        let files = ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []).sorted { $0.lastPathComponent < $1.lastPathComponent }
        for f in files {
            guard let d = try? Data(contentsOf: f), let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else {
                try? FileManager.default.removeItem(at: f); continue
            }
            let code = (try? await send(kind: j["kind"] as? String ?? "crash", message: j["message"] as? String ?? "",
                                        stack: j["stack"] as? String ?? "", version: j["version"] as? String ?? Reporter.appVersion)) ?? -1
            if (200..<500).contains(code) { try? FileManager.default.removeItem(at: f) } else { break }
        }
    }

    /// Errore non fatale: parte subito, senza disturbare se non c'è rete.
    func error(_ where_: String, _ message: String) {
        let stack = Thread.callStackSymbols.joined(separator: "\n")
        Task { _ = try? await send(kind: "error", message: "\(where_) · \(message)", stack: stack, version: Reporter.appVersion) }
    }

    /// Segnalazione scritta dall'utente: nil se è partita, altrimenti "too_many" o "network".
    func feedback(_ message: String, contact: String?, image: UIImage?) async -> String? {
        var path: String?
        if let image, let jpeg = Reporter.shrink(image) {
            let p = "\(deviceId)/\(UUID().uuidString).jpg"
            if let (c, _) = try? await post("/storage/v1/object/feedback/\(p)", jpeg, type: "image/jpeg"), (200..<300).contains(c) { path = p }
        }
        var body = base()
        body["message"] = message.trimmingCharacters(in: .whitespacesAndNewlines)
        let ct = contact?.trimmingCharacters(in: .whitespaces) ?? ""
        body["contact"] = ct.isEmpty ? NSNull() : ct
        body["screenshot"] = path ?? NSNull()
        guard let data = try? JSONSerialization.data(withJSONObject: body),
              let (c, t) = try? await post("/rest/v1/rpc/send_feedback", data, type: "application/json") else { return "network" }
        if (200..<300).contains(c) { return nil }
        return t.contains("too_many") ? "too_many" : "network"
    }

    /* ---------------- dettagli ---------------- */

    static var appVersion: String {
        let i = Bundle.main.infoDictionary
        return "\(i?["CFBundleShortVersionString"] as? String ?? "?") (\(i?["CFBundleVersion"] as? String ?? "?"))"
    }

    private func base() -> [String: Any] {
        var sys = utsname(); uname(&sys)
        let model = withUnsafePointer(to: &sys.machine) { $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) } }
        return ["device": deviceId, "platform": "ios", "app_version": Reporter.appVersion,
                "os_version": "iOS \(ProcessInfo.processInfo.operatingSystemVersionString)", "model": model]
    }

    @discardableResult
    private func send(kind: String, message: String, stack: String, version: String) async throws -> Int {
        var body = base()
        body["kind"] = kind; body["message"] = message; body["stack"] = String(stack.prefix(30000)); body["app_version"] = version
        let data = try JSONSerialization.data(withJSONObject: body)
        return try await post("/rest/v1/rpc/report_crash", data, type: "application/json").0
    }

    private func post(_ path: String, _ body: Data, type: String) async throws -> (Int, String) {
        guard let url = URL(string: Store.catalogURL + path) else { throw URLError(.badURL) }
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = "POST"
        req.setValue(Store.catalogKey, forHTTPHeaderField: "apikey")
        if let token = await account?.accessToken() { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        req.setValue(type, forHTTPHeaderField: "Content-Type")
        let (d, r) = try await URLSession.shared.upload(for: req, from: body)
        return ((r as? HTTPURLResponse)?.statusCode ?? -1, String(data: d, encoding: .utf8) ?? "")
    }

    /// Screenshot rimpicciolito a 1600 px, in JPEG: basta per capire il problema e pesa poco.
    private static func shrink(_ img: UIImage) -> Data? {
        let s = min(1, 1600 / max(img.size.width, img.size.height))
        let size = CGSize(width: img.size.width * s, height: img.size.height * s)
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        return UIGraphicsImageRenderer(size: size, format: f).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
            .jpegData(compressionQuality: 0.85)
    }
}
