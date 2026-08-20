import SwiftUI

/// Gap between the two video tiles.
private let tileGap: CGFloat = 12

/// The avatar and student tiles, both square. In landscape they stack vertically on the
/// right; in portrait they sit in a row across the top.
///
/// The side length is computed once by `ClassroomView` from the screen orientation and
/// passed in — this view does not derive it itself, since computing it in two places
/// invites the two from disagreeing.
struct VideoPanel: View {
    @ObservedObject var session: AvatarRtcSession
    /// How the two tiles are arranged: a column on the right in landscape, a row at the
    /// top in portrait.
    var axis: Axis = .vertical
    /// Side length of one square tile, computed once by the parent from the orientation.
    var tileSize: CGFloat
    /// Whether free talk has started.
    var freeTalk: Bool = false
    /// Whether the recording permission has been granted.
    var micGranted: Bool = false
    /// Permission was denied: the mic goes grey, and tapping prompts for it again.
    var micDenied: Bool = false
    var onToggleFreeTalk: () -> Void = {}
    /// Whether the student's own camera is on.
    var cameraOn: Bool = true
    var onToggleCamera: () -> Void = {}

    var body: some View {
        // The parent computes the side length once and we set the size directly, rather
        // than negotiating through aspectRatio / fixedSize: a scaledToFill image inside
        // the ZStack stretches the container to the image's ratio, and an aspectRatio
        // on the outside then gets dragged along by the content, turning the square
        // into a rectangle.
        Group {
            if axis == .vertical {
                VStack(spacing: tileGap) { tiles }
            } else {
                HStack(spacing: tileGap) { tiles }
            }
        }
    }

    @ViewBuilder
    private var tiles: some View {
            // Top: the avatar. The classroom background sits under the render view,
            // since the model has a transparent background.
            //
            // Two layers: crop the picture to a square first, then overlay the mic.
            //
            // The mic cannot go in the same ZStack: scaledToFill on the background
            // stretches the ZStack's intrinsic size, and .frame only constrains the
            // displayed box — the inner alignment is still computed against the
            // stretched area, so the bottom-right corner gets pushed outside the box
            // and clipped. That is why only a sliver of it used to show on the left.
            ZStack(alignment: .bottomTrailing) {
                ZStack {
                    Image("classroom_background")
                        .resizable()
                        .scaledToFill()
                    AvatarStage(session: session)
                    if !session.isReady {
                        TeacherComingOverlay(detail: session.status, failed: session.hasFailed)
                    }
                }
                .frame(width: tileSize, height: tileSize)
                .clipShape(RoundedRectangle(cornerRadius: 16))

                MicButton(
                    active: freeTalk && micGranted,
                    denied: micDenied,
                    onTap: onToggleFreeTalk
                )
                .padding(10)
            }
            .frame(width: tileSize, height: tileSize)

            // Bottom: the student. Two layers as well, for the same reason — the button
            // goes outside the clip, otherwise it is pushed out of the box (the inner
            // content stretches the ZStack's intrinsic size).
            ZStack(alignment: .bottomTrailing) {
                Group {
                    if cameraOn {
                        CameraPreview()
                    } else {
                        // With the camera off, leave a solid tile and show no preview.
                        Color.clear
                    }
                }
                .frame(width: tileSize, height: tileSize)
                .background(
                    LinearGradient(
                        colors: [Color(red: 0, green: 0.54, blue: 0.48),
                                 Color(red: 0.15, green: 0.65, blue: 0.60)],
                        startPoint: .topLeading, endPoint: .bottomTrailing
                    )
                )
                .clipShape(RoundedRectangle(cornerRadius: 16))

                CameraButton(active: cameraOn, onTap: onToggleCamera)
                    .padding(10)
            }
            .frame(width: tileSize, height: tileSize)
    }
}

/// The mic toggle, overlaid on the bottom-right of the teacher tile.
///
/// Red = mic off (tap to enter free talk and go live), green = in conversation (tap to
/// hang up). Grey when permission was denied; tapping prompts for it again.
private struct MicButton: View {
    let active: Bool
    let denied: Bool
    let onTap: () -> Void

    private var background: Color {
        if denied { return Color(white: 0.62) }
        return active
            ? Color(red: 0.18, green: 0.62, blue: 0.36)
            : Color(red: 0.84, green: 0.27, blue: 0.27)
    }

    var body: some View {
        Button(action: onTap) {
            Text(denied ? "🚫" : "🎙")
                .font(.system(size: 20))
                .frame(width: 44, height: 44)
                .background(background)
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
    }
}

/// The camera toggle, overlaid on the bottom-right of the student tile.
///
/// Same color semantics as `MicButton`: green = camera on, red = off. It only affects
/// the local preview and involves no RTC publishing — the student's video is never sent
/// upstream in the first place.
private struct CameraButton: View {
    let active: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            Text(active ? "📹" : "🚫")
                .font(.system(size: 20))
                .frame(width: 44, height: 44)
                .background(active
                    ? Color(red: 0.18, green: 0.62, blue: 0.36)
                    : Color(red: 0.84, green: 0.27, blue: 0.27))
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
    }
}

/// The waiting overlay shown before the avatar is in place.
///
/// Downloading the model plus connecting RTC takes several seconds, during which the
/// tile is empty and needs to say something. It shows a single line and does not expose
/// internal stages such as download progress.
private struct TeacherComingOverlay: View {
    /// The stage message. Hidden during the normal flow; on failure the reason is shown
    /// to make troubleshooting easier.
    let detail: String
    let failed: Bool

    private var failureText: String? { failed ? detail : nil }

    var body: some View {
        VStack(spacing: 10) {
            ProgressView().tint(.white)
            Text(Localization.shared.t.teacherComing)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white)
            if let failureText {
                Text(failureText)
                    .font(.system(size: 12))
                    .foregroundStyle(.white.opacity(0.85))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.45))
    }
}
