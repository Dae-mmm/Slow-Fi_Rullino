import SwiftUI

/// Concorsi: bacheca dei vincitori, concorsi aperti, in giuria e chiusi.
struct ContestsScreen: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var account: Account
    @EnvironmentObject var model: ContestsModel
    let goCamera: () -> Void
    let goAccount: () -> Void

    private var role: Role { account.profile?.role ?? .user }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                PageHeading(kicker: L("ct_kicker"), title: L("tab_contests")) {
                    if account.signedIn {
                        if model.loading { ProgressView().tint(Palette.safelight) }
                        else { Button(L("ct_refresh")) { Task { await model.refresh() } }.buttonStyle(.secondary) }
                    }
                }
                if !account.signedIn {
                    Text(L("ct_sign_in")).foregroundStyle(Palette.muted)
                    Button(L("ct_go_account"), action: goAccount).buttonStyle(.primary)
                } else {
                    if let e = model.error { Text(e).foregroundStyle(Palette.safelight) }
                    if !model.board.isEmpty {
                        section(L("ct_board"))
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 10) {
                                ForEach(model.board) { b in boardCard(b.contest, b.entry) }
                            }
                        }
                    }
                    let visible = model.contests.filter { $0.phase() != .draft || role == .op }
                    if visible.isEmpty && !model.loading { Text(L("ct_none")).foregroundStyle(Palette.muted) }
                    ForEach([Contest.Phase.open, .upcoming, .judging, .closed, .draft], id: \.self) { ph in
                        let group = visible.filter { $0.phase() == ph }
                        if !group.isEmpty {
                            section(phaseLabel(ph))
                            ForEach(group) { c in ContestCard(contest: c, goCamera: goCamera) }
                        }
                    }
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 18)
        }
        .background(DottedPage())
        .task(id: account.signedIn) { await model.refresh() }
        .fullScreenCover(item: Binding(get: { model.judging }, set: { if $0 == nil { model.closeJudging() } })) { c in
            JudgingView(contest: c, isOp: role == .op).environmentObject(model)
        }
    }

    private func section(_ t: String) -> some View {
        Text(t.uppercased()).font(Fonts.mono(10)).tracking(1.4).foregroundStyle(Palette.safelight).padding(.top, 6)
    }

    private func boardCard(_ c: Contest, _ e: ContestEntry) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ContestPhoto(path: e.photoPath).frame(width: 220, height: 147).clipped()
            HStack(spacing: 8) {
                Text("\(e.place ?? 0)°").font(Fonts.barlow(24, .semibold)).foregroundStyle(Palette.safelight)
                VStack(alignment: .leading, spacing: 0) {
                    Text(e.author ?? L("ct_anonymous")).font(Fonts.barlow(14)).foregroundStyle(Palette.ink).lineLimit(1)
                    Text(c.title).font(Fonts.barlow(11)).foregroundStyle(Palette.muted).lineLimit(1)
                }
                Spacer()
                if let a = e.avgScore { Text(String(format: "%.1f", a)).font(Fonts.mono(12)).foregroundStyle(Palette.muted) }
            }
        }
        .padding(10).frame(width: 240).card()
    }
}

func phaseLabel(_ p: Contest.Phase) -> String {
    switch p {
    case .draft: L("ct_phase_draft")
    case .upcoming: L("ct_phase_upcoming")
    case .open: L("ct_phase_open")
    case .judging: L("ct_phase_judging")
    case .closed: L("ct_phase_closed")
    }
}

private func dayTime(_ d: Date) -> String { d.formatted(date: .abbreviated, time: .shortened) }

struct ContestCard: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var account: Account
    @EnvironmentObject var model: ContestsModel
    let contest: Contest
    let goCamera: () -> Void

    var body: some View {
        let c = contest
        let phase = c.phase()
        let entry = model.mine[c.id]
        let emName = c.requiredEmulsion.map { id in store.emulsion(id)?.name ?? id }
        let isJudge = account.profile?.role == .judge || account.profile?.role == .op
        HStack(alignment: .top, spacing: 18) {
            VStack(alignment: .leading, spacing: 6) {
                Text(c.title.uppercased()).font(Fonts.barlow(22, .semibold)).foregroundStyle(Palette.ink)
                if !c.theme.isEmpty { Text(L("ct_theme", c.theme)).foregroundStyle(Palette.ink) }
                if !c.description.isEmpty { Text(c.description).font(Fonts.barlow(13)).foregroundStyle(Palette.muted) }
                Text(L("ct_dates", dayTime(c.startsAt), dayTime(c.endsAt))).font(Fonts.mono(11)).foregroundStyle(Palette.faint)
                HStack(spacing: 6) {
                    ChipView(text: phaseLabel(phase), active: phase == .open)
                    if c.giftRoll { ChipView(text: L("ct_chip_gift", c.giftExposures), active: true) }
                    if let emName { ChipView(text: emName) }
                }
                Text(L("ct_rules_title").uppercased()).font(Fonts.mono(10)).foregroundStyle(Palette.faint).padding(.top, 4)
                Group {
                    Text("· " + L(c.giftRoll ? "ct_rule_gift" : "ct_rule_during"))
                    if let emName, !c.giftRoll { Text("· " + L("ct_rule_emulsion", emName)) }
                    Text("· " + L("ct_rule_one"))
                    Text("· " + L("ct_rule_release"))
                }
                .font(Fonts.barlow(13)).foregroundStyle(Palette.muted)
                let coins = c.prizeCoins.filter { $0 > 0 }
                if !coins.isEmpty || c.prizeEmulsion != nil {
                    Text(L("ct_prizes_title").uppercased()).font(Fonts.mono(10)).foregroundStyle(Palette.faint).padding(.top, 4)
                    let lines = coins.enumerated().map { L("ct_prize_line", $0.offset + 1, $0.element) }
                        + (c.prizeEmulsion != nil ? [L("ct_prize_exclusive")] : []) + [L("ct_prize_board")]
                    Text(lines.joined(separator: "\n")).font(Fonts.barlow(13)).foregroundStyle(Palette.muted)
                }
            }
            Spacer(minLength: 0)
            VStack(alignment: .trailing, spacing: 8) {
                if let entry {
                    Text(L("ct_in_competition", entry.frame)).font(Fonts.mono(12)).foregroundStyle(Palette.safelight)
                    ContestPhoto(path: entry.photoPath).frame(width: 200, height: 133).clipped()
                    if phase == .open {
                        Button(L("ct_withdraw")) { Task { await model.withdraw(entry) } }.buttonStyle(.secondary).disabled(model.busy)
                    }
                    if phase == .closed {
                        if let r = model.results[entry.id] {
                            if let p = entry.place { Text(L("ct_your_place", p)).foregroundStyle(Palette.safelight) }
                            if let a = entry.avgScore { Text(L("ct_avg", String(format: "%.1f", a))).font(Fonts.mono(12)).foregroundStyle(Palette.ink) }
                            if r.isEmpty { Text(L("ct_no_votes")).font(Fonts.barlow(12)).foregroundStyle(Palette.muted) }
                            ForEach(Array(r.enumerated()), id: \.offset) { _, s in
                                Text("\(s.score)/10" + (s.note.map { " — \($0)" } ?? "")).font(Fonts.barlow(12)).foregroundStyle(Palette.muted)
                                    .multilineTextAlignment(.trailing)
                            }
                        } else {
                            Button(L("ct_see_votes")) { Task { await model.loadResults(entry) } }.buttonStyle(.secondary).disabled(model.busy)
                        }
                    }
                } else if phase == .open && c.giftRoll && !model.hasGiftRoll(c) {
                    Button(L(model.gifts[c.id] == nil ? "ct_take_gift" : "ct_reload_gift")) {
                        Task { if await model.takeGiftRoll(c) { goCamera() } }
                    }.buttonStyle(.primary).disabled(model.busy)
                    EngravedNote(text: L("ct_gift_note"))
                } else if phase == .open {
                    EngravedNote(text: L("ct_how_to_send"))
                } else if phase == .upcoming {
                    EngravedNote(text: L("ct_starts", c.startsAt.formatted(date: .abbreviated, time: .omitted)))
                }
                if isJudge && phase == .judging {
                    Button(L("ct_judge")) { Task { await model.openJudging(c) } }.buttonStyle(.primary).disabled(model.busy)
                } else if phase == .judging && entry == nil {
                    EngravedNote(text: L("ct_jury_working"))
                }
            }
            .frame(width: 230, alignment: .trailing)
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading).card()
    }
}

/// Una foto in gara: si scarica dal bucket privato e resta in cache.
struct ContestPhoto: View {
    @EnvironmentObject var model: ContestsModel
    let path: String
    var fit = false
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color(hex: 0x0D0C0C)
            if let image {
                if fit { Image(uiImage: image).resizable().scaledToFit() }
                else { Image(uiImage: image).resizable().scaledToFill() }
            } else { ProgressView().tint(Palette.faint) }
        }
        .clipShape(RoundedRectangle(cornerRadius: 3))
        .task(id: path) { image = await model.image(path) }
    }
}

/* ---------------- Giuria ---------------- */

struct JudgingView: View {
    @EnvironmentObject var model: ContestsModel
    let contest: Contest
    let isOp: Bool
    @State private var open: ContestEntry?
    @State private var confirmClose = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(L("ct_jury").uppercased()).font(Fonts.mono(10)).tracking(1.4).foregroundStyle(Palette.safelight)
                    Text(contest.title).font(Fonts.barlow(26, .semibold)).foregroundStyle(Palette.ink)
                    Text(L("ct_jury_progress", model.myVotes.count, model.judgingEntries.count)).font(Fonts.mono(11)).foregroundStyle(Palette.muted)
                }
                Spacer()
                if isOp {
                    Button(L("ct_close_award")) { confirmClose = true }.buttonStyle(.primary)
                        .disabled(model.busy || model.judgingEntries.isEmpty)
                }
                Button(L("close")) { model.closeJudging() }.buttonStyle(.secondary)
            }
            Text(L("ct_jury_blind")).font(Fonts.barlow(12)).foregroundStyle(Palette.faint)
            if model.judgingEntries.isEmpty { Text(L(model.busy ? "ct_loading" : "ct_no_entries")).foregroundStyle(Palette.muted) }
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 200), spacing: 10)], spacing: 10) {
                    ForEach(model.judgingEntries) { e in
                        ContestPhoto(path: e.photoPath).frame(height: 133).clipped()
                            .overlay(alignment: .topTrailing) {
                                let v = model.myVotes[e.id]
                                Text(v.map { "\($0.score)/10" } ?? "—").font(Fonts.mono(12))
                                    .foregroundStyle(v != nil ? Palette.safelight : Palette.muted)
                                    .padding(.horizontal, 6).padding(.vertical, 2).background(Color.black.opacity(0.65)).padding(6)
                            }
                            .onTapGesture { open = e }
                    }
                }
            }
        }
        .padding(16)
        .background(Palette.body.ignoresSafeArea())
        .sheet(item: $open) { e in
            VoteView(entry: e, score: model.myVotes[e.id]?.score ?? 0, note: model.myVotes[e.id]?.note ?? "") { open = nil }
                .environmentObject(model)
        }
        .alert(L("ct_close_award"), isPresented: $confirmClose) {
            Button(L("ct_close_award"), role: .destructive) { Task { await model.close(contest) } }
            Button(L("cancel"), role: .cancel) {}
        } message: { Text(L("ct_close_text")) }
    }
}

struct VoteView: View {
    @EnvironmentObject var model: ContestsModel
    let entry: ContestEntry
    @State var score: Int
    @State var note: String
    let done: () -> Void

    var body: some View {
        HStack(spacing: 16) {
            ContestPhoto(path: entry.photoPath, fit: true).frame(maxWidth: .infinity, maxHeight: .infinity)
            VStack(alignment: .leading, spacing: 10) {
                Text(L("ct_your_vote").uppercased()).font(Fonts.mono(10)).tracking(1.4).foregroundStyle(Palette.safelight)
                ForEach([Array(1...5), Array(6...10)], id: \.self) { row in
                    HStack(spacing: 6) {
                        ForEach(row, id: \.self) { v in
                            Button { score = v } label: {
                                Text("\(v)").font(Fonts.mono(16, .semibold)).frame(width: 44, height: 40)
                                    .foregroundStyle(score == v ? Color(hex: 0x1B0E09) : Palette.ink)
                                    .background(RoundedRectangle(cornerRadius: 3).fill(score == v ? Palette.safelight : Palette.plate))
                                    .overlay(RoundedRectangle(cornerRadius: 3).stroke(score == v ? Color(hex: 0xFF8A66) : Palette.line, lineWidth: 1))
                            }
                        }
                    }
                }
                TextField(L("ct_note_optional"), text: $note, axis: .vertical).lineLimit(3...6)
                    .padding(8).background(RoundedRectangle(cornerRadius: 3).stroke(Palette.line))
                    .onChange(of: note) { _, n in if n.count > 500 { note = String(n.prefix(500)) } }
                HStack {
                    Button(L("ct_vote")) { Task { await model.vote(entry, score: score, note: note); done() } }
                        .buttonStyle(.primary).disabled(!(1...10).contains(score) || model.busy)
                    Button(L("cancel"), action: done).buttonStyle(.secondary)
                }
                Spacer()
            }
            .frame(width: 280)
        }
        .padding(16)
        .background(Palette.body.ignoresSafeArea())
    }
}

/* ---------------- Invio dal lentino ---------------- */

struct SendToContestView: View {
    @EnvironmentObject var model: ContestsModel
    let roll: Roll
    let frame: DevFrame
    let url: URL
    let contests: [Contest]
    let done: () -> Void
    @State private var chosen: Contest?
    @State private var people = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L("ct_send_title", frame.frame)).font(Fonts.barlow(22, .semibold)).foregroundStyle(Palette.ink)
            ForEach(contests) { c in
                Button { chosen = c } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(c.title).font(Fonts.barlow(16, .semibold)).foregroundStyle(Palette.ink)
                        Text(L("ct_until", dayTime(c.endsAt))).font(Fonts.barlow(12)).foregroundStyle(Palette.muted)
                    }
                    .padding(10).frame(maxWidth: .infinity, alignment: .leading).card(selected: chosen?.id == c.id)
                }
            }
            Toggle(L("ct_has_people"), isOn: $people).tint(Palette.safelight).foregroundStyle(Palette.ink)
            // Su iPhone le liberatorie non ci sono ancora: le foto con persone si mandano da Android.
            if people { Text(L("ct_ios_no_release")).font(Fonts.barlow(13)).foregroundStyle(Palette.safelight) }
            Text(L("ct_send_note")).font(Fonts.barlow(12)).foregroundStyle(Palette.muted)
            HStack {
                Button(L(model.busy ? "ct_sending" : "ct_send")) {
                    guard let c = chosen else { return }
                    Task { if await model.submit(c, roll: roll, frame: frame, url: url) { done() } }
                }
                .buttonStyle(.primary).disabled(chosen == nil || people || model.busy)
                Button(L("cancel"), action: done).buttonStyle(.secondary)
            }
            Spacer()
        }
        .padding(20)
        .background(Palette.body.ignoresSafeArea())
        .onAppear { chosen = contests.first }
    }
}
