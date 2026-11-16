import SwiftUI

struct RollsScreen: View {
    @EnvironmentObject var store: Store
    @State private var open: Roll?
    @State private var toDelete: Roll?

    var body: some View {
        if let o = open, let fresh = store.roll(o.id) {
            LightTable(roll: fresh, close: { open = nil })
        } else {
            list
        }
    }

    private var rolls: [Roll] {
        store.data.rolls.filter { $0.state != .loaded }.sorted { $0.id > $1.id }
    }

    private var list: some View {
        TimelineView(.periodic(from: .now, by: 30)) { tl in
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    let n = store.toDevelop.count
                    if n > 0 {
                        HStack {
                            Text(n == 1 ? L("to_develop_one") : L("to_develop_many", n)).foregroundStyle(Palette.ink)
                            Spacer()
                            Button(L("go_lab")) { store.deliverAll() }
                                .buttonStyle(.borderedProminent).tint(Palette.safelight)
                        }
                        .padding(12)
                        .background(Palette.plateHi, in: RoundedRectangle(cornerRadius: 10))
                    }
                    if let p = store.developProgress {
                        Text(L("developing") + " " + L("x_of_y", p.done + 1, p.total)).font(mono).foregroundStyle(Palette.muted)
                    }
                    if rolls.isEmpty {
                        Text(L("rolls_empty")).foregroundStyle(Palette.muted).padding(.top, 30)
                    }
                    ForEach(rolls) { r in row(r, now: tl.date) }
                }
                .padding(18)
            }
        }
        .alert(L("delete_roll_confirm"), isPresented: Binding(get: { toDelete != nil }, set: { if !$0 { toDelete = nil } })) {
            Button(L("delete"), role: .destructive) { if let r = toDelete { store.deleteRoll(r) } }
            Button(L("cancel"), role: .cancel) {}
        }
    }

    private func row(_ r: Roll, now: Date) -> some View {
        HStack(spacing: 12) {
            Canister(name: r.emulsionName, iso: r.iso, bw: r.isBw).frame(width: 56, height: 40)
            VStack(alignment: .leading, spacing: 3) {
                Text(r.emulsionName).font(.headline).foregroundStyle(Palette.ink)
                Text(L("shot_status", r.shotCount, status(r, now: now))).font(.caption).foregroundStyle(Palette.muted)
            }
            Spacer()
            switch r.state {
            case .shelved:
                Button(L("reload")) { store.reload(r) }.buttonStyle(.bordered).tint(Palette.ink)
                Button(L("delete")) { toDelete = r }.foregroundStyle(Color(hex: 0xFF8A80))
            case .developed where store.isReady(r, now: now):
                Button(L("negatives_arrow")) { open = r }.buttonStyle(.borderedProminent).tint(Palette.safelight)
            case .finished:
                Button(L("delete")) { toDelete = r }.foregroundStyle(Color(hex: 0xFF8A80))
            default:
                EmptyView()
            }
        }
        .padding(12)
        .background(Palette.plate, in: RoundedRectangle(cornerRadius: 10))
    }

    private func status(_ r: Roll, now: Date) -> String {
        switch r.state {
        case .loaded: return L("state_loaded")
        case .shelved: return L("state_shelved")
        case .finished: return L("state_finished")
        case .developing: return L("at_lab_tank")
        case .developed:
            if store.isReady(r, now: now) { return L("negatives_printed", store.frames(of: r).count, store.frames(of: r).filter(\.printed).count) }
            return L("at_lab_back_in", formatWait((r.readyAt ?? now).timeIntervalSince(now)))
        }
    }
}

/// Il caricatore disegnato: etichetta col nome dell'emulsione.
struct Canister: View {
    let name: String
    let iso: Int
    let bw: Bool
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 5).fill(LinearGradient(colors: bw ? [Color(hex: 0x3A3836), Color(hex: 0x111010)] : [Color(hex: 0xE2A23B), Color(hex: 0x9A4F12)], startPoint: .top, endPoint: .bottom))
            VStack(spacing: 0) {
                Text(name.uppercased()).font(.system(size: 7, weight: .heavy)).lineLimit(1).minimumScaleFactor(0.5)
                Text("\(iso)").font(.system(size: 12, weight: .black, design: .rounded))
            }
            .foregroundStyle(bw ? Palette.ink : Color(hex: 0x1A1210))
            .padding(3)
        }
    }
}

/* ---------------- Tavolo luminoso ---------------- */

struct LightTable: View {
    @EnvironmentObject var store: Store
    let roll: Roll
    let close: () -> Void
    @State private var selecting = false
    @State private var chosen: Set<Int> = []
    @State private var loupe: DevFrame?
    @State private var confirmDelete = false

    var body: some View {
        let frames = store.frames(of: roll)
        VStack(spacing: 0) {
            HStack {
                Button(L("back_rolls"), action: close).foregroundStyle(Palette.ink)
                Text(roll.emulsionName).font(.headline).foregroundStyle(Palette.ink).padding(.leading, 8)
                Text(L("negatives_printed", frames.count, frames.filter(\.printed).count)).font(.caption).foregroundStyle(Palette.muted)
                Spacer()
                if selecting {
                    Text(L("n_chosen", chosen.count)).font(.caption).foregroundStyle(Palette.muted)
                    Button(L("print")) {
                        store.printFrames(frames.filter { chosen.contains($0.frame) })
                        selecting = false; chosen = []
                    }.buttonStyle(.borderedProminent).tint(Palette.safelight).disabled(chosen.isEmpty)
                    Button(L("cancel")) { selecting = false; chosen = [] }.foregroundStyle(Palette.ink)
                } else {
                    Button(L("select")) { selecting = true }.buttonStyle(.bordered).tint(Palette.ink)
                    Button(L("delete_roll2")) { confirmDelete = true }.foregroundStyle(Color(hex: 0xFF8A80))
                }
            }
            .padding(12)
            Text(selecting ? L("hint_select") : L("hint_loupe")).font(.caption).foregroundStyle(Palette.muted)
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 0)], spacing: 14) {
                    ForEach(frames, id: \.frame) { f in
                        NegativeFrame(url: store.url(f), number: f.frame, bw: roll.isBw, printed: f.printed,
                                      selected: chosen.contains(f.frame))
                            .onTapGesture {
                                if selecting {
                                    if chosen.contains(f.frame) { chosen.remove(f.frame) } else { chosen.insert(f.frame) }
                                } else { loupe = f }
                            }
                    }
                }
                .padding(14)
            }
            .background(LinearGradient(colors: [Color(hex: 0xF2EEE6), Color(hex: 0xD8D2C6)], startPoint: .top, endPoint: .bottom))
        }
        .background(Palette.body)
        .fullScreenCover(item: Binding(get: { loupe.map { LoupeItem(f: $0) } }, set: { loupe = $0?.f })) { item in
            Loupe(frame: item.f, url: store.url(item.f), close: { loupe = nil })
        }
        .alert(L("delete_all_confirm"), isPresented: $confirmDelete) {
            Button(L("delete"), role: .destructive) { store.deleteRoll(roll); close() }
            Button(L("cancel"), role: .cancel) {}
        }
    }
}

private struct LoupeItem: Identifiable {
    let f: DevFrame
    var id: String { f.file }
}

/// Un fotogramma della striscia: negativo invertito, con perforazioni.
struct NegativeFrame: View {
    let url: URL
    let number: Int
    let bw: Bool
    let printed: Bool
    let selected: Bool
    @State private var image: UIImage?

    var body: some View {
        VStack(spacing: 0) {
            sprockets
            ZStack(alignment: .topTrailing) {
                Group {
                    if let image {
                        Image(uiImage: image).resizable().scaledToFill()
                            .colorInvert()
                            .colorMultiply(bw ? .white : Color(red: 1, green: 0.72, blue: 0.45))
                    } else { Color(hex: 0x2B1D14) }
                }
                .frame(width: 132, height: 88).clipped()
                if printed {
                    Text(L("printed_badge")).font(.system(size: 8, weight: .bold)).padding(3)
                        .background(Palette.safelight).foregroundStyle(.black).padding(3)
                }
            }
            .padding(.horizontal, 6)
            sprockets
            Text("\(number)").font(.system(size: 10, design: .monospaced)).foregroundStyle(Color(hex: 0xE2A23B)).padding(.bottom, 2)
        }
        .background(bw ? Color(hex: 0x1A1918) : Color(hex: 0x6B3A1A))
        .overlay(RoundedRectangle(cornerRadius: 2).stroke(selected ? Palette.safelight : .clear, lineWidth: 3))
        .task(id: url) {
            let u = url
            image = await Task.detached { Developer.thumbnail(u, maxPixel: 360).map { UIImage(cgImage: $0) } }.value
        }
    }

    private var sprockets: some View {
        HStack(spacing: 7) {
            ForEach(0..<9, id: \.self) { _ in RoundedRectangle(cornerRadius: 1).fill(Color(hex: 0xF2EEE6)).frame(width: 8, height: 5) }
        }
        .frame(height: 12)
    }
}

/// Lente: il positivo grande, da stampare.
struct Loupe: View {
    @EnvironmentObject var store: Store
    let frame: DevFrame
    let url: URL
    let close: () -> Void
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image { Image(uiImage: image).resizable().scaledToFit().padding(20) }
            VStack {
                HStack {
                    Text(L("positive_n", frame.frame) + (frame.layers > 1 ? L("n_exposures_suffix", frame.layers) : ""))
                        .foregroundStyle(Palette.ink)
                    Spacer()
                    let printed = store.data.frames.first { $0.file == frame.file }?.printed ?? false
                    Button(printed ? L("already_printed") : L("print")) { store.printFrames([frame]) }
                        .buttonStyle(.borderedProminent).tint(Palette.safelight).disabled(printed)
                    Button(L("back_on_table"), action: close).buttonStyle(.bordered).tint(Palette.ink)
                }
                .padding(16)
                Spacer()
            }
        }
        .task {
            let u = url
            image = await Task.detached { Developer.thumbnail(u, maxPixel: 2400).map { UIImage(cgImage: $0) } }.value
        }
    }
}
