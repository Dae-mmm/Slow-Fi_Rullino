import SwiftUI

/* Pezzi meccanici della fotocamera, disegnati come sull'app Android. */

struct Engraving: View {
    let text: String
    var size: CGFloat = 11
    var body: some View {
        Text(text)
            .font(.system(size: size, weight: .bold, design: .rounded))
            .tracking(1.5)
            .foregroundStyle(Color(hex: 0x3E3A36))
            .shadow(color: .white.opacity(0.35), radius: 0, x: 0, y: 1)
    }
}

/// Alluminio spazzolato della calotta.
struct BrushedMetal: View {
    var body: some View {
        Canvas { ctx, size in
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .linearGradient(
                Gradient(colors: [Color(hex: 0xD9D5CF), Color(hex: 0xB3AEA7), Color(hex: 0x8E8983)]),
                startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
            var rng = SeededRandom(seed: 7)
            var y: CGFloat = 0
            while y < size.height {
                var p = Path(); p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: size.width, y: y))
                ctx.stroke(p, with: .color(.white.opacity(0.05 + rng.next() * 0.06)), lineWidth: 1)
                y += 2 + rng.next() * 3
            }
            var b = Path(); b.move(to: CGPoint(x: 0, y: size.height - 1)); b.addLine(to: CGPoint(x: size.width, y: size.height - 1))
            ctx.stroke(b, with: .color(Color(hex: 0x5E5A55)), lineWidth: 2)
        }
    }
}

/// Similpelle del corpo, con la sua grana.
struct Leatherette: View {
    var body: some View {
        Canvas { ctx, size in
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(hex: 0x1B1A19)))
            var rng = SeededRandom(seed: 11)
            let n = min(Int(size.width * size.height / 90), 9000)
            for _ in 0..<n {
                let c = rng.next() > 0.5 ? Color(hex: 0x242322) : Color(hex: 0x131212)
                let r = 1 + rng.next() * 1.6
                let x = rng.next() * size.width, y = rng.next() * size.height
                ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)), with: .color(c))
            }
        }
        .drawingGroup()
    }
}

struct SeededRandom {
    var state: UInt64
    init(seed: UInt64) { state = seed &* 0x9E3779B97F4A7C15 | 1 }
    mutating func next() -> CGFloat {
        state ^= state << 13; state ^= state >> 7; state ^= state << 17
        return CGFloat(state % 1_000_000) / 1_000_000
    }
}

/* ---------------- Contapose ---------------- */

struct FrameCounter: View {
    let number: Int
    let total: Int
    /// Durante il riavvolgimento: numero che sta arrivando dal basso e avanzamento del giro.
    var rollTo: Int? = nil
    var rollProgress: CGFloat = 0

    var body: some View {
        ZStack {
            Circle().fill(RadialGradient(colors: [Color(hex: 0x332E2B), Color(hex: 0x080707)], center: UnitPoint(x: 0.45, y: 0.35), startRadius: 0, endRadius: 30))
            if let to = rollTo {
                let p = min(max(rollProgress, 0), 1)
                digit(number).offset(y: -30 * p)
                digit(to).offset(y: 30 * (1 - p))
            } else {
                digit(number)
                    .id(number)
                    .transition(.asymmetric(insertion: .move(edge: .bottom), removal: .move(edge: .top)))
            }
        }
        .frame(width: 55, height: 55)
        .clipShape(Circle())
        .overlay(Circle().stroke(Color(hex: 0x111111), lineWidth: 4))
        .animation(rollTo == nil ? .easeOut(duration: 0.26) : nil, value: number)
    }

    private func digit(_ n: Int) -> some View {
        Text(n <= 0 ? "S" : String(format: "%02d", n))
            .font(Fonts.mono(25))
            .foregroundStyle(n >= total ? Palette.safelight : Palette.ink)
    }
}

/* ---------------- Pulsante R ---------------- */

struct ReleaseButton: View {
    let on: Bool
    let enabled: Bool
    let toggle: () -> Void
    var body: some View {
        Text("R")
            .font(Fonts.mono(13))
            .foregroundStyle(on ? Palette.safelight : Palette.ink)
            .frame(width: 32, height: 32)
            .background(Circle().fill(RadialGradient(colors: [Color(hex: 0x4A4541), Color(hex: 0x161514)], center: UnitPoint(x: 0.4, y: 0.35), startRadius: 0, endRadius: 22)))
            .overlay(Circle().stroke(on ? Color(hex: 0x953B23) : Color(hex: 0x050505), lineWidth: 2))
            .contentShape(Circle())
            .onTapGesture { if enabled { Haptics.tick(); toggle() } else { Haptics.reject() } }
    }
}

/* ---------------- Manovella di riavvolgimento, vista dall'alto ---------------- */

/// Si gira col dito in senso orario: ogni giro completo chiama `onFullTurn`.
struct RewindCrank: View {
    let enabled: Bool
    let highlight: Bool
    var resetKey: AnyHashable = 0
    var diameter: CGFloat = 112
    let onProgress: (CGFloat) -> Void
    let onFullTurn: () -> Void

    @State private var angle: CGFloat = 0
    @State private var turn: CGFloat = 0
    @State private var quarter = 0
    @State private var last: CGPoint?

    var body: some View {
        Canvas { ctx, size in
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            let r0 = min(size.width, size.height) / 2 - 2
            let a = angle * .pi / 180
            let crank: Color = !enabled ? Color(hex: 0x6A6560) : (highlight ? Palette.safelight : Color(hex: 0xCFCAC4))
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - r0, y: c.y - r0, width: 2 * r0, height: 2 * r0)), with: .color(Color(hex: 0x1E1C1B)))
            for i in 0..<48 {
                let t = a + CGFloat(i) * 2 * .pi / 48
                var p = Path()
                p.move(to: CGPoint(x: c.x + cos(t) * (r0 - 7), y: c.y + sin(t) * (r0 - 7)))
                p.addLine(to: CGPoint(x: c.x + cos(t) * (r0 - 1), y: c.y + sin(t) * (r0 - 1)))
                ctx.stroke(p, with: .color(Color(hex: 0x4A4542)), lineWidth: 1.5)
            }
            let ri = r0 - 8
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - ri, y: c.y - ri, width: 2 * ri, height: 2 * ri)),
                     with: .radialGradient(Gradient(colors: [Color(hex: 0x55504C), Color(hex: 0x2A2725)]), center: c, startRadius: 0, endRadius: r0 * 0.85))
            var arc = Path()
            arc.addArc(center: c, radius: r0 * 0.62, startAngle: .degrees(-150), endAngle: .degrees(-30), clockwise: false)
            ctx.stroke(arc, with: .color(Color(hex: 0xCFCAC4, alpha: 0.4)), lineWidth: 1.5)
            let reach = r0 * 0.66
            let tip = CGPoint(x: c.x + reach * cos(a), y: c.y + reach * sin(a))
            var arm = Path(); arm.move(to: c); arm.addLine(to: tip)
            ctx.stroke(arm, with: .color(crank), style: StrokeStyle(lineWidth: 8, lineCap: .round))
            ctx.fill(Path(ellipseIn: CGRect(x: tip.x - 12, y: tip.y - 12, width: 24, height: 24)), with: .color(Color(hex: 0x0C0B0B)))
            ctx.fill(Path(ellipseIn: CGRect(x: tip.x - 10, y: tip.y - 10, width: 20, height: 20)), with: .color(crank))
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - 7, y: c.y - 7, width: 14, height: 14)), with: .color(Color(hex: 0xB9B4AE)))
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - 3, y: c.y - 3, width: 6, height: 6)), with: .color(Color(hex: 0x3A3634)))
        }
        .frame(width: diameter, height: diameter)
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { v in
                    guard enabled else {
                        if last == nil { Haptics.reject(); last = v.location }
                        return
                    }
                    let c = CGPoint(x: diameter / 2, y: diameter / 2)
                    defer { last = v.location }
                    guard let prev = last else { return }
                    var d = (atan2(v.location.y - c.y, v.location.x - c.x) - atan2(prev.y - c.y, prev.x - c.x)) * 180 / .pi
                    if d > 180 { d -= 360 }
                    if d < -180 { d += 360 }
                    guard d > 0 else { return } // si riavvolge solo in senso orario
                    angle += d
                    turn += d
                    let q = Int(turn / 90)
                    if q > quarter { Haptics.tick(); quarter = q }
                    if turn >= 360 {
                        turn -= 360
                        quarter = 0
                        Haptics.confirm()
                        onFullTurn()
                    }
                    onProgress(turn / 360)
                }
                .onEnded { _ in last = nil }
        )
        .onChange(of: resetKey) { _, _ in turn = 0; quarter = 0; onProgress(0) }
    }
}

/* ---------------- Leva di carica e pulsante di scatto, visti dall'alto ---------------- */

private let leverDeg: CGFloat = 120

/// Tocca il pulsante al centro per scattare; trascina in cerchio (da sinistra verso il basso)
/// per caricare la leva: a fine corsa avanza la pellicola e la molla la riporta a riposo.
struct LeverShutter: View {
    let armed: Bool
    let leverEnabled: Bool
    var side: CGFloat = 150
    let onShoot: () -> Void
    let onAdvanced: () -> Void
    let onProgress: (CGFloat) -> Void

    @State private var progress: CGFloat = 0
    @State private var pressed = false
    @State private var mode: Mode = .idle
    @State private var last: CGPoint?
    @State private var lastTick = 0

    private enum Mode { case idle, shutter, lever, blocked }

    private var pivot: CGPoint { CGPoint(x: side * 0.6, y: side * 0.42) }

    var body: some View {
        Canvas { ctx, size in
            let pivot = CGPoint(x: size.width * 0.6, y: size.height * 0.42)
            let armLen = size.width * 0.42
            let a = (180 - progress * leverDeg) * .pi / 180
            let tip = CGPoint(x: pivot.x + armLen * cos(a), y: pivot.y + armLen * sin(a))
            let metal = leverEnabled ? Color(hex: 0xE4E0DA) : Color(hex: 0x9A958F)
            if leverEnabled {
                var arc = Path()
                arc.addArc(center: pivot, radius: armLen, startAngle: .degrees(180 - leverDeg), endAngle: .degrees(180), clockwise: false)
                ctx.stroke(arc, with: .color(Color(hex: 0xCFCAC4, alpha: 0.25)), lineWidth: 2)
            }
            let nx = -sin(a), ny = cos(a)
            var arm = Path()
            arm.move(to: CGPoint(x: pivot.x + nx * 9, y: pivot.y + ny * 9))
            arm.addLine(to: CGPoint(x: tip.x + nx * 5, y: tip.y + ny * 5))
            arm.addLine(to: CGPoint(x: tip.x - nx * 5, y: tip.y - ny * 5))
            arm.addLine(to: CGPoint(x: pivot.x - nx * 9, y: pivot.y - ny * 9))
            arm.closeSubpath()
            ctx.fill(arm, with: .linearGradient(Gradient(colors: [metal, Color(hex: 0x8F8A84)]), startPoint: pivot, endPoint: tip))
            ctx.fill(Path(ellipseIn: CGRect(x: tip.x - 12, y: tip.y - 12, width: 24, height: 24)), with: .color(Color(hex: 0x0C0B0B)))
            ctx.fill(Path(ellipseIn: CGRect(x: tip.x - 10, y: tip.y - 10, width: 20, height: 20)),
                     with: .radialGradient(Gradient(colors: [Color(hex: 0x4A4542), Color(hex: 0x1C1A19)]), center: tip, startRadius: 0, endRadius: 11))
            if leverEnabled {
                ctx.fill(Path(ellipseIn: CGRect(x: tip.x - 3, y: tip.y - 3, width: 6, height: 6)), with: .color(Palette.safelight))
            }
            // Pulsante di scatto (design): anello zigrinato, cupola cromata, anello arancione quando è armato.
            let outer: CGFloat = 30
            if armed {
                ctx.fill(Path(ellipseIn: CGRect(x: pivot.x - outer - 5, y: pivot.y - outer - 5, width: 2 * outer + 10, height: 2 * outer + 10)), with: .color(Palette.safelight.opacity(0.35)))
            }
            ctx.fill(Path(ellipseIn: CGRect(x: pivot.x - outer - 2, y: pivot.y - outer - 2, width: 2 * outer + 4, height: 2 * outer + 4)), with: .color(Color(hex: 0x7B756F)))
            ctx.fill(Path(ellipseIn: CGRect(x: pivot.x - outer, y: pivot.y - outer, width: 2 * outer, height: 2 * outer)), with: .color(Color(hex: 0x151413)))
            let ringR = outer - 4
            for i in 0..<60 {
                let t = CGFloat(i) * .pi * 2 / 60
                var l = Path()
                l.move(to: CGPoint(x: pivot.x + cos(t) * (ringR - 6), y: pivot.y + sin(t) * (ringR - 6)))
                l.addLine(to: CGPoint(x: pivot.x + cos(t) * ringR, y: pivot.y + sin(t) * ringR))
                ctx.stroke(l, with: .color(i % 2 == 0 ? Color(hex: 0xBAB4AF) : Color(hex: 0x5F5A56)), lineWidth: 2.2)
            }
            if armed {
                ctx.stroke(Path(ellipseIn: CGRect(x: pivot.x - ringR - 1, y: pivot.y - ringR - 1, width: 2 * ringR + 2, height: 2 * ringR + 2)), with: .color(Palette.safelight), lineWidth: 3)
            }
            let br = (ringR - 7) * (pressed ? 0.94 : 1)
            ctx.fill(Path(ellipseIn: CGRect(x: pivot.x - br, y: pivot.y - br, width: 2 * br, height: 2 * br)),
                     with: .radialGradient(Gradient(colors: [pressed ? Color(hex: 0xC9C4BF) : Color(hex: 0xF0ECE8), Color(hex: 0x77726E), Color(hex: 0x252321)]),
                                           center: CGPoint(x: pivot.x - br * 0.2, y: pivot.y - br * 0.4), startRadius: 0, endRadius: br * 1.6))
        }
        .frame(width: side, height: side)
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { v in
                    if mode == .idle {
                        let d = hypot(v.startLocation.x - pivot.x, v.startLocation.y - pivot.y)
                        if d <= 26 { mode = .shutter; pressed = true; return }
                        if !leverEnabled { mode = .blocked; Haptics.reject(); return }
                        mode = .lever
                        last = v.location
                        lastTick = Int(progress * 10)
                        return
                    }
                    guard mode == .lever, let prev = last else { return }
                    var d = (atan2(v.location.y - pivot.y, v.location.x - pivot.x) - atan2(prev.y - pivot.y, prev.x - pivot.x)) * 180 / .pi
                    if d > 180 { d -= 360 }
                    if d < -180 { d += 360 }
                    last = v.location
                    progress = min(max(progress - d / leverDeg, 0), 1)
                    onProgress(progress)
                    let t = Int(progress * 10)
                    if t > lastTick { Haptics.tick() }
                    lastTick = t
                }
                .onEnded { v in
                    switch mode {
                    case .shutter:
                        pressed = false
                        if hypot(v.location.x - pivot.x, v.location.y - pivot.y) <= 40 { onShoot() }
                    case .lever:
                        let complete = progress >= 0.95
                        if complete { Haptics.confirm(); onAdvanced() }
                        withAnimation(.spring(response: 0.35, dampingFraction: 0.6)) { progress = 0 }
                        onProgress(0)
                    default: break
                    }
                    mode = .idle
                    last = nil
                }
        )
    }
}

let focalMarks: [CGFloat] = [24, 28, 35, 50, 85, 135]

/* ---------------- Ghiera dei tempi, vista di profilo ---------------- */

/// nil = automatico (A), poi da 1/1000 a 1 secondo.
let shutterSpeeds: [Double?] = [nil, 1.0 / 1000, 1.0 / 500, 1.0 / 250, 1.0 / 125, 1.0 / 60, 1.0 / 30, 1.0 / 15, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1]

func shutterMark(_ s: Double?) -> String {
    guard let s else { return "A" }
    return s >= 1 ? "1" : "\(Int((1 / s).rounded()))"
}

func shutterLabel(_ s: Double?) -> String {
    guard let s else { return "A" }
    return s >= 1 ? "1 s" : "1/\(Int((1 / s).rounded()))"
}
