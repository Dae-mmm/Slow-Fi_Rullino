import SwiftUI

/* Componenti comuni del design Figma: pulsanti, chip, intestazioni, schede, scatole e caricatori. */

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Fonts.barlow(14, .semibold)).tracking(1.1)
            .textCase(.uppercase)
            .foregroundStyle(enabled ? Color(hex: 0x1B0E09) : Color(hex: 0x8A6A5E))
            .padding(.horizontal, 14).frame(minHeight: 34)
            .background(RoundedRectangle(cornerRadius: 3).fill(enabled ? Palette.safelight : Color(hex: 0x3A2A24)))
            .overlay(RoundedRectangle(cornerRadius: 3).stroke(enabled ? Color(hex: 0xFF8A66) : .clear, lineWidth: 1))
            .shadow(color: .black.opacity(0.5), radius: 2, y: 2)
            .offset(y: configuration.isPressed ? 1 : 0)
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Fonts.barlow(14, .semibold)).tracking(1.1)
            .textCase(.uppercase)
            .foregroundStyle(enabled ? Palette.ink : Palette.faint)
            .padding(.horizontal, 14).frame(minHeight: 34)
            .background(RoundedRectangle(cornerRadius: 3).fill(LinearGradient(colors: [Color(hex: 0x322E2B), Color(hex: 0x201E1C)], startPoint: .top, endPoint: .bottom)))
            .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(hex: 0x56504B), lineWidth: 1))
            .shadow(color: .black.opacity(0.5), radius: 2, y: 2)
            .offset(y: configuration.isPressed ? 1 : 0)
    }
}

extension ButtonStyle where Self == PrimaryButtonStyle { static var primary: PrimaryButtonStyle { .init() } }
extension ButtonStyle where Self == SecondaryButtonStyle { static var secondary: SecondaryButtonStyle { .init() } }

/// Etichetta tonda: stato di un rullino, ruolo, tipo di pellicola.
struct ChipView: View {
    let text: String
    var active = false
    var body: some View {
        Text(text.uppercased())
            .font(Fonts.mono(9, .semibold)).tracking(0.6)
            .foregroundStyle(active ? Palette.safelight : Palette.muted)
            .padding(.horizontal, 9).padding(.vertical, 3)
            .background(Capsule().fill(active ? Palette.safelightDark : .clear))
            .overlay(Capsule().stroke(active ? Color(hex: 0xA6472C) : Color(hex: 0x4A4541), lineWidth: 1))
    }
}

/// Intestazione di pagina: sopratitolo arancione in mono e titolo grande.
struct PageHeading<Trailing: View>: View {
    let kicker: String
    let title: String
    @ViewBuilder var trailing: () -> Trailing
    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(kicker.uppercased()).font(Fonts.mono(10)).tracking(1.4).foregroundStyle(Palette.safelight)
                Text(title).font(Fonts.barlow(32, .semibold)).foregroundStyle(Palette.ink)
            }
            Spacer()
            trailing()
        }
    }
}

extension PageHeading where Trailing == EmptyView {
    init(kicker: String, title: String) { self.init(kicker: kicker, title: title) { EmptyView() } }
}

/// Fondo delle pagine: piano scuro punteggiato.
struct DottedPage: View {
    var base = Color(hex: 0x171514)
    var dot = Color(hex: 0x3B3532)
    var step: CGFloat = 5
    var body: some View {
        Canvas { ctx, size in
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(base))
            var y: CGFloat = 0
            while y < size.height {
                var x: CGFloat = 0
                while x < size.width {
                    ctx.fill(Path(ellipseIn: CGRect(x: x, y: y, width: 1.2, height: 1.2)), with: .color(dot))
                    x += step
                }
                y += step
            }
        }
        .drawingGroup()
        .ignoresSafeArea()
    }
}

extension View {
    /// Scheda scura con bordo, come i pannelli del design.
    func card(selected: Bool = false) -> some View {
        self
            .background(RoundedRectangle(cornerRadius: 4).fill(LinearGradient(colors: [Color(hex: 0x262321), Color(hex: 0x1C1A19)], startPoint: .top, endPoint: .bottom)))
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(selected ? Palette.safelight : Palette.line, lineWidth: 1))
    }
}

/// Colori delle scatole: arancione per le colore, crema per il bianco e nero, blu per le diapositive.
enum BoxTone {
    case orange, cream, blue
    var face: Color { switch self { case .orange: Color(hex: 0xDF5C36); case .cream: Color(hex: 0xD8CCB0); case .blue: Color(hex: 0x4C8FA6) } }
    var ink: Color { switch self { case .orange: Color(hex: 0x1B0E09); case .cream: Color(hex: 0x1B1A18); case .blue: Color(hex: 0x0E1A1F) } }
    static func of(bw: Bool, slide: Bool) -> BoxTone { bw ? .cream : (slide ? .blue : .orange) }
}

/// La scatola della pellicola, disegnata: fascia, marchio, nome, ISO.
struct FilmBox: View {
    let name: String
    let iso: Int
    let tone: BoxTone
    var exposures = 36
    var compact = false
    var body: some View {
        let w: CGFloat = compact ? 66 : 132, h: CGFloat = compact ? 76 : 150
        ZStack(alignment: .topLeading) {
            Rectangle().fill(Color.black.opacity(0.8)).offset(x: 5, y: 5)
            VStack(alignment: .leading, spacing: 0) {
                Text("SLOW—FI").font(Fonts.mono(compact ? 7 : 9, .semibold)).tracking(1.2).foregroundStyle(tone.ink.opacity(0.75))
                    .padding(.leading, 7).padding(.top, 6)
                Spacer().frame(height: compact ? 8 : 12)
                Rectangle().fill(Color(hex: 0x1B1A18)).frame(height: compact ? 5 : 7)
                Rectangle().fill(Color(hex: 0xF2C46B)).frame(height: 2)
                if !compact {
                    Text(name.uppercased()).font(Fonts.barlow(22, .bold)).foregroundStyle(tone.ink).lineLimit(1).padding(.leading, 8).padding(.top, 6)
                }
                Text("\(iso)").font(Fonts.mono(compact ? 13 : 20, .semibold)).foregroundStyle(tone.ink).padding(.leading, 7).padding(.top, compact ? 4 : 0)
                if !compact { Text("35 mm · \(exposures) exp.").font(.system(size: 9)).foregroundStyle(tone.ink.opacity(0.8)).padding(.leading, 8) }
                Spacer(minLength: 0)
            }
            .frame(width: w, height: h, alignment: .topLeading)
            .background(tone.face)
            .overlay(Rectangle().stroke(tone.face.opacity(0.7), lineWidth: 2))
        }
        .frame(width: w + 5, height: h + 5, alignment: .topLeading)
        .rotationEffect(.degrees(-1.5))
    }
}

/// Il caricatore 35 mm visto di lato: tappo, corpo con etichetta, coda di pellicola.
struct Cartridge: View {
    let name: String
    let iso: Int
    let bw: Bool
    var exposures = 36
    var scale: CGFloat = 1
    var body: some View {
        HStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 3)
                .fill(LinearGradient(colors: [Color(hex: 0x272523), Color(hex: 0xB1AAA4), Color(hex: 0x3B3735)], startPoint: .leading, endPoint: .trailing))
                .frame(width: 9 * scale, height: 60 * scale)
            VStack(spacing: 0) {
                Text(name.uppercased()).font(Fonts.barlow(10 * scale, .bold)).tracking(1).foregroundStyle(Palette.safelight).lineLimit(1).minimumScaleFactor(0.6).padding(.horizontal, 3)
                Text("\(iso)").font(Fonts.mono(16 * scale)).foregroundStyle(Palette.ink)
                Text((bw ? "B&W" : "COLOR") + " / \(exposures)").font(.system(size: 7 * scale)).foregroundStyle(Palette.muted)
            }
            .frame(width: 80 * scale, height: 54 * scale)
            .background(RoundedRectangle(cornerRadius: 4).fill(LinearGradient(colors: [Color(hex: 0x6B6662), Color(hex: 0x252321), Color(hex: 0x33302E), Color(hex: 0x807A75)], startPoint: .leading, endPoint: .trailing)))
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(hex: 0x0B0A0A), lineWidth: 1))
            UnevenRoundedRectangle(topLeadingRadius: 0, bottomLeadingRadius: 0, bottomTrailingRadius: 6, topTrailingRadius: 2)
                .fill(Color(hex: 0x302A24)).frame(width: 34 * scale, height: 38 * scale)
        }
    }
}

/// Scritta incisa piccola, come sul corpo macchina.
struct EngravedNote: View {
    let text: String
    var color = Color(hex: 0x756E68)
    var body: some View {
        Text(text.uppercased()).font(Fonts.barlow(10)).tracking(1.4).foregroundStyle(color).multilineTextAlignment(.center)
            .shadow(color: .black, radius: 0, y: -1)
    }
}

/// Etichetta nel mirino: mono piccolo, fondo scuro, bordo sottile.
struct ViewfinderBadge: View {
    let text: String
    var color = Color(hex: 0xF2EEE9)
    var round = false
    var body: some View {
        let r: CGFloat = round ? 20 : 2
        Text(text).font(Fonts.mono(10)).foregroundStyle(color).multilineTextAlignment(.center)
            .padding(.horizontal, 7).padding(.vertical, 4)
            .background(RoundedRectangle(cornerRadius: r).fill(Color(hex: 0x0C0B0A, alpha: 0.67)))
            .overlay(RoundedRectangle(cornerRadius: r).stroke(Color(hex: 0x8D847A, alpha: 0.33), lineWidth: 1))
    }
}

/// Metallo scuro della calotta: righe fini e riflessi verticali.
struct DarkMetal: View {
    var body: some View {
        Canvas { ctx, size in
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .linearGradient(
                Gradient(stops: [.init(color: Color(hex: 0x171615), location: 0), .init(color: Color(hex: 0x34312F), location: 0.2),
                                 .init(color: Color(hex: 0x1D1B1A), location: 0.52), .init(color: Color(hex: 0x34312F), location: 0.78),
                                 .init(color: Color(hex: 0x181716), location: 1)]),
                startPoint: .zero, endPoint: CGPoint(x: size.width, y: 0)))
            var y: CGFloat = 0
            while y < size.height {
                var p = Path(); p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: size.width, y: y))
                ctx.stroke(p, with: .color(.white.opacity(0.03)), lineWidth: 1); y += 3
            }
            ctx.fill(Path(CGRect(x: 0, y: size.height - 3, width: size.width, height: 3)), with: .color(Color(hex: 0x090909)))
            var hl = Path(); hl.move(to: CGPoint(x: 0, y: size.height - 4)); hl.addLine(to: CGPoint(x: size.width, y: size.height - 4))
            ctx.stroke(hl, with: .color(Color(hex: 0x4B4540)), lineWidth: 1)
        }
    }
}

/// Corpo in similpelle punteggiata.
struct DottedLeather: View {
    var body: some View {
        Canvas { ctx, size in
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(hex: 0x171615)))
            var y: CGFloat = 0
            while y < size.height {
                var x: CGFloat = 0
                while x < size.width { ctx.fill(Path(ellipseIn: CGRect(x: x, y: y, width: 1.4, height: 1.4)), with: .color(Color(hex: 0x3C3734))); x += 4 }
                y += 4
            }
            ctx.fill(Path(CGRect(x: 0, y: 0, width: size.width, height: 10)),
                     with: .linearGradient(Gradient(colors: [.black.opacity(0.67), .clear]), startPoint: .zero, endPoint: CGPoint(x: 0, y: 10)))
        }
        .drawingGroup()
    }
}
