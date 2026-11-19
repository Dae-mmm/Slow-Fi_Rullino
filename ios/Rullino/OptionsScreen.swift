import SwiftUI
import UniformTypeIdentifiers

/// Opzioni del design: schede numerate su due colonne. La modalità sviluppatore la vedono solo gli OP.
struct OptionsScreen: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var account: Account
    @State private var email = ""
    @State private var code = ""
    @State private var username = ""
    @State private var shown = ""
    @State private var confirmDelete = false
    @State private var importing = false

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }

    private var isOp: Bool { account.profile?.role == .op }

    private func run(_ op: @escaping () async throws -> Void) {
        guard !account.busy else { return }
        account.busy = true
        Task {
            do { try await op() } catch let e as AccountError { store.say(e.key) } catch { store.say("acc_err_network") }
            account.busy = false
        }
    }

    private func roleName(_ r: Role) -> String {
        switch r { case .op: L("role_op"); case .judge: L("role_judge"); case .user: L("role_user") }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                PageHeading(kicker: L("kick_system"), title: L("tab_options")) {
                    Text(L("version", version).uppercased()).font(Fonts.mono(10)).foregroundStyle(Palette.muted)
                }
                HStack(alignment: .top, spacing: 12) {
                    OptCard(number: "01", title: L("account")) { accountContent }
                        .frame(maxWidth: .infinity)
                    VStack(spacing: 12) {
                        OptCard(number: "02", title: L("lab_title")) {
                            Text(L("opt_lab_text")).font(Fonts.barlow(14)).foregroundStyle(Palette.muted)
                            HStack(spacing: 8) {
                                ForEach(LabTime.allCases, id: \.self) { t in
                                    let on = store.data.labTime == t
                                    Button { store.data.labTime = t } label: {
                                        Text(L(t.key)).font(Fonts.barlow(16)).frame(maxWidth: .infinity, minHeight: 40)
                                            .foregroundStyle(on ? Palette.safelight : Palette.ink)
                                            .background(Rectangle().fill(on ? Color(hex: 0x2D1710) : Color(hex: 0x161514)))
                                            .overlay(Rectangle().stroke(on ? Palette.safelight : Color(hex: 0x4A4541), lineWidth: 1))
                                    }
                                }
                            }
                            Text(L("opt_lab_note")).font(Fonts.barlow(12)).foregroundStyle(Palette.muted)
                        }
                        if isOp {
                            OptCard(number: "03", title: L("dev_mode")) {
                                Toggle(isOn: $store.data.skipLab) {
                                    VStack(alignment: .leading) {
                                        Text(L("skip_lab")).foregroundStyle(Palette.ink)
                                        Text(L("skip_lab_note")).font(Fonts.barlow(12)).foregroundStyle(Palette.muted)
                                    }
                                }
                                .tint(Palette.safelight)
                                HStack {
                                    Button(L("import_emulsion")) { importing = true }.buttonStyle(.secondary)
                                    Button(L("plus100")) { Task { await store.opGrant(100) } }.buttonStyle(.secondary)
                                    Button(L("daily_again")) { Task { await store.opResetDaily() } }.buttonStyle(.secondary)
                                }
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, 24).padding(.vertical, 20)
        }
        .background(DottedPage())
        .onAppear { username = account.profile?.username ?? ""; shown = account.profile?.displayName ?? "" }
        .onChange(of: account.profile?.username) { _, v in username = v ?? "" }
        .onChange(of: account.profile?.displayName) { _, v in shown = v ?? "" }
        .alert(L("acc_delete_confirm"), isPresented: $confirmDelete) {
            Button(L("acc_delete"), role: .destructive) { run { try await account.deleteAccount(); store.forgetEconomy(); store.say("acc_deleted") } }
            Button(L("cancel"), role: .cancel) {}
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [UTType(exportedAs: "com.slowfi.emulsion"), .zip, .data]) { result in
            if case .success(let url) = result { store.importEmulsion(url) }
        }
    }

    @ViewBuilder
    private var accountContent: some View {
        if !account.signedIn {
            Text(L("acc_intro")).font(Fonts.barlow(14)).foregroundStyle(Palette.muted)
            if let pending = account.pendingEmail {
                Text(L("acc_code_sent", pending)).font(Fonts.barlow(14)).foregroundStyle(Palette.ink)
                DesignField(placeholder: L("acc_code"), text: $code)
                    .keyboardType(.numberPad).textContentType(.oneTimeCode)
                HStack {
                    Button(L("acc_sign_in")) { run { try await account.verify(code); code = ""; await store.refreshEconomy() } }
                        .buttonStyle(.primary).disabled(account.busy || code.count < 6)
                    Button(L("acc_change_email")) { code = ""; account.pendingEmail = nil }
                        .font(Fonts.barlow(14)).foregroundStyle(Palette.muted)
                }
            } else {
                DesignField(placeholder: L("acc_email"), text: $email)
                    .keyboardType(.emailAddress).textContentType(.emailAddress)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button(L("acc_send_code")) { run { try await account.sendCode(email) } }
                    .buttonStyle(.primary).disabled(account.busy || !email.contains("@"))
            }
        } else {
            HStack(spacing: 12) {
                let initials = String((account.profile?.displayName ?? account.email ?? "?").prefix(2)).uppercased()
                Text(initials).font(Fonts.barlow(16)).foregroundStyle(Palette.safelight)
                    .frame(width: 44, height: 44).background(Circle().fill(Color(hex: 0x3A1D14)))
                VStack(alignment: .leading, spacing: 0) {
                    Text(account.email ?? "").font(Fonts.barlow(17, .semibold)).foregroundStyle(Palette.ink).lineLimit(1)
                    if let u = account.profile?.username { Text("@" + u).font(Fonts.barlow(13)).foregroundStyle(Palette.muted) }
                }
                Spacer()
                if let p = account.profile {
                    ChipView(text: roleName(p.role) + (Features.proVisible && account.entitlements.pro ? " · PRO" : ""), active: p.role != .user)
                }
            }
            if account.profile != nil {
                DesignField(placeholder: L("acc_username"), text: $username)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Text(L("acc_username_invalid")).font(Fonts.barlow(11)).foregroundStyle(Palette.muted)
                DesignField(placeholder: L("acc_display_name"), text: $shown)
                Button(L("save")) { run { try await account.updateProfile(username: username, displayName: shown); store.say("acc_saved") } }
                    .buttonStyle(.secondary).disabled(account.busy)
                if account.profile?.role != .user { Text(L("acc_judge_area")).font(Fonts.barlow(12)).foregroundStyle(Palette.muted) }
            }
            HStack(spacing: 14) {
                Button(L("acc_sign_out")) { run { await store.refreshEconomy(); await account.signOut(); store.forgetEconomy(); store.say("acc_signed_out") } }
                    .buttonStyle(.secondary).disabled(account.busy)
                Button(L("acc_delete")) { confirmDelete = true }
                    .font(Fonts.barlow(14)).underline().foregroundStyle(Palette.danger).disabled(account.busy)
            }
        }
    }
}

/// Scheda numerata delle opzioni.
struct OptCard<Content: View>: View {
    let number: String
    let title: String
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Text(number).font(Fonts.mono(22)).foregroundStyle(Color(hex: 0x5A544F))
                Text(title).font(Fonts.barlow(22, .semibold)).foregroundStyle(Palette.ink)
            }
            Rectangle().fill(Palette.line).frame(height: 1)
            content()
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }
}

/// Campo di testo scuro del design.
struct DesignField: View {
    let placeholder: String
    @Binding var text: String
    var body: some View {
        TextField("", text: $text, prompt: Text(placeholder).foregroundColor(Palette.faint))
            .font(Fonts.barlow(16)).foregroundStyle(Palette.ink)
            .padding(.horizontal, 10).frame(height: 40)
            .background(Rectangle().fill(Color(hex: 0x161514)))
            .overlay(Rectangle().stroke(Color(hex: 0x4A4541), lineWidth: 1))
    }
}
