import Foundation
import Security

/// Livelli di accesso: utente normale, giudice dei concorsi, OP (admin/moderatore/sviluppatore).
enum Role: String { case user, judge, op }

struct Profile {
    var id: String
    var email: String
    var username: String?
    var displayName: String?
    var role: Role
}

/// Cosa sblocca l'abbonamento. Per ora niente: vedi `Features.proVisible`.
struct Entitlements { var pro = false }

enum Features {
    /// Mostra nell'app ciò che riguarda la versione Pro. Spento finché non decidiamo cosa include:
    /// il dato dell'abbonamento arriva comunque dal server.
    static let proVisible = false
}

enum AccountError: Error { case network, send, code, usernameTaken, usernameInvalid, other
    var key: String {
        switch self {
        case .network, .other: "acc_err_network"
        case .send: "acc_err_send"
        case .code: "acc_err_code"
        case .usernameTaken: "acc_username_taken"
        case .usernameInvalid: "acc_username_invalid"
        }
    }
}

private struct Session: Codable {
    var accessToken: String
    var refreshToken: String
    var expiresAt: Date
    var userId: String
    var email: String
}

/// Account su Supabase Auth con codice via email. La sessione sta nel Portachiavi.
@MainActor
final class Account: ObservableObject {
    @Published private(set) var profile: Profile?
    @Published private(set) var signedIn = false
    @Published private(set) var entitlements = Entitlements()
    @Published var busy = false
    @Published var pendingEmail: String?

    private var session: Session? { didSet { signedIn = session != nil } }
    private let base = Store.catalogURL
    private let key = Store.catalogKey

    var email: String? { session?.email }
    var userId: String? { session?.userId }

    /// Token valido per gli altri client (concorsi); lo rinnova se sta per scadere.
    func accessToken() async -> String? { try? await validToken() }

    init() {
        session = Keychain.load()
        signedIn = session != nil
        if signedIn { Task { try? await refresh() } }
    }

    func sendCode(_ email: String) async throws {
        // La lingua del telefono finisce nei dati dell'utente: l'email del codice esce in quella lingua.
        let lang = Locale.current.language.languageCode?.identifier ?? "en"
        let (c, _) = try await call("POST", "/auth/v1/otp", ["email": email.trimmingCharacters(in: .whitespaces), "create_user": true, "data": ["locale": lang]])
        guard (200..<300).contains(c) else { throw AccountError.send }
        pendingEmail = email.trimmingCharacters(in: .whitespaces)
    }

    func verify(_ code: String) async throws {
        guard let email = pendingEmail else { return }
        let (c, d) = try await call("POST", "/auth/v1/verify", ["type": "email", "email": email, "token": code.trimmingCharacters(in: .whitespaces)])
        guard (200..<300).contains(c), let s = parseSession(d) else { throw AccountError.code }
        save(s)
        pendingEmail = nil
        try await refresh()
    }

    func refresh() async throws {
        guard let token = try await validToken(), let uid = session?.userId else { return }
        let (c1, d1) = try await call("GET", "/rest/v1/profiles?id=eq.\(uid)&select=id,username,display_name,role", nil, token: token)
        if (200..<300).contains(c1), let arr = try? JSONSerialization.jsonObject(with: d1) as? [[String: Any]], let p = arr.first {
            profile = Profile(id: uid, email: session?.email ?? "",
                              username: p["username"] as? String, displayName: p["display_name"] as? String,
                              role: Role(rawValue: p["role"] as? String ?? "") ?? .user)
        }
        let (c2, d2) = try await call("GET", "/rest/v1/subscriptions?user_id=eq.\(uid)&select=status,current_period_end", nil, token: token)
        if (200..<300).contains(c2), let arr = try? JSONSerialization.jsonObject(with: d2) as? [[String: Any]] {
            var pro = false
            if let s = arr.first, let st = s["status"] as? String, ["active", "trialing", "grace"].contains(st) {
                if let end = s["current_period_end"] as? String, let date = ISO8601DateFormatter.flexible(end) {
                    pro = date > Date()
                } else { pro = true }
            }
            entitlements = Entitlements(pro: pro)
        }
    }

    func updateProfile(username: String, displayName: String) async throws {
        guard let token = try await validToken() else { throw AccountError.other }
        let (c, d) = try await call("POST", "/rest/v1/rpc/update_my_profile", ["p_username": username, "p_display_name": displayName], token: token)
        guard (200..<300).contains(c) else {
            let text = String(decoding: d, as: UTF8.self)
            if text.contains("23505") { throw AccountError.usernameTaken }
            if text.contains("23514") { throw AccountError.usernameInvalid }
            throw AccountError.other
        }
        try await refresh()
    }

    func signOut() async {
        if let s = session { _ = try? await call("POST", "/auth/v1/logout", [:], token: s.accessToken) }
        clear()
    }

    func deleteAccount() async throws {
        guard let token = try await validToken() else { throw AccountError.other }
        let (c, _) = try await call("POST", "/functions/v1/delete-account", [:], token: token)
        guard (200..<300).contains(c) else { throw AccountError.other }
        clear()
    }

    /* ---------------- sessione ---------------- */

    private func validToken() async throws -> String? {
        guard let s = session else { return nil }
        if s.expiresAt.timeIntervalSinceNow > 60 { return s.accessToken }
        let (c, d) = try await call("POST", "/auth/v1/token?grant_type=refresh_token", ["refresh_token": s.refreshToken])
        if (200..<300).contains(c), let n = parseSession(d) { save(n); return n.accessToken }
        if c == 400 || c == 401 { clear() }
        return nil
    }

    private func parseSession(_ d: Data) -> Session? {
        guard let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let access = j["access_token"] as? String, let refresh = j["refresh_token"] as? String,
              let user = j["user"] as? [String: Any], let id = user["id"] as? String else { return nil }
        let exp = (j["expires_in"] as? Double) ?? 3600
        return Session(accessToken: access, refreshToken: refresh, expiresAt: Date().addingTimeInterval(exp),
                       userId: id, email: user["email"] as? String ?? "")
    }

    private func save(_ s: Session) { session = s; Keychain.save(s) }

    private func clear() {
        session = nil
        Keychain.delete()
        profile = nil
        entitlements = Entitlements()
    }

    private func call(_ method: String, _ path: String, _ body: [String: Any]?, token: String? = nil) async throws -> (Int, Data) {
        guard let url = URL(string: base + path) else { throw AccountError.other }
        var req = URLRequest(url: url, timeoutInterval: 15)
        req.httpMethod = method
        req.setValue(key, forHTTPHeaderField: "apikey")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        do {
            let (d, r) = try await URLSession.shared.data(for: req)
            return ((r as? HTTPURLResponse)?.statusCode ?? 0, d)
        } catch {
            throw AccountError.network
        }
    }

    /* ---------------- Portachiavi ---------------- */

    private enum Keychain {
        static let service = "com.slowfi.rullino.session"

        static func save(_ s: Session) {
            guard let data = try? JSONEncoder().encode(s) else { return }
            delete()
            let q: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                kSecValueData as String: data,
            ]
            SecItemAdd(q as CFDictionary, nil)
        }

        static func load() -> Session? {
            let q: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecReturnData as String: true,
                kSecMatchLimit as String: kSecMatchLimitOne,
            ]
            var out: CFTypeRef?
            guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
            return try? JSONDecoder().decode(Session.self, from: d)
        }

        static func delete() {
            let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
            SecItemDelete(q as CFDictionary)
        }
    }
}

extension ISO8601DateFormatter {
    /// Date di Postgres, con o senza frazioni di secondo.
    static func flexible(_ raw: String) -> Date? {
        // Postgres manda fino a 6 decimali: ne teniamo 3, che il formatter legge di sicuro.
        let s = raw.replacingOccurrences(of: #"(\.\d{3})\d+"#, with: "$1", options: .regularExpression)
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }
}
