import Foundation
import UIKit

/// Un concorso (tabella `contests` su Supabase).
struct Contest: Identifiable, Equatable {
    enum Phase { case draft, upcoming, open, judging, closed }

    var id: String
    var title: String
    var theme: String
    var description: String
    var startsAt: Date
    var endsAt: Date
    var requiredEmulsion: String?
    var giftRoll: Bool
    var giftExposures: Int
    var prizeCoins: [Int]
    var prizeEmulsion: String?
    var prizeEmulsionUrl: String?
    var status: String
    var boardUntil: Date?

    func phase(_ now: Date = Date()) -> Phase {
        if status == "draft" { return .draft }
        if status == "closed" { return .closed }
        if now < startsAt { return .upcoming }
        if now <= endsAt { return .open }
        return .judging
    }

    /// Stesse regole che il server ricontrolla all'invio.
    func accepts(_ r: Roll) -> Bool {
        if giftRoll { return r.contestId == id && r.contestRef != nil }
        if let req = requiredEmulsion, r.emulsionId != req { return false }
        return r.createdAt >= startsAt
    }

    init(_ j: [String: Any]) {
        id = j["id"] as? String ?? ""
        title = j["title"] as? String ?? ""
        theme = j["theme"] as? String ?? ""
        description = j["description"] as? String ?? ""
        startsAt = (j["starts_at"] as? String).flatMap(ISO8601DateFormatter.flexible) ?? .distantPast
        endsAt = (j["ends_at"] as? String).flatMap(ISO8601DateFormatter.flexible) ?? .distantPast
        requiredEmulsion = j["required_emulsion"] as? String
        giftRoll = j["gift_roll"] as? Bool ?? false
        giftExposures = j["gift_exposures"] as? Int ?? 24
        prizeCoins = j["prize_coins"] as? [Int] ?? []
        prizeEmulsion = j["prize_emulsion"] as? String
        prizeEmulsionUrl = j["prize_emulsion_url"] as? String
        status = j["status"] as? String ?? ""
        boardUntil = (j["board_until"] as? String).flatMap(ISO8601DateFormatter.flexible)
    }
}

struct ContestEntry: Identifiable, Equatable {
    var id: String
    var contestId: String
    var frame: Int
    var hasPeople: Bool
    var photoPath: String
    var place: Int?
    var avgScore: Double?
    var author: String?

    init(_ j: [String: Any]) {
        id = j["id"] as? String ?? ""
        contestId = j["contest_id"] as? String ?? ""
        frame = j["frame"] as? Int ?? 0
        hasPeople = j["has_people"] as? Bool ?? false
        photoPath = j["photo_path"] as? String ?? ""
        place = j["place"] as? Int
        avgScore = (j["avg_score"] as? NSNumber)?.doubleValue ?? (j["avg_score"] as? String).flatMap(Double.init)
        let p = j["profiles"] as? [String: Any]
        author = (p?["display_name"] as? String) ?? (p?["username"] as? String)
    }
}

/// Quello che un OP compila nel modulo per creare o modificare un concorso.
struct ContestDraft {
    var title = "", theme = "", description = ""
    var startsAt = Date()
    var endsAt = Date().addingTimeInterval(7 * 86_400)
    var requiredEmulsion: String?
    var giftRoll = false
    var giftExposures = 24
    var prizeCoins = [50, 30, 20]
    var prizeEmulsion: String?
    var prizeEmulsionUrl: String?

    init() {}
    init(_ c: Contest) {
        title = c.title; theme = c.theme; description = c.description; startsAt = c.startsAt; endsAt = c.endsAt
        requiredEmulsion = c.requiredEmulsion; giftRoll = c.giftRoll; giftExposures = c.giftExposures
        prizeCoins = (c.prizeCoins + [0, 0, 0]).prefix(3).map { $0 }
        prizeEmulsion = c.prizeEmulsion; prizeEmulsionUrl = c.prizeEmulsionUrl
    }

    func json(_ status: String?) -> [String: Any] {
        let f = ISO8601DateFormatter()
        var j: [String: Any] = [
            "title": title.trimmingCharacters(in: .whitespacesAndNewlines),
            "theme": theme.trimmingCharacters(in: .whitespacesAndNewlines),
            "description": description.trimmingCharacters(in: .whitespacesAndNewlines),
            "starts_at": f.string(from: startsAt), "ends_at": f.string(from: endsAt),
            "required_emulsion": requiredEmulsion.map { $0 as Any } ?? NSNull(),
            "gift_roll": giftRoll, "gift_exposures": giftExposures, "prize_coins": prizeCoins,
            "prize_emulsion": prizeEmulsion.flatMap { $0.isEmpty ? nil : $0 as Any } ?? NSNull(),
            "prize_emulsion_url": prizeEmulsionUrl.map { $0 as Any } ?? NSNull(),
        ]
        if let status { j["status"] = status }
        return j
    }
}

struct BoardItem: Identifiable { var contest: Contest; var entry: ContestEntry; var id: String { entry.id } }

struct ContestScore: Equatable { var entryId: String; var score: Int; var note: String? }

enum ContestError: Error { case network, signedOut, notAllowed, already, other, message(String) }

/// Concorsi: elenco, rullino in regalo, invio, giuria, chiusura, bacheca e premi.
@MainActor
final class ContestsModel: ObservableObject {
    @Published var contests: [Contest] = []
    @Published var mine: [String: ContestEntry] = [:]
    @Published var gifts: [String: String] = [:]
    @Published var board: [BoardItem] = []
    @Published var loading = false
    @Published var busy = false
    @Published var error: String?

    @Published var judging: Contest?
    @Published var judgingEntries: [ContestEntry] = []
    @Published var myVotes: [String: ContestScore] = [:]
    @Published var results: [String: [ContestScore]] = [:]

    private let base = Store.catalogURL
    private let key = Store.catalogKey
    weak var account: Account?
    weak var store: Store?

    private func token() async throws -> String {
        guard let t = await account?.accessToken() else { throw ContestError.signedOut }
        return t
    }
    private func uid() throws -> String {
        guard let u = account?.userId else { throw ContestError.signedOut }
        return u
    }

    func refresh() async {
        guard account?.signedIn == true, !loading else { return }
        loading = true
        defer { loading = false }
        do {
            let t = try await token(), me = try uid()
            contests = try await list("/rest/v1/contests?select=*&order=ends_at.desc", t).map(Contest.init)
            mine = Dictionary(try await list("/rest/v1/contest_entries?user_id=eq.\(me)&select=*", t).map(ContestEntry.init).map { ($0.contestId, $0) }, uniquingKeysWith: { a, _ in a })
            gifts = Dictionary(try await list("/rest/v1/contest_gifts?user_id=eq.\(me)&select=contest_id,roll_ref", t).compactMap { j in
                (j["contest_id"] as? String).flatMap { c in (j["roll_ref"] as? String).map { (c, $0) } }
            }, uniquingKeysWith: { a, _ in a })
            let now = Date()
            board = try await list("/rest/v1/contest_entries?place=not.is.null&select=*,profiles(username,display_name),contests(*)&order=place.asc", t)
                .compactMap { j -> BoardItem? in
                    guard let cj = j["contests"] as? [String: Any] else { return nil }
                    let c = Contest(cj)
                    return (c.boardUntil ?? .distantPast) > now ? BoardItem(contest: c, entry: ContestEntry(j)) : nil
                }
                .sorted { $0.contest.endsAt != $1.contest.endsAt ? $0.contest.endsAt > $1.contest.endsAt : ($0.entry.place ?? 9) < ($1.entry.place ?? 9) }
            error = nil
            await claimRewards(t)
        } catch {
            self.error = explain(error)
        }
    }

    /* ---------------- Rullino in regalo ---------------- */

    func hasGiftRoll(_ c: Contest) -> Bool {
        guard let ref = gifts[c.id] else { return false }
        return store?.data.rolls.contains { $0.contestRef == ref } ?? false
    }

    func takeGiftRoll(_ c: Contest) async -> Bool {
        guard let store else { return false }
        return await act {
            guard let eid = c.requiredEmulsion else { throw ContestError.message(L("ct_gift_no_emulsion")) }
            var e = store.emulsion(eid)
            if e == nil {
                if store.catalog == nil { await store.loadCatalog() }
                guard let item = store.catalog?.first(where: { $0.id == eid }) else { throw ContestError.message(L("ct_emulsion_missing")) }
                await store.download(item)
                e = store.emulsion(eid)
            }
            guard let e else { throw ContestError.message(L("ct_emulsion_missing")) }
            let t = try await token(), me = try uid()
            let ref: String
            if let r = gifts[c.id] { ref = r } else {
                ref = UUID().uuidString.lowercased()
                try await send("POST", "/rest/v1/contest_gifts", json: ["contest_id": c.id, "user_id": me, "roll_ref": ref], t)
                gifts[c.id] = ref
            }
            if let local = store.data.rolls.first(where: { $0.contestRef == ref }) {
                if local.state == .shelved {
                    if let a = store.activeRoll, a.shotCount == 0 { store.unloadEmptyRoll(a) }
                    store.reload(local)
                }
            } else if !store.loadGiftRoll(e, exposures: c.giftExposures, contestId: c.id, ref: ref) {
                throw ContestError.other
            }
        }
    }

    /* ---------------- Partecipare ---------------- */

    func eligible(_ r: Roll) -> [Contest] {
        contests.filter { $0.phase() == .open && $0.accepts(r) && mine[$0.id] == nil }
    }

    /// Su iOS le liberatorie non ci sono ancora: si mandano solo foto senza persone.
    func submit(_ c: Contest, roll: Roll, frame: DevFrame, url: URL) async -> Bool {
        await act {
            let t = try await token(), me = try uid()
            guard let cg = Developer.thumbnail(url, maxPixel: 2048), let jpg = UIImage(cgImage: cg).jpegData(compressionQuality: 0.88)
            else { throw ContestError.message(L("ct_photo_missing")) }
            let path = "\(c.id)/\(me)/photo.jpg"
            try await send("POST", "/storage/v1/object/contest-entries/\(path)", body: jpg, type: "image/jpeg", t, upsert: true)
            try await send("POST", "/rest/v1/contest_entries", json: [
                "contest_id": c.id, "user_id": me, "roll_ref": roll.contestRef ?? "local-\(roll.id)",
                "frame": frame.frame, "emulsion_id": roll.emulsionId,
                "loaded_at": ISO8601DateFormatter().string(from: roll.createdAt),
                "has_people": false, "photo_path": path,
            ], t)
            if let e = try await list("/rest/v1/contest_entries?user_id=eq.\(me)&contest_id=eq.\(c.id)&select=*", t).first {
                mine[c.id] = ContestEntry(e)
            }
            store?.say("ct_sent", c.title)
        }
    }

    func withdraw(_ e: ContestEntry) async {
        _ = await act {
            let t = try await token()
            try await send("DELETE", "/rest/v1/contest_entries?id=eq.\(e.id)", t)
            try? await send("DELETE", "/storage/v1/object/contest-entries/\(e.photoPath)", t)
            mine[e.contestId] = nil
            store?.say("ct_withdrawn")
        }
    }

    /* ---------------- Giuria ---------------- */

    func openJudging(_ c: Contest) async {
        _ = await act {
            let t = try await token(), me = try uid()
            judging = c
            judgingEntries = try await list("/rest/v1/contest_entries?contest_id=eq.\(c.id)&select=*&order=created_at.asc", t).map(ContestEntry.init)
            let s = try await list("/rest/v1/contest_scores?judge_id=eq.\(me)&select=entry_id,score,note,contest_entries!inner(contest_id)&contest_entries.contest_id=eq.\(c.id)", t)
            myVotes = Dictionary(s.map { j in
                let id = j["entry_id"] as? String ?? ""
                return (id, ContestScore(entryId: id, score: j["score"] as? Int ?? 0, note: j["note"] as? String))
            }, uniquingKeysWith: { a, _ in a })
        }
    }

    func closeJudging() { judging = nil; judgingEntries = []; myVotes = [:] }

    func vote(_ e: ContestEntry, score: Int, note: String) async {
        _ = await act {
            let t = try await token(), me = try uid()
            let n = String(note.trimmingCharacters(in: .whitespacesAndNewlines).prefix(500))
            try await send("POST", "/rest/v1/contest_scores?on_conflict=entry_id,judge_id", json: [
                "entry_id": e.id, "judge_id": me, "score": max(1, min(10, score)), "note": n.isEmpty ? NSNull() : n,
            ], t, prefer: "resolution=merge-duplicates")
            myVotes[e.id] = ContestScore(entryId: e.id, score: score, note: n.isEmpty ? nil : n)
        }
    }

    /// Solo OP: chiude, calcola la classifica, assegna i premi e accende la bacheca.
    func close(_ c: Contest) async {
        let ok = await act {
            let t = try await token()
            try await send("POST", "/rest/v1/rpc/close_contest", json: ["c": c.id], t)
            store?.say("ct_closed_msg", c.title)
        }
        if ok { closeJudging(); await refresh() }
    }

    func loadResults(_ e: ContestEntry) async {
        _ = await act {
            let t = try await token()
            results[e.id] = try await list("/rest/v1/contest_scores?entry_id=eq.\(e.id)&select=entry_id,score,note", t).map {
                ContestScore(entryId: e.id, score: $0["score"] as? Int ?? 0, note: $0["note"] as? String)
            }
        }
    }

    /* ---------------- Gestione concorsi (OP) ---------------- */

    /// Crea o aggiorna. status: "draft", "open", oppure nil per lasciarlo com'è.
    func save(_ existing: Contest?, _ d: ContestDraft, status: String?) async -> Bool {
        let ok = await act {
            if d.title.trimmingCharacters(in: .whitespaces).isEmpty { throw ContestError.message(L("cm_err_title")) }
            if d.endsAt <= d.startsAt { throw ContestError.message(L("cm_err_dates")) }
            if d.giftRoll && d.requiredEmulsion == nil { throw ContestError.message(L("cm_err_gift")) }
            let t = try await token(), me = try uid()
            if let existing {
                try await send("PATCH", "/rest/v1/contests?id=eq.\(existing.id)", json: d.json(status), t)
            } else {
                var j = d.json(status ?? "draft"); j["created_by"] = me
                try await send("POST", "/rest/v1/contests", json: j, t)
            }
            store?.say(status == "open" ? "cm_published" : "cm_saved")
        }
        if ok { await reloadContests() }
        return ok
    }

    func delete(_ c: Contest) async -> Bool {
        let ok = await act {
            let t = try await token()
            try await send("DELETE", "/rest/v1/contests?id=eq.\(c.id)", t)
            store?.say("cm_deleted")
        }
        if ok { await reloadContests() }
        return ok
    }

    private func reloadContests() async {
        if let t = try? await token(), let l = try? await list("/rest/v1/contests?select=*&order=ends_at.desc", t) { contests = l.map(Contest.init) }
    }

    /// Carica un'emulsione esclusiva (premio) fuori dal catalogo pubblico; restituisce nome e indirizzo.
    func uploadPrize(_ url: URL) async -> (String, String)? {
        var out: (String, String)?
        _ = await act {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let bytes = try? Data(contentsOf: url) else { throw ContestError.message(L("open_file_failed")) }
            let name = url.deletingPathExtension().lastPathComponent
            let safe = String(name.map { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" ? $0 : "-" }.prefix(40))
            let path = "exclusive/\(UUID().uuidString.prefix(8).lowercased())-\(safe.isEmpty ? "premio" : safe).emulsion"
            let t = try await token()
            try await send("POST", "/storage/v1/object/emulsions/\(path)", body: bytes, type: "application/octet-stream", t, upsert: true)
            out = (name, base + "/storage/v1/object/public/emulsions/" + path)
        }
        return out
    }

    /* ---------------- Premi ---------------- */

    private var pendingPrizes: [String] {
        get { UserDefaults.standard.stringArray(forKey: "contestPrizes") ?? [] }
        set { UserDefaults.standard.set(newValue, forKey: "contestPrizes") }
    }

    private func claimRewards(_ t: String) async {
        guard case let (c, d)? = try? await call("POST", "/rest/v1/rpc/claim_rewards", body: Data("{}".utf8), type: "application/json", t),
              (200..<300).contains(c), let arr = try? JSONSerialization.jsonObject(with: d) as? [[String: Any]] else { return }
        var coins = 0
        for r in arr {
            coins += r["coins"] as? Int ?? 0
            if let u = r["emulsion_url"] as? String, !pendingPrizes.contains(u) { pendingPrizes.append(u) }
        }
        if coins > 0 { store?.data.coins += coins; store?.say("ct_prize_coins", coins) }
        for u in pendingPrizes {
            guard let url = URL(string: u), case let (d, resp)? = try? await URLSession.shared.data(from: url),
                  (resp as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? false,
                  let e = store?.install(d) else { continue }
            pendingPrizes.removeAll { $0 == u }
            store?.say("ct_prize_emulsion", e.name)
        }
    }

    /* ---------------- Foto dal bucket privato ---------------- */

    func image(_ path: String) async -> UIImage? {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("contests", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent(path.replacingOccurrences(of: "/", with: "_"))
        if let d = try? Data(contentsOf: file), let img = UIImage(data: d) { return img }
        guard let t = try? await token(),
              case let (c, d)? = try? await call("GET", "/storage/v1/object/authenticated/contest-entries/\(path)", t),
              (200..<300).contains(c), let img = UIImage(data: d) else { return nil }
        try? d.write(to: file, options: .atomic)
        return img
    }

    /* ---------------- dettagli ---------------- */

    @discardableResult
    private func act(_ block: () async throws -> Void) async -> Bool {
        guard !busy else { return false }
        busy = true
        defer { busy = false }
        do { try await block(); return true } catch {
            store?.toast = explain(error)
            return false
        }
    }

    private func explain(_ e: Error) -> String {
        switch e as? ContestError {
        case .network: L("acc_err_network")
        case .signedOut: L("ct_sign_in")
        case .notAllowed: L("ct_err_rules")
        case .already: L("ct_err_already")
        case .message(let m): m
        default: L("ct_err_other")
        }
    }

    private func list(_ path: String, _ t: String) async throws -> [[String: Any]] {
        let (c, d) = try await call("GET", path, t)
        try check(c, d)
        return (try? JSONSerialization.jsonObject(with: d) as? [[String: Any]]) ?? []
    }

    private func send(_ method: String, _ path: String, json: [String: Any], _ t: String, prefer: String? = nil) async throws {
        let body = try JSONSerialization.data(withJSONObject: json)
        try await send(method, path, body: body, type: "application/json", t, prefer: prefer)
    }

    private func send(_ method: String, _ path: String, body: Data? = nil, type: String? = nil, _ t: String, prefer: String? = nil, upsert: Bool = false) async throws {
        let (c, d) = try await call(method, path, body: body, type: type, t, prefer: prefer, upsert: upsert)
        try check(c, d)
    }

    private func check(_ code: Int, _ d: Data) throws {
        guard !(200..<300).contains(code) else { return }
        let text = String(decoding: d, as: UTF8.self)
        if code == 401 { throw ContestError.signedOut }
        if code == 409 || text.contains("23505") { throw ContestError.already }
        if code == 403 || text.contains("42501") { throw ContestError.notAllowed }
        throw ContestError.other
    }

    private func call(_ method: String, _ path: String, body: Data? = nil, type: String? = nil, _ t: String, prefer: String? = nil, upsert: Bool = false) async throws -> (Int, Data) {
        guard let url = URL(string: base + path) else { throw ContestError.other }
        var req = URLRequest(url: url, timeoutInterval: 30)
        req.httpMethod = method
        req.setValue(key, forHTTPHeaderField: "apikey")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization")
        if let prefer { req.setValue(prefer, forHTTPHeaderField: "Prefer") }
        if upsert { req.setValue("true", forHTTPHeaderField: "x-upsert") }
        if let body { req.setValue(type ?? "application/json", forHTTPHeaderField: "Content-Type"); req.httpBody = body }
        do {
            let (d, r) = try await URLSession.shared.data(for: req)
            return ((r as? HTTPURLResponse)?.statusCode ?? 0, d)
        } catch {
            throw ContestError.network
        }
    }
}
