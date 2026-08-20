import AVFoundation
import SwiftUI

@main
struct SpatiusScenesApp: App {
    /// Configure first, then enter the classroom. The avatar is picked on the config
    /// page too and written back to the backend's .env — the same flow as the Web
    /// client; the client no longer decides which avatar to use.
    @State private var scene: String?

    /// Set the audio session up at launch exactly as it needs to be once the mic is
    /// live, and never touch it again.
    ///
    /// Left alone it defaults to `.soloAmbient` (playback only), and when the mic goes
    /// live Agora needs a recording input, so it changes both category and mode.
    /// Configuring `.playAndRecord` + `.voiceChat` up front means it has nothing left
    /// to change; `.voiceChat` also turns on the system echo canceller — the classroom
    /// plays through the speaker, so a student's open mic picks up the teacher.
    ///
    /// ⚠️ Known issue: even with this, **turning on the mic still interrupts audio
    /// that is playing** (it sounds like a stutter, a rushed finish, then the real
    /// interruption). So the rebuild is not triggered by category/mode alone; the root
    /// cause is still open — do not assume this setup has fixed it.
    init() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(
                .playAndRecord,
                mode: .voiceChat,
                options: [.defaultToSpeaker, .allowBluetooth]
            )
            try session.setActive(true)
        } catch {
            // A failure here does not block launch: the mic falls back to the rebuild
            // described above, but the classroom still works.
            print("[SpatiusScenes] audio session setup failed: \(error)")
        }
    }

    var body: some Scene {
        WindowGroup {
            // The classroom covers the config page; going back is driven by the
            // classroom's own gesture (see ClassroomView). No NavigationStack: hiding
            // the navigation bar also disables the swipe-back gesture, and this
            // full-screen scene has no room for a permanent navigation bar.
            ZStack {
                ConfigView(onDone: { picked in scene = picked })
                if scene == "tutoring" {
                    ClassroomView(onExit: { scene = nil })
                        .transition(.move(edge: .trailing))
                } else if scene == "live" {
                    LiveRoomView(onExit: { scene = nil })
                        .transition(.move(edge: .trailing))
                } else if scene == "service" {
                    ServiceDeskView(onExit: { scene = nil })
                        .transition(.move(edge: .trailing))
                } else if scene == "companion" {
                    CompanionRoomView(onExit: { scene = nil })
                        .transition(.move(edge: .trailing))
                }
            }
            .animation(.easeInOut(duration: 0.25), value: scene)
            .preferredColorScheme(.light)
            // Only the classroom goes full screen; the config page involves typing, so
            // it keeps the status bar.
            .statusBarHidden(scene != nil)
        }
    }
}
