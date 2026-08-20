import Foundation

/// Connection credentials for one classroom session, issued by the backend.
struct SessionCredentials: Decodable {
    /// Later say / interrupt / free-talk / stop calls use this to find the session.
    let sessionId: String
    let appId: String
    let channelName: String
    let token: String
    let uid: UInt
    /// The conversational agent's uid.
    ///
    /// Used to tell whether it has joined the channel: the ConvoAI agent is started
    /// asynchronously only after the backend's `/api/session` returns, a second or two
    /// later than the client connects. A say sent during that window still gets a 200
    /// from the backend, but nobody speaks the line — it looks like "we're in the
    /// classroom but the question is never read out".
    let agentUid: UInt
    /// The avatar the backend actually started; the client loads this model.
    let avatarId: String
    /// Spatius app id and region, needed for SDK initialization.
    let spatiusAppId: String
    let spatiusRegion: String
}

/// What the companion remembers so far, as reported by `/api/memory`.
struct MemorySummary: Decodable {
    /// Earlier conversations folded into notes, empty until the first compaction.
    let summary: String
    /// How many turns are held raw, on top of the summary.
    let turns: Int
    /// Total characters, which is what the compaction threshold is measured against.
    let size: Int
}

/// Backend client.
///
/// The backend runs on the user's own machine (`backend/`) with the
/// credentials configured in its .env; all this asks for is a session. The endpoints
/// are identical to the Web and Android clients'.
enum AgentClient {

    private static var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        return URLSession(configuration: config)
    }()

    private static func request(_ path: String, baseURL: String = RtcConfig.baseURL) throws -> URLRequest {
        guard let url = URL(string: baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path) else {
            throw NSError(domain: "AgentClient", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "Invalid backend URL"])
        }
        return URLRequest(url: url)
    }

    @discardableResult
    private static func post(path: String, body: [String: Any]) async throws -> Data {
        var req = try request(path)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            // The backend puts the reason in an error field — better than echoing raw
            // HTML back.
            let detail = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw NSError(domain: "AgentClient", code: code,
                          userInfo: [NSLocalizedDescriptionKey: detail ?? "HTTP \(code)"])
        }
        return data
    }

    /// Start a classroom session. **Billing starts on this call** — always call
    /// ``stopSession(sessionId:)`` when leaving.
    ///
    /// The avatar is left to the backend: it lives in the .env and the config page can
    /// already change it.
    /// - Parameter lang: the UI language. The avatar's persona has to follow it,
    ///   otherwise after switching to English the teacher answers in Chinese as soon as
    ///   the student speaks — the questions and the read-aloud stem are already in
    ///   English, so only the LLM path gives it away.
    /// The characters this backend offers, with their cover art.
    ///
    /// Fetched rather than compiled in: adding a character is then a backend change
    /// instead of a rebuild of three apps, and the cover art stays a CDN URL rather than
    /// going back into the release cycle as a bundled image.
    static func fetchCatalogue() async -> [AvatarChoice] {
        guard let req = try? request("/api/catalogue") else { return [] }
        guard let (data, response) = try? await URLSession.shared.data(for: req),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = root["avatars"] as? [[String: Any]]
        else { return [] }

        return rows.compactMap { row in
            guard let id = row["id"] as? String, let name = row["name"] as? String else { return nil }
            return AvatarChoice(id: id, name: name, cover: row["coverUrl"] as? String ?? "")
        }
    }

    static func createSession(lang: String) async throws -> SessionCredentials {
        // Ask for Agora explicitly. This client ships the Agora SDK alone, and the
        // backend's TRANSPORT defaults to LiveKit — served that, we would get a room name
        // and a URL we cannot use, and fail on a decode error that names none of this.
        let data = try await post(
            path: "/api/session",
            body: ["lang": lang, "transport": "agora"]
        )
        do {
            return try JSONDecoder().decode(SessionCredentials.self, from: data)
        } catch {
            throw NSError(
                domain: "AgentClient",
                code: -2,
                userInfo: [
                    NSLocalizedDescriptionKey:
                        "Session response is missing the fields this client needs. Check "
                        + "the backend's Agora credentials."
                ]
            )
        }
    }

    /// Have the avatar speak a piece of text. Upstream limit is 512 bytes.
    ///
    /// Everything below is fire-and-forget: failures are swallowed. Failing to speak
    /// one bit of feedback should not interrupt the class.
    static func say(sessionId: String, text: String) async {
        guard !sessionId.isEmpty else { return }
        _ = try? await post(path: "/api/session/say", body: ["sessionId": sessionId, "text": text])
    }

    /// Interrupt whatever is being spoken.
    ///
    /// For cases where we moved on but have nothing new to say — flipping to a question
    /// that is already answered correctly, for instance: without this the agent finishes
    /// reading the previous question's stem, which no longer matches the screen.
    static func interrupt(sessionId: String) async {
        guard !sessionId.isEmpty else { return }
        _ = try? await post(path: "/api/session/interrupt", body: ["sessionId": sessionId])
    }

    /// Switch to the free-talk persona. The server only changes state and says nothing;
    /// the transition line is sent by the client via say.
    ///
    /// - Parameters:
    ///   - custom: a character written by the user, for the companion scene. The backend
    ///     ignores it for every other persona.
    ///   - memoryKey: which stored memory that character reads, for the companion scene.
    ///     Empty everywhere else, which the backend reads as the default.
    static func startFreeTalk(
        sessionId: String,
        lang: String,
        persona: String = "freetalk",
        custom: String = "",
        memoryKey: String = ""
    ) async {
        guard !sessionId.isEmpty else { return }
        _ = try? await post(path: "/api/session/free-talk",
                            body: [
                                "sessionId": sessionId,
                                "lang": lang,
                                "persona": persona,
                                "custom": custom,
                                "memoryKey": memoryKey,
                            ])
    }

    /// End the session. **Must be called** — a session bills continuously from the
    /// moment it is created.
    ///
    /// - Parameter remember: which memory to keep this conversation in, or nil to keep
    ///   nothing. Only the companion scene passes one — the other three start fresh every
    ///   time. On the Agora path the transcript lives with the agent and goes away when it
    ///   stops, so collecting it is part of stopping rather than a call of its own.
    static func stopSession(sessionId: String, remember: String? = nil) async {
        guard !sessionId.isEmpty else { return }
        _ = try? await post(path: "/api/session/stop",
                            body: [
                                "sessionId": sessionId,
                                "remember": remember == nil ? "" : "1",
                                "persona": remember ?? "",
                            ])
    }

    /// What the companion remembers so far.
    ///
    /// Read on entry rather than kept on the device: the memory is a file next to the
    /// backend, so a phone and a laptop talking to one backend are talking to the same
    /// companion.
    static func fetchMemory(persona: String) async -> MemorySummary {
        do {
            // A memory key is `<persona id>-<lang>` and needs no escaping in practice, but
            // the custom one can carry whatever id ends up there, so it is escaped anyway.
            let escaped = persona.addingPercentEncoding(
                withAllowedCharacters: .alphanumerics
            ) ?? persona
            let req = try request("/api/memory?persona=\(escaped)")
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                throw NSError(domain: "AgentClient", code: -1,
                              userInfo: [NSLocalizedDescriptionKey: "memory read failed"])
            }
            return try JSONDecoder().decode(MemorySummary.self, from: data)
        } catch {
            // An empty memory rather than a failure: a companion that cannot read what it
            // remembers should still be able to talk, and the scene shows "nothing yet".
            print("[AgentClient] memory read failed: \(error)")
            return MemorySummary(summary: "", turns: 0, size: 0)
        }
    }

    /// Forget everything for one memory, so the scene can be shown from a blank slate.
    static func clearMemory(persona: String) async {
        _ = try? await post(path: "/api/memory/clear", body: ["persona": persona])
    }

    /// Whether the backend is up; returns the transport currently in effect.
    ///
    /// The config page uses this to verify the address right after it is entered, so a
    /// typo does not surface only once you are in the classroom.
    /// Whether the backend at this address answers.
    ///
    /// The transport it reports is deliberately ignored: this client ships the Agora SDK
    /// alone and asks for that transport explicitly, so naming the backend's setting on
    /// screen offers a choice that does not exist here and shows a word the user can do
    /// nothing about.
    static func health(baseURL: String) async throws {
        let req = try request("/health", baseURL: baseURL)
        let (_, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw NSError(domain: "AgentClient", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "No response from backend"])
        }
    }

    /// The backend's current config. Key names match EDITABLE_KEYS in server.py.
    static func fetchConfig() async throws -> [String: String] {
        let req = try request("/api/config")
        let (data, _) = try await session.data(for: req)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        // `_fields` lists the fields each transport needs; it is not a config value.
        return json.reduce(into: [String: String]()) { acc, entry in
            guard !entry.key.hasPrefix("_"), let value = entry.value as? String else { return }
            acc[entry.key] = value
        }
    }

    /// Write back to the backend's .env; takes effect immediately.
    static func saveConfig(_ config: [String: String]) async throws {
        _ = try await post(path: "/api/config", body: config)
    }
}
