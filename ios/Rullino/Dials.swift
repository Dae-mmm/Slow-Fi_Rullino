import SwiftUI

/* Ghiere a cilindro: i numeri sono stampati sul metallo e girano via ai lati
   come su un anello vero, senza sovrapporsi. */

private struct DialMark {
    var arc: CGFloat
    var text: String
    var faded = false
    var dot = false
}

/// Disegna un anello zigrinato visto di profilo; scroll = quanto è girato (punti di circonferenza).
private func drawCylinderDial(_ ctx: GraphicsContext, _ size: CGSize, scroll: CGFloat, marks: [DialMark]) {
    let w = size.width, h = size.height, c = w / 2, r = w / 2
    ctx.fill(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 7), with: .color(Color(hex: 0x0A0909)))
    var inner = ctx
    inner.clip(to: Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 7))

    // Zigrinatura sopra e sotto, in prospettiva.
    let pitch: CGFloat = 4, band = h * 0.24
    var s = -scroll.truncatingRemainder(dividingBy: pitch) - pitch * (r * 1.6 / pitch).rounded(.down)
    while s < r * 1.6 {
        let a = s / r
        if abs(a) < .pi / 2 {
            let x = c + r * sin(a), k = cos(a)
            let lw = max(pitch * 0.45 * k, 0.6)
            let shade = Color(hex: 0x3A3532).opacity(0.25 + 0.75 * k)
            inner.fill(Path(CGRect(x: x - lw / 2, y: 1, width: lw, height: band)), with: .color(shade))
            inner.fill(Path(CGRect(x: x - lw / 2, y: h - band - 1, width: lw, height: band)), with: .color(shade))
        }
        s += pitch
    }
    // Fascia liscia dove sono stampati i numeri.
    inner.fill(Path(CGRect(x: 0, y: band + 1, width: w, height: h - 2 * band - 2)),
               with: .linearGradient(Gradient(colors: [Color(hex: 0x1E1C1B), Color(hex: 0x161514), Color(hex: 0x121110)]),
                                     startPoint: CGPoint(x: 0, y: band), endPoint: CGPoint(x: 0, y: h - band)))
    for m in marks {
        let a = (m.arc - scroll) / r
        guard abs(a) < .pi / 2 * 0.97 else { continue }
        let x = c + r * sin(a), k = cos(a)
        // Stampato: crema opaco, nessun colore sul valore scelto; i lontani si perdono nel buio.
        let alpha = (m.faded ? 0.35 : 1) * (0.15 + 0.85 * k * k)
        var t = inner
        t.translateBy(x: x, y: h / 2)
        t.scaleBy(x: max(k, 0.05), y: 1)
        t.draw(Text(m.text).font(Fonts.mono(12)).foregroundColor(Color(red: 226 / 255, green: 219 / 255, blue: 208 / 255).opacity(alpha)), at: .zero)
        if m.dot {
            inner.fill(Path(ellipseIn: CGRect(x: x - 1.6, y: h - band - 4.6, width: 3.2, height: 3.2)), with: .color(Palette.safelight.opacity(k)))
        }
    }
    // Luce in alto, ombra in basso, bordi del cilindro che girano via.
    inner.fill(Path(CGRect(x: 4, y: 1, width: w - 8, height: 1.5)), with: .color(Color(hex: 0x6A645F)))
    inner.fill(Path(CGRect(x: 4, y: h - 3, width: w - 8, height: 3)), with: .color(Color(hex: 0x050505)))
    inner.fill(Path(CGRect(x: 0, y: 0, width: w * 0.16, height: h)),
               with: .linearGradient(Gradient(colors: [.black.opacity(0.93), .clear]), startPoint: .zero, endPoint: CGPoint(x: w * 0.16, y: 0)))
    inner.fill(Path(CGRect(x: w * 0.84, y: 0, width: w * 0.16, height: h)),
               with: .linearGradient(Gradient(colors: [.clear, .black.opacity(0.93)]), startPoint: CGPoint(x: w * 0.84, y: 0), endPoint: CGPoint(x: w, y: 0)))
    // Indice fisso: una barretta sottile.
    inner.fill(Path(CGRect(x: c - 1, y: 0, width: 2, height: h * 0.3)), with: .color(Palette.safelight))
}

/* ---------------- Ghiera dei tempi ---------------- */

/// Con lo scatto elastico: trascinandola resta "attaccata" al valore, poi salta al successivo;
/// al rilascio torna in posizione con un piccolo rimbalzo. recommended = tempo consigliato dall'esposimetro.
struct ShutterDial: View {
    @Binding var index: Int
    var recommended: Int?
    @State private var shown: CGFloat?
    @State private var raw: CGFloat = 0
    private let step: CGFloat = 46

    private func magnet(_ p: CGFloat) -> CGFloat {
        let n = p.rounded(), d = p - n
        return n + 0.5 * (d < 0 ? -1 : 1) * pow(2 * abs(d), 2.6)
    }

    var body: some View {
        let last = CGFloat(shutterSpeeds.count - 1)
        let p = shown ?? CGFloat(index)
        Canvas { ctx, size in
            let marks = shutterSpeeds.enumerated().map { i, s in
                DialMark(arc: CGFloat(i) * step, text: shutterMark(s), dot: i == recommended && i != index)
            }
            drawCylinderDial(ctx, size, scroll: p * step, marks: marks)
        }
        .frame(height: 40)
        .gesture(
            DragGesture(minimumDistance: 2)
                .onChanged { v in
                    if shown == nil { rawStart = CGFloat(index); shown = rawStart }
                    let np = min(max(rawStart - v.translation.width / step, -0.35), last + 0.35)
                    raw = np
                    shown = magnet(np)
                    let now = Int(min(max(np.rounded(), 0), last))
                    if now != index { Haptics.tick(); index = now }
                }
                .onEnded { _ in
                    let target = Int(min(max(raw.rounded(), 0), last))
                    index = target
                    rawStart = CGFloat(target)
                    withAnimation(.spring(response: 0.32, dampingFraction: 0.45)) { shown = nil }
                }
        )
        .onAppear { rawStart = CGFloat(index) }
        .onChange(of: index) { _, i in if shown == nil { rawStart = CGFloat(i) } }
        .accessibilityLabel(L("shutter_speed"))
        .accessibilityValue(shutterLabel(shutterSpeeds[min(max(index, 0), shutterSpeeds.count - 1)]))
    }

    @State private var rawStart: CGFloat = 0
}

/* ---------------- Ghiera dello zoom ---------------- */

/// Ghiera delle focali in scala logaritmica; si aggancia alla focale incisa vicina.
struct ZoomRing: View {
    @Binding var focal: CGFloat
    let maxFocal: CGFloat
    let enabled: Bool
    @State private var startFocal: CGFloat?
    private let perDoubling: CGFloat = 110

    private func arc(_ f: CGFloat) -> CGFloat { log2(f) * perDoubling }

    var body: some View {
        Canvas { ctx, size in
            let marks = focalMarks.map { DialMark(arc: arc($0), text: "\(Int($0))", faded: $0 > max(maxFocal, 24) + 0.5) }
            drawCylinderDial(ctx, size, scroll: arc(focal), marks: marks)
        }
        .frame(height: 36)
        .opacity(enabled ? 1 : 0.5)
        .gesture(
            DragGesture(minimumDistance: 2)
                .onChanged { v in
                    guard enabled else { return }
                    if startFocal == nil { startFocal = focal }
                    var f = (startFocal ?? focal) * pow(2, -v.translation.width / perDoubling)
                    f = min(max(f, 24), max(maxFocal, 24))
                    for m in focalMarks where (focal < m && f >= m) || (focal > m && f <= m) { Haptics.tick() }
                    focal = f
                }
                .onEnded { _ in
                    startFocal = nil
                    if let near = focalMarks.filter({ $0 <= max(maxFocal, 24) }).min(by: { abs(log($0 / focal)) < abs(log($1 / focal)) }),
                       abs(log(near / focal)) < 0.12 {
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.6)) { focal = near }
                        Haptics.tick()
                    }
                }
        )
    }
}

/* ---------------- Esposimetro ---------------- */

/// Lettura dell'esposimetro: errore in stop (positivo = sovraesposto) e tempi di riferimento.
struct MeterReading: Equatable {
    var error: Float
    var recommendedIndex: Int?
    var baseIndex: Int?
}

func meterReading(ev100: Double, filmIso: Int, shutterIndex: Int) -> MeterReading {
    let needed = VirtualCamera.neededShutter(ev100: ev100, filmIso: filmIso)
    let rec = shutterSpeeds.indices.filter { shutterSpeeds[$0] != nil }
        .min { abs(log(shutterSpeeds[$0]! / needed)) < abs(log(shutterSpeeds[$1]! / needed)) }
    if shutterIndex >= 0, shutterIndex < shutterSpeeds.count, let chosen = shutterSpeeds[shutterIndex] {
        return MeterReading(error: VirtualCamera.manualError(ev100: ev100, filmIso: filmIso, shutter: chosen), recommendedIndex: rec, baseIndex: shutterIndex)
    }
    // In automatico la compatta sceglie da sola: si vede solo dove sbaglia, centrato sul tempo consigliato.
    return MeterReading(error: VirtualCamera.errorFor(ev100: ev100, filmIso: filmIso), recommendedIndex: rec, baseIndex: rec)
}

/// Esposimetro a barretta come nei mirini reflex: da −2 a +2 stop a terzi. Sopra ogni stop intero
/// è stampato il tempo che lo corregge, così la barretta indica a quale tempo girare la ghiera.
struct ExposureMeter: View {
    let reading: MeterReading

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.35)) { tl in
            Canvas { ctx, size in
                let w = size.width, h = size.height, pad: CGFloat = 14
                func x(_ s: CGFloat) -> CGFloat { pad + (s + 2) / 4 * (w - 2 * pad) }
                let tickTop = h * 0.48
                for i in -6...6 {
                    let s = CGFloat(i) / 3, whole = i % 3 == 0
                    let len = i == 0 ? h * 0.36 : (whole ? h * 0.26 : h * 0.14)
                    ctx.fill(Path(CGRect(x: x(s) - (whole ? 0.75 : 0.5), y: tickTop, width: whole ? 1.5 : 1, height: len)),
                             with: .color(Palette.ink.opacity(i == 0 ? 1 : 0.67)))
                }
                if let base = reading.baseIndex {
                    for k in -2...2 {
                        let i = base - k   // sovraesposto di k stop → k posizioni più veloce
                        guard i >= 0, i < shutterSpeeds.count, let s = shutterSpeeds[i] else { continue }
                        ctx.draw(Text(shutterMark(s)).font(Fonts.mono(9)).foregroundColor(Palette.ink.opacity(k == 0 ? 1 : 0.67)),
                                 at: CGPoint(x: x(CGFloat(k)), y: h * 0.26))
                    }
                }
                ctx.draw(Text("−").font(Fonts.mono(9)).foregroundColor(Palette.ink.opacity(0.6)), at: CGPoint(x: pad * 0.35, y: tickTop + h * 0.2))
                ctx.draw(Text("+").font(Fonts.mono(9)).foregroundColor(Palette.ink.opacity(0.6)), at: CGPoint(x: w - pad * 0.35, y: tickTop + h * 0.2))
                let e = CGFloat(min(max(reading.error, -2.15), 2.15))
                let over = abs(reading.error) > 2.1
                let blink = !over || Int(tl.date.timeIntervalSinceReferenceDate / 0.35) % 2 == 0
                if blink { ctx.fill(Path(CGRect(x: x(e) - 1.5, y: tickTop - 2, width: 3, height: h - tickTop + 2)), with: .color(Palette.safelight)) }
            }
        }
        .frame(height: 30)
    }
}
