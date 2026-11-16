import SwiftUI

struct OptionsScreen: View {
    @EnvironmentObject var store: Store
    @State private var taps = 0

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }

    var body: some View {
        Form {
            Section(L("lab_title")) {
                Text(L("opt_lab_text")).font(.callout)
                Picker(L("lab_title"), selection: $store.data.labTime) {
                    ForEach(LabTime.allCases, id: \.self) { t in Text(L(t.key)).tag(t) }
                }
                .pickerStyle(.segmented)
                Text(L("opt_lab_note")).font(.caption).foregroundStyle(.secondary)
            }
            if store.data.devMode {
                Section(L("dev_mode")) {
                    Toggle(isOn: $store.data.skipLab) {
                        VStack(alignment: .leading) {
                            Text(L("skip_lab"))
                            Text(L("skip_lab_note")).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Button(L("plus100")) { store.data.coins += 100; store.say("plus100") }
                    Button(L("daily_again")) { store.data.lastDaily = ""; store.say("daily_again_msg") }
                    Button(L("dev_off"), role: .destructive) {
                        store.data.devMode = false; store.data.skipLab = false; taps = 0; store.say("dev_off_msg")
                    }
                }
            }
            Section {
                HStack {
                    Text(L("version", version))
                    Spacer()
                    if store.data.devMode { Text(L("you_are_dev")).foregroundStyle(.secondary) }
                    else if taps >= 3 { Text(L("taps_left", 7 - taps)).foregroundStyle(.secondary) }
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    guard !store.data.devMode else { return }
                    taps += 1
                    if taps >= 7 { store.data.devMode = true; Haptics.confirm() }
                }
            }
        }
        .scrollContentBackground(.hidden)
    }
}
