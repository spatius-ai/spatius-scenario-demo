import AVFoundation
import SwiftUI

/// The companion: the avatar in the middle of a warm room, and nothing to do but talk.
///
/// The other three scenes are built around a task — questions to answer, an audience to
/// play to, a menu to work through. This one deliberately has none of that. The mic opens
/// on entry, the conversation runs on the LLM from the first word, and the only interface
/// is the room itself.
///
/// What makes it different from free talk in the other scenes is that it remembers.
/// Everything either side says is kept by the backend (see memory.py) and folded into the
/// persona next time, so the companion opens the second conversation already knowing how
/// the first one went. Past a few thousand characters the memory is summarised down rather
/// than growing without bound.
///
/// Laid out for a phone rather than reflowed from the Web version: the picker is a single
/// column of full-width rows instead of a grid, and what is remembered comes up as a sheet
/// from the bottom rather than a panel in the corner.
struct CompanionRoomView: View {
    /// How much of the screen height the avatar occupies, centred. Sized down from full
    /// bleed: at close to life size the figure looms over the room.
    private static let avatarHeightFraction = 0.6

    /// The avatar's height in landscape, as a share of the window's *short* edge. Android
    /// expresses this against `maxWidth`, which is the short edge there once the window has
    /// rotated; on iOS `geo.size.width` is the long edge, so the short edge is named
    /// explicitly — reusing the same expression makes the figure twice the screen's height.
    private static let landscapeAvatarHeightOfShortEdge = 0.9

    /// How many characters a hand-written character may run to. Matches the Web client:
    /// the text goes into a system prompt, and one that keeps growing crowds out
    /// everything the backend wraps around it.
    private static let customLimit = 600

    let onExit: () -> Void

    @StateObject private var session = AvatarRtcSession()
    @ObservedObject private var localization = Localization.shared

    private var t: Strings { localization.t }
    private var lang: Lang { localization.lang }

    /// Who is in the room, picked before it opens.
    ///
    /// The scene has no task, so the character is the whole of it — asking first, rather
    /// than dropping the user into a default, is what makes the choice feel like part of
    /// the scene instead of a setting. Nothing connects until this is answered: a session
    /// bills from the moment it is created, and on iOS that means `AvatarStage` is not
    /// mounted either, since mounting it is what starts one.
    @State private var chosen: Persona?

    /// A character the user wrote, used when they pick the custom entry.
    @State private var customPrompt = ""
    @State private var writingCustom = false

    /// The memory this session belongs to, fixed when the room opens.
    ///
    /// One per character and language — per character so that what you told the flatmate
    /// does not come back out of the mentor, per language because a conversation held in
    /// English goes into the persona verbatim and asking a model answering in Chinese to
    /// pull a detail out of English transcript adds a translation step that loses things.
    ///
    /// Fixed rather than followed live: the language toggle stays available inside the
    /// room, and following it would file the second half of a conversation under a memory
    /// the first half is not in — and against a persona the backend was never switched to.
    @State private var sessionMemoryKey = ""

    /// Whether the mic is open. It opens after the greeting — this scene is nothing but
    /// the conversation — and the control is there to close it, not to start it.
    @State private var micOpen = false

    /// What the companion already remembers, read once on entry. Shown so the memory is
    /// visible rather than an invisible claim: without it, a returning visitor has no way
    /// to tell whether anything was kept.
    @State private var remembered: MemorySummary?
    @State private var showMemory = false

    /// The memory key for whatever is currently chosen, used before the room opens and as
    /// the fallback if it opened without one.
    private var memoryKey: String {
        "\(chosen?.id ?? "friend")-\(lang.rawValue)"
    }

    var body: some View {
        GeometryReader { geo in
            // Landscape crops a portrait frame to a slice down its middle, so each
            // orientation gets its own photograph and the figure is sized off the width.
            let isLandscape = geo.size.width > geo.size.height

            ZStack {
                // A warm room rather than a workplace: this scene is the one with nothing
                // to get done, and the setting is most of what says so. Sized from the
                // geometry rather than `UIScreen.main.bounds` — under `ignoresSafeArea`
                // the two do not agree, and `scaledToFill` left unbounded expands the
                // stack to the image's own proportions.
                Image(isLandscape ? "CompanionRoomBackgroundLandscape" : "CompanionRoomBackground")
                    .resizable()
                    .scaledToFill()
                    .frame(width: geo.size.width, height: geo.size.height)
                    .clipped()
                    .overlay(
                        LinearGradient(
                            colors: [.black.opacity(0.22), .black.opacity(0.48)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )

                // Held to a band in the middle rather than filling the screen. Full-bleed,
                // the figure looms over the room at close to life size and reads as
                // confrontational — the opposite of what this scene is for. Mounted only
                // once a character is chosen, because mounting it creates the session.
                if chosen != nil {
                    AvatarStage(session: session)
                        // In landscape the height comes from the window's *width*: the
                        // height is the tight dimension there, and sizing off it would
                        // shrink the figure on every device that is merely wide.
                        .frame(
                            width: geo.size.width,
                            height: isLandscape
                                ? min(geo.size.width, geo.size.height) * Self.landscapeAvatarHeightOfShortEdge
                                : geo.size.height * Self.avatarHeightFraction
                        )
                        // The figure's own pixels lose their alpha towards the foot, so it
                        // dissolves into the room rather than ending on a straight edge
                        // across the middle of the screen.
                        .mask(
                            LinearGradient(
                                stops: [
                                    .init(color: .black, location: 0),
                                    .init(color: .black, location: 0.75),
                                    .init(color: .clear, location: 1),
                                ],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                }

                if chosen == nil {
                    picker
                } else if !session.isConnected {
                    connectingOverlay
                }

                titleBar

                if chosen != nil {
                    VStack(spacing: 0) {
                        Spacer(minLength: 0)
                        controls
                            .padding(.bottom, 34)
                    }
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .ignoresSafeArea()
        // Swipe right from the left edge to leave, matching the other three scenes — a
        // phone has no room for a permanent back button over the video, and the gesture is
        // what people reach for anyway.
        .gesture(
            DragGesture(minimumDistance: 30)
                .onEnded { value in
                    let fromLeftEdge = value.startLocation.x < 40
                    let movedRight = value.translation.width > 80
                    if fromLeftEdge && movedRight { leave() }
                }
        )
        // Two-parameter onChange; the newer single-parameter form is iOS 17 only.
        .onChange(of: chosen) { persona in
            guard persona != nil else { return }
            Task { await enter() }
        }
        // The memory has to be kept, which is what the argument is for — the other three
        // scenes call `stop()` with nothing and start fresh every time.
        .onDisappear {
            let key = sessionMemoryKey
            Task { await session.stop(remember: key.isEmpty ? nil : key) }
        }
        .sheet(isPresented: $showMemory) {
            memorySheet
        }
        // Pinned to the top-right rather than placed in each scene's header: the four
        // scenes lay their top bars out differently, and an expanding panel dropped into
        // those rows distorts them. The bottom-right corner is where the scenes put their
        // own controls, so the panel overlapped them there.
        .overlay(alignment: .topTrailing) {
            PerfPanel(session: session)
                .padding(.top, 44)
                .padding(.trailing, 12)
        }
    }

    // MARK: - Picking a character

    /// Who is in the room. A column of full-width rows rather than the Web client's grid:
    /// a phone has the width for one across, and each row carries a line of description
    /// that a third of a screen would truncate away.
    private var picker: some View {
        ZStack {
            Color(red: 0.08, green: 0.055, blue: 0.04).opacity(0.62)
                .background(.ultraThinMaterial)

            ScrollView {
                VStack(spacing: 10) {
                    VStack(spacing: 6) {
                        Text(t.companionPick)
                            .font(.system(size: 21, weight: .semibold))
                            .foregroundStyle(.white)
                        Text(t.companionPickHint)
                            .font(.system(size: 13))
                            .foregroundStyle(.white.opacity(0.68))
                            .multilineTextAlignment(.center)
                    }
                    .padding(.bottom, 8)

                    ForEach(CompanionPersonas.all(for: lang)) { persona in
                        PersonaCard(
                            icon: persona.icon,
                            name: persona.name,
                            blurb: persona.blurb,
                            dashed: false
                        ) {
                            customPrompt = ""
                            writingCustom = false
                            chosen = persona
                        }
                    }

                    // Writing one is the same shape as picking one, so it sits in the list
                    // rather than below it — it is another character, not a settings
                    // escape hatch. Dashed is what marks it as the one you fill in.
                    PersonaCard(
                        icon: "✎",
                        name: t.companionCustom,
                        blurb: t.companionCustomBlurb,
                        dashed: true
                    ) {
                        withAnimation(.easeInOut(duration: 0.2)) { writingCustom.toggle() }
                    }

                    if writingCustom { customEditor }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 60)
            }
        }
    }

    private var customEditor: some View {
        VStack(spacing: 8) {
            ZStack(alignment: .topLeading) {
                // TextEditor has no placeholder of its own, so one is drawn behind it and
                // the editor's own background is cleared to let it show through.
                if customPrompt.isEmpty {
                    Text(t.companionCustomPlaceholder)
                        .font(.system(size: 13.5))
                        .foregroundStyle(.white.opacity(0.44))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 14)
                }
                TextEditor(text: $customPrompt)
                    .font(.system(size: 13.5))
                    .foregroundStyle(.white)
                    .scrollContentBackground(.hidden)
                    .background(Color.clear)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    // Enforced here rather than by a modifier: SwiftUI has no maxLength,
                    // and a prompt is pasted as often as it is typed.
                    .onChange(of: customPrompt) { text in
                        if text.count > Self.customLimit {
                            customPrompt = String(text.prefix(Self.customLimit))
                        }
                    }
            }
            .frame(height: 128)
            .background(Color(red: 0.11, green: 0.082, blue: 0.063).opacity(0.72), in: RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(Color.white.opacity(0.26), lineWidth: 1)
            )

            HStack {
                Text("\(customPrompt.count) / \(Self.customLimit)")
                    .font(.system(size: 11.5))
                    .foregroundStyle(.white.opacity(0.52))
                Spacer()
                Button(action: startCustom) {
                    Text(t.companionStart)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 22)
                        .padding(.vertical, 9)
                        .background(Color(red: 0.77, green: 0.49, blue: 0.21), in: Capsule())
                }
                .disabled(trimmedCustom.isEmpty)
                .opacity(trimmedCustom.isEmpty ? 0.42 : 1)
            }
        }
    }

    private var trimmedCustom: String {
        customPrompt.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func startCustom() {
        let prompt = trimmedCustom
        guard !prompt.isEmpty else { return }
        chosen = Persona(
            id: CompanionPersonas.customId,
            name: String(prompt.prefix(12)),
            blurb: "",
            icon: "✎",
            prompt: prompt
        )
    }

    // MARK: - Chrome

    private var titleBar: some View {
        VStack {
            HStack {
                Button(action: leave) {
                    Image(systemName: "xmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 32, height: 32)
                        .background(.white.opacity(0.16), in: Circle())
                }
                .accessibilityLabel(t.back)

                Spacer()

                LangToggle()
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)

            Spacer()
        }
        .padding(.top, 52)
    }

    /// The only controls: close the mic, and look at what is remembered. Kept to the foot
    /// of the screen and small, so the room stays the thing on screen.
    private var controls: some View {
        HStack(spacing: 12) {
            Button {
                toggleMic()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: micOpen ? "circle.fill" : "mic.fill")
                        .font(.system(size: 11))
                    Text(micOpen ? t.companionListening : t.companionMicOff)
                        .font(.system(size: 14, weight: .semibold))
                        .lineLimit(1)
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 20)
                .padding(.vertical, 11)
                // Outlined and see-through: it floats over the room, and a filled block
                // there reads as a piece of UI dropped on the picture. Listening is warm
                // rather than the usual recording red — nothing here is being recorded for
                // anyone else, and red reads as an alarm in a room like this.
                .background(
                    micOpen
                        ? Color(red: 0.55, green: 0.32, blue: 0.10).opacity(0.45)
                        : Color(red: 0.08, green: 0.055, blue: 0.04).opacity(0.40),
                    in: Capsule()
                )
                .overlay(
                    Capsule().strokeBorder(
                        micOpen ? Color(red: 1, green: 0.77, blue: 0.47).opacity(0.9)
                                : Color.white.opacity(0.72),
                        lineWidth: 1.5
                    )
                )
            }
            .disabled(!session.isConnected)
            .opacity(session.isConnected ? 1 : 0.5)

            Button {
                showMemory = true
            } label: {
                Text(t.companionMemory)
                    .font(.system(size: 13))
                    .foregroundStyle(.white.opacity(0.86))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 11)
                    .background(
                        Color(red: 0.08, green: 0.055, blue: 0.04).opacity(0.34),
                        in: Capsule()
                    )
                    .overlay(
                        Capsule().strokeBorder(Color.white.opacity(0.34), lineWidth: 1.5)
                    )
            }
        }
    }

    private var connectingOverlay: some View {
        ZStack {
            Color(red: 0.08, green: 0.055, blue: 0.04).opacity(0.78)
            VStack(spacing: 12) {
                if !session.hasFailed {
                    ProgressView().tint(.white)
                }
                Text(session.hasFailed ? session.status : t.enteringCompanion)
                    .font(.system(size: 13))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
        }
    }

    // MARK: - What it remembers

    /// Shown on request rather than always: the point of the scene is that the memory
    /// surfaces in conversation, not that it is displayed. A sheet rather than the Web
    /// client's floating panel — a phone has no corner to spare beside the room.
    private var memorySheet: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let remembered, remembered.size > 0 {
                        Text(t.companionMemoryMeta(remembered.turns, remembered.size))
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)

                        if remembered.summary.isEmpty {
                            Text(t.companionMemoryRaw)
                                .font(.system(size: 14))
                                .foregroundStyle(.secondary)
                        } else {
                            Text(remembered.summary)
                                .font(.system(size: 14))
                                .lineSpacing(4)
                        }

                        Button(t.companionForget) { forget() }
                            .font(.system(size: 13))
                            .foregroundStyle(.red)
                            .padding(.top, 4)
                    } else {
                        Text(t.companionMemoryEmpty)
                            .font(.system(size: 14))
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
            .navigationTitle(t.companionMemory)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(t.back) { showMemory = false }
                }
            }
        }
        // The room is dark and the sheet is a document about it, so it keeps the light
        // scheme the rest of the app runs in rather than inheriting the scene's mood.
        .presentationDetents([.medium, .large])
    }

    /// Forget everything and start over, so the scene can be shown from a blank slate.
    private func forget() {
        let key = sessionMemoryKey.isEmpty ? memoryKey : sessionMemoryKey
        Task {
            await AgentClient.clearMemory(persona: key)
            remembered = await AgentClient.fetchMemory(persona: key)
        }
    }

    // MARK: - Lifecycle

    /// Opens the room once a character has been chosen.
    ///
    /// `AvatarStage` owns startup — mounting it is what creates the session — so this
    /// waits for the connection rather than starting one itself, the same as the other
    /// scenes.
    private func enter() async {
        // Fixed for the rest of the session — see `sessionMemoryKey`.
        sessionMemoryKey = memoryKey

        // Read the memory before the greeting: it is what decides which of the two
        // greetings is used, and the backend builds the persona from it at free-talk time.
        remembered = await AgentClient.fetchMemory(persona: sessionMemoryKey)

        while !session.isConnected, !session.hasFailed, !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        guard session.isConnected else { return }

        // The persona before the greeting: the backend assembles the character — memory
        // included — when the session switches to free talk, and a line spoken before that
        // would be answered by whatever the session started as.
        await session.startFreeTalk(
            persona: "companion",
            custom: chosen?.prompt ?? "",
            memoryKey: sessionMemoryKey
        )

        // A fixed greeting, in one of two versions depending on whether there is anything
        // to remember. It is read verbatim rather than generated: the only way to make the
        // avatar speak is `say`, which takes the text as given. What the memory does
        // affect is everything after this — it is in the persona, so the first real reply
        // already draws on it.
        let hasMemory = (remembered?.size ?? 0) > 0
        await session.speak(hasMemory ? t.companionHelloAgain : t.companionHello)
        await session.waitUntilSilent()

        // Opened after the greeting rather than before it: open first and the companion
        // hears its own line through the room and answers itself.
        await openMic()
    }

    private func toggleMic() {
        Task {
            if micOpen {
                await session.unpublishMic()
                micOpen = false
            } else {
                await openMic()
            }
        }
    }

    /// Open the mic, asking for the recording permission first.
    private func openMic() async {
        // AVAudioSession rather than AVAudioApplication, which is iOS 17 only — the
        // deployment target is iOS 16.
        let granted = await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
        guard granted else { return }
        await session.publishMic()
        micOpen = true
    }

    private func leave() {
        // Stops the capture as well as the publish, or the recording indicator stays lit
        // after the scene is gone. Stopping the session itself — and with it keeping what
        // was said — is left to `onDisappear`, which runs on the way out regardless of how
        // the scene was left.
        Task { await session.unpublishMic() }
        onExit()
    }
}

/// One character to pick from, or the dashed row that opens the editor.
///
/// A full-width row rather than the Web client's card in a grid: a phone has the width for
/// one across, and the line of description is what the choice is actually made on.
private struct PersonaCard: View {
    let icon: String
    let name: String
    let blurb: String
    let dashed: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 14) {
                Text(icon).font(.system(size: 26))

                VStack(alignment: .leading, spacing: 3) {
                    Text(name)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                    if !blurb.isEmpty {
                        Text(blurb)
                            .font(.system(size: 12))
                            .foregroundStyle(.white.opacity(0.66))
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 15)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                Color(red: 0.15, green: 0.11, blue: 0.078).opacity(0.62),
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(
                        Color.white.opacity(0.24),
                        style: StrokeStyle(lineWidth: 1, dash: dashed ? [5, 4] : [])
                    )
            )
        }
        .buttonStyle(.plain)
    }
}
