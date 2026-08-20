import Foundation

/// Backend address.
///
/// This is the only setting stored on the device — everything else (Spatius / Agora
/// credentials, avatar, sample rate) lives in the backend's .env, shared by all three
/// platforms, and is read and written by clients through `/api/config`.
///
/// Why it isn't derived automatically the way the Web client does it: a web page can
/// read `location.hostname` because the backend runs on the same machine; a phone
/// cannot reach the dev machine's localhost, so the user has to type the LAN address.
/// The backend prints it on startup, and `GET /health` returns it as `lanUrl`.
enum RtcConfig {

    private static let key = "tutoring.backendBaseUrl"

    /// The simulator runs on the host machine, so localhost is directly reachable.
    static let defaultBaseURL = "http://localhost:8787"

    static var baseURL: String {
        get {
            let saved = UserDefaults.standard.string(forKey: key) ?? ""
            return saved.isEmpty ? defaultBaseURL : saved
        }
        set {
            let normalized = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
                .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            UserDefaults.standard.set(normalized, forKey: key)
        }
    }
}
