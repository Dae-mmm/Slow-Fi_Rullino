import SwiftUI

enum Tab: CaseIterable { case camera, rolls, shop, contests, options
    var key: String {
        switch self { case .camera: "tab_camera"; case .rolls: "tab_rolls"; case .shop: "tab_shop"; case .contests: "tab_contests"; case .options: "tab_options" }
    }
    var glyph: String {
        switch self { case .camera: "◉"; case .rolls: "▣"; case .shop: "⌂"; case .contests: "◈"; case .options: "⚙" }
    }
}

struct RootView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var account: Account
    @State private var tab = Tab.camera

    var body: some View {
        HStack(spacing: 0) {
            // Fascia di navigazione del design: marchio S—F e voci con segno e nome.
            VStack(spacing: 0) {
                Text("S—F").font(Fonts.mono(10, .semibold)).tracking(2).foregroundStyle(Palette.muted)
                    .padding(.horizontal, 6).padding(.vertical, 4)
                    .overlay(Rectangle().stroke(Color(hex: 0x514A46), lineWidth: 1))
                    .padding(.top, 8)
                Spacer()
                ForEach(Tab.allCases, id: \.self) { t in
                    let on = tab == t
                    Button { tab = t } label: {
                        VStack(spacing: 2) {
                            Text(t.glyph).font(Fonts.mono(20))
                            Text(L(t.key).uppercased()).font(Fonts.barlow(9)).tracking(0.8).lineLimit(1)
                        }
                        .foregroundStyle(on ? Palette.safelight : Palette.faint)
                        .frame(width: 68, height: 54)
                        .background(on ? LinearGradient(colors: [Color(hex: 0x291711), .clear], startPoint: .leading, endPoint: .trailing) : LinearGradient(colors: [.clear], startPoint: .leading, endPoint: .trailing))
                        .overlay(alignment: .leading) { if on { Rectangle().fill(Palette.safelight).frame(width: 2) } }
                    }
                }
                Spacer()
            }
            .frame(width: 68)
            .frame(maxHeight: .infinity)
            .background(Palette.deep)
            .overlay(alignment: .trailing) { Rectangle().fill(Color(hex: 0x282422)).frame(width: 1) }

            ZStack {
                Palette.body.ignoresSafeArea()
                switch tab {
                case .camera: CameraScreen(goShop: { tab = .shop }, goRolls: { tab = .rolls })
                case .rolls: RollsScreen()
                case .shop: ShopScreen()
                case .contests: ContestsScreen(goCamera: { tab = .camera }, goAccount: { tab = .options })
                case .options: OptionsScreen()
                }
            }
        }
        .background(Palette.plate.ignoresSafeArea())
        .overlay(alignment: .bottom) {
            if let t = store.toast {
                Text(t)
                    .font(.callout).foregroundStyle(Palette.ink)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(RoundedRectangle(cornerRadius: 3).fill(Color(hex: 0x272321)))
                    .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(hex: 0x5D554F), lineWidth: 1))
                    .padding(.bottom, 16)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: t) {
                        try? await Task.sleep(nanoseconds: 2_600_000_000)
                        if store.toast == t { withAnimation { store.toast = nil } }
                    }
            }
        }
        .animation(.easeOut(duration: 0.2), value: store.toast)
        // La modalità sviluppatore segue il ruolo dell'account: solo gli OP la vedono.
        .onChange(of: account.profile?.role) { _, role in
            if let role { store.data.devMode = role == .op; if role != .op { store.data.skipLab = false } }
        }
        .onChange(of: account.signedIn) { _, on in if !on { store.data.devMode = false; store.data.skipLab = false } }
        .onOpenURL { url in
            switch url.pathExtension.lowercased() {
            case "emulsion": store.importEmulsion(url); tab = .shop
            case "rullino": store.importRoll(url); tab = .rolls
            default: break
            }
        }
    }
}
