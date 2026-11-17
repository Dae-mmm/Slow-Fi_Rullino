import AVFoundation
import SwiftUI
import UIKit

/// Fotocamera posteriore: anteprima "pulita" come un mirino ottico e scatto su file.
final class CameraController: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate {
    let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "rullino.camera")
    private(set) var device: AVCaptureDevice?
    private var configured = false
    private var pending: [Int64: (URL, (Bool) -> Void)] = [:]
    private var rotation: AVCaptureDevice.RotationCoordinator?
    weak var previewLayer: AVCaptureVideoPreviewLayer? { didSet { attachRotation() } }

    @Published var authorized: Bool?
    /// Fattore di zoom che corrisponde all'obiettivo principale (24 mm circa).
    @Published private(set) var baseZoom: CGFloat = 1
    @Published private(set) var maxZoom: CGFloat = 1
    /// Luce della scena (EV a ISO 100) letta dall'esposizione automatica del telefono: è l'esposimetro.
    @Published private(set) var ev100: Double?
    private var meterTimer: Timer?
    /// Uno scatto a tempo vero ha messo l'esposizione in manuale: va rimessa in automatico dopo.
    private var manualPending = false

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            authorized = true
            run()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async {
                    self.authorized = ok
                    if ok { self.run() }
                }
            }
        default:
            authorized = false
        }
    }

    func stop() {
        queue.async { if self.session.isRunning { self.session.stopRunning() } }
    }

    private func run() {
        queue.async {
            if !self.configured { self.configure() }
            if !self.session.isRunning { self.session.startRunning() }
        }
    }

    private func configure() {
        session.beginConfiguration()
        session.sessionPreset = .photo
        let types: [AVCaptureDevice.DeviceType] = [.builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera, .builtInWideAngleCamera]
        let found = AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: .back).devices.first
        guard let cam = found, let input = try? AVCaptureDeviceInput(device: cam), session.canAddInput(input) else {
            session.commitConfiguration(); return
        }
        session.addInput(input)
        if session.canAddOutput(output) { session.addOutput(output) }
        output.maxPhotoQualityPrioritization = .quality
        session.commitConfiguration()
        device = cam
        configured = true

        // Sui dispositivi con più obiettivi lo zoom 1 è il grandangolo spinto: partiamo dal principale.
        let base = cam.virtualDeviceSwitchOverVideoZoomFactors.first.map { CGFloat(truncating: $0) } ?? 1
        let top = min(cam.maxAvailableVideoZoomFactor, base * 135 / 24)
        try? cam.lockForConfiguration()
        cam.videoZoomFactor = base
        cam.unlockForConfiguration()
        DispatchQueue.main.async {
            self.baseZoom = base
            self.maxZoom = max(top, base)
            self.attachRotation()
            self.meterTimer?.invalidate()
            self.meterTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
                guard let self, let d = self.device, d.exposureMode != .custom else { return }
                let t = CMTimeGetSeconds(d.exposureDuration)
                let iso = Double(d.iso), n = Double(d.lensAperture)
                guard t > 0, iso > 0, n > 0 else { return }
                // exposureTargetOffset: di quanto l'immagine è sotto (−) o sopra (+) l'esposizione giusta,
                // per esempio di notte quando il telefono è arrivato al limite di tempo e ISO.
                let offset = VirtualCamera.deadZone(Double(d.exposureTargetOffset))
                self.ev100 = VirtualCamera.ev100(seconds: t, fNumber: n, iso: iso) + Double(offset)
            }
        }
    }

    private func attachRotation() {
        guard let device, let layer = previewLayer else { return }
        let rc = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: layer)
        rotation = rc
        layer.connection?.videoRotationAngle = rc.videoRotationAngleForHorizonLevelPreview
    }

    func refreshPreviewRotation() {
        guard let rc = rotation, let c = previewLayer?.connection else { return }
        let a = rc.videoRotationAngleForHorizonLevelPreview
        if c.isVideoRotationAngleSupported(a) { c.videoRotationAngle = a }
    }

    /// Focale equivalente (24–135 mm) → fattore di zoom del dispositivo.
    func setFocal(_ mm: CGFloat) {
        let z = min(max(baseZoom * mm / 24, baseZoom), maxZoom)
        queue.async {
            guard let d = self.device, (try? d.lockForConfiguration()) != nil else { return }
            d.videoZoomFactor = z
            d.unlockForConfiguration()
        }
    }

    var maxFocal: CGFloat { 24 * maxZoom / max(baseZoom, 0.01) }

    /// Scatta. Con seconds il tempo è vero: il sensore resta aperto per quel tempo (nei limiti del telefono)
    /// e gli ISO portano la foto a un'esposizione media, così il mosso è giusto e la pellicola resta simulata.
    func capture(to url: URL, seconds: Double? = nil, done: @escaping (Bool) -> Void) {
        let angle = rotation?.videoRotationAngleForHorizonLevelCapture
        queue.async {
            guard self.configured else { DispatchQueue.main.async { done(false) }; return }
            if let seconds, let d = self.device, d.isExposureModeSupported(.custom), (try? d.lockForConfiguration()) != nil {
                let f = d.activeFormat
                let tAuto = CMTimeGetSeconds(d.exposureDuration), isoAuto = Double(d.iso)
                let offset = Double(VirtualCamera.deadZone(Double(d.exposureTargetOffset)))
                let t = min(max(seconds, CMTimeGetSeconds(f.minExposureDuration)), CMTimeGetSeconds(f.maxExposureDuration))
                let iso = Float(min(max(isoAuto * tAuto / t * pow(2, -offset), Double(f.minISO)), Double(f.maxISO)))
                let sem = DispatchSemaphore(value: 0)
                d.setExposureModeCustom(duration: CMTime(seconds: t, preferredTimescale: 1_000_000), iso: iso) { _ in sem.signal() }
                d.unlockForConfiguration()
                _ = sem.wait(timeout: .now() + 3)
                self.manualPending = true
            }
            if let a = angle, let c = self.output.connection(with: .video), c.isVideoRotationAngleSupported(a) {
                c.videoRotationAngle = a
            }
            let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
            settings.photoQualityPrioritization = .quality
            self.pending[settings.uniqueID] = (url, done)
            self.output.capturePhoto(with: settings, delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let id = photo.resolvedSettings.uniqueID
        queue.async {
            guard let (url, done) = self.pending.removeValue(forKey: id) else { return }
            var ok = false
            if error == nil, let d = photo.fileDataRepresentation() {
                ok = (try? d.write(to: url, options: .atomic)) != nil
            }
            if self.manualPending, let d = self.device, (try? d.lockForConfiguration()) != nil {
                d.exposureMode = .continuousAutoExposure
                d.unlockForConfiguration()
                self.manualPending = false
            }
            DispatchQueue.main.async { done(ok) }
        }
    }
}

/// Vista di anteprima.
struct CameraPreview: UIViewRepresentable {
    let camera: CameraController

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
        var onLayout: (() -> Void)?
        override func layoutSubviews() { super.layoutSubviews(); onLayout?() }
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.backgroundColor = .black
        v.previewLayer.session = camera.session
        v.previewLayer.videoGravity = .resizeAspectFill
        camera.previewLayer = v.previewLayer
        v.onLayout = { [weak camera] in camera?.refreshPreviewRotation() }
        return v
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}
}
