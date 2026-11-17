import SwiftUI
import UniformTypeIdentifiers

struct OptionsScreen: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var account: Account
    @State private var taps = 0
    @State private var email = ""
    @State private var code = ""
    @State private var username = ""
    @State private var shown = ""
    @State private var confirmDelete = false
    @State private var importing = false

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }

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

    @ViewBuilder
    private var accountSection: some View {
        Section(L("account")) {
            if !account.signedIn {
                Text(L("acc_intro")).font(.callout)
                if let pending = account.pendingEmail {
                    Text(L("acc_code_sent", pending)).font(.callout)
                    TextField(L("acc_code"), text: $code)
                        .keyboardType(.numberPad).textContentType(.oneTimeCode)
                    HStack {
                        Button(L("acc_sign_in")) { run { try await account.verify(code); code = "" } }
                            .disabled(account.busy || code.count < 6)
                        Spacer()
                        Button(L("acc_change_email")) { code = ""; account.pendingEmail = nil }.foregroundStyle(.secondary)
                    }
                    .buttonStyle(.borderless)
                } else {
                    TextField(L("acc_email"), text: $email)
                        .keyboardType(.emailAddress).textContentType(.emailAddress)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button(L("acc_send_code")) { run { try await account.sendCode(email) } }
                        .disabled(account.busy || !email.contains("@"))
                }
            } else {
                Text(L("acc_signed_in_as", account.email ?? ""))
                if let p = account.profile {
                    Text(L("acc_role", roleName(p.role)) + (Features.proVisible && account.entitlements.pro ? " · PRO" : ""))
                        .foregroundStyle(p.role == .user ? Color.secondary : Palette.safelight)
                    TextField(L("acc_username"), text: $username)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Text(L("acc_username_invalid")).font(.caption).foregroundStyle(.secondary)
                    TextField(L("acc_display_name"), text: $shown)
                    Button(L("save")) { run { try await account.updateProfile(username: username, displayName: shown); store.say("acc_saved") } }
                        .disabled(account.busy)
                    if p.role != .user { Text(L("acc_judge_area")).font(.caption).foregroundStyle(.secondary) }
                }
                Button(L("acc_sign_out")) { run { await account.signOut(); store.say("acc_signed_out") } }.disabled(account.busy)
                Button(L("acc_delete"), role: .destructive) { confirmDelete = true }.disabled(account.busy)
            }
        }
    }

    var body: some View {
        Form {
            accountSection
            Section(L("lab_title")) {
                Text(L("opt_lab_text")).font(.callout)
                Picker(L("lab_title"), selection: $store.data.labTime) {
                    ForEach(LabTime.allCases, id: \.self) { t in Text(L(t.key)).tag(t) }
                }
                .pickerStyle(.segmented)
                Text(L("opt_lab_note")).font(.caption).foregroundStyle(.secondary)
            }
            if store.data.devMode {
                Section(L("dev_mode")) {
                    Toggle(isOn: $store.data.skipLab) {
                        VStack(alignment: .leading) {
                            Text(L("skip_lab"))
                            Text(L("skip_lab_note")).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Button(L("import_emulsion")) { importing = true }
                    Button(L("plus100")) { store.data.coins += 100; store.say("plus100") }
                    Button(L("daily_again")) { store.data.lastDaily = ""; store.say("daily_again_msg") }
                    Button(L("dev_off"), role: .destructive) {
                        store.data.devMode = false; store.data.skipLab = false; taps = 0; store.say("dev_off_msg")
                    }
                }
            }
            Section {
                HStack {
                    Text(L("version", version))
                    Spacer()
                    if store.data.devMode { Text(L("you_are_dev")).foregroundStyle(.secondary) }
                    else if taps >= 3 { Text(L("taps_left", 7 - taps)).foregroundStyle(.secondary) }
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    guard !store.data.devMode else { return }
                    taps += 1
                    if taps >= 7 { store.data.devMode = true; Haptics.confirm() }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .onChange(of: account.profile?.role) { _, role in if role == .op { store.data.devMode = true } }
        .onAppear { username = account.profile?.username ?? ""; shown = account.profile?.displayName ?? "" }
        .onChange(of: account.profile?.username) { _, v in username = v ?? "" }
        .onChange(of: account.profile?.displayName) { _, v in shown = v ?? "" }
        .alert(L("acc_delete_confirm"), isPresented: $confirmDelete) {
            Button(L("acc_delete"), role: .destructive) { run { try await account.deleteAccount(); store.say("acc_deleted") } }
            Button(L("cancel"), role: .cancel) {}
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [UTType(exportedAs: "com.slowfi.emulsion"), .zip, .data]) { result in
            if case .success(let url) = result { store.importEmulsion(url) }
        }
    }
}
