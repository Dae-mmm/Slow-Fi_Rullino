import SwiftUI

struct CameraScreen: View {
    @EnvironmentObject var store: Store
    @StateObject private var camera = CameraController()
    let goShop: () -> Void
    let goRolls: () -> Void

    @State private var busy = false
    @State private var blackout = false
    @State private var releaseOn = false
    @State private var crank: CGFloat = 0
    @State private var optimistic: Int?
    @State private var optimisticKey = ""
    @State private var showPicker = false
    @State private var focal: CGFloat = 24
    @State private var windProgress: CGFloat = 0

    private var r: Roll? { store.activeRoll }

    /// Esposimetro: la luce letta dal telefono confrontata con la pellicola e il tempo scelto.
    private var meter: MeterReading? {
        guard let r, r.state == .loaded, let ev = camera.ev100 else { return nil }
        return meterReading(ev100: ev, filmIso: r.iso, shutterIndex: store.data.shutterIndex)
    }

    var body: some View {
        VStack(spacing: 0) {
            topPlate
            HStack(spacing: 14) {
                leftColumn.frame(width: 150)
                centerColumn
                VStack(spacing: 2) {
                    let isArmed = (r?.wound ?? false) && !busy
                    EngravedNote(text: isArmed ? L("eng_armed") : L("eng_wind"), color: isArmed ? Palette.safelight : Color(hex: 0x756E68))
                    LeverShutter(
                        armed: isArmed,
                        leverEnabled: r?.canWind ?? false,
                        onShoot: shoot,
                        onAdvanced: { if let r { store.wind(r) } },
                        onProgress: { windProgress = $0 }
                    )
                    EngravedNote(text: L("eng_lever"))
                }
                .frame(width: 150)
            }
            .padding(.horizontal, 15).padding(.vertical, 10)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(DottedLeather())
        }
        .background(Palette.body)
        .onAppear { camera.start() }
        .onDisappear { camera.stop() }
        .onChange(of: r?.id) { _, _ in releaseOn = false }
        .onChange(of: focal) { _, f in camera.setFocal(f) }
        .sheet(isPresented: $showPicker) { RollPicker(goShop: { showPicker = false; goShop() }) }
    }

    /* ---------------- Contatore ---------------- */

    private var dbCounter: Int {
        guard let r else { return 0 }
        return r.wound ? r.cursor : r.cursor - 1
    }

    private var rollKey: String { r.map { "\($0.id)-\($0.cursor)-\($0.wound)" } ?? "none" }

    private var counter: Int { (optimisticKey == rollKey ? optimistic : nil) ?? dbCounter }

    /// (da, verso, avanzamento) mentre si gira la manovella.
    private var rolling: (Int, Int, CGFloat)? {
        guard let r, r.shotCount > 0, releaseOn else { return nil }
        guard crank > 0 else { return nil }
        let hasOpt = optimisticKey == rollKey && optimistic != nil
        if !hasOpt && r.cursor > 1 && counter != r.cursor - 1 { return (counter, r.cursor - 1, crank) }
        if hasOpt && counter > 1 { return (counter, counter - 1, crank) }
        return nil
    }

    /// Calotta del design: ISO e pellicola, targhetta e ghiera dei tempi, contapose.
    private var topPlate: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .lastTextBaseline, spacing: 4) {
                    Text("ISO").font(Fonts.barlow(11)).tracking(1.4).foregroundStyle(Palette.muted)
                    Text(r.map { "\($0.iso)" } ?? "—").font(Fonts.mono(25)).foregroundStyle(Palette.ink)
                }
                Text((r?.emulsionName ?? L("engr_empty")).uppercased()).font(Fonts.barlow(10)).tracking(1.2).foregroundStyle(Palette.muted).lineLimit(1)
            }
            .frame(width: 150, alignment: .leading)
            VStack(spacing: 3) {
                EngravedNote(text: "SHUTTER SPEED").padding(.top, 4)
                ShutterDial(index: $store.data.shutterIndex, recommended: meter?.recommendedIndex).frame(maxWidth: 440)
            }
            .frame(maxWidth: .infinity)
            HStack(spacing: 8) {
                Text(L("eng_frame")).font(Fonts.barlow(10)).tracking(1).foregroundStyle(Palette.ink)
                FrameCounter(
                    number: rolling?.0 ?? counter,
                    total: r?.exposures ?? 36,
                    rollTo: rolling?.1,
                    rollProgress: rolling?.2 ?? 0
                )
            }
            .frame(width: 150, alignment: .trailing)
        }
        .padding(.horizontal, 16)
        .frame(height: 78)
        .background(DarkMetal())
    }

    /* ---------------- Colonna sinistra: R, manovella, dorso ---------------- */

    private var leftColumn: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                ReleaseButton(on: releaseOn, enabled: (r?.shotCount ?? 0) > 0) { releaseOn.toggle() }
                EngravedNote(text: "REWIND")
                Spacer()
            }
            RewindCrank(
                enabled: (r?.shotCount ?? 0) > 0 && releaseOn,
                highlight: releaseOn,
                resetKey: AnyHashable("\(r?.id ?? 0)-\(releaseOn)"),
                diameter: 84,
                onProgress: { crank = $0 },
                onFullTurn: fullTurn
            )
            .accessibilityLabel(L("crank"))
            backWindow
            Text(statusText)
                .font(Fonts.barlow(12)).tracking(0.6).foregroundStyle(Palette.muted).multilineTextAlignment(.center).lineLimit(2)
            Spacer(minLength: 0)
            // Un solo tasto: carica un rullino, o scaricalo (lo ritrovi in Rullini).
            if let r {
                Button(L("cam_unload")) { releaseOn = false; store.unload(r) }.buttonStyle(.secondary)
            } else {
                Button(L("cam_load")) { showPicker = true }.buttonStyle(.primary)
            }
        }
    }

    private func fullTurn() {
        guard let roll = r else { return }
        if roll.cursor > 1 {
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
        if releaseOn { return L("cam_r_hint") }
        if r.isAtEnd { return L("cam_at_end") }
        if r.cursor <= r.shotCount { return L("cam_frame_exposed", r.cursor, r.shotCount, r.exposures) }
        return L("cam_count", r.shotCount, r.exposures)
    }

    private var backWindow: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 4).fill(Color(hex: 0x211F1D))
            if let r {
                Cartridge(name: r.emulsionName, iso: r.iso, bw: r.isBw, exposures: r.exposures, scale: 0.62)
            } else {
                Text(L("window_empty").uppercased()).font(Fonts.mono(10)).foregroundStyle(Color(hex: 0x625D57))
            }
            RoundedRectangle(cornerRadius: 4).stroke(Color(hex: 0x080808), lineWidth: 3)
        }
        .frame(width: 120, height: 56)
        .clipped()
    }

    /* ---------------- Mirino ---------------- */

    private var centerColumn: some View {
        VStack(spacing: 8) {
            ZStack {
                Color(hex: 0x090909)
                viewfinderContent
                ViewfinderGlass().allowsHitTesting(false)
                if blackout { Color.black }
            }
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 15).fill(Color(hex: 0x080808)))
            .shadow(color: .black.opacity(0.6), radius: 6, y: 6)
            .aspectRatio(1.5, contentMode: .fit)
            .frame(maxHeight: .infinity)
            VStack(spacing: 3) {
                ZoomRing(focal: $focal, maxFocal: camera.maxFocal, enabled: camera.authorized == true)
                    .frame(maxWidth: 350)
                EngravedNote(text: "FOCAL LENGTH · MM")
            }
        }
    }

    @ViewBuilder
    private var viewfinderContent: some View {
        if camera.authorized == false {
            VStack(spacing: 10) {
                Text(L("cam_need_permission")).foregroundStyle(Palette.ink)
                Button(L("grant")) {
                    if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
                }.buttonStyle(.primary)
            }
        } else if let r {
            if camera.simulated {
                // Simulatore: niente fotocamera, il mirino mostra la scena finta su cui si scatta.
                Image(uiImage: SimulatedScene.image(seed: 7)).resizable().scaledToFill().clipped()
                    .overlay(alignment: .bottom) { ViewfinderBadge(text: L("cam_simulated"), round: true).padding(.bottom, 90) }
            } else {
                CameraPreview(camera: camera)
            }
            VStack {
                HStack {
                    if r.wound && store.exposuresOnFrame(r) > 0 {
                        ViewfinderBadge(text: L("double_exposure_badge", r.cursor), color: Color(hex: 0xFFC0AA))
                    }
                    Spacer()
                    ViewfinderBadge(text: shutterLabel(shutterSpeeds[min(max(store.data.shutterIndex, 0), shutterSpeeds.count - 1)]) + " · \(Int(focal.rounded())) MM")
                }
                if !r.isAtEnd && !r.wound { badge(L("wind_first")) }
                Spacer()
                if r.isAtEnd {
                    badge(L("cam_finished_note", r.shotCount))
                }
                if store.developProgress != nil { badge(L("developing")) }
                if let meter, !r.isAtEnd {
                    ExposureMeter(reading: meter)
                        .frame(maxWidth: 260)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(RoundedRectangle(cornerRadius: 3).fill(Color(hex: 0x0A0908, alpha: 0.7)))
                }
            }
            .padding(10)
        } else {
            VStack(spacing: 12) {
                Text(L("cam_empty")).foregroundStyle(Palette.ink)
                Button(L("choose_roll")) { showPicker = true }.buttonStyle(.primary)
            }
        }
    }

    private func badge(_ s: String) -> some View { ViewfinderBadge(text: s, round: true) }

    /* ---------------- Scatto ---------------- */

    private func shoot() {
        guard let current = r, current.wound, !busy, camera.authorized == true else { Haptics.reject(); return }
        busy = true
        blackout = true
        let (url, rel) = store.rawFile(for: current)
        let shutter = shutterSpeeds[min(max(store.data.shutterIndex, 0), shutterSpeeds.count - 1)]
        // Quello che legge l'esposimetro adesso: lo sviluppo partirà da qui.
        let meterEv = camera.ev100
        // Breve buio dello specchio: il tempo agisce sull'esposizione della pellicola, il mirino resta in automatico.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
            withAnimation(.easeOut(duration: 0.18)) { blackout = false }
        }
        camera.capture(to: url) { ok in
            if ok { store.recordShot(current.id, file: rel, shutter: shutter, meterEv: meterEv) } else { store.say("shot_failed") }

            releaseOn = false
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
                let shelved = store.data.rolls.filter { $0.state == .shelved }
                if !shelved.isEmpty {
                    Section(L("picker_continue")) {
                        ForEach(shelved) { s in
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(s.emulsionName).font(.headline)
                                    Text(L("cam_count", s.shotCount, s.exposures)).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Button(L("load")) {
                                    if let a = store.activeRoll, a.shotCount == 0 { store.unloadEmptyRoll(a) }
                                    store.reload(s); dismiss()
                                }.buttonStyle(.primary)
                            }
                        }
                    }
                }
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
                            Button(L("load")) { Task { if await store.takeAndLoad(e, exposures: exposures) { dismiss() } } }
                                .buttonStyle(.primary)
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

/// Vetro del mirino: vignettatura, righe sottili e angoli di messa a fuoco.
struct ViewfinderGlass: View {
    var body: some View {
        Canvas { ctx, size in
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .radialGradient(
                Gradient(stops: [.init(color: .clear, location: 0.55), .init(color: .black.opacity(0.53), location: 1)]),
                center: CGPoint(x: size.width / 2, y: size.height / 2), startRadius: 0, endRadius: max(size.width, size.height) * 0.62))
            var y: CGFloat = 3
            while y < size.height { ctx.fill(Path(CGRect(x: 0, y: y, width: size.width, height: 1)), with: .color(.black.opacity(0.04))); y += 4 }
            let c = Color(hex: 0xF2EEE9, alpha: 0.67), l: CGFloat = 20
            var p = Path()
            let tl = CGPoint(x: size.width * 0.28, y: size.height * 0.28)
            p.move(to: CGPoint(x: tl.x + l, y: tl.y)); p.addLine(to: tl); p.addLine(to: CGPoint(x: tl.x, y: tl.y + l))
            let br = CGPoint(x: size.width * 0.72, y: size.height * 0.72)
            p.move(to: CGPoint(x: br.x - l, y: br.y)); p.addLine(to: br); p.addLine(to: CGPoint(x: br.x, y: br.y - l))
            ctx.stroke(p, with: .color(c), lineWidth: 1)
        }
    }
}
