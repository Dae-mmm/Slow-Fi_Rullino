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
            Circle().fill(Color(hex: 0x0C0B0B))
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
        .frame(width: 46, height: 46)
        .clipShape(Circle())
        .overlay(Circle().stroke(Color(hex: 0x8E8983), lineWidth: 3))
        .animation(rollTo == nil ? .easeOut(duration: 0.26) : nil, value: number)
    }

    private func digit(_ n: Int) -> some View {
        Text(n <= 0 ? "S" : "\(n)")
            .font(.system(size: 18, weight: .bold, design: .monospaced))
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
            .font(.system(size: 13, weight: .bold, design: .monospaced))
            .foregroundStyle(on ? Color(hex: 0x1A1918) : Palette.ink)
            .frame(width: 30, height: 30)
            .background(Circle().fill(on ? Palette.safelight : Color(hex: 0x2A2725)))
            .overlay(Circle().stroke(Color(hex: 0x8E8983), lineWidth: 2))
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
            ctx.fill(Path(ellipseIn: CGRect(x: pivot.x - 27, y: pivot.y - 27, width: 54, height: 54)), with: .color(Color(hex: 0x1E1C1B)))
            ctx.fill(Path(ellipseIn: CGRect(x: pivot.x - 25, y: pivot.y - 25, width: 50, height: 50)),
                     with: .radialGradient(Gradient(colors: [Color(hex: 0xE9E5DF), Color(hex: 0x8A857F)]), center: pivot, startRadius: 0, endRadius: 26))
            let br: CGFloat = pressed ? 13.5 : 15
            ctx.fill(Path(ellipseIn: CGRect(x: pivot.x - br - 2, y: pivot.y - br - 2, width: 2 * br + 4, height: 2 * br + 4)), with: .color(Color(hex: 0x5A5550)))
            ctx.fill(Path(ellipseIn: CGRect(x: pivot.x - br, y: pivot.y - br, width: 2 * br, height: 2 * br)),
                     with: .radialGradient(Gradient(colors: [pressed ? Color(hex: 0xB9B4AE) : Color(hex: 0xF4F1EC), Color(hex: 0x8F8A84)]),
                                           center: CGPoint(x: pivot.x - 4, y: pivot.y - 4), startRadius: 0, endRadius: br * 1.4))
            if armed {
                ctx.stroke(Path(ellipseIn: CGRect(x: pivot.x - br - 4, y: pivot.y - br - 4, width: 2 * br + 8, height: 2 * br + 8)),
                           with: .color(Palette.safelight), lineWidth: 2)
            }
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

/* ---------------- Ghiera dello zoom, vista di profilo ---------------- */

let focalMarks: [CGFloat] = [24, 28, 35, 50, 85, 135]

struct ZoomRing: View {
    @Binding var focal: CGFloat
    let maxFocal: CGFloat
    let enabled: Bool
    @State private var startFocal: CGFloat?

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let pxPerLog: CGFloat = w * 0.9 / log(135 / 24)
            Canvas { ctx, size in
                ctx.fill(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 6),
                         with: .linearGradient(Gradient(colors: [Color(hex: 0x2A2725), Color(hex: 0x0E0D0D), Color(hex: 0x2A2725)]),
                                               startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
                let center = size.width / 2
                let off = log(focal / 24) * pxPerLog
                var x = center - off - 400
                while x < size.width {
                    var p = Path(); p.move(to: CGPoint(x: x, y: 2)); p.addLine(to: CGPoint(x: x, y: 8))
                    ctx.stroke(p, with: .color(Color(hex: 0x3E3A36)), lineWidth: 1)
                    x += 6
                }
                for m in focalMarks where m <= max(maxFocal, 24) + 0.5 {
                    let mx = center - off + log(m / 24) * pxPerLog
                    ctx.draw(Text("\(Int(m))").font(.system(size: 11, design: .monospaced)).foregroundColor(Palette.ink),
                             at: CGPoint(x: mx, y: size.height * 0.62))
                }
                var idx = Path(); idx.move(to: CGPoint(x: center, y: 0)); idx.addLine(to: CGPoint(x: center, y: 10))
                ctx.stroke(idx, with: .color(Palette.safelight), lineWidth: 2)
            }
            .opacity(enabled ? 1 : 0.5)
            .gesture(
                DragGesture(minimumDistance: 2)
                    .onChanged { v in
                        guard enabled else { return }
                        let s = startFocal ?? focal
                        if startFocal == nil { startFocal = focal }
                        var f = s * exp(-v.translation.width / pxPerLog)
                        f = min(max(f, 24), max(maxFocal, 24))
                        if let near = focalMarks.first(where: { abs(log($0 / f)) < 0.04 }) { f = near }
                        if Int(f) != Int(focal) && focalMarks.contains(f) { Haptics.tick() }
                        focal = f
                    }
                    .onEnded { _ in startFocal = nil }
            )
        }
        .frame(height: 30)
    }
}

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

/// Si trascina a destra o a sinistra e scatta da un tempo all'altro.
struct ShutterDial: View {
    @Binding var index: Int
    @State private var pos: CGFloat?
    @State private var start: CGFloat = 0
    private let step: CGFloat = 40

    var body: some View {
        let last = CGFloat(shutterSpeeds.count - 1)
        let p = pos ?? CGFloat(index)
        Canvas { ctx, size in
            ctx.fill(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 5),
                     with: .linearGradient(Gradient(colors: [Color(hex: 0x151413), Color(hex: 0x3B3836), Color(hex: 0x1A1918)]),
                                           startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
            var x = -(p * step).truncatingRemainder(dividingBy: 5)
            while x < size.width {
                var l = Path(); l.move(to: CGPoint(x: x, y: size.height * 0.72)); l.addLine(to: CGPoint(x: x, y: size.height - 2))
                ctx.stroke(l, with: .color(Color(hex: 0x0B0A0A, alpha: 0.8)), lineWidth: 1.8)
                x += 5
            }
            let center = size.width / 2
            for (i, s) in shutterSpeeds.enumerated() {
                let mx = center + (CGFloat(i) - p) * step
                guard mx > -20, mx < size.width + 20 else { continue }
                let near = abs(mx - center) < step / 2
                let fade = 1 - 0.6 * abs(mx - center) / center
                let color: Color = near ? Palette.safelight : (s == nil ? Color(hex: 0xFFD36B) : Palette.ink)
                ctx.draw(Text(shutterMark(s)).font(.system(size: 11, weight: .bold, design: .monospaced)).foregroundColor(color.opacity(near ? 1 : fade)),
                         at: CGPoint(x: mx, y: size.height * 0.42))
            }
            var tri = Path()
            tri.move(to: CGPoint(x: center - 4, y: 0)); tri.addLine(to: CGPoint(x: center + 4, y: 0)); tri.addLine(to: CGPoint(x: center, y: 5)); tri.closeSubpath()
            ctx.fill(tri, with: .color(Palette.safelight))
        }
        .frame(height: 34)
        .gesture(
            DragGesture(minimumDistance: 2)
                .onChanged { v in
                    if pos == nil { start = CGFloat(index); pos = start }
                    let np = min(max(start - v.translation.width / step, 0), last)
                    let before = index
                    pos = np
                    let now = Int(np.rounded())
                    if now != before { Haptics.tick(); index = now }
                }
                .onEnded { _ in
                    withAnimation(.spring(response: 0.25, dampingFraction: 0.8)) { pos = nil }
                }
        )
        .accessibilityLabel(L("shutter_speed"))
        .accessibilityValue(shutterLabel(shutterSpeeds[min(max(index, 0), shutterSpeeds.count - 1)]))
    }
}
