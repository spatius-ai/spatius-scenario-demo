import SwiftUI

/// Sample rates at which the avatar accepts audio; the set supported by Motion Server.
private let sampleRates = ["8000", "16000", "22050", "24000", "32000", "44100", "48000"]
private let defaultSampleRate = "24000"
private let agoraConsole = URL(string: "https://console.agora.io/")!

/// Public sample avatars, the same list as the Web config page uses
/// (characters.ts in spatius-avatar-demo). Keep this in sync with that file when it
/// changes — do not start a separate list here.
/// A character to pick, from either source.
///
/// `cover` is either the name of a bundled image or an https URL: the built-in list stands
/// in until the backend answers, and the grid does not have to know which it is showing.
struct AvatarChoice: Identifiable, Equatable {
    let id: String
    let name: String
    let cover: String

    var coverURL: URL? { cover.hasPrefix("http") ? URL(string: cover) : nil }
}

private let avatarOptions: [(id: String, name: String, image: String)] = [
    ("41c62a7c-993c-4b6b-b6d3-549ce3c8be00", "Kian", "avatar_kian"),
    ("dbb01388-7c57-47bf-ab59-c492caeb9d90", "Julian", "avatar_julian"),
    ("d51ab422-3db7-47cc-afa8-7273b02bc70b", "Clara", "avatar_clara"),
    ("c7069121-8245-4015-9940-82d0dc0c6bda", "Halima", "avatar_halima"),
    ("8b86dda1-98ed-4acd-8a4e-b1a00ba69268", "Leyla", "avatar_leyla"),
    ("566981dd-1d95-4844-953e-d67e18b2fde8", "Adrian", "avatar_adrian"),
    ("981ed26d-fbfe-42eb-a5d5-56ebd104847b", "Haru", "avatar_haru"),
    ("56f31c71-58ff-410f-85d2-11b9658c7b49", "Ethan", "avatar_ethan"),
    ("e06640cb-e011-4806-bd3e-6b07575eff2e", "Samir", "avatar_samir"),
]

/// The config page's second step. Only one case — it exists to get the system's
/// navigation back gesture.
private enum Step: Hashable { case avatar }

/// Required fields. Missing any one of them means no connection, so it is better to
/// block on the button up front.
private let requiredKeys = [
    "SPATIUS_API_KEY", "SPATIUS_APP_ID",
    "AGORA_APP_ID", "AGORA_APP_CERTIFICATE", "AGORA_PIPELINE_ID",
]

/// The config page.
///
/// Two steps, matching the Web version: credentials first, then avatar. It configures
/// the same backend and writes the same .env; only the layout differs, reflowed into a
/// single vertically scrolling column for phones — the Web version's row of cards does
/// not fit in portrait.
///
/// One extra field compared to Web: the backend address. A web page can derive where the
/// backend is from location.hostname, but a phone cannot reach the dev machine's
/// localhost, so the LAN address has to be typed in — the backend prints it on startup,
/// and `/health` returns it as lanUrl.
struct ConfigView: View {
    let onDone: (String) -> Void

    /// Which scene to open. Saved with the rest on submit.
    @State private var scene = "tutoring"

    @ObservedObject private var localization = Localization.shared
    private var t: Strings { localization.t }

    @State private var path: [Step] = []
    @State private var baseURL = RtcConfig.baseURL
    @State private var config: [String: String] = [:]
    /// The status line, stored as a case rather than a finished string: built eagerly it
    /// freezes whatever language was current at the time, so switching afterwards would
    /// leave the old language on screen.
    @State private var status = ConfigStatus.none
    @State private var busy = false

    /// The cast, from the backend. The built-in list stands in until the request lands and
    /// stays if it fails — an unreachable backend should not leave the step with nothing.
    @State private var avatars: [AvatarChoice] = avatarOptions.map {
        AvatarChoice(id: $0.id, name: $0.name, cover: $0.image)
    }

    private var ready: Bool {
        requiredKeys.allSatisfy { !(config[$0] ?? "").trimmingCharacters(in: .whitespaces).isEmpty }
    }

    var body: some View {
        // The two steps use a real navigation stack: the second step is pushed, which is
        // what makes the system's swipe-back work — swiping back is the norm on phones,
        // and a hand-rolled "back" button would be both redundant and unidiomatic.
        NavigationStack(path: $path) {
            Form { credentialsStep }
                .navigationTitle(t.stepCredentials)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) { LangToggle() }
                }
                .navigationDestination(for: Step.self) { _ in
                    Form { avatarStep }
                        .navigationTitle(t.stepAvatar)
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) { LangToggle() }
                        }
                }
        }
        .task { await loadConfig() }
    }

    // MARK: - Step one: credentials

    @ViewBuilder
    private var credentialsStep: some View {
        // 1. Backend address. The only thing that has to be typed on the phone; copy the
        //    value from the terminal output when the backend starts up.
        Section(t.sectionBackendUrl) {
            field(t.fieldBackendUrl, text: $baseURL, keyboard: .URL)
            hint(t.backendUrlHint)
            Button(busy ? t.connecting : t.testConnection) { testConnection() }
                .disabled(busy)
            if !status.isEmpty {
                Text(status.text(t)).font(.caption)
            }
        }

        // 2. Credentials. Read-only — copying five secrets between apps on a phone is
        //    painful, and the keyboard mangles keys (auto-capitalization and
        //    autocorrect) in ways that are invisible afterwards. The user is already at
        //    a computer running the backend; filling them in there once covers all
        //    three platforms.
        Section {
            ForEach(credentialRows, id: \.key) { row in
                HStack {
                    Text(row.key).font(.system(.footnote, design: .monospaced))
                    Spacer()
                    Text(row.filled ? t.credentialConfigured : t.credentialMissing)
                        .font(.caption)
                        .foregroundStyle(row.filled ? Color.accentColor : .secondary)
                }
            }
        } header: {
            Text(t.sectionCredentials)
        } footer: {
            Text(t.credentialsHint)
        }

        Section {
            Button(t.next) { goNext() }
                .disabled(!ready)
        } footer: {
            if !ready { Text(t.fillAllFirst) }
        }
    }

    /// Whether each credential is configured. The phone only shows whether it is set and
    /// offers no editing.
    private var credentialRows: [(key: String, filled: Bool)] {
        requiredKeys.map { key in
            (key, !(config[key] ?? "").trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    // MARK: - Step two: avatar / voice / scene

    @ViewBuilder
    private var avatarStep: some View {
        Section(t.sectionAvatar) {
            // Tiles rather than one full-width row each: a row apiece turned nine
            // characters into a list longer than the screen, where the face — the only
            // thing telling them apart — was a thumbnail beside the name. Adaptive
            // columns give three across a phone held upright and more as it widens,
            // without naming a count per size class.
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 96), spacing: 10)],
                spacing: 10
            ) {
                ForEach(avatars) { option in
                    let selected = config["SPATIUS_AVATAR_ID"] == option.id
                    Button {
                        config["SPATIUS_AVATAR_ID"] = option.id
                    } label: {
                        VStack(spacing: 6) {
                            Group {
                                if let url = option.coverURL {
                                    AsyncImage(url: url) { image in
                                        image.resizable().scaledToFill()
                                    } placeholder: {
                                        Color.secondary.opacity(0.15)
                                    }
                                } else {
                                    Image(option.cover).resizable().scaledToFill()
                                }
                            }
                            .aspectRatio(1, contentMode: .fill)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            Text(option.name)
                                .font(.caption)
                                .lineLimit(1)
                                .foregroundStyle(.primary)
                        }
                        .padding(6)
                        // Selection has to read at a glance across a grid, which the
                        // checkmark alone did not do once the tiles got small.
                        .background(
                            RoundedRectangle(cornerRadius: 10)
                                .fill(selected ? Color.accentColor.opacity(0.12) : Color.clear)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .strokeBorder(selected ? Color.accentColor : .clear, lineWidth: 2)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 4)
        }

        // The voice belongs to the agent in the console and we cannot change it, so the
        // images point the way there; the sample rate has to match it.
        Section(t.sectionTtsModel) {
            guideImage("guide_agora_voice", url: agoraConsole)
            guideImage("guide_agora_5", url: agoraConsole)
            Picker(t.fieldSampleRate, selection: sampleRateBinding) {
                ForEach(sampleRates, id: \.self) { Text($0).tag($0) }
            }
            hint(t.sampleRateNote)
        }

        // One row per scene, picked here rather than on a screen of its own: which scene
        // to open is one more choice among the ones already on this page.
        Section(t.sectionScene) {
            SceneRow(
                label: t.sceneTutoring,
                image: "classroom_background",
                selected: scene == "tutoring"
            ) { scene = "tutoring" }
            SceneRow(
                label: t.sceneLive,
                image: "LiveRoomBackground",
                selected: scene == "live"
            ) { scene = "live" }
            SceneRow(
                label: t.sceneService,
                image: "ServiceDeskBackground",
                selected: scene == "service"
            ) { scene = "service" }
            SceneRow(
                label: t.sceneCompanion,
                image: "CompanionRoomBackground",
                selected: scene == "companion"
            ) { scene = "companion" }
        }

        Section {
            Button(busy ? t.saving : t.start) { save() }
                .disabled(busy)
            if !status.isEmpty {
                Text(status.text(t)).font(.caption)
            }
        }
    }

    // MARK: - Components

    /// A credential text field.
    ///
    /// Auto-capitalization and autocorrect are off: the keyboard mangles pasted keys,
    /// and the damage is invisible afterwards — it shows up as "I copied it correctly
    /// and it still won't connect".
    private func field(
        _ label: String,
        text: Binding<String>,
        keyboard: UIKeyboardType = .default
    ) -> some View {
        TextField(label, text: text)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(keyboard)
    }

    /// A console screenshot. Tapping opens the corresponding console; phones have no
    /// hover-to-zoom, so the whole image is laid out full width.
    private func guideImage(_ name: String, url: URL) -> some View {
        Link(destination: url) {
            Image(name)
                .resizable()
                .scaledToFit()
                .clipShape(RoundedRectangle(cornerRadius: 10))
        }
        .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 6, trailing: 0))
    }

    private func hint(_ text: String) -> some View {
        Text(text).font(.caption).foregroundStyle(.secondary)
    }

    private var sampleRateBinding: Binding<String> {
        Binding(
            get: {
                let value = config["AGORA_AVATAR_SAMPLE_RATE"] ?? ""
                return value.isEmpty ? defaultSampleRate : value
            },
            set: { config["AGORA_AVATAR_SAMPLE_RATE"] = $0 }
        )
    }

    // MARK: - Actions

    /// Fetch the backend's current config on entry: anything already filled in is shown
    /// back, so it does not have to be entered again.
    private func loadConfig() async {
        do {
            config = try await AgentClient.fetchConfig()
        } catch {
            status = .loadFailed(error.localizedDescription)
        }
    }

    private func testConnection() {
        busy = true
        status = .connecting
        RtcConfig.baseURL = baseURL
        Task {
            do {
                try await AgentClient.health(baseURL: baseURL)
                status = .backendOnline
                await loadConfig()
            } catch {
                status = .connectFailed(error.localizedDescription)
            }
            busy = false
        }
    }

    /// Save the credentials once before moving to step two.
    ///
    /// Submitting only on the last step is not enough: credentials are copied over one
    /// at a time from various consoles, so if things are interrupted at step two —
    /// switching away from the app, going back to a console to look something up — all
    /// of that work is lost and has to start over on return.
    private func goNext() {
        path.append(.avatar)
        RtcConfig.baseURL = baseURL
        Task { try? await AgentClient.saveConfig(config) }
        // Fetched here rather than when the view first appears: on first launch the
        // backend address is still the default, which on a phone points at the phone
        // itself, so the request fails and the step falls back to the bundled cast.
        // By this point the address has been entered and saved.
        Task {
            let fetched = await AgentClient.fetchCatalogue()
            if !fetched.isEmpty { avatars = fetched }
        }
    }

    private func save() {
        busy = true
        Task {
            do {
                try await AgentClient.saveConfig(config)
                onDone(scene)
            } catch {
                status = .saveFailed(error.localizedDescription)
            }
            busy = false
        }
    }
}


/// What the status line is currently reporting.
///
/// A case with its own data rather than a finished string, so the sentence is assembled
/// at display time and follows the language toggle.
private enum ConfigStatus {
    case none
    case connecting
    case backendOnline
    case connectFailed(String)
    case loadFailed(String)
    case saveFailed(String)

    func text(_ t: Strings) -> String {
        switch self {
        case .none: return ""
        case .connecting: return t.connecting
        case .backendOnline: return t.backendOnline
        case .connectFailed(let reason): return t.connectFailedShort(reason)
        case .loadFailed(let reason): return t.loadConfigFailed(reason)
        case .saveFailed(let reason): return t.saveFailed(reason)
        }
    }

    var isEmpty: Bool {
        if case .none = self { return true }
        return false
    }
}

/// One scene to pick from.
///
/// A row rather than the Web client's grid of squares: a phone has the width for one
/// across, and stacking them keeps the thumbnails big enough to tell apart.
private struct SceneRow: View {
    let label: String
    let image: String
    let selected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Image(image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 84, height: 56)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                Text(label).foregroundStyle(.primary)
                Spacer()
                if selected {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.tint)
                }
            }
        }
        .buttonStyle(.plain)
    }
}
