import SwiftUI

enum Tab: CaseIterable { case camera, rolls, shop, options
    var key: String {
        switch self { case .camera: "tab_camera"; case .rolls: "tab_rolls"; case .shop: "tab_shop"; case .options: "tab_options" }
    }
    var icon: String {
        switch self { case .camera: "camera"; case .rolls: "film.stack"; case .shop: "storefront"; case .options: "gearshape" }
    }
}

struct RootView: View {
    @EnvironmentObject var store: Store
    @State private var tab = Tab.camera

    var body: some View {
        HStack(spacing: 0) {
            VStack(spacing: 18) {
                ForEach(Tab.allCases, id: \.self) { t in
                    Button { tab = t } label: {
                        VStack(spacing: 3) {
                            Image(systemName: t.icon).font(.system(size: 20))
                            Text(L(t.key)).font(.system(size: 9))
                        }
                        .foregroundStyle(tab == t ? Palette.safelight : Palette.muted)
                        .frame(width: 64)
                    }
                }
            }
            .frame(maxHeight: .infinity)
            .background(Palette.plate)

            ZStack {
                Palette.body.ignoresSafeArea()
                switch tab {
                case .camera: CameraScreen(goShop: { tab = .shop }, goRolls: { tab = .rolls })
                case .rolls: RollsScreen()
                case .shop: ShopScreen()
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
                    .background(Color.black.opacity(0.85), in: Capsule())
                    .padding(.bottom, 16)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: t) {
                        try? await Task.sleep(nanoseconds: 2_600_000_000)
                        if store.toast == t { withAnimation { store.toast = nil } }
                    }
            }
        }
        .animation(.easeOut(duration: 0.2), value: store.toast)
    }
}
