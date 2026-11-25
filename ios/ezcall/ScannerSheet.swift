import SwiftUI
import UIKit
import VisionKit

@MainActor
struct ScannerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var problem = false
    @State private var invalidCode = false
    let scanned: (String) -> Void

    var body: some View {
        NavigationStack {
            if DataScannerViewController.isSupported && DataScannerViewController.isAvailable && !problem {
                QRScanner(
                    scanned: { code in
                        if RelayWire.pairingSecret(code) != nil {
                            scanned(code)
                        } else {
                            invalidCode = true
                        }
                    },
                    failed: { problem = true }
                )
                .overlay(alignment: .bottom) {
                    Text(ezText(invalidCode ? "Use the code shown by ezcall on Android." : "Center the QR code in the camera."))
                        .font(.footnote)
                        .padding(12)
                        .background(.regularMaterial, in: Capsule())
                        .padding()
                        .allowsHitTesting(false)
                }
                .navigationTitle("Scan QR Code")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { cancel }
            } else {
                ContentUnavailableView {
                    Label("Camera Unavailable", systemImage: "camera")
                } description: {
                    Text(ezText(DataScannerViewController.isSupported
                         ? "Allow camera access to scan the pairing code."
                         : "Open ezcall on an iPhone to scan the pairing code."))
                } actions: {
                    if DataScannerViewController.isSupported {
                        Button("Open Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                            dismiss()
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .navigationTitle("Scan QR Code")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { cancel }
            }
        }
    }

    private var cancel: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
    }
}

@MainActor
private struct QRScanner: UIViewControllerRepresentable {
    let scanned: (String) -> Void
    let failed: () -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        ScannerController(scanned: scanned, failed: failed)
    }

    func updateUIViewController(_ controller: ScannerController, context: Context) {
        controller.scanned = scanned
        controller.failed = failed
    }
}

@MainActor
private final class ScannerController: UIViewController, DataScannerViewControllerDelegate {
    var scanned: (String) -> Void
    var failed: () -> Void

    private let scanner = DataScannerViewController(
        recognizedDataTypes: [.barcode(symbologies: [.qr])],
        qualityLevel: .balanced,
        recognizesMultipleItems: false,
        isHighFrameRateTrackingEnabled: false,
        isPinchToZoomEnabled: true,
        isGuidanceEnabled: true,
        isHighlightingEnabled: true
    )

    init(scanned: @escaping (String) -> Void, failed: @escaping () -> Void) {
        self.scanned = scanned
        self.failed = failed
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("Use the pairing scanner constructor.") }

    override func viewDidLoad() {
        super.viewDidLoad()
        scanner.delegate = self
        addChild(scanner)
        scanner.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scanner.view)
        NSLayoutConstraint.activate([
            scanner.view.topAnchor.constraint(equalTo: view.topAnchor),
            scanner.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scanner.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scanner.view.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
        scanner.didMove(toParent: self)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        do {
            try scanner.startScanning()
        } catch {
            failed()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        scanner.stopScanning()
    }

    func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem],
                     allItems: [RecognizedItem]) {
        guard let item = addedItems.first, case .barcode(let barcode) = item,
              let code = barcode.payloadStringValue else { return }
        scanned(code)
    }

    func dataScanner(_ dataScanner: DataScannerViewController,
                     becameUnavailableWithError error: DataScannerViewController.ScanningUnavailable) {
        failed()
    }
}
