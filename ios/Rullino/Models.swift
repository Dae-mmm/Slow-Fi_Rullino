import Foundation

extension KeyedDecodingContainer {
    /// Valore se presente, altrimenti il predefinito (come i default di kotlinx-serialization).
    func v<T: Decodable>(_ key: Key, _ def: T) -> T {
        (try? decodeIfPresent(T.self, forKey: key)) ?? def
    }
}

/// Ordine dei settori dei colori selettivi: deve coincidere con lo shader.
let hueKeys = ["red", "orange", "yellow", "green", "aqua", "blue", "purple", "magenta"]

struct HslAdj: Decodable {
    var hue: Float = 0, sat: Float = 0, lum: Float = 0
    enum CodingKeys: String, CodingKey { case hue, sat, lum }
    init() {}
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        hue = c.v(.hue, 0); sat = c.v(.sat, 0); lum = c.v(.lum, 0)
    }
}

struct SplitTone: Decodable {
    var shadowHue: Float = 210, shadowAmount: Float = 0, highlightHue: Float = 40, highlightAmount: Float = 0, balance: Float = 0
    enum CodingKeys: String, CodingKey { case shadowHue, shadowAmount, highlightHue, highlightAmount, balance }
    init() {}
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        shadowHue = c.v(.shadowHue, 210); shadowAmount = c.v(.shadowAmount, 0)
        highlightHue = c.v(.highlightHue, 40); highlightAmount = c.v(.highlightAmount, 0); balance = c.v(.balance, 0)
    }
}

struct MonoMix: Decodable {
    var enabled = false, red: Float = 30, green: Float = 59, blue: Float = 11
    enum CodingKeys: String, CodingKey { case enabled, red, green, blue }
    init() {}
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        enabled = c.v(.enabled, false); red = c.v(.red, 30); green = c.v(.green, 59); blue = c.v(.blue, 11)
    }
}

struct GrainParams: Decodable {
    var amount: Float = 20, size: Float = 1.2, roughness: Float = 50
    enum CodingKeys: String, CodingKey { case amount, size, roughness }
    init() {}
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        amount = c.v(.amount, 20); size = c.v(.size, 1.2); roughness = c.v(.roughness, 50)
    }
}

/// Parametri come li esporta Camera Oscura (unità dell'interfaccia, −100…100 salvo eccezioni).
struct FilmParams: Decodable {
    var exposure: Float = 0, temperature: Float = 0, tint: Float = 0, gamma: Float = 1
    var contrast: Float = 0, highlights: Float = 0, shadows: Float = 0, fade: Float = 0
    var saturation: Float = 0, vibrance: Float = 0
    var hsl: [String: HslAdj] = [:]
    var split = SplitTone()
    var mono = MonoMix()
    var grain = GrainParams()
    var vignette: Float = 0
    /// "negative" (predefinito) o "slide": decide come la pellicola reagisce agli errori di esposizione.
    var process = ""
    /// Alone rosso attorno alle luci forti, 0…100 (tipo CineStill).
    var halation: Float = 0

    enum CodingKeys: String, CodingKey {
        case exposure, temperature, tint, gamma, contrast, highlights, shadows, fade, saturation, vibrance, hsl, split, mono, grain, vignette, process, halation
    }
    init() {}
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        exposure = c.v(.exposure, 0); temperature = c.v(.temperature, 0); tint = c.v(.tint, 0)
        gamma = c.v(.gamma, 1); contrast = c.v(.contrast, 0); highlights = c.v(.highlights, 0)
        shadows = c.v(.shadows, 0); fade = c.v(.fade, 0); saturation = c.v(.saturation, 0)
        vibrance = c.v(.vibrance, 0); hsl = c.v(.hsl, [:]); split = c.v(.split, SplitTone())
        mono = c.v(.mono, MonoMix()); grain = c.v(.grain, GrainParams()); vignette = c.v(.vignette, 0)
        process = c.v(.process, ""); halation = c.v(.halation, 0)
    }
}

/// Un'emulsione (look della pellicola), come esce da un file .emulsion.
struct Emulsion: Decodable, Identifiable {
    var id: String
    var name: String
    var iso: Int
    var exposures: Int
    var type: String
    var note: String
    var params: FilmParams

    var isBw: Bool { type == "bw" || params.mono.enabled }
    var isSlide: Bool {
        switch params.process.lowercased() {
        case "slide", "reversal", "positive": return true
        case "negative": return false
        default: return [id, name].contains { s in let l = s.lowercased(); return l.contains("diapositiva") || l.contains("slide") || l.contains("chrome") }
        }
    }

    enum CodingKeys: String, CodingKey { case id, name, iso, exposures, type, note, params }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = c.v(.name, "")
        iso = c.v(.iso, 400); exposures = c.v(.exposures, 36)
        type = c.v(.type, "color"); note = c.v(.note, ""); params = c.v(.params, FilmParams())
    }
}

enum RollState: String, Codable { case loaded, shelved, finished, developing, developed }

struct Roll: Codable, Identifiable, Equatable {
    var id: Int
    var emulsionId: String
    var emulsionName: String
    var iso: Int
    var exposures: Int
    var isBw: Bool
    var cursor = 1          // posa su cui cade il prossimo scatto
    var shotCount = 0       // pose esposte almeno una volta
    var wound = false       // leva caricata: si può scattare
    var state = RollState.loaded
    var createdAt = Date()
    var readyAt: Date?
    /// Sviluppo chiesto al laboratorio: -1 pull, 0 normale, +1/+2 push.
    var push = 0

    init(id: Int, emulsionId: String, emulsionName: String, iso: Int, exposures: Int, isBw: Bool) {
        self.id = id; self.emulsionId = emulsionId; self.emulsionName = emulsionName
        self.iso = iso; self.exposures = exposures; self.isBw = isBw
    }

    enum CodingKeys: String, CodingKey {
        case id, emulsionId, emulsionName, iso, exposures, isBw, cursor, shotCount, wound, state, createdAt, readyAt, push
    }

    // Decodifica tollerante: i campi nuovi non fanno perdere i rullini salvati.
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        emulsionId = try c.decode(String.self, forKey: .emulsionId)
        emulsionName = c.v(.emulsionName, ""); iso = c.v(.iso, 400); exposures = c.v(.exposures, 36)
        isBw = c.v(.isBw, false); cursor = c.v(.cursor, 1); shotCount = c.v(.shotCount, 0)
        wound = c.v(.wound, false); state = c.v(.state, .loaded); createdAt = c.v(.createdAt, Date())
        readyAt = c.v(.readyAt, nil); push = c.v(.push, 0)
    }

    var canWind: Bool { state == .loaded && !wound && cursor <= exposures }
    var isAtEnd: Bool { state == .loaded && cursor > exposures }
}

struct Exposure: Codable, Equatable {
    var rollId: Int
    var frame: Int
    var file: String        // relativo alla cartella dei dati
    var shutter: Double?    // tempo scelto sulla ghiera, in secondi; nil = automatico
}

struct DevFrame: Codable, Equatable {
    var rollId: Int
    var frame: Int
    var file: String
    var layers: Int
    var printed = false
}

enum LabTime: String, Codable, CaseIterable {
    case hour, threeHours, day
    var seconds: TimeInterval {
        switch self { case .hour: 3600; case .threeHours: 3 * 3600; case .day: 24 * 3600 }
    }
    var key: String {
        switch self { case .hour: "lab_1h"; case .threeHours: "lab_3h"; case .day: "lab_1d" }
    }
}

struct AppData: Codable, Equatable {
    var rolls: [Roll] = []
    var exposures: [Exposure] = []
    var frames: [DevFrame] = []
    var inventory: [String: Int] = [:]
    var coins = 0
    var lastDaily = ""
    var nextId = 1
    var labTime = LabTime.day
    var devMode = false
    var skipLab = false
    /// Versione del catalogo installata per ogni emulsione scaricata.
    var catalogVersions: [String: Int] = [:]
    /// Posizione della ghiera dei tempi (0 = automatico).
    var shutterIndex = 0

    init() {}

    // Decodifica tollerante: i campi aggiunti nelle versioni nuove non azzerano i dati salvati.
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        rolls = c.v(.rolls, []); exposures = c.v(.exposures, []); frames = c.v(.frames, [])
        inventory = c.v(.inventory, [:]); coins = c.v(.coins, 0); lastDaily = c.v(.lastDaily, "")
        nextId = c.v(.nextId, 1); labTime = c.v(.labTime, .day); devMode = c.v(.devMode, false)
        skipLab = c.v(.skipLab, false); catalogVersions = c.v(.catalogVersions, [:])
        shutterIndex = c.v(.shutterIndex, 0)
    }
}

/// Una riga del catalogo online (tabella `emulsions` su Supabase).
struct CatalogItem: Decodable, Identifiable {
    var id: String
    var name: String
    var iso: Int
    var exposures: Int
    var type: String
    var note: String
    var version: Int
    var sizeBytes: Int
    var priceCents: Int
    var boxUrl: String?
    var fileUrl: String

    enum CodingKeys: String, CodingKey {
        case id, name, iso, exposures, type, note, version
        case sizeBytes = "size_bytes", priceCents = "price_cents", boxUrl = "box_url", fileUrl = "file_url"
    }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        fileUrl = try c.decode(String.self, forKey: .fileUrl)
        name = c.v(.name, id); iso = c.v(.iso, 400); exposures = c.v(.exposures, 36)
        type = c.v(.type, "color"); note = c.v(.note, ""); version = c.v(.version, 1)
        sizeBytes = c.v(.sizeBytes, 0); priceCents = c.v(.priceCents, 0); boxUrl = c.v(.boxUrl, nil)
    }
}
