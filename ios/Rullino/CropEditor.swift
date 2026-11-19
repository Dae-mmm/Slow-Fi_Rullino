import SwiftUI

/// I formati di stampa (lato corto : lato lungo), come i fogli in negozio.
struct CropRatio: Hashable {
    let a: Int, b: Int
    var label: String { "\(a):\(b)" }
    static let all = [CropRatio(a: 1, b: 1), CropRatio(a: 4, b: 5), CropRatio(a: 3, b: 4),
                      CropRatio(a: 5, b: 7), CropRatio(a: 2, b: 3), CropRatio(a: 9, b: 16)]
}

/// La taglierina: sopra la stampa una cornice bianca tratteggiata e luminosa che sposti col dito,
/// allarghi o stringi con due dita (o tirando gli angoli), nel formato che scegli.
/// Restituisce il taglio in coordinate normalizzate (0…1) dell'immagine dritta e il formato ("4x5").
struct CropEditor: View {
    let url: URL
    let cancel: () -> Void
    let done: (CGRect, String) -> Void

    @State private var image: UIImage?
    @State private var ratio = CropRatio.all[1]
    @State private var portrait: Bool?
    // Cornice in pixel dell'immagine mostrata: centro e larghezza (l'altezza viene dal formato).
    @State private var cx: CGFloat = -1
    @State private var cy: CGFloat = -1
    @State private var w: CGFloat = -1
    @State private var dragStart: CGPoint?
    @State private var zoomStart: CGFloat?
    @State private var cornerStart: CGFloat?

    private var imgSize: CGSize { image?.size ?? CGSize(width: 1, height: 1) }
    private var vertical: Bool { portrait ?? (imgSize.height >= imgSize.width) }
    /// larghezza / altezza della cornice
    private var r: CGFloat { vertical ? CGFloat(ratio.a) / CGFloat(ratio.b) : CGFloat(ratio.b) / CGFloat(ratio.a) }
    private var maxW: CGFloat { min(imgSize.width, imgSize.height * r) }

    private func clamped(_ x: CGFloat, _ y: CGFloat, _ ww: CGFloat) -> (CGFloat, CGFloat, CGFloat) {
        let nw = min(max(ww, maxW * 0.15), maxW), nh = nw / r
        return (min(max(x, nw / 2), imgSize.width - nw / 2), min(max(y, nh / 2), imgSize.height - nh / 2), nw)
    }
    private func set(_ x: CGFloat, _ y: CGFloat, _ ww: CGFloat) { (cx, cy, w) = clamped(x, y, ww) }

    var body: some View {
        VStack(spacing: 12) {
            Text(L("crop_title").uppercased()).font(Fonts.mono(11)).tracking(2).foregroundStyle(Palette.faint)
            GeometryReader { g in
                if let image {
                    let s = min(g.size.width / imgSize.width, g.size.height / imgSize.height)
                    let ox = (g.size.width - imgSize.width * s) / 2, oy = (g.size.height - imgSize.height * s) / 2
                    let (x, y, ww) = clamped(cx, cy, w)
                    let hh = ww / r
                    let frame = CGRect(x: ox + (x - ww / 2) * s, y: oy + (y - hh / 2) * s, width: ww * s, height: hh * s)
                    ZStack(alignment: .topLeading) {
                        Image(uiImage: image).resizable().scaledToFit().frame(width: g.size.width, height: g.size.height)
                        // Terzi, appena accennati.
                        Path { p in
                            for i in 1...2 {
                                let fx = frame.minX + frame.width * CGFloat(i) / 3, fy = frame.minY + frame.height * CGFloat(i) / 3
                                p.move(to: CGPoint(x: fx, y: frame.minY)); p.addLine(to: CGPoint(x: fx, y: frame.maxY))
                                p.move(to: CGPoint(x: frame.minX, y: fy)); p.addLine(to: CGPoint(x: frame.maxX, y: fy))
                            }
                        }.stroke(Color.white.opacity(0.25), lineWidth: 1)
                        // La cornice luminosa: alone morbido, tratteggio bianco.
                        Rectangle().path(in: frame).stroke(Color.white.opacity(0.35), lineWidth: 6).blur(radius: 4)
                        Rectangle().path(in: frame).stroke(Color.white, style: StrokeStyle(lineWidth: 1.5, dash: [10, 6]))
                        Path { p in
                            let k: CGFloat = 18
                            for (px, py, dx, dy) in [(frame.minX, frame.minY, 1.0, 1.0), (frame.maxX, frame.minY, -1.0, 1.0),
                                                     (frame.minX, frame.maxY, 1.0, -1.0), (frame.maxX, frame.maxY, -1.0, -1.0)] {
                                p.move(to: CGPoint(x: px + k * dx, y: py)); p.addLine(to: CGPoint(x: px, y: py)); p.addLine(to: CGPoint(x: px, y: py + k * dy))
                            }
                        }.stroke(Color.white, lineWidth: 3)
                        // Maniglie sugli angoli: tirandole la cornice cresce o si stringe attorno al centro.
                        ForEach(0..<4, id: \.self) { i in
                            let sx: CGFloat = i % 2 == 0 ? -1 : 1, sy: CGFloat = i < 2 ? -1 : 1
                            Color.clear.contentShape(Rectangle()).frame(width: 44, height: 44)
                                .position(x: frame.midX + sx * frame.width / 2, y: frame.midY + sy * frame.height / 2)
                                .gesture(DragGesture(minimumDistance: 0)
                                    .onChanged { v in
                                        if cornerStart == nil { cornerStart = w }
                                        let grow = (v.translation.width * sx + v.translation.height * sy * r) / s
                                        set(cx, cy, (cornerStart ?? w) + grow)
                                    }
                                    .onEnded { _ in cornerStart = nil })
                        }
                    }
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture()
                            .onChanged { v in
                                if dragStart == nil { dragStart = CGPoint(x: cx, y: cy) }
                                let st = dragStart ?? .zero
                                set(st.x + v.translation.width / s, st.y + v.translation.height / s, w)
                            }
                            .onEnded { _ in dragStart = nil }
                            .simultaneously(with: MagnificationGesture()
                                .onChanged { z in
                                    if zoomStart == nil { zoomStart = w }
                                    set(cx, cy, (zoomStart ?? w) * z)
                                }
                                .onEnded { _ in zoomStart = nil })
                    )
                } else {
                    ProgressView().tint(Palette.ink).frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            Text(L("crop_hint")).font(Fonts.barlow(12)).foregroundStyle(Color(hex: 0x6B655E))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(CropRatio.all, id: \.self) { c in
                        let on = c == ratio
                        Button { ratio = c; set(cx, cy, w) } label: {
                            Text(c.label).font(Fonts.mono(13)).foregroundStyle(on ? Palette.safelight : Palette.ink)
                                .padding(.horizontal, 12).padding(.vertical, 8)
                                .background(RoundedRectangle(cornerRadius: 3).fill(on ? Color(hex: 0x2D1710) : Color(hex: 0x161514)))
                                .overlay(RoundedRectangle(cornerRadius: 3).stroke(on ? Palette.safelight : Color(hex: 0x4A4541), lineWidth: 1))
                        }
                    }
                }
            }
            HStack(spacing: 12) {
                Button(L(vertical ? "crop_portrait" : "crop_landscape")) { portrait = !vertical; set(cx, cy, w) }.buttonStyle(.secondary)
                Spacer()
                Button(L("cancel"), action: cancel).buttonStyle(.secondary)
                Button(L("crop_cut")) {
                    guard image != nil else { return }
                    let (x, y, ww) = clamped(cx, cy, w), hh = ww / r
                    let n = CGRect(x: (x - ww / 2) / imgSize.width, y: (y - hh / 2) / imgSize.height,
                                   width: ww / imgSize.width, height: hh / imgSize.height)
                    done(n, vertical ? "\(ratio.a)x\(ratio.b)" : "\(ratio.b)x\(ratio.a)")
                }.buttonStyle(.primary)
            }
        }
        .padding(16)
        .background(Color(hex: 0x0C0B0B).opacity(0.95).ignoresSafeArea())
        .task {
            let u = url
            let img = await Task.detached { Developer.thumbnail(u, maxPixel: 2048).map { UIImage(cgImage: $0) } }.value
            image = img
            if let img {
                let mw = min(img.size.width, img.size.height * r)
                set(img.size.width / 2, img.size.height / 2, mw * 0.9)
            }
        }
    }
}
