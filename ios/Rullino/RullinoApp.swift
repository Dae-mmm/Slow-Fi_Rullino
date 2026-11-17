import SwiftUI

@main
struct RullinoApp: App {
    @StateObject private var store = Store()
    @StateObject private var account = Account()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(account)
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

enum Palette {
    static let body = Color(hex: 0x141312)
    static let plate = Color(hex: 0x1E1C1B)
    static let plateHi = Color(hex: 0x2A2725)
    static let line = Color(hex: 0x3A3533)
    static let ink = Color(hex: 0xEBE6E0)
    static let muted = Color(hex: 0x9C958D)
    static let safelight = Color(hex: 0xFF6A3D)
    static let chrome = Color(hex: 0xC9C4BE)
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

let mono = Font.system(size: 11, design: .monospaced)
