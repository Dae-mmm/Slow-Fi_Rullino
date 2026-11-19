import SwiftUI

@main
struct RullinoApp: App {
    @StateObject private var store = Store()
    @StateObject private var account = Account()
    @StateObject private var contests = ContestsModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .font(Fonts.barlow(16))
                .environmentObject(store)
                .environmentObject(account)
                .environmentObject(contests)
                .onAppear {
                    contests.account = account; contests.store = store; store.account = account
                    Task { await store.refreshEconomy() }
                }
                .preferredColorScheme(.dark)
                .statusBarHidden(true)
                .persistentSystemOverlays(.hidden)
        }
    }
}

/// Testo localizzato (stesse chiavi dell'app Android). I segnaposto %1$@ ricevono sempre stringhe.
func L(_ key: String, _ args: Any...) -> String {
    let f = NSLocalizedString(key, comment: "")
    let a: [CVarArg] = args.map { "\($0)" as NSString }
    return String(format: f, locale: Locale.current, arguments: a)
}

/// Colori del design (Figma).
enum Palette {
    static let body = Color(hex: 0x141312)
    static let deep = Color(hex: 0x0D0C0C)
    static let leather = Color(hex: 0x171615)
    static let plate = Color(hex: 0x24211F)
    static let plateHi = Color(hex: 0x2D2927)
    static let line = Color(hex: 0x3A3533)
    static let ink = Color(hex: 0xEBE6E0)
    static let muted = Color(hex: 0x9C958D)
    static let faint = Color(hex: 0x6E6862)
    static let safelight = Color(hex: 0xFF6A3D)
    static let safelightDark = Color(hex: 0x2D1710)
    static let chrome = Color(hex: 0xC9C4BE)
    static let danger = Color(hex: 0xFF8A80)
}

/// Barlow Condensed per testi e incisioni, IBM Plex Mono per i numeri.
enum Fonts {
    static func barlow(_ size: CGFloat, _ weight: Font.Weight = .medium) -> Font {
        let name: String
        switch weight {
        case .bold, .heavy, .black: name = "BarlowCondensed-Bold"
        case .semibold: name = "BarlowCondensed-SemiBold"
        case .regular, .light, .thin, .ultraLight: name = "BarlowCondensed-Regular"
        default: name = "BarlowCondensed-Medium"
        }
        return .custom(name, size: size)
    }
    static func mono(_ size: CGFloat, _ weight: Font.Weight = .medium) -> Font {
        let name: String
        switch weight {
        case .semibold, .bold, .heavy, .black: name = "IBMPlexMono-SemiBold"
        case .regular, .light, .thin, .ultraLight: name = "IBMPlexMono-Regular"
        default: name = "IBMPlexMono-Medium"
        }
        return .custom(name, size: size)
    }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}

enum Haptics {
    static func tick() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func confirm() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func reject() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
}

let mono = Fonts.mono(11)
