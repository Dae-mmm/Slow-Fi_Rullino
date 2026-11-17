import SwiftUI
import UniformTypeIdentifiers

/// Modulo per creare o modificare un concorso (solo OP). existing nil = concorso nuovo.
struct ContestEditor: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var model: ContestsModel
    let existing: Contest?
    let close: () -> Void
    @State private var d = ContestDraft()
    @State private var importing = false
    @State private var confirmDelete = false

    private let durations: [(Int, String)] = [(3, "cm_3d"), (7, "cm_1w"), (14, "cm_2w"), (30, "cm_1m")]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(L("cm_kicker").uppercased()).font(Fonts.mono(10)).tracking(1.4).foregroundStyle(Palette.safelight)
                    Text(L(existing == nil ? "cm_new" : "cm_edit")).font(Fonts.barlow(26, .semibold)).foregroundStyle(Palette.ink)
                }
                Spacer()
                if existing != nil { Button(L("delete")) { confirmDelete = true }.foregroundStyle(Color(hex: 0xFF8A80)) }
                Button(L("cancel"), action: close).buttonStyle(.secondary)
                if existing == nil || existing?.status == "draft" {
                    Button(L("cm_save_draft")) { save("draft") }.buttonStyle(.secondary).disabled(model.busy)
                    Button(L("cm_publish")) { save("open") }.buttonStyle(.primary).disabled(model.busy)
                } else {
                    Button(L("cm_save")) { save(nil) }.buttonStyle(.primary).disabled(model.busy)
                }
            }
            ScrollView {
                HStack(alignment: .top, spacing: 20) {
                    // Cosa e quando.
                    VStack(alignment: .leading, spacing: 10) {
                        section(L("cm_sec_what"))
                        field(L("cm_title"), $d.title)
                        field(L("cm_theme"), $d.theme)
                        TextField(L("cm_description"), text: $d.description, axis: .vertical).lineLimit(3...6)
                            .padding(10).background(RoundedRectangle(cornerRadius: 3).stroke(Palette.line))
                        section(L("cm_sec_when"))
                        HStack {
                            DatePicker(L("cm_starts"), selection: Binding(get: { d.startsAt }, set: { s in
                                let len = d.endsAt.timeIntervalSince(d.startsAt); d.startsAt = s; d.endsAt = s.addingTimeInterval(len)
                            })).foregroundStyle(Palette.muted)
                            Button(L("cm_now")) {
                                let len = d.endsAt.timeIntervalSince(d.startsAt); d.startsAt = Date(); d.endsAt = d.startsAt.addingTimeInterval(len)
                            }.foregroundStyle(Palette.safelight)
                        }
                        DatePicker(L("cm_ends"), selection: $d.endsAt, in: d.startsAt...).foregroundStyle(Palette.muted)
                        HStack(spacing: 6) {
                            ForEach(durations.indices, id: \.self) { i in
                                let (days, key) = durations[i]
                                pill(L(key), d.endsAt.timeIntervalSince(d.startsAt) == Double(days * 86_400)) {
                                    d.endsAt = d.startsAt.addingTimeInterval(Double(days * 86_400))
                                }
                            }
                        }
                        Text(L("cm_when_note")).font(Fonts.barlow(12)).foregroundStyle(Palette.faint)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    // Pellicola e premi.
                    VStack(alignment: .leading, spacing: 10) {
                        section(L("cm_sec_film"))
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 6, alignment: .leading)], alignment: .leading, spacing: 6) {
                            pill(L("cm_any_film"), d.requiredEmulsion == nil) { d.requiredEmulsion = nil; d.giftRoll = false }
                            ForEach(store.catalog ?? []) { item in
                                pill("\(item.name) · \(item.iso)", d.requiredEmulsion == item.id) { d.requiredEmulsion = item.id }
                            }
                        }
                        if store.catalog == nil { Text(L("ct_loading")).font(Fonts.barlow(12)).foregroundStyle(Palette.faint) }
                        Toggle(isOn: $d.giftRoll) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(L("cm_gift")).foregroundStyle(Palette.ink)
                                Text(L("cm_gift_note")).font(Fonts.barlow(12)).foregroundStyle(Palette.faint)
                            }
                        }
                        .tint(Palette.safelight).disabled(d.requiredEmulsion == nil)
                        if d.giftRoll {
                            HStack(spacing: 6) {
                                Text(L("cm_exposures")).foregroundStyle(Palette.muted)
                                ForEach([12, 24, 36], id: \.self) { n in pill("\(n)", d.giftExposures == n) { d.giftExposures = n } }
                            }
                        }
                        section(L("ct_prizes_title"))
                        HStack(spacing: 8) {
                            ForEach(0..<3, id: \.self) { i in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(L("cm_place_coins", i + 1)).font(Fonts.mono(10)).foregroundStyle(Palette.muted)
                                    TextField("0", value: Binding(get: { d.prizeCoins[i] }, set: { d.prizeCoins[i] = max(0, min(99_999, $0)) }), format: .number)
                                        .keyboardType(.numberPad)
                                        .padding(8).background(RoundedRectangle(cornerRadius: 3).stroke(Palette.line))
                                }
                            }
                        }
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(L("ct_prize_exclusive")).foregroundStyle(Palette.ink)
                                Text(d.prizeEmulsionUrl != nil ? L("cm_prize_loaded", d.prizeEmulsion ?? "") : L("cm_prize_none"))
                                    .font(Fonts.barlow(12)).foregroundStyle(d.prizeEmulsionUrl != nil ? Palette.safelight : Palette.faint)
                            }
                            Spacer()
                            if d.prizeEmulsionUrl != nil { Button(L("cm_remove")) { d.prizeEmulsion = nil; d.prizeEmulsionUrl = nil }.foregroundStyle(Palette.muted) }
                            Button(L("cm_choose_file")) { importing = true }.buttonStyle(.secondary).disabled(model.busy)
                        }
                        if d.prizeEmulsionUrl != nil {
                            field(L("cm_prize_name"), Binding(get: { d.prizeEmulsion ?? "" }, set: { d.prizeEmulsion = $0 }))
                        }
                        Text(L("cm_rules_note")).font(Fonts.barlow(12)).foregroundStyle(Palette.faint)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .padding(16)
        .background(Palette.body.ignoresSafeArea())
        .onAppear {
            if let existing { d = ContestDraft(existing) }
            if store.catalog == nil { Task { await store.loadCatalog() } }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [UTType(exportedAs: "com.slowfi.emulsion"), .zip, .data]) { result in
            guard case .success(let url) = result else { return }
            Task {
                if case let (name, link)? = await model.uploadPrize(url) {
                    if (d.prizeEmulsion ?? "").isEmpty { d.prizeEmulsion = name }
                    d.prizeEmulsionUrl = link
                }
            }
        }
        .alert(L("cm_delete_title", existing?.title ?? ""), isPresented: $confirmDelete) {
            Button(L("delete"), role: .destructive) { Task { if let existing, await model.delete(existing) { close() } } }
            Button(L("cancel"), role: .cancel) {}
        } message: { Text(L("cm_delete_text")) }
    }

    private func save(_ status: String?) {
        Task { if await model.save(existing, d, status: status) { close() } }
    }

    private func section(_ t: String) -> some View {
        Text(t.uppercased()).font(Fonts.mono(10)).tracking(1.4).foregroundStyle(Palette.safelight).padding(.top, 4)
    }

    private func field(_ label: String, _ text: Binding<String>) -> some View {
        TextField(label, text: text).padding(10).background(RoundedRectangle(cornerRadius: 3).stroke(Palette.line))
    }

    /// Scelta a pillola, accesa quando è selezionata.
    private func pill(_ text: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(Fonts.barlow(14)).lineLimit(1)
                .foregroundStyle(on ? Palette.safelight : Palette.ink)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(Capsule().fill(on ? Palette.safelightDark : .clear))
                .overlay(Capsule().stroke(on ? Color(hex: 0xA6472C) : Palette.line, lineWidth: 1))
        }
    }
}
