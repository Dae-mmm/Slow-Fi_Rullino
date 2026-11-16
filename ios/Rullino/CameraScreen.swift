import SwiftUI

struct CameraScreen: View {
    @EnvironmentObject var store: Store
    @StateObject private var camera = CameraController()
    let goShop: () -> Void
    let goRolls: () -> Void

    @State private var busy = false
    @State private var blackout = false
    @State private var releaseOn = false
    @State private var unloadTurns = 0
    @State private var crank: CGFloat = 0
    @State private var optimistic: Int?
    @State private var optimisticKey = ""
    @State private var showBack = false
    @State private var showPicker = false
    @State private var confirmDelete = false
    @State private var focal: CGFloat = 24
    @State private var windProgress: CGFloat = 0

    private var r: Roll? { store.activeRoll }

    var body: some View {
        VStack(spacing: 0) {
            topPlate
            HStack(spacing: 14) {
                leftColumn.frame(width: 150)
                centerColumn
                LeverShutter(
                    armed: (r?.wound ?? false) && !busy,
                    leverEnabled: r?.canWind ?? false,
                    onShoot: shoot,
                    onAdvanced: { if let r { store.wind(r) } },
                    onProgress: { windProgress = $0 }
                )
                .frame(width: 150)
            }
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Leatherette())
        }
        .background(Palette.body)
        .onAppear { camera.start() }
        .onDisappear { camera.stop() }
        .onChange(of: r?.id) { _, _ in releaseOn = false; unloadTurns = 0 }
        .onChange(of: focal) { _, f in camera.setFocal(f) }
        .sheet(isPresented: $showPicker) { RollPicker(goShop: { showPicker = false; goShop() }) }
        .confirmationDialog(backTitle, isPresented: $showBack, titleVisibility: .visible) {
            if let r {
                Button(L("rewind_all_out")) { optimistic = nil; store.rewindOut(r) }
                Button(L("shelve_long")) { store.shelve(r) }
                Button(L("delete_roll"), role: .destructive) { confirmDelete = true }
                Button(L("close_back"), role: .cancel) {}
            }
        } message: { Text(L("back_help")) }
        .alert(L("delete_roll_confirm"), isPresented: $confirmDelete) {
            Button(L("delete"), role: .destructive) { if let r { store.deleteRoll(r) } }
            Button(L("cancel"), role: .cancel) {}
        }
    }

    private var backTitle: String {
        guard let r else { return "" }
        return r.emulsionName + " · " + L("cam_count", r.shotCount, r.exposures)
    }

    /* ---------------- Contatore ---------------- */

    private var turnsNeeded: Int { max(2, ((r?.shotCount ?? 0) + 5) / 6) }

    private var dbCounter: Int {
        guard let r else { return 0 }
        return r.wound ? r.cursor : r.cursor - 1
    }

    private var rollKey: String { r.map { "\($0.id)-\($0.cursor)-\($0.wound)" } ?? "none" }

    private var counter: Int { (optimisticKey == rollKey ? optimistic : nil) ?? dbCounter }

    /// (da, verso, avanzamento) mentre si gira la manovella.
    private var rolling: (Int, Int, CGFloat)? {
        guard let r, r.shotCount > 0, releaseOn else { return nil }
        if r.isAtEnd {
            let t = min(max((CGFloat(unloadTurns) + crank) / CGFloat(turnsNeeded), 0), 1)
            let v = CGFloat(dbCounter) * (1 - t)
            let from = Int(v.rounded(.up))
            return from <= 0 ? nil : (from, from - 1, CGFloat(from) - v)
        }
        guard crank > 0 else { return nil }
        let hasOpt = optimisticKey == rollKey && optimistic != nil
        if !hasOpt && r.cursor > 1 && counter != r.cursor - 1 { return (counter, r.cursor - 1, crank) }
        if hasOpt && counter > 1 { return (counter, counter - 1, crank) }
        return nil
    }

    private var topPlate: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 2) {
                Engraving(text: "ISO \(r.map { "\($0.iso)" } ?? "—")", size: 10)
                Engraving(text: r?.emulsionName.uppercased() ?? L("engr_empty"), size: 10)
            }
            .padding(.bottom, 12)
            Spacer()
            Engraving(text: "RULLINO", size: 15).padding(.bottom, 22)
            Spacer()
            FrameCounter(
                number: rolling?.0 ?? counter,
                total: r?.exposures ?? 36,
                rollTo: rolling?.1,
                rollProgress: rolling?.2 ?? 0
            )
            .padding(.bottom, 10)
        }
        .padding(.horizontal, 32)
        .frame(height: 72)
        .background(BrushedMetal())
    }

    /* ---------------- Colonna sinistra: R, manovella, dorso ---------------- */

    private var leftColumn: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                ReleaseButton(on: releaseOn, enabled: (r?.shotCount ?? 0) > 0) { releaseOn.toggle(); unloadTurns = 0 }
                Engraving(text: "REWIND", size: 10)
            }
            RewindCrank(
                enabled: (r?.shotCount ?? 0) > 0 && releaseOn,
                highlight: releaseOn,
                resetKey: AnyHashable("\(r?.id ?? 0)-\(releaseOn)"),
                diameter: 112,
                onProgress: { crank = $0 },
                onFullTurn: fullTurn
            )
            .accessibilityLabel(L("crank"))
            ScrollView {
                VStack(spacing: 10) {
                    backWindow
                    Text(statusText)
                        .font(mono).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
                    if let r, r.shotCount == 0 {
                        Button(L("cam_change_roll")) { store.unloadEmptyRoll(r) }
                            .font(.system(size: 12)).foregroundStyle(Palette.muted)
                    } else if r != nil {
                        Button(L("cam_open_back")) { showBack = true }
                            .font(.system(size: 12)).buttonStyle(.bordered).tint(Palette.ink)
                    } else {
                        Button(L("cam_developed_rolls"), action: goRolls)
                            .font(.system(size: 12)).foregroundStyle(Palette.muted)
                    }
                }
            }
        }
    }

    private func fullTurn() {
        guard let roll = r else { return }
        if roll.isAtEnd {
            unloadTurns += 1
            if unloadTurns >= turnsNeeded {
                releaseOn = false; unloadTurns = 0
                store.rewindOut(roll)
            }
        } else if roll.cursor > 1 {
            // Torna alla posa precedente, già pronta: lo scatto successivo ci va sopra.
            optimistic = roll.cursor - 1
            store.rewindFrame(roll)
            if let n = store.activeRoll { optimisticKey = "\(n.id)-\(n.cursor)-\(n.wound)" }
        } else {
            Haptics.reject()
        }
    }

    private var statusText: String {
        guard let r else { return L("cam_no_roll") }
        if releaseOn && r.isAtEnd { return L("cam_rewinding", unloadTurns * 100 / turnsNeeded) }
        if releaseOn { return L("cam_r_hint") }
        if r.isAtEnd { return L("cam_at_end") }
        if r.cursor <= r.shotCount { return L("cam_frame_exposed", r.cursor, r.shotCount, r.exposures) }
        return L("cam_count", r.shotCount, r.exposures)
    }

    private var backWindow: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8).fill(Color(hex: 0x0C0B0B))
            RoundedRectangle(cornerRadius: 8).stroke(Palette.line, lineWidth: 2)
            if let r {
                VStack(spacing: 2) {
                    Text(r.emulsionName.uppercased()).font(.system(size: 11, weight: .bold)).foregroundStyle(Palette.ink).lineLimit(1)
                    Text("ISO \(r.iso) · \(r.exposures)").font(.system(size: 10, design: .monospaced)).foregroundStyle(Palette.safelight)
                }
            } else {
                Text(L("window_empty")).font(.system(size: 12, design: .monospaced)).foregroundStyle(Palette.muted)
            }
        }
        .frame(width: 132, height: 70)
    }

    /* ---------------- Mirino ---------------- */

    private var centerColumn: some View {
        VStack(spacing: 8) {
            ZStack {
                Color.black
                viewfinderContent
                if blackout { Color.black }
            }
            .aspectRatio(1.5, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(hex: 0x0B0A0A), lineWidth: 3))
            .frame(maxHeight: .infinity)
            ZoomRing(focal: $focal, maxFocal: camera.maxFocal, enabled: camera.authorized == true)
                .frame(maxWidth: 360)
        }
    }

    @ViewBuilder
    private var viewfinderContent: some View {
        if camera.authorized == false {
            VStack(spacing: 10) {
                Text(L("cam_need_permission")).foregroundStyle(Palette.ink)
                Button(L("grant")) {
                    if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
                }.buttonStyle(.borderedProminent).tint(Palette.safelight)
            }
        } else if let r {
            CameraPreview(camera: camera)
            VStack {
                HStack {
                    if r.wound && store.exposuresOnFrame(r) > 0 {
                        badge(L("double_exposure_badge", r.cursor))
                    }
                    Spacer()
                    Text("\(Int(focal.rounded())) mm").font(.system(size: 13, design: .monospaced)).foregroundStyle(Palette.ink)
                        .padding(.horizontal, 8).padding(.vertical, 3).background(Color.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 4))
                }
                Spacer()
                if r.isAtEnd {
                    badge(L("cam_finished_note", r.shotCount))
                } else if !r.wound {
                    badge(L("wind_first"))
                }
                if store.developProgress != nil { badge(L("developing")) }
            }
            .padding(10)
        } else {
            VStack(spacing: 12) {
                Text(L("cam_empty")).foregroundStyle(Palette.ink)
                Button(L("choose_roll")) { showPicker = true }.buttonStyle(.borderedProminent).tint(Palette.safelight)
            }
        }
    }

    private func badge(_ s: String) -> some View {
        Text(s).font(.system(size: 12)).foregroundStyle(Palette.ink).multilineTextAlignment(.center)
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(Color.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
    }

    /* ---------------- Scatto ---------------- */

    private func shoot() {
        guard let current = r, current.wound, !busy, camera.authorized == true else { Haptics.reject(); return }
        busy = true
        blackout = true
        withAnimation(.easeOut(duration: 0.22).delay(0.05)) { blackout = false }
        let (url, rel) = store.rawFile(for: current)
        camera.capture(to: url) { ok in
            if ok { store.recordShot(current.id, file: rel) } else { store.say("shot_failed") }
            releaseOn = false
            unloadTurns = 0
            busy = false
        }
    }
}

/// I rullini che hai in magazzino: scegline uno da caricare.
struct RollPicker: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let goShop: () -> Void
    @State private var exposures = 36

    var body: some View {
        NavigationStack {
            List {
                let owned = store.emulsions.filter { store.owned($0) > 0 }
                if owned.isEmpty {
                    Text(L("picker_none"))
                    Button(L("go_shop"), action: goShop)
                } else {
                    Picker(L("exposures_label"), selection: $exposures) {
                        Text("24").tag(24); Text("36").tag(36)
                    }.pickerStyle(.segmented)
                    ForEach(owned) { e in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(e.name).font(.headline)
                                Text(L("iso_owned", e.iso, store.owned(e))).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button(L("load")) { store.loadRoll(e, exposures: exposures); dismiss() }
                                .buttonStyle(.borderedProminent).tint(Palette.safelight)
                        }
                    }
                    Button(L("buy_more"), action: goShop)
                }
            }
            .navigationTitle(L("choose_roll"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("close")) { dismiss() } } }
        }
    }
}
