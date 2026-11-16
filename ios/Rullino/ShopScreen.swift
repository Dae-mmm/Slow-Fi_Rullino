import SwiftUI

/// Il Foto Lab: rullino del giorno, scaffale con le emulsioni e i gettoni.
struct ShopScreen: View {
    @EnvironmentObject var store: Store

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 14) {
                    Shopkeeper().frame(width: 72, height: 90)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(greeting).foregroundStyle(Palette.ink)
                        Text(L("coins", store.data.coins) + " · " + L("stock_total", store.data.inventory.values.reduce(0, +)))
                            .font(mono).foregroundStyle(Palette.muted)
                        Text(store.dailyAvailable ? L("daily_available") : L("daily_taken"))
                            .font(.caption).foregroundStyle(store.dailyAvailable ? Palette.safelight : Palette.muted)
                    }
                }
                .padding(14)
                .background(Palette.plateHi, in: RoundedRectangle(cornerRadius: 10))

                Text(L("shelf_title", Store.rollPrice)).font(.headline).foregroundStyle(Palette.ink)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 190), spacing: 12)], spacing: 12) {
                    ForEach(store.emulsions) { e in card(e) }
                }
                Text(L("tokens_note")).font(.caption).foregroundStyle(Palette.muted)
            }
            .padding(18)
        }
    }

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        return h < 13 ? L("greet_morning") : (h < 19 ? L("greet_afternoon") : L("greet_evening"))
    }

    private func card(_ e: Emulsion) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Canister(name: e.name, iso: e.iso, bw: e.isBw).frame(width: 56, height: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(e.name).font(.headline).foregroundStyle(Palette.ink)
                    Text("ISO \(e.iso) · " + (e.isBw ? L("bw_long") : L("color_long"))).font(.caption).foregroundStyle(Palette.muted)
                }
            }
            if !e.note.isEmpty { Text(e.note).font(.caption).foregroundStyle(Palette.muted).lineLimit(2) }
            let n = store.owned(e)
            Text(n == 1 ? L("in_stock_one") : (n > 1 ? L("in_stock_many", n) : L("none_in_stock")))
                .font(.caption2).foregroundStyle(Palette.muted)
            HStack {
                if store.dailyAvailable {
                    Button(L("free_today")) { store.takeFree(e) }.buttonStyle(.borderedProminent).tint(Palette.safelight)
                }
                Button(L("buy") + " · \(Store.rollPrice)") { store.buy(e) }.buttonStyle(.bordered).tint(Palette.ink)
                    .disabled(store.data.coins < Store.rollPrice)
            }
            .font(.system(size: 13))
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.plate, in: RoundedRectangle(cornerRadius: 10))
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
            let px = min(size.width / 8, size.height / CGFloat(rows.count))
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
                    if let c { ctx.fill(Path(CGRect(x: CGFloat(x) * px, y: CGFloat(y) * px, width: px, height: px)), with: .color(c)) }
                }
            }
        }
        .accessibilityLabel(L("shopkeeper"))
    }
}
