import AvatarKit
import SwiftUI

/// The avatar rendering view.
///
/// The order is "start session → load avatar → connect RTC": both the avatar and the
/// Spatius app id are handed down by the backend along with the session (the config
/// page writes them into its .env), so model loading has to come after the session —
/// it cannot start from a client-side avatarId the way it used to.
struct AvatarStage: View {
    @ObservedObject var session: AvatarRtcSession

    var body: some View {
        ZStack {
            if let avatar = session.avatar {
                AvatarViewRepresentable(avatar: avatar, session: session)
            }
        }
        .task {
            await session.prepare()
        }
    }
}

/// Wraps the UIKit `AvatarView` for SwiftUI and connects RTC once the view is ready.
///
/// The view instance is owned by `AvatarRtcSession`: when SwiftUI rebuilds this view
/// — on rotation, for instance — the same `AvatarView` is reused, so an established
/// RTC session never loses its render target.
private struct AvatarViewRepresentable: UIViewRepresentable {
    let avatar: Avatar
    let session: AvatarRtcSession

    func makeUIView(context: Context) -> AvatarView {
        let view = session.obtainAvatarView(for: avatar)
        // Connecting is idempotent (the session guards it), so a rebuild does not
        // start a second session.
        Task { @MainActor in
            await session.connect(avatarView: view)
        }
        return view
    }

    func updateUIView(_ uiView: AvatarView, context: Context) {}

    /// On rebuild, detach the old view from its previous superview to avoid the
    /// "already has a parent view" constraint conflict.
    static func dismantleUIView(_ uiView: AvatarView, coordinator: ()) {
        uiView.removeFromSuperview()
    }
}
