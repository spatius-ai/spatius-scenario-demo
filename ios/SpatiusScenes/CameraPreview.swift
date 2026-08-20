import AVFoundation
import SwiftUI

/// Front-camera preview of the student.
///
/// `resizeAspectFill` fills the square tile — the camera delivers 4:3, so crop to fill
/// rather than letterbox it.
struct CameraPreview: UIViewRepresentable {

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        context.coordinator.start(on: view)
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    static func dismantleUIView(_ uiView: PreviewView, coordinator: Coordinator) {
        coordinator.stop()
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    /// Making the layer itself the preview layer saves syncing the frame by hand.
    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

        /// The capture connection outputs portrait by default, so the image comes out
        /// rotated 90° once the UI is in landscape. Syncing the orientation on layout
        /// also keeps up when the device flips between the two landscape sides.
        override func layoutSubviews() {
            super.layoutSubviews()
            guard let connection = previewLayer.connection else { return }
            let orientation = window?.windowScene?.interfaceOrientation ?? .landscapeRight
            // Use videoOrientation rather than videoRotationAngle, which is iOS 17
            // only — the deployment target is iOS 16.
            let videoOrientation: AVCaptureVideoOrientation
            switch orientation {
            case .landscapeLeft: videoOrientation = .landscapeLeft
            case .portrait: videoOrientation = .portrait
            case .portraitUpsideDown: videoOrientation = .portraitUpsideDown
            default: videoOrientation = .landscapeRight
            }
            if connection.isVideoOrientationSupported {
                connection.videoOrientation = videoOrientation
            }
            // Mirror the front camera — it matches what people expect from a mirror.
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = true
            }
        }
    }

    final class Coordinator {
        private let session = AVCaptureSession()
        /// Capture configuration and start/stop go on a background queue to keep the
        /// main thread unblocked.
        private let queue = DispatchQueue(label: "camera.preview")

        func start(on view: PreviewView) {
            view.previewLayer.session = session
            view.previewLayer.videoGravity = .resizeAspectFill

            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard granted, let self else { return }
                self.queue.async {
                    self.configureAndRun()
                }
            }
        }

        private func configureAndRun() {
            session.beginConfiguration()
            session.sessionPreset = .high
            if let device = AVCaptureDevice.default(
                .builtInWideAngleCamera, for: .video, position: .front
            ), let input = try? AVCaptureDeviceInput(device: device),
               session.canAddInput(input) {
                session.addInput(input)
            }
            session.commitConfiguration()
            if !session.isRunning { session.startRunning() }
        }

        func stop() {
            queue.async { [session] in
                if session.isRunning { session.stopRunning() }
            }
        }
    }
}
