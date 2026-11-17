import Foundation
import Photos
import SwiftUI

/// Stato dell'app: rullini, pose, negativi, magazzino, gettoni e impostazioni, salvati in un JSON.
@MainActor
final class Store: ObservableObject {
    static let rollPrice = 10

    @Published private(set) var emulsions: [Emulsion] = []
    @Published var data = AppData() { didSet { if data != oldValue { save() } } }
    @Published var toast: String?
    @Published var developProgress: (done: Int, total: Int)?

    let root: URL
    private var stateFile: URL { root.appendingPathComponent("state.json") }

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = base.appendingPathComponent("Rullino", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        if let url = Bundle.main.url(forResource: "emulsions", withExtension: "json"),
           let d = try? Data(contentsOf: url),
           let list = try? JSONDecoder().decode([Emulsion].self, from: d) {
            emulsions = list
        }
        mergeInstalled()
        if let d = try? Data(contentsOf: stateFile), let s = try? JSONDecoder().decode(AppData.self, from: d) {
            data = s
        }
        // Rullini rimasti "in vasca" se l'app è stata chiusa durante lo sviluppo.
        let stuck = data.rolls.filter { $0.state == .developing }.map(\.id)
        if !stuck.isEmpty { Task { for id in stuck { await develop(id) } } }
    }

    private func save() {
        guard let d = try? JSONEncoder().encode(data) else { return }
        try? d.write(to: stateFile, options: .atomic)
    }

    func say(_ key: String, _ args: Any...) {
        let f = NSLocalizedString(key, comment: "")
        toast = String(format: f, locale: Locale.current, arguments: args.map { "\($0)" as NSString })
    }

    func emulsion(_ id: String) -> Emulsion? { emulsions.first { $0.id == id } }

    private func mergeInstalled() {
        for e in EmulsionInstaller.installed(in: root) {
            if let i = emulsions.firstIndex(where: { $0.id == e.id }) { emulsions[i] = e } else { emulsions.append(e) }
        }
    }

    /// Importa un file .emulsion (dal selettore di File o da "Apri con Rullino").
    func importEmulsion(_ url: URL) {
        guard data.devMode else { say("import_needs_dev"); return }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let bytes = try? Data(contentsOf: url) else { say("open_file_failed"); return }
        if let e = install(bytes) { say("emulsion_added", e.name) }
    }

    @discardableResult
    private func install(_ bytes: Data) -> Emulsion? {
        do {
            let e = try EmulsionInstaller.install(bytes, into: root)
            if let i = emulsions.firstIndex(where: { $0.id == e.id }) { emulsions[i] = e } else { emulsions.append(e) }
            return e
        } catch let err as EmulsionError {
            toast = err.message
        } catch {
            say("invalid_file")
        }
        return nil
    }

    /* ---------------- Catalogo online ---------------- */

    static let catalogURL = "https://voxkoarjawgabrljvvon.supabase.co"
    /// Chiave pubblicabile: fatta per stare nelle app, le regole del database danno solo lettura.
    static let catalogKey = "sb_publishable_sl-yaeRs7zND4Ldw5Fbo7w_pcMrdqbH"

    @Published var catalog: [CatalogItem]?
    @Published var catalogFailed = false
    @Published var downloading: Set<String> = []

    func loadCatalog() async {
        catalogFailed = false
        guard var req = URL(string: Store.catalogURL + "/rest/v1/emulsions?select=*&published=eq.true&order=sort.asc,name.asc")
            .map({ URLRequest(url: $0, timeoutInterval: 15) }) else { return }
        req.setValue(Store.catalogKey, forHTTPHeaderField: "apikey")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        do {
            let (d, resp) = try await URLSession.shared.data(for: req)
            guard (resp as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? false else { throw URLError(.badServerResponse) }
            catalog = try JSONDecoder().decode([CatalogItem].self, from: d)
        } catch {
            catalogFailed = true
        }
    }

    func isInstalled(_ item: CatalogItem) -> Bool { emulsion(item.id) != nil }
    func needsUpdate(_ item: CatalogItem) -> Bool { isInstalled(item) && (data.catalogVersions[item.id] ?? 1) < item.version }

    func download(_ item: CatalogItem) async {
        guard !downloading.contains(item.id), let url = URL(string: item.fileUrl) else { return }
        let update = isInstalled(item)
        downloading.insert(item.id)
        defer { downloading.remove(item.id) }
        do {
            let (d, resp) = try await URLSession.shared.data(from: url)
            guard (resp as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? false else { throw URLError(.badServerResponse) }
            if let e = install(d) {
                data.catalogVersions[item.id] = item.version
                say(update ? "updated" : "downloaded", e.name)
            }
        } catch {
            say("download_failed")
        }
    }

    /* ---------------- Rullini ---------------- */

    var activeRoll: Roll? { data.rolls.first { $0.state == .loaded } }

    func rollDir(_ id: Int) -> URL {
        let u = root.appendingPathComponent("rolls/\(id)", isDirectory: true)
        try? FileManager.default.createDirectory(at: u.appendingPathComponent("raw"), withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: u.appendingPathComponent("dev"), withIntermediateDirectories: true)
        return u
    }

    private func update(_ id: Int, _ f: (inout Roll) -> Void) {
        guard let i = data.rolls.firstIndex(where: { $0.id == id }) else { return }
        var r = data.rolls[i]
        f(&r)
        data.rolls[i] = r
    }

    func roll(_ id: Int) -> Roll? { data.rolls.first { $0.id == id } }

    func owned(_ e: Emulsion) -> Int { data.inventory[e.id] ?? 0 }

    func loadRoll(_ e: Emulsion, exposures: Int) {
        guard activeRoll == nil else { say("err_roll_in_camera"); return }
        guard owned(e) > 0 else { say("err_no_rolls_of", e.name); return }
        data.inventory[e.id] = owned(e) - 1
        let r = Roll(id: data.nextId, emulsionId: e.id, emulsionName: e.name, iso: e.iso, exposures: exposures, isBw: e.isBw)
        data.nextId += 1
        data.rolls.append(r)
    }

    /// Rullino caricato e mai scattato: torna in magazzino.
    func unloadEmptyRoll(_ r: Roll) {
        data.rolls.removeAll { $0.id == r.id }
        data.inventory[r.emulsionId] = (data.inventory[r.emulsionId] ?? 0) + 1
    }

    func shelve(_ r: Roll) {
        update(r.id) { $0.state = .shelved; $0.wound = false }
        say("shelved_msg")
    }

    func reload(_ r: Roll) {
        guard activeRoll == nil else { say("err_roll_in_camera"); return }
        update(r.id) { $0.state = .loaded; $0.wound = false }
    }

    func wind(_ r: Roll) {
        update(r.id) { if $0.canWind { $0.wound = true } }
    }

    func rawFile(for r: Roll) -> (URL, String) {
        let name = String(format: "rolls/%d/raw/%02d_%d.jpg", r.id, r.cursor, Int(Date().timeIntervalSince1970 * 1000))
        _ = rollDir(r.id)
        return (root.appendingPathComponent(name), name)
    }

    func recordShot(_ rollId: Int, file: String) {
        guard let r = roll(rollId), r.state == .loaded, r.wound else { return }
        data.exposures.append(Exposure(rollId: rollId, frame: r.cursor, file: file))
        update(rollId) {
            $0.shotCount = max($0.shotCount, $0.cursor)
            $0.cursor += 1
            $0.wound = false
        }
    }

    func exposuresOnFrame(_ r: Roll) -> Int {
        data.exposures.filter { $0.rollId == r.id && $0.frame == r.cursor }.count
    }

    /// Indietro di una posa: si è già sulla posa, pronta per scattarci sopra (doppia esposizione).
    func rewindFrame(_ r: Roll) {
        update(r.id) { $0.cursor = max(1, $0.cursor - 1); $0.wound = true }
    }

    /// Riavvolge tutto: il rullino esce dalla macchina e aspetta il laboratorio.
    func rewindOut(_ r: Roll) {
        update(r.id) { $0.state = .finished; $0.wound = false }
        say("rewound_out")
    }

    func deleteRoll(_ r: Roll) {
        try? FileManager.default.removeItem(at: root.appendingPathComponent("rolls/\(r.id)"))
        data.exposures.removeAll { $0.rollId == r.id }
        data.frames.removeAll { $0.rollId == r.id }
        data.rolls.removeAll { $0.id == r.id }
        say("roll_deleted")
    }

    /* ---------------- Laboratorio ---------------- */

    var waitSeconds: TimeInterval { data.skipLab ? 0 : data.labTime.seconds }

    func isReady(_ r: Roll, now: Date = Date()) -> Bool {
        r.state == .developed && (data.skipLab || (r.readyAt ?? .distantPast) <= now)
    }

    var toDevelop: [Roll] { data.rolls.filter { $0.state == .finished && $0.shotCount > 0 } }

    /// Consegna in un colpo tutti i rullini riavvolti.
    func deliverAll() {
        let ids = toDevelop.map(\.id)
        guard !ids.isEmpty else { say("sk_nothing"); return }
        for id in ids { update(id) { $0.state = .developing } }
        if ids.count == 1 { say("sk_look") } else { say("sk_many", ids.count) }
        Task { for id in ids { await develop(id) } }
    }

    private func develop(_ id: Int) async {
        guard let r = roll(id) else { return }
        guard let e = emulsion(r.emulsionId) else { say("dev_emulsion_gone", r.emulsionName); return }
        let groups = Dictionary(grouping: data.exposures.filter { $0.rollId == id }, by: \.frame)
        let frames = groups.keys.sorted()
        let dir = rollDir(id)
        let root = self.root
        for (i, f) in frames.enumerated() {
            developProgress = (i, frames.count)
            if data.frames.contains(where: { $0.rollId == id && $0.frame == f }) { continue }
            let files = groups[f]!.map { root.appendingPathComponent($0.file) }
            let rel = String(format: "rolls/%d/dev/%02d.jpg", id, f)
            let out = dir.appendingPathComponent(String(format: "dev/%02d.jpg", f))
            let params = e.params, iso = e.iso
            let ok = await Task.detached(priority: .userInitiated) {
                Developer.develop(files: files, params: params, iso: iso, out: out, seed: Float.random(in: 0..<997))
            }.value
            if ok { data.frames.append(DevFrame(rollId: id, frame: f, file: rel, layers: files.count)) }
        }
        developProgress = nil
        let wait = waitSeconds
        update(id) { $0.state = .developed; $0.readyAt = Date().addingTimeInterval(wait) }
    }

    func frames(of r: Roll) -> [DevFrame] {
        data.frames.filter { $0.rollId == r.id }.sorted { $0.frame < $1.frame }
    }

    func url(_ f: DevFrame) -> URL { root.appendingPathComponent(f.file) }

    /// Stampa: copia i positivi nella libreria Foto.
    func printFrames(_ frames: [DevFrame]) {
        let urls = frames.map { url($0) }
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                Task { @MainActor in self.say("err_gallery_save") }
                return
            }
            PHPhotoLibrary.shared().performChanges({
                for u in urls { PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: u) }
            }) { ok, _ in
                Task { @MainActor in
                    if ok {
                        for f in frames {
                            if let i = self.data.frames.firstIndex(of: f) { self.data.frames[i].printed = true }
                        }
                        if frames.count == 1 { self.say("print_one") } else { self.say("print_many", frames.count) }
                    } else { self.say("print_failed") }
                }
            }
        }
    }

    /* ---------------- Negozio ---------------- */

    private var today: String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; return f.string(from: Date())
    }
    var dailyAvailable: Bool { data.lastDaily != today }

    func takeFree(_ e: Emulsion) {
        guard dailyAvailable else { say("sk_daily_taken"); return }
        data.lastDaily = today
        data.inventory[e.id] = owned(e) + 1
        say("sk_daily_given", e.name)
    }

    func buy(_ e: Emulsion) {
        guard data.coins >= Store.rollPrice else { say("sk_no_coins"); return }
        data.coins -= Store.rollPrice
        data.inventory[e.id] = owned(e) + 1
        say("sk_bought", e.name)
    }
}

/// "tra 3 h 20 min" ecc., con le stesse stringhe di Android.
func formatWait(_ seconds: TimeInterval) -> String {
    let m = Int((seconds / 60).rounded(.up))
    if m >= 48 * 60 { return L("wait_days", m / (24 * 60)) }
    if m >= 24 * 60 { return L("wait_day1") }
    if m >= 60 {
        let h = m / 60, mm = m % 60
        if mm == 0 { return h == 1 ? L("wait_h1") : L("wait_h", h) }
        return L("wait_hm", h, mm)
    }
    return L("wait_m", max(1, m))
}
