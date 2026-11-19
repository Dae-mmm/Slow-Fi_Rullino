import Foundation

/// Il negozio sul server: gettoni, rullino del giorno, magazzino e "tasca" (fino a 3 rullini caricabili offline).
/// Il telefono chiede, il server decide prezzi e saldo. Stesso funzionamento dell'app Android.
enum EconomyError: Error { case code(String), network, signedOut }

extension Store {
    static let trialEmulsion = "bundled-neutra-100"
    static let trialId = "trial"

    /* ---------------- con il server ---------------- */

    private func rpc(_ name: String, _ args: [String: Any] = [:]) async throws -> Data {
        try await request("POST", "/rest/v1/rpc/" + name, body: args)
    }

    /// Chiamata al server con il token dell'utente; gli errori "di gioco" diventano EconomyError.code.
    func request(_ method: String, _ path: String, body: Any? = nil, prefer: String? = nil) async throws -> Data {
        guard let account, let token = await account.accessToken() else { throw EconomyError.signedOut }
        guard let url = URL(string: Store.catalogURL + path) else { throw EconomyError.network }
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = method
        req.setValue(Store.catalogKey, forHTTPHeaderField: "apikey")
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let prefer { req.setValue(prefer, forHTTPHeaderField: "Prefer") }
        if let body { req.httpBody = try JSONSerialization.data(withJSONObject: body) }
        let (d, r): (Data, URLResponse)
        do { (d, r) = try await URLSession.shared.data(for: req) } catch { throw EconomyError.network }
        let code = (r as? HTTPURLResponse)?.statusCode ?? 0
        if !(200..<300).contains(code) {
            let msg = ((try? JSONSerialization.jsonObject(with: d)) as? [String: Any])?["message"] as? String ?? ""
            if code == 401 { throw EconomyError.signedOut }
            throw EconomyError.code(msg)
        }
        return d
    }

    var signedIn: Bool { account?.signedIn == true }

    private func applyState(_ d: Data) {
        guard let s = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return }
        data.coins = s["coins"] as? Int ?? data.coins
        data.dailyOpen = s["daily_available"] as? Bool ?? false
        data.serverInventory = (s["inventory"] as? [String: Int]) ?? [:]
        data.pocket = ((s["pocket"] as? [[String: Any]]) ?? []).compactMap { p in
            guard let id = p["id"] as? String, let e = p["emulsion_id"] as? String else { return nil }
            return PocketRoll(id: id, emulsionId: e)
        }
        mirror()
    }

    /// Il magazzino che si vede nell'app: quello del server più la tasca (o il rullino di prova).
    func mirror() {
        var counts: [String: Int] = [:]
        if signedIn { for (k, v) in data.serverInventory { counts[k, default: 0] += v } }
        for p in data.pocket { counts[p.emulsionId, default: 0] += 1 }
        if !signedIn && data.trialAvailable { counts[Store.trialEmulsion, default: 0] += 1 }
        data.inventory = counts.filter { $0.value > 0 }
    }

    private func flushQueue() async throws {
        for op in data.econQueue {
            guard op.count == 2 else { continue }
            _ = try await rpc(op[0] == "use" ? "pocket_use" : "pocket_return", ["p": op[1]])
            data.econQueue.removeAll { $0 == op }
        }
    }

    /// Manda le operazioni in coda, riempie la tasca e aggiorna saldo e magazzino. Senza rete non fa nulla.
    func refreshEconomy() async {
        guard signedIn else { mirror(); return }
        do {
            try await flushQueue()
            applyState(try await rpc("pocket_fill"))
        } catch { mirror() }
    }

    func buy(_ e: Emulsion) async {
        do {
            applyState(try await rpc("buy_roll", ["e": e.id]))
            if let d = try? await rpc("pocket_fill") { applyState(d) }
            say("sk_bought", e.name)
        } catch { say(economyKey(error), e.name) }
    }

    func takeFree(_ e: Emulsion) async {
        do {
            applyState(try await rpc("claim_daily", ["e": e.id]))
            if let d = try? await rpc("pocket_fill") { applyState(d) }
            say("sk_daily_given", e.name)
        } catch { say(economyKey(error), e.name) }
    }

    func opGrant(_ n: Int) async {
        do { applyState(try await rpc("op_grant_coins", ["n": n])); say("plus100") } catch { say(economyKey(error)) }
    }

    func opResetDaily() async {
        do { applyState(try await rpc("op_reset_daily")); say("daily_again_msg") } catch { say(economyKey(error)) }
    }

    /// Prende un rullino (dalla tasca, dal magazzino sul server o il rullino di prova) e lo carica in macchina.
    func takeAndLoad(_ e: Emulsion, exposures: Int) async -> Bool {
        if let a = activeRoll {
            guard a.shotCount == 0 else { say("err_roll_in_camera"); return false }
        }
        let pocketId: String
        if let p = data.pocket.first(where: { $0.emulsionId == e.id }) {
            data.pocket.removeAll { $0 == p }
            data.econQueue.append(["use", p.id])
            pocketId = p.id
            Task { try? await flushQueue() }
        } else if !signedIn {
            guard e.id == Store.trialEmulsion, data.trialAvailable else { say("eco_sign_in"); return false }
            data.trialAvailable = false
            pocketId = Store.trialId
        } else {
            do {
                let d = try await rpc("pocket_take", ["e": e.id])
                let id = (String(data: d, encoding: .utf8) ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "\"\n "))
                _ = try await rpc("pocket_use", ["p": id])
                data.serverInventory[e.id] = max(0, (data.serverInventory[e.id] ?? 1) - 1)
                pocketId = id
            } catch { say(economyKey(error), e.name); return false }
        }
        if let a = activeRoll, a.shotCount == 0 { unloadEmptyRoll(a) }
        loadRoll(e, exposures: exposures, pocketId: pocketId)
        mirror()
        Task { await refreshEconomy() }
        return true
    }

    /// Un rullino mai scattato torna in magazzino (il codice va restituito al server).
    func giveBack(_ r: Roll) {
        switch r.pocketId {
        case nil: break                                   // rullino di prima del magazzino sul server
        case Store.trialId: data.trialAvailable = true
        case let id?:
            data.econQueue.append(["return", id])
            data.serverInventory[r.emulsionId, default: 0] += 1
            Task { try? await flushQueue() }
        }
        mirror()
    }

    /// OP: mette un'emulsione nel catalogo pubblico; il server la legge e la pubblica con lo stesso id.
    func uploadCatalog(_ bytes: Data, id: String) async throws {
        guard let account, let token = await account.accessToken() else { throw EconomyError.signedOut }
        let safe = String(id.map { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" ? $0 : "-" }.prefix(64))
        guard let url = URL(string: Store.catalogURL + "/storage/v1/object/emulsions/\(safe).emulsion") else { throw EconomyError.network }
        var req = URLRequest(url: url, timeoutInterval: 60)
        req.httpMethod = "POST"
        req.setValue(Store.catalogKey, forHTTPHeaderField: "apikey")
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        req.setValue("true", forHTTPHeaderField: "x-upsert")
        let (_, resp) = try await URLSession.shared.upload(for: req, from: bytes)
        guard (resp as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) == true else { throw EconomyError.network }
    }

    /* ---------------- Rullini condivisi: un file si importa una volta sola ---------------- */

    func createShare(_ r: Roll) async throws -> String {
        guard let me = account?.userId else { throw EconomyError.signedOut }
        let d = try await request("POST", "/rest/v1/roll_shares",
                                  body: ["created_by": me, "emulsion_id": r.emulsionId, "shot_count": r.shotCount],
                                  prefer: "return=representation")
        guard let row = ((try? JSONSerialization.jsonObject(with: d)) as? [[String: Any]])?.first, let id = row["id"] as? String
        else { throw EconomyError.network }
        return id
    }

    /// "ok" la prima volta, poi "already_claimed"; "unknown" se il codice non esiste.
    func claimShare(_ id: String) async throws -> String {
        let d = try await rpc("claim_roll_share", ["s": id])
        return (String(data: d, encoding: .utf8) ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "\"\n "))
    }

    /// I rullini in viaggio già importati da qualcuno spariscono da qui (sono passati di mano).
    func syncShared() async {
        let shared = data.rolls.filter { $0.state == .shared && $0.shareId != nil }
        guard signedIn, !shared.isEmpty, let me = account?.userId else { return }
        let ids = shared.compactMap(\.shareId).joined(separator: ",")
        guard let d = try? await request("GET", "/rest/v1/roll_shares?id=in.(\(ids))&select=id,claimed_by"),
              let rows = (try? JSONSerialization.jsonObject(with: d)) as? [[String: Any]] else { return }
        for row in rows {
            guard let id = row["id"] as? String, let by = row["claimed_by"] as? String,
                  let r = data.rolls.first(where: { $0.shareId == id }) else { continue }
            if by == me { update(r.id) { $0.state = .shelved; $0.shareId = nil } }
            else { deleteRoll(r); say("share_delivered", r.emulsionName) }
        }
    }

    /// Riprende un rullino "in viaggio": funziona solo se nessuno l'ha ancora importato.
    func takeBack(_ r: Roll) async {
        guard let id = r.shareId else { return }
        do {
            if try await claimShare(id) == "ok" { update(r.id) { $0.state = .shelved; $0.shareId = nil }; say("share_taken_back") }
            else { deleteRoll(r); say("share_delivered", r.emulsionName) }
        } catch { say("share_failed") }
    }

    /// Uscendo dall'account: saldo, magazzino e tasca restano sul server, qui si azzerano.
    /// Il negozio vende solo ciò che è a catalogo (più le emulsioni di serie e i premi):
    /// un file importato a mano resta solo tuo.
    func sellable(_ e: Emulsion) -> Bool {
        if e.id.hasPrefix("bundled-") || data.prizeEmulsions.contains(e.id) { return true }
        guard let catalog else { return true }
        return catalog.contains { $0.id == e.id }
    }

    func forgetEconomy() {
        data.coins = 0; data.dailyOpen = false; data.serverInventory = [:]; data.pocket = []; data.econQueue = []
        mirror()
    }

    func economyKey(_ e: Error) -> String {
        switch e as? EconomyError {
        case .code("not_enough_coins"): "sk_no_coins"
        case .code("daily_taken"): "sk_daily_taken"
        case .code("no_rolls"): "err_no_rolls_of"
        case .code("unknown_emulsion"): "eco_not_for_sale"
        case .signedOut, .code("not_signed_in"): "eco_sign_in"
        case .network: "eco_offline"
        default: "eco_error"
        }
    }
}
