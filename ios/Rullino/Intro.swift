import PhotosUI
import SwiftUI

/// Il primo avvio: quattro cartoncini che spiegano il gioco lento.
/// Carica un rullino, scatta senza guardare, porta a sviluppare e aspetta, stampa.
struct IntroScreen: View {
    let done: () -> Void
    @State private var page = 0
    @State private var pulse = false
    private let pages: [(String, String)] = [("intro1_title", "intro1_text"), ("intro2_title", "intro2_text"),
                                              ("intro3_title", "intro3_text"), ("intro4_title", "intro4_text")]

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Palette.deep.ignoresSafeArea()
            TabView(selection: $page) {
                ForEach(pages.indices, id: \.self) { i in
                    HStack(spacing: 40) {
                        IntroArt(index: i, t: pulse ? 1 : 0).frame(maxWidth: .infinity, maxHeight: .infinity)
                        VStack(alignment: .leading, spacing: 14) {
                            Text(String(format: "%02d / %02d", i + 1, pages.count)).font(Fonts.mono(12)).tracking(2).foregroundStyle(Palette.safelight)
                            Text(L(pages[i].0).uppercased()).font(Fonts.barlow(30, .semibold)).tracking(1.5).foregroundStyle(Palette.ink)
                            Text(L(pages[i].1)).font(Fonts.barlow(17, .regular)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.horizontal, 40).padding(.vertical, 28)
                    .tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            HStack(spacing: 16) {
                HStack(spacing: 6) {
                    ForEach(pages.indices, id: \.self) { i in
                        Circle().fill(i == page ? Palette.safelight : Palette.line).frame(width: i == page ? 8 : 6, height: i == page ? 8 : 6)
                    }
                }
                let last = page == pages.count - 1
                if !last { Button(L("intro_skip"), action: done).foregroundStyle(Palette.muted) }
                Button(L(last ? "intro_start" : "intro_next")) {
                    if last { done() } else { withAnimation { page += 1 } }
                }.buttonStyle(.primary)
            }
            .padding(.horizontal, 40).padding(.vertical, 20)
        }
        .onAppear { withAnimation(.easeInOut(duration: 2.4).repeatForever(autoreverses: true)) { pulse = true } }
    }
}

/// I disegni dei cartoncini.
private struct IntroArt: View, Animatable {
    let index: Int
    var t: CGFloat
    var animatableData: CGFloat { get { t } set { t = newValue } }

    var body: some View {
        Canvas { c, size in
            switch index {
            case 0: canister(c, size)
            case 1: viewfinder(c, size)
            case 2: lab(c, size)
            default: print(c, size)
            }
        }
    }

    private let film = Color(hex: 0x7A3A12)

    /// Un rullino con la coda di pellicola che esce.
    private func canister(_ c: GraphicsContext, _ s: CGSize) {
        let h = min(s.width, s.height) * 0.62, w = h * 0.62
        let l = s.width / 2 - w * 0.9, top = (s.height - h) / 2
        let tail = w * (1.2 + 0.15 * t)
        c.fill(Path(CGRect(x: l + w * 0.8, y: top + h * 0.3, width: tail, height: h * 0.4)), with: .color(film))
        var x = l + w
        while x < l + w * 0.8 + tail - 8 {
            c.fill(Path(roundedRect: CGRect(x: x, y: top + h * 0.32, width: 8, height: 6), cornerRadius: 1.5), with: .color(Palette.deep))
            c.fill(Path(roundedRect: CGRect(x: x, y: top + h * 0.7 - 12, width: 8, height: 6), cornerRadius: 1.5), with: .color(Palette.deep))
            x += 18
        }
        c.fill(Path(roundedRect: CGRect(x: l, y: top, width: w, height: h), cornerRadius: 10),
               with: .linearGradient(Gradient(colors: [Color(hex: 0x2A2624), Color(hex: 0x4A4440), Color(hex: 0x2A2624)]),
                                     startPoint: CGPoint(x: l, y: 0), endPoint: CGPoint(x: l + w, y: 0)))
        c.fill(Path(CGRect(x: l - 4, y: top - 10, width: w + 8, height: 14)), with: .color(Palette.chrome))
        c.fill(Path(CGRect(x: l - 4, y: top + h - 4, width: w + 8, height: 14)), with: .color(Palette.chrome))
        c.fill(Path(CGRect(x: l + w * 0.35, y: top - 26, width: w * 0.3, height: 18)), with: .color(Palette.chrome))
        c.fill(Path(CGRect(x: l + 10, y: top + h * 0.42, width: w - 20, height: h * 0.16)), with: .color(Palette.safelight))
    }

    /// Il mirino: niente anteprima.
    private func viewfinder(_ c: GraphicsContext, _ s: CGSize) {
        let w = min(s.width, s.height * 1.4) * 0.95, h = w * 0.66
        let l = (s.width - w) / 2, tp = (s.height - h) / 2, k = w * 0.1
        var p = Path()
        for (x, y, dx, dy) in [(l, tp, 1.0, 1.0), (l + w, tp, -1.0, 1.0), (l, tp + h, 1.0, -1.0), (l + w, tp + h, -1.0, -1.0)] {
            p.move(to: CGPoint(x: x + k * dx, y: y)); p.addLine(to: CGPoint(x: x, y: y)); p.addLine(to: CGPoint(x: x, y: y + k * dy))
        }
        c.stroke(p, with: .color(Palette.ink), lineWidth: 4)
        let r = w * 0.07
        c.stroke(Path(ellipseIn: CGRect(x: l + w / 2 - r, y: tp + h / 2 - r, width: r * 2, height: r * 2)), with: .color(Palette.ink.opacity(0.5)), lineWidth: 2)
        c.fill(Path(CGRect(x: l, y: tp, width: w, height: h)), with: .color(.white.opacity(0.06 + 0.12 * t)))
        let d = w * 0.06
        c.fill(Path(ellipseIn: CGRect(x: l + w - w * 0.12 - d, y: tp + h + w * 0.1 - d, width: d * 2, height: d * 2)), with: .color(Palette.safelight))
    }

    /// Il laboratorio: la vaschetta sotto la luce rossa, e il tempo che passa.
    private func lab(_ c: GraphicsContext, _ s: CGSize) {
        let cx = s.width / 2, cy = s.height / 2, r = min(s.width, s.height) * 0.42
        c.fill(Path(ellipseIn: CGRect(x: cx - r * 1.3, y: cy - r * 1.3, width: r * 2.6, height: r * 2.6)),
               with: .radialGradient(Gradient(colors: [Palette.safelight.opacity(0.35 + 0.2 * t), .clear]), center: CGPoint(x: cx, y: cy), startRadius: 0, endRadius: r * 1.3))
        c.fill(Path(roundedRect: CGRect(x: cx - r, y: cy, width: r * 2, height: r * 0.5), cornerRadius: 12), with: .color(Color(hex: 0x1F1C1A)))
        c.fill(Path(CGRect(x: cx - r + 12, y: cy + 10, width: r * 2 - 24, height: r * 0.3)), with: .color(Palette.safelightDark))
        let o = CGPoint(x: cx, y: cy - r * 0.45), orr = r * 0.32
        c.stroke(Path(ellipseIn: CGRect(x: o.x - orr, y: o.y - orr, width: orr * 2, height: orr * 2)), with: .color(Palette.ink), lineWidth: 4)
        let a = (Double(t) * 300 - 90) * .pi / 180
        var hand = Path(); hand.move(to: o); hand.addLine(to: CGPoint(x: o.x + orr * 0.75 * cos(a), y: o.y + orr * 0.75 * sin(a)))
        c.stroke(hand, with: .color(Palette.safelight), lineWidth: 4)
        var small = Path(); small.move(to: o); small.addLine(to: CGPoint(x: o.x, y: o.y - orr * 0.5))
        c.stroke(small, with: .color(Palette.ink), lineWidth: 4)
    }

    /// La stampa, con la cornicetta del taglio.
    private func print(_ c: GraphicsContext, _ s: CGSize) {
        let w = min(s.width, s.height * 1.3) * 0.9, h = w * 0.72
        let l = (s.width - w) / 2, tp = (s.height - h) / 2, m = w * 0.05
        c.fill(Path(CGRect(x: l, y: tp, width: w, height: h)), with: .color(Color(hex: 0xF2EEE6)))
        c.fill(Path(CGRect(x: l + m, y: tp + m, width: w - 2 * m, height: h - 2 * m)),
               with: .linearGradient(Gradient(colors: [Color(hex: 0x6FA3C8), Color(hex: 0xF0C38A)]), startPoint: CGPoint(x: 0, y: tp + m), endPoint: CGPoint(x: 0, y: tp + h * 0.6)))
        let sr = w * 0.06
        c.fill(Path(ellipseIn: CGRect(x: l + w * 0.68 - sr, y: tp + h * 0.32 - sr, width: sr * 2, height: sr * 2)), with: .color(Color(hex: 0xFFD27A)))
        c.fill(Path(CGRect(x: l + m, y: tp + h * 0.62, width: w - 2 * m, height: h * 0.38 - m)), with: .color(Color(hex: 0x3F5B3A)))
        let cw = (w - 2 * m) * (0.55 + 0.1 * t), ch = min(cw, h - 2 * m)
        c.stroke(Path(CGRect(x: l + w / 2 - cw / 2, y: tp + h / 2 - ch / 2, width: cw, height: ch)),
                 with: .color(.white), style: StrokeStyle(lineWidth: 3, dash: [14, 8]))
    }
}

/// "Segnala un problema": cosa è successo, un contatto se vuoi una risposta, e uno screenshot.
struct FeedbackSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var contact = ""
    @State private var item: PhotosPickerItem?
    @State private var image: UIImage?
    @State private var sending = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(L("fb_title").uppercased()).font(Fonts.barlow(22, .semibold)).tracking(1.5).foregroundStyle(Palette.ink)
                Text(L("fb_intro")).font(Fonts.barlow(14)).foregroundStyle(Palette.muted)
                ZStack(alignment: .topLeading) {
                    TextEditor(text: $text).scrollContentBackground(.hidden).frame(height: 130)
                        .padding(6).background(RoundedRectangle(cornerRadius: 4).stroke(Palette.line))
                    if text.isEmpty {
                        Text(L("fb_placeholder")).foregroundStyle(Palette.faint).padding(.horizontal, 12).padding(.vertical, 14).allowsHitTesting(false)
                    }
                }
                TextField(L("fb_contact"), text: $contact).textInputAutocapitalization(.never).keyboardType(.emailAddress)
                    .padding(10).background(RoundedRectangle(cornerRadius: 4).stroke(Palette.line))
                HStack(spacing: 10) {
                    PhotosPicker(selection: $item, matching: .images) {
                        Text(L(image == nil ? "fb_attach" : "fb_attach_change"))
                    }.buttonStyle(.secondary)
                    if image != nil {
                        Text(L("fb_attached")).font(Fonts.mono(12)).foregroundStyle(Palette.safelight)
                        Button(L("fb_remove")) { image = nil; item = nil }.foregroundStyle(Palette.muted)
                    }
                }
                Text(L("fb_privacy")).font(Fonts.barlow(12)).foregroundStyle(Palette.faint)
                HStack {
                    Spacer()
                    Button(L("cancel")) { dismiss() }.foregroundStyle(Palette.muted).disabled(sending)
                    Button(L(sending ? "fb_sending" : "fb_send")) {
                        sending = true
                        Task {
                            let err = await Reporter.shared.feedback(text.prefix(4000).description, contact: String(contact.prefix(200)), image: image)
                            sending = false
                            switch err {
                            case nil: store.say("fb_sent"); dismiss()
                            case "too_many": store.say("fb_too_many")
                            default: store.say("fb_failed")
                            }
                        }
                    }
                    .buttonStyle(.primary)
                    .disabled(sending || text.trimmingCharacters(in: .whitespacesAndNewlines).count < 3)
                }
            }
            .padding(22)
        }
        .background(Palette.plate.ignoresSafeArea())
        .onChange(of: item) { _, it in
            Task {
                if let d = try? await it?.loadTransferable(type: Data.self) { image = UIImage(data: d) }
            }
        }
    }
}
