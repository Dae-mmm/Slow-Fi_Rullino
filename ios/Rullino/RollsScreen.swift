import SwiftUI

struct RollsScreen: View {
    @EnvironmentObject var store: Store
    @State private var open: Roll?
    @State private var toDelete: Roll?
    @State private var shared: SharedFile?
    @EnvironmentObject var account: Account

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
                    PageHeading(kicker: L("kick_archive", String(format: "%02d", rolls.count)), title: L("rolls_title")) {
                        if n > 0 {
                            HStack(spacing: 16) {
                                VStack(alignment: .leading, spacing: 0) {
                                    Text(n == 1 ? L("to_develop_one") : L("to_develop_many", n)).font(Fonts.barlow(18, .semibold)).foregroundStyle(Palette.ink)
                                    Text(L("lab_dont_wait")).font(Fonts.barlow(12)).foregroundStyle(Palette.muted)
                                }
                                Button(L("go_lab")) { store.deliverAll() }.buttonStyle(.primary)
                            }
                            .padding(.horizontal, 16).padding(.vertical, 10)
                            .background(RoundedRectangle(cornerRadius: 4).fill(Color(hex: 0x241714)))
                            .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(hex: 0x6E3322), lineWidth: 1))
                        }
                    }
                    if let p = store.developProgress {
                        Text(L("developing") + " " + L("x_of_y", p.done + 1, p.total)).font(mono).foregroundStyle(Palette.muted)
                    }
                    if rolls.isEmpty {
                        Text(L("rolls_empty")).foregroundStyle(Palette.muted).padding(.top, 30)
                    }
                    ForEach(rolls) { r in row(r, now: tl.date) }
                }
                .padding(.horizontal, 24).padding(.vertical, 20)
            }
            .background(DottedPage())
        }
        .task { await store.syncShared() }
        .sheet(item: $shared) { f in ActivityView(items: [f.url, L("share_text", f.name)]) }
        .alert(L("delete_roll_confirm"), isPresented: Binding(get: { toDelete != nil }, set: { if !$0 { toDelete = nil } })) {
            Button(L("delete"), role: .destructive) { if let r = toDelete { store.deleteRoll(r) } }
            Button(L("cancel"), role: .cancel) {}
        }
    }

    private func row(_ r: Roll, now: Date) -> some View {
        HStack(spacing: 14) {
            Cartridge(name: r.emulsionName, iso: r.iso, bw: r.isBw, exposures: r.exposures, scale: 0.9)
            VStack(alignment: .leading, spacing: 3) {
                ChipView(text: status(r, now: now), active: store.isReady(r, now: now))
                Text(r.emulsionName).font(Fonts.barlow(22, .semibold)).foregroundStyle(Palette.ink).lineLimit(1)
                Text("ISO \(r.iso) · \(r.shotCount)/\(r.exposures)").font(Fonts.barlow(13)).tracking(0.4).foregroundStyle(Palette.muted)
            }
            Spacer()
            Text(String(format: "%02d", r.id)).font(Fonts.mono(28)).foregroundStyle(Color(hex: 0x4A4541))
            switch r.state {
            case .shelved:
                // Scaricato: lo ricarichi per continuare, lo passi a qualcuno o lo mandi in sviluppo così com'è.
                Button(L("load")) {
                    if let a = store.activeRoll, a.shotCount == 0 { store.unloadEmptyRoll(a) }
                    store.reload(r)
                }.buttonStyle(.secondary)
                Button(L("share")) { share(r) }.buttonStyle(.secondary)
                Button(L("develop_it")) { store.rewindOut(r) }.buttonStyle(.primary)
                Button(L("delete")) { toDelete = r }.foregroundStyle(Color(hex: 0xFF8A80))
            case .shared:
                // In viaggio: finché nessuno lo importa puoi rimandare il file o riprenderlo.
                Button(L("share_resend")) { share(r) }.buttonStyle(.secondary)
                Button(L("share_take_back")) { Task { await store.takeBack(r) } }.buttonStyle(.primary)
            case .developed where store.isReady(r, now: now):
                Button(L("negatives_arrow")) { open = r }.buttonStyle(.primary)
            case .finished:
                // Come vuoi lo sviluppo: pull, normale o push.
                HStack(spacing: 4) {
                    ForEach([-1, 0, 1, 2], id: \.self) { p in
                        let on = r.push == p
                        Button { store.setPush(r, p) } label: {
                            Text(p > 0 ? "+\(p)" : (p < 0 ? "−1" : "N")).font(Fonts.mono(12))
                                .foregroundStyle(on ? Palette.safelight : Palette.ink)
                                .frame(width: 34, height: 32)
                                .background(Rectangle().fill(on ? Color(hex: 0x2D1710) : Color(hex: 0x161514)))
                                .overlay(Rectangle().stroke(on ? Palette.safelight : Color(hex: 0x4A4541), lineWidth: 1))
                        }
                    }
                }
                .accessibilityLabel(L("push_title"))
                Button(L("delete")) { toDelete = r }.foregroundStyle(Color(hex: 0xFF8A80))
            default:
                EmptyView()
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .card()
    }

    /// Il codice nasce sul server: chi apre il file per primo se lo tiene, gli altri trovano il rullino già preso.
    private func share(_ r: Roll) {
        guard account.signedIn else { store.say("share_sign_in"); return }
        let from = account.profile.flatMap { $0.displayName ?? $0.username }
        store.say("share_preparing")
        Task { @MainActor in
            do {
                let id: String
                if let existing = r.shareId { id = existing } else { id = try await store.createShare(r) }
                let url = try RollShare.export(r, store: store, from: from, shareId: id)
                store.update(r.id) { $0.state = .shared; $0.shareId = id }
                shared = SharedFile(url: url, name: r.emulsionName)
            } catch { store.say("share_failed") }
        }
    }

    private func status(_ r: Roll, now: Date) -> String {
        switch r.state {
        case .loaded: return L("state_loaded")
        case .shelved: return L("state_shelved")
        case .shared: return L("state_shared")
        case .finished: return L("state_finished")
        case .developing: return L("at_lab_tank")
        case .developed:
            if store.isReady(r, now: now) { return L("negatives_printed", store.frames(of: r).count, store.frames(of: r).filter(\.printed).count) }
            return L("at_lab_back_in", formatWait((r.readyAt ?? now).timeIntervalSince(now)))
        }
    }
}

struct SharedFile: Identifiable {
    let url: URL
    let name: String
    var id: String { url.path }
}

/// Il foglio di condivisione di iOS (Messaggi, Mail, AirDrop, File…).
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
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
            HStack(spacing: 14) {
                Button(L("back_rolls").uppercased(), action: close).font(Fonts.barlow(16, .semibold)).tracking(1).foregroundStyle(Palette.safelight)
                VStack(alignment: .leading, spacing: 0) {
                    Text(L("developed_on", roll.readyAt.map { $0.formatted(date: .abbreviated, time: .omitted) } ?? "—").uppercased())
                        .font(Fonts.mono(9)).tracking(0.6).foregroundStyle(Palette.muted)
                    Text(roll.emulsionName + " · " + L("negatives_printed", frames.count, frames.filter(\.printed).count))
                        .font(Fonts.barlow(20, .semibold)).foregroundStyle(Palette.ink).lineLimit(1)
                }
                Spacer()
                if selecting {
                    Text(L("n_chosen", chosen.count)).font(.caption).foregroundStyle(Palette.muted)
                    Button(L("print")) {
                        store.printFrames(frames.filter { chosen.contains($0.frame) })
                        selecting = false; chosen = []
                    }.buttonStyle(.primary).disabled(chosen.isEmpty)
                    Button(L("cancel")) { selecting = false; chosen = [] }.foregroundStyle(Palette.ink)
                } else {
                    Button(L("select")) { selecting = true }.buttonStyle(.secondary)
                    Button(L("delete_roll2")) { confirmDelete = true }.foregroundStyle(Color(hex: 0xFF8A80))
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
            .background(Color(hex: 0x221F1D))
            ScrollView {
                HStack {
                    Text(L("contact_sheet", String(format: "%02d", roll.id)).uppercased()).font(Fonts.barlow(12, .bold)).tracking(0.6)
                    Spacer()
                    Text("\(roll.exposures) · " + (roll.isBw ? "B&W" : "COLOR") + (roll.push != 0 ? " · PUSH \(roll.push > 0 ? "+" : "")\(roll.push)" : ""))
                        .font(Fonts.mono(10))
                }
                .foregroundStyle(Color(hex: 0x2A2522))
                .padding(.horizontal, 24).padding(.top, 12)
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
                Text(selecting ? L("hint_select") : L("hint_loupe")).font(Fonts.barlow(12)).foregroundStyle(Color(hex: 0x6B655E))
            }
            .background(DottedPage(base: Color(hex: 0xE6DCC6), dot: Color(hex: 0xD2C6AC), step: 6))
        }
        .background(Palette.body)
        .fullScreenCover(item: Binding(get: { loupe.map { LoupeItem(f: $0) } }, set: { loupe = $0?.f })) { item in
            Loupe(frame: item.f, url: store.url(item.f), roll: roll, close: { loupe = nil })
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
        // 6 perforazioni per fotogramma, distribuite in modo uniforme.
        HStack(spacing: 0) {
            ForEach(0..<6, id: \.self) { _ in
                RoundedRectangle(cornerRadius: 1).fill(Color(hex: 0xF2EEE6)).frame(width: 8, height: 5).frame(maxWidth: .infinity)
            }
        }
        .frame(height: 12)
    }
}

/// Lente: il positivo grande, da stampare.
struct Loupe: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var contests: ContestsModel
    let frame: DevFrame
    let url: URL
    let roll: Roll
    let close: () -> Void
    @State private var image: UIImage?
    @State private var sending = false
    @State private var cropping = false
    @State private var cut: String?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image { Image(uiImage: image).resizable().scaledToFit().padding(20) }
            if let cut {
                // Il cartellino "tagliata": forbici, formato e dove è finita.
                VStack(spacing: 4) {
                    Text("✂  " + L("crop_done_title", cut)).font(Fonts.mono(16)).tracking(1.5).foregroundStyle(Palette.safelight)
                    Text(L("crop_done_where")).font(Fonts.barlow(12)).foregroundStyle(Palette.ink)
                }
                .padding(.horizontal, 22).padding(.vertical, 14)
                .background(RoundedRectangle(cornerRadius: 4).fill(Color(hex: 0x1B0E09).opacity(0.9)))
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Palette.safelight, lineWidth: 1))
                .transition(.opacity)
            }
            VStack {
                HStack {
                    Text(L("positive_n", frame.frame) + (frame.layers > 1 ? L("n_exposures_suffix", frame.layers) : ""))
                        .foregroundStyle(Palette.ink)
                    Spacer()
                    let printed = store.data.frames.first { $0.file == frame.file }?.printed ?? false
                    Button(printed ? L("already_printed") : L("print")) { store.printFrames([frame]) }
                        .buttonStyle(.primary).disabled(printed)
                    Button(L("crop")) { cropping = true }.buttonStyle(.secondary)
                    if !contests.eligible(roll).isEmpty {
                        Button(L("ct_send_short")) { sending = true }.buttonStyle(.primary)
                    }
                    Button(L("back_on_table"), action: close).buttonStyle(.secondary)
                }
                .padding(16)
                Spacer()
            }
        }
        .task {
            let u = url
            image = await Task.detached { Developer.thumbnail(u, maxPixel: 2400).map { UIImage(cgImage: $0) } }.value
            if contests.contests.isEmpty { await contests.refresh() }
        }
        .overlay {
            if cropping {
                CropEditor(url: url, cancel: { cropping = false }) { rect, tag in
                    cropping = false
                    store.printCrop(frame, rect, tag: tag) {
                        withAnimation { cut = tag.replacingOccurrences(of: "x", with: ":") }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2.8) { withAnimation { cut = nil } }
                    }
                }
            }
        }
        .sheet(isPresented: $sending) {
            SendToContestView(roll: roll, frame: frame, url: url, contests: contests.eligible(roll)) { sending = false }
                .environmentObject(contests)
        }
    }
}
