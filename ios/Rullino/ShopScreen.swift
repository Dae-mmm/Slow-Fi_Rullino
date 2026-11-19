import SwiftUI

/// Il Foto Lab: rullino del giorno, scaffale con le emulsioni e i gettoni.
struct ShopScreen: View {
    @EnvironmentObject var store: Store

    var body: some View {
        content.task { await store.refreshEconomy() }
    }

    private var content: some View {
        HStack(spacing: 0) {
            scene.frame(width: 300)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    PageHeading(kicker: L("kick_lab"), title: headline) {
                        VStack(alignment: .trailing, spacing: 0) {
                            Text("◉ \(store.data.coins)").font(Fonts.mono(24)).foregroundStyle(Palette.safelight)
                            Text(L("stock_total", store.data.inventory.values.reduce(0, +)).uppercased()).font(Fonts.mono(9)).foregroundStyle(Palette.muted)
                        }
                    }
                    panel(L("daily_title")) {
                        Text(store.dailyAvailable ? L("daily_available") : L("daily_taken"))
                            .font(Fonts.barlow(15)).foregroundStyle(store.dailyAvailable ? Palette.ink : Palette.muted)
                    }
                    panel(L("shelf_title", Store.rollPrice)) {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 240), spacing: 12)], spacing: 12) {
                            ForEach(store.emulsions) { e in card(e) }
                        }
                        Text(L("tokens_note")).font(Fonts.barlow(12)).foregroundStyle(Palette.muted)
                    }
                    panel(L("catalog_tab")) { catalogSection }
                }
                .padding(.horizontal, 20).padding(.vertical, 18)
            }
            .background(DottedPage())
        }
    }

    /// Titolo dal saluto: la prima frase ("Buongiorno.").
    private var headline: String {
        let g = greeting
        let first = g.split(whereSeparator: { "!.".contains($0) }).first.map(String.init) ?? g
        return first.trimmingCharacters(in: .whitespaces) + "."
    }

    private func panel<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title.uppercased()).font(Fonts.mono(10)).tracking(1.4).foregroundStyle(Palette.safelight)
            content()
        }
        .padding(.horizontal, 18).padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    /// La scena: muro blu notte, insegna al neon, negoziante, bancone.
    private var scene: some View {
        ZStack {
            Canvas { ctx, size in
                ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(hex: 0x15202A)))
                var y: CGFloat = 0
                while y < size.height { ctx.fill(Path(CGRect(x: 0, y: y, width: size.width, height: 2)), with: .color(Color(hex: 0x1C2A36))); y += 6 }
            }
            Shopkeeper().padding(.top, 70).padding(.bottom, 50).padding(.horizontal, 27)
            VStack(spacing: 0) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: -4) {
                        Text("FOTO").font(Fonts.barlow(26, .bold)).foregroundStyle(Color(hex: 0xFF7A4D)).shadow(color: Color(hex: 0xFF6A3D), radius: 8)
                        Text("LAB").font(Fonts.barlow(26, .bold)).foregroundStyle(Color(hex: 0x7FE0D0)).shadow(color: Color(hex: 0x4FD1C0), radius: 8)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 4)
                    .background(Color(hex: 0x0E1218))
                    .overlay(Rectangle().stroke(Color(hex: 0x3A2620), lineWidth: 4))
                    Spacer()
                    Text(greeting).font(Fonts.barlow(13, .semibold)).foregroundStyle(Color(hex: 0x1A1420))
                        .padding(.horizontal, 10).padding(.vertical, 8)
                        .frame(width: 150, alignment: .leading)
                        .background(Color(hex: 0xF2E8D5))
                        .overlay(Rectangle().stroke(Color(hex: 0x1A1420), lineWidth: 2))
                }
                .padding(.horizontal, 14).padding(.top, 16)
                Spacer()
                Rectangle().fill(Color(hex: 0x8A5A36)).frame(height: 9)
                ZStack {
                    Canvas { ctx, size in
                        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(hex: 0x5B3820)))
                        var x: CGFloat = 0
                        while x < size.width { ctx.fill(Path(CGRect(x: x, y: 0, width: 2, height: size.height)), with: .color(Color(hex: 0x4A2D19))); x += 32 }
                    }
                    Text(L("since").uppercased()).font(Fonts.barlow(10)).tracking(3).foregroundStyle(Color(hex: 0xB98A64))
                }
                .frame(height: 48)
            }
        }
        .overlay(alignment: .trailing) { Rectangle().fill(Color(hex: 0x0B0A0A)).frame(width: 3) }
    }

    @ViewBuilder
    private var catalogSection: some View {
        if let items = store.catalog {
            if items.isEmpty {
                Text(L("store_empty")).foregroundStyle(Palette.muted)
            }
            ForEach(items) { item in catalogRow(item) }
        } else if store.catalogFailed {
            HStack {
                Text(L("store_unreachable")).foregroundStyle(Palette.muted)
                Button(L("retry")) { Task { await store.loadCatalog() } }.buttonStyle(.secondary)
            }
        } else {
            Text(L("store_loading")).foregroundStyle(Palette.muted)
                .task { await store.loadCatalog() }
        }
    }

    private func catalogRow(_ item: CatalogItem) -> some View {
        HStack(spacing: 12) {
            Group {
                if let s = item.boxUrl, let u = URL(string: s) {
                    AsyncImage(url: u) { img in img.resizable().scaledToFit() } placeholder: { Color.clear }
                } else {
                    FilmBox(name: item.name, iso: item.iso, tone: BoxTone.of(bw: item.type == "bw", slide: false), compact: true)
                }
            }
            .frame(width: 72, height: 82)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).font(Fonts.barlow(18, .semibold)).foregroundStyle(Palette.ink)
                Text("ISO \(item.iso) · " + (item.type == "bw" ? L("tag_bw") : L("color_long")) + (item.sizeBytes > 0 ? " · \(item.sizeBytes / 1024) KB" : ""))
                    .font(mono).foregroundStyle(Palette.muted)
                if !item.note.isEmpty { Text(item.note).font(.caption).foregroundStyle(Palette.muted).lineLimit(2) }
            }
            Spacer()
            if store.downloading.contains(item.id) {
                ProgressView()
            } else if store.needsUpdate(item) {
                Button(L("update")) { Task { await store.download(item) } }.buttonStyle(.primary)
            } else if store.isInstalled(item) {
                Text(L("installed")).font(.caption).foregroundStyle(Palette.muted)
            } else if item.priceCents > 0 {
                Text(L("soon")).font(.caption).foregroundStyle(Palette.muted)
            } else {
                Button(L("free")) { Task { await store.download(item) } }.buttonStyle(.primary)
            }
        }
        .padding(12)
        .card()
    }

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        return h < 13 ? L("greet_morning") : (h < 19 ? L("greet_afternoon") : L("greet_evening"))
    }

    private func card(_ e: Emulsion) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                FilmBox(name: e.name, iso: e.iso, tone: BoxTone.of(bw: e.isBw, slide: e.isSlide), exposures: e.exposures, compact: true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(e.name).font(Fonts.barlow(18, .semibold)).foregroundStyle(Palette.ink)
                    Text("ISO \(e.iso) · " + (e.isBw ? L("bw_long") : L("color_long"))).font(.caption).foregroundStyle(Palette.muted)
                }
            }
            if !e.note.isEmpty { Text(e.note).font(.caption).foregroundStyle(Palette.muted).lineLimit(2) }
            let n = store.owned(e)
            Text(n == 1 ? L("in_stock_one") : (n > 1 ? L("in_stock_many", n) : L("none_in_stock")))
                .font(.caption2).foregroundStyle(Palette.muted)
            HStack {
                if store.dailyAvailable {
                    Button(L("free_today")) { Task { await store.takeFree(e) } }.buttonStyle(.primary)
                }
                Button(L("buy") + " · \(Store.rollPrice)") { Task { await store.buy(e) } }.buttonStyle(.secondary)
                    .disabled(store.data.coins < Store.rollPrice)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }
}

/// Negoziante segnaposto in pixel art (verrà sostituito dalla grafica vera).
struct Shopkeeper: View {
    private let rows = [
        "..hhhh..",
        ".hhhhhh.",
        ".ffffff.",
        ".fefffe.",
        ".ffffff.",
        "..fmmf..",
        ".cccccc.",
        "cccaaccc",
        "cccaaccc",
        "cc.aa.cc",
    ]
    var body: some View {
        Canvas { ctx, size in
            let px = min(size.width / 8, size.height / CGFloat(rows.count)).rounded(.down)
            let left = (size.width - px * 8) / 2, top = size.height - px * CGFloat(rows.count)
            for (y, row) in rows.enumerated() {
                for (x, ch) in row.enumerated() {
                    let c: Color?
                    switch ch {
                    case "h": c = Color(hex: 0x5A3A22)
                    case "f": c = Color(hex: 0xE8B98F)
                    case "e": c = Color(hex: 0x1A1210)
                    case "m": c = Color(hex: 0xA0522D)
                    case "c": c = Color(hex: 0x2F6B8F)
                    case "a": c = Color(hex: 0xEEEEEE)
                    default: c = nil
                    }
                    if let c { ctx.fill(Path(CGRect(x: left + CGFloat(x) * px, y: top + CGFloat(y) * px, width: px, height: px)), with: .color(c)) }
                }
            }
        }
        .accessibilityLabel(L("shopkeeper"))
    }
}
