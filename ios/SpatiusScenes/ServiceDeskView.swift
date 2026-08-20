import AVFoundation
import SwiftUI

/// Bank customer service: the avatar over the bank hall, with the topics on offer and
/// whatever is currently being explained on a sheet over the lower half of the screen.
///
/// Different from the Web layout, which puts the avatar and the topics side by side on a
/// desktop. A phone has no width to spare for a column, so the avatar fills the screen and
/// the panel floats over the bottom of it — and the mic control sits just above the panel,
/// where it is clear of the sheet and still on the avatar rather than on the list.
///
/// Two ways to ask. The menu is canned text read verbatim through `speak` — see
/// ServiceData.swift for why accuracy matters more here than in the other scenes — and
/// questions can be asked over and over, each one cutting off whatever is still playing.
/// The button above the panel opens the mic instead and hands the whole thing to the LLM,
/// with the same business knowledge carried as its persona.
struct ServiceDeskView: View {
    /// The band the avatar occupies, measured from the top. The panel overlaps its lower
    /// part, so these deliberately add up to more than the screen.
    private static let avatarHeightFraction = 0.7

    /// How much of the screen the question panel takes, anchored to the bottom.
    private static let panelHeightFraction = 0.5

    /// How much of a landscape window the avatar column takes, leaving the rest to the menu.
    /// Matches the Android layout.
    private static let landscapeAvatarColumnFraction = 0.42

    /// The avatar's height in landscape, as a share of the window's *short* edge. Android
    /// expresses this against `maxWidth`, which is the short edge there once the window has
    /// rotated; on iOS `geo.size.width` is the long edge, so the short edge is named
    /// explicitly rather than reusing the same expression and getting a figure twice the
    /// height of the screen.
    private static let landscapeAvatarHeightOfShortEdge = 0.8

    /// Three across: a phone panel fits two stacked full-width rows before it runs out,
    /// which is not enough of the menu to be worth scrolling.
    private static let gridColumns = Array(
        repeating: GridItem(.flexible(), spacing: 8), count: 3
    )

    let onExit: () -> Void

    @StateObject private var session = AvatarRtcSession()
    @ObservedObject private var localization = Localization.shared

    private var t: Strings { localization.t }
    private var lang: Lang { localization.lang }

    /// The exchange so far, shown above the question list.
    @State private var turns: [ServiceTurn] = []
    @State private var nextTurnId = 1

    /// The card that came with the current answer, or nil when the reply was speech only.
    @State private var card: AnswerCard?

    /// Where in the menu the customer is: nil at the top showing categories, otherwise the
    /// category whose questions are listed.
    ///
    /// Nothing is consumed by being asked. A question stays on the list after it has been
    /// answered — someone who half caught a limit or a document name wants to hear it
    /// again, and a menu that empties as it is used ends up blank in front of a customer
    /// who still has questions.
    @State private var openCategory: ServiceCategory?

    /// The search box. Matches across every category rather than the list on screen —
    /// someone typing 「挂失」 while inside the account category means they want that
    /// question, wherever it lives.
    @State private var query = ""

    /// Whether the avatar is currently reading an answer. Only drives the indicator — it
    /// does not lock anything, since a new question is allowed to cut the current one off.
    @State private var speaking = false

    /// Whether the mic is open and the LLM is answering.
    @State private var live = false

    /// Which answer is currently playing.
    ///
    /// Every `ask` takes a ticket. When the audio for one finally starts, the handler
    /// checks its ticket is still the current one before filling in the bubble — otherwise
    /// a reply that was interrupted three questions ago wakes up and settles a bubble
    /// belonging to the answer now playing.
    @State private var currentAsk = 0

    private var searching: Bool {
        !query.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// Matched against the label, the question as asked, and the answer, so a word that
    /// only appears in the reply still finds it.
    private var results: [ServiceQuestion] {
        let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !needle.isEmpty else { return [] }
        return ServiceData.allQuestions(for: lang).filter { $0.haystack.contains(needle) }
    }

    /// The questions listed right now: search results, or the open category's set.
    private var visibleQuestions: [ServiceQuestion] {
        searching ? results : (openCategory?.questions ?? [])
    }

    /// Categories show only at the top level — inside one, or while searching, the list is
    /// questions.
    private var visibleCategories: [ServiceCategory] {
        (openCategory == nil && !searching) ? ServiceData.categories(for: lang) : []
    }

    var body: some View {
        // Every height here is a share of the screen, so the whole scene is measured once
        // from a single geometry rather than from `UIScreen.main.bounds`: under
        // `ignoresSafeArea` those two do not agree, and sizing children against the wrong
        // one pushes the layout past the edges. `scaledToFill` on the backdrop needs the
        // same treatment — clipped to a known frame it fills the screen; left unbounded it
        // expands the stack to the image's own proportions and takes everything with it.
        GeometryReader { geo in
            // Landscape puts the avatar beside the menu instead of above it — stacked, a
            // landscape phone leaves the avatar a letterbox strip and the menu two rows.
            let isLandscape = geo.size.width > geo.size.height

            ZStack {
                // The hall photograph, dimmed so the avatar in front of it keeps contrast —
                // at full brightness the lit counter competes with the face. A portrait
                // frame cropped to a landscape window keeps only a slice down its middle,
                // so each orientation gets a photograph shot for it.
                Image(isLandscape ? "ServiceDeskBackgroundLandscape" : "ServiceDeskBackground")
                    .resizable()
                    .scaledToFill()
                    .frame(width: geo.size.width, height: geo.size.height)
                    .clipped()
                    .overlay(
                        LinearGradient(
                            colors: [.black.opacity(0.25), .black.opacity(0.5)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )

                // The avatar holds the upper band and fades out towards its foot: the
                // figure's own pixels lose their alpha down the gradient, so it dissolves
                // into the hall behind it rather than being cut off square.
                HStack(spacing: 0) {
                    VStack(spacing: 0) {
                        // Landscape pins the figure to the foot of its column; portrait
                        // keeps it in the upper band, where it started.
                        if isLandscape { Spacer(minLength: 0) }
                        AvatarStage(session: session)
                        // In landscape the height comes from the window's *width*, per the
                        // design: the avatar keeps a consistent presence across devices
                        // rather than growing with whatever height the phone happens to have.
                        .frame(
                            width: isLandscape
                                ? geo.size.width * Self.landscapeAvatarColumnFraction
                                : geo.size.width,
                            height: isLandscape
                                ? min(geo.size.width, geo.size.height) * Self.landscapeAvatarHeightOfShortEdge
                                : geo.size.height * Self.avatarHeightFraction
                        )
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
                        // Portrait keeps the figure in the upper band; landscape pins it to
                        // the bottom of its own column.
                        if !isLandscape { Spacer(minLength: 0) }
                    }
                    if isLandscape { Spacer(minLength: 0) }
                }

                titleBar

                // Anchored to the bottom in portrait, overlapping the foot of the avatar's
                // band; in landscape it becomes the right-hand column and takes full height.
                if isLandscape {
                    // The scene ignores the safe area so the hall reaches the screen edges,
                    // which leaves the menu to inset itself — otherwise it runs under the
                    // status bar and out to the physical edge, over the title and the
                    // performance readout. Centred vertically in what is left: the column
                    // is taller than the questions need, and pinned to the top it leaves an
                    // empty band under itself.
                    let insetTop = geo.safeAreaInsets.top + 12
                    let insetBottom = geo.safeAreaInsets.bottom + 12
                    panel(height: geo.size.height - insetTop - insetBottom, bottomInset: 0)
                        .frame(width: geo.size.width * (1 - Self.landscapeAvatarColumnFraction) - geo.safeAreaInsets.trailing - 16)
                        .padding(.top, insetTop)
                        .padding(.bottom, insetBottom)
                        .padding(.trailing, geo.safeAreaInsets.trailing + 16)
                        .frame(width: geo.size.width, height: geo.size.height, alignment: .trailing)
                } else {
                    VStack(spacing: 0) {
                        Spacer(minLength: 0)
                        panel(height: geo.size.height * Self.panelHeightFraction)
                    }
                }

                // Outside the panel, sitting just above it: it is the alternative to the
                // whole menu rather than one more control within it.
                if isLandscape {
                    // Under the avatar rather than over the menu: the menu now occupies its
                    // own column, and a button floating above it would sit in the middle of
                    // the conversation.
                    VStack(spacing: 0) {
                        Spacer(minLength: 0)
                        HStack(spacing: 0) {
                            liveButton
                                .padding(.leading, 16)
                            Spacer(minLength: 0)
                        }
                        .padding(.bottom, 16)
                    }
                } else {
                    VStack(spacing: 0) {
                        Spacer(minLength: 0)
                        liveButton
                            .padding(.bottom, geo.size.height * Self.panelHeightFraction + 34)
                    }
                }

                if !session.isConnected {
                    connectingOverlay
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .ignoresSafeArea()
        // Swipe right from the left edge to leave, matching the classroom and the live
        // room — a phone has no room for a permanent back button over the video, and the
        // gesture is what people reach for anyway. No rating step: leaving ends the scene.
        .gesture(
            DragGesture(minimumDistance: 30)
                .onEnded { value in
                    let fromLeftEdge = value.startLocation.x < 40
                    let movedRight = value.translation.width > 80
                    if fromLeftEdge && movedRight { leave() }
                }
        )
        .task { await run() }
        .onDisappear { Task { await session.stop() } }
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

    // MARK: - Chrome

    /// Title bar, styled after the one in a banking app's web view, with the mic control
    /// The mic control is not here — it sits above the question panel.
    private var titleBar: some View {
        VStack {
            HStack(spacing: 10) {
                Button(action: leave) {
                    Image(systemName: "xmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 32, height: 32)
                        .background(.white.opacity(0.16), in: Circle())
                }
                .accessibilityLabel(t.serviceLeave)

                VStack(spacing: 1) {
                    Text(t.serviceTitle)
                        .font(.system(size: 15, weight: .semibold))
                    HStack(spacing: 4) {
                        Circle()
                            .fill(Color(red: 0.24, green: 0.86, blue: 0.52))
                            .frame(width: 6, height: 6)
                        Text(t.serviceOnline).font(.system(size: 11))
                    }
                    .opacity(0.75)
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)

                // Balances the close button so the title sits centred between them; the
                // mic control lives above the panel now, not up here.
                Color.clear.frame(width: 32, height: 32)
            }
            .padding(.horizontal, 14)
            .padding(.top, 6)
            .background(
                LinearGradient(
                    colors: [.black.opacity(0.55), .clear],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .padding(.top, -60)
                .allowsHitTesting(false)
            )

            Spacer()
        }
        .padding(.top, 52)
    }

    /// Open the mic, or close it again. Red and filled while listening, so there is no
    /// mistaking an open mic for a closed one.
    private var liveButton: some View {
        Button {
            if live { endLive() } else { startLive() }
        } label: {
            HStack(spacing: 5) {
                Image(systemName: live ? "circle.fill" : "mic.fill")
                    .font(.system(size: 10))
                Text(live ? t.serviceLiveOn : t.serviceLive)
                    .font(.system(size: 12, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            // Outlined and see-through rather than a solid slab: it floats over the avatar
            // above the panel, and
            // a filled block there reads as a piece of UI dropped on the picture. The border
            // is what keeps it legible as a control over a moving background.
            .background(
                live
                    ? Color(red: 0.84, green: 0.27, blue: 0.27).opacity(0.4)
                    : Color(red: 0.03, green: 0.08, blue: 0.15).opacity(0.4),
                in: Capsule()
            )
            .overlay(
                Capsule().strokeBorder(
                    live ? Color(red: 1, green: 0.54, blue: 0.54) : Color.white.opacity(0.9),
                    lineWidth: 1.5
                )
            )
            .shadow(color: .black.opacity(0.28), radius: 8, y: 3)
        }
        .disabled(!session.isConnected)
        .opacity(session.isConnected ? 1 : 0.5)
    }

    private var connectingOverlay: some View {
        ZStack {
            Color.black.opacity(0.72)
            VStack(spacing: 12) {
                if !session.hasFailed {
                    ProgressView().tint(.white)
                }
                Text(session.hasFailed ? session.status : t.enteringService)
                    .font(.system(size: 13))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
        }
    }

    // MARK: - The panel

    /// The questions, on a translucent sheet over the bottom of the screen. Held to half
    /// the height: the avatar has to stay visible above it, and a sheet that grows with
    /// its content eventually covers the face.
    private func panel(height: CGFloat, bottomInset: CGFloat = 24) -> some View {
        VStack(spacing: 10) {
            transcript
            if live {
                liveIndicator
            } else {
                questions
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity)
        .frame(height: height, alignment: .top)
        // See-through, so the hall and the avatar's feet carry on behind the questions
        // rather than being walled off by a solid sheet.
        .background(.ultraThinMaterial)
        // Inset from the screen edges and outlined on all four sides: run to the edges it
        // has no boundary of its own and reads as the bottom of the window rather than as
        // a surface holding the conversation.
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(Color.white.opacity(0.55), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.22), radius: 16, y: -4)
        .padding(.horizontal, 12)
        // Clear of the home indicator in portrait. Landscape passes 0: the column is
        // already inset by the safe area, and a second gap under it pushes the sheet off
        // centre in a way that reads as stuck to the bottom.
        .padding(.bottom, bottomInset)
    }

    /// The conversation, on its own surface inside the panel so it reads as a transcript
    /// rather than bubbles floating loose above the questions.
    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                transcriptRows
            }
            .frame(maxHeight: 150)
            .background(Color.white.opacity(0.42))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color.black.opacity(0.08), lineWidth: 1)
            )
            // Two-parameter onChange; the newer single-parameter form is iOS 17 only.
            // Keyed on a string rather than the count, so settling a bubble in place — the
            // dots becoming a paragraph, which changes its height but not the count —
            // still scrolls the reply back into view.
            .onChange(of: scrollKey) { _ in
                scrollToEnd(proxy)
            }
        }
    }

    @ViewBuilder
    private var transcriptRows: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(turns) { turn in
                TurnBubble(turn: turn, speakingLabel: t.serviceSpeaking)
                    .id(turn.id)
            }
            if let card {
                AnswerCardView(card: card).id(cardAnchor)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
    }

    /// Keep the newest line in view. The card lands under the reply and adds its own
    /// height on top of it, so it is the anchor whenever there is one.
    private func scrollToEnd(_ proxy: ScrollViewProxy) {
        guard let last = turns.last else { return }
        withAnimation {
            if card == nil {
                proxy.scrollTo(last.id, anchor: .bottom)
            } else {
                proxy.scrollTo(cardAnchor, anchor: .bottom)
            }
        }
    }

    /// Enter, or the search button.
    ///
    /// The list filters as they type, so submitting has to do more than re-run it: a
    /// single match is asked outright, since typing enough to narrow it to one and then
    /// having to tap it is a step nobody wants. Several matches are left on screen to
    /// choose from.
    private func submitSearch() {
        let matches = results
        guard matches.count == 1 else { return }
        ask(matches[0])
    }

    /// Identifies the card row for the scroll-to-end, which has no id of its own.
    private var cardAnchor: String { "answer-card" }

    /// Changes whenever the transcript's height could have changed: a turn added or
    /// dropped, a pending bubble settled, or a card appearing under the reply.
    private var scrollKey: String {
        let pending = turns.filter(\.pending).count
        return "\(turns.count)-\(pending)-\(card == nil ? 0 : 1)"
    }

    private var questions: some View {
        VStack(spacing: 8) {
            searchRow
            questionsHeader

            if searching && results.isEmpty {
                Text(t.serviceSearchEmpty)
                    .font(.system(size: 12.5))
                    .foregroundStyle(Color(red: 0.42, green: 0.47, blue: 0.58))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            ScrollView {
                LazyVGrid(columns: Self.gridColumns, spacing: 8) {
                    // Top level: the categories.
                    ForEach(visibleCategories) { category in
                        CategoryTile(category: category) {
                            openCategory = category
                            query = ""
                        }
                        .disabled(!session.isConnected)
                    }

                    // Inside a category, or the search results. Never disabled while
                    // connected: tapping one while another answer is playing cuts it off
                    // and starts this one.
                    ForEach(visibleQuestions) { question in
                        QuestionTile(question: question) { ask(question) }
                            .disabled(!session.isConnected)
                    }
                }
                .padding(.bottom, 4)
            }
        }
    }

    private var searchRow: some View {
        HStack(spacing: 6) {
            TextField(t.serviceSearch, text: $query)
                .font(.system(size: 13.5))
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .onSubmit(submitSearch)
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(Color.white.opacity(0.85), in: RoundedRectangle(cornerRadius: 9))
                .overlay(
                    RoundedRectangle(cornerRadius: 9)
                        .stroke(Color.black.opacity(0.12), lineWidth: 1)
                )
                .disabled(!session.isConnected)

            Button(action: submitSearch) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 14))
                    .foregroundStyle(Color(red: 0.12, green: 0.39, blue: 0.82))
                    .frame(width: 38, height: 36)
                    .background(Color.white.opacity(0.85), in: RoundedRectangle(cornerRadius: 9))
                    .overlay(
                        RoundedRectangle(cornerRadius: 9)
                            .stroke(Color.black.opacity(0.12), lineWidth: 1)
                    )
            }
            .accessibilityLabel(t.serviceSearch)
            .disabled(!session.isConnected)
        }
    }

    private var questionsHeader: some View {
        HStack {
            // Inside a category, the heading doubles as the way back out.
            if let openCategory, !searching {
                Button {
                    self.openCategory = nil
                    query = ""
                } label: {
                    HStack(spacing: 2) {
                        Image(systemName: "chevron.left").font(.system(size: 11, weight: .semibold))
                        Text(openCategory.label).font(.system(size: 13, weight: .semibold))
                    }
                    .foregroundStyle(Color(red: 0.12, green: 0.39, blue: 0.82))
                }
            } else {
                Text(searching ? t.serviceAsk : t.serviceCategories)
                    .font(.system(size: 12))
                    .foregroundStyle(Color(red: 0.42, green: 0.47, blue: 0.58))
            }

            Spacer()

            if searching {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Color(red: 0.12, green: 0.39, blue: 0.82))
                }
            } else if speaking {
                Text(t.serviceSpeaking)
                    .font(.system(size: 11.5))
                    .foregroundStyle(Color(red: 0.42, green: 0.47, blue: 0.58))
            }
        }
    }

    /// A pulse rather than a spinner: nothing is loading, something is listening.
    private var liveIndicator: some View {
        VStack(spacing: 10) {
            PulsingDot()
            Text(t.serviceLiveListening)
                .font(.system(size: 14))
                .foregroundStyle(Color(red: 0.10, green: 0.15, blue: 0.25))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Lifecycle

    private func run() async {
        // AvatarStage owns startup; this waits for it rather than calling prepare again.
        while !session.isConnected, !session.hasFailed, !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        guard session.isConnected else { return }

        speaking = true
        await say(t.serviceGreeting)
        speaking = false
    }

    private func leave() {
        // Stops the capture as well as the publish, or the recording indicator stays lit
        // after the scene is gone. Stopping the session itself is left to `onDisappear`,
        // which runs on the way out regardless of how the scene was left — doing it here
        // as well would have two tasks tearing the same session down at once.
        Task { await session.unpublishMic() }
        onExit()
    }

    // MARK: - Transcript

    private func addTurn(_ who: ServiceTurn.Who, _ text: String, pending: Bool = false) -> Int {
        let id = nextTurnId
        nextTurnId += 1
        turns.append(ServiceTurn(id: id, who: who, text: text, pending: pending))
        return id
    }

    /// Swap a pending bubble for the text it was holding a place for.
    private func settleTurn(_ id: Int) {
        guard let index = turns.firstIndex(where: { $0.id == id }) else { return }
        turns[index].pending = false
    }

    /// Drop a pending bubble whose reply never arrived, or was cut off by the next
    /// question.
    private func dropTurn(_ id: Int) {
        turns.removeAll { $0.id == id }
    }

    /// Say one line, showing dots until it can be heard.
    ///
    /// Everything the avatar says goes through here so the caption and the voice stay
    /// together — see `ask` for why that gap matters.
    private func say(_ text: String) async {
        let id = addTurn(.agent, text, pending: true)
        await session.speak(text)
        await session.waitUntilSpeaking()
        settleTurn(id)
    }

    // MARK: - Answering

    /// Read one answer out.
    ///
    /// Interrupting is the point: tapping a second question while the first is still being
    /// read cuts it off and starts the new one. Waiting for a paragraph of bank policy to
    /// finish before the menu responds makes the whole thing feel stuck, and a customer
    /// who has heard the part they needed should be able to move on.
    private func ask(_ question: ServiceQuestion) {
        guard !live else { return }

        currentAsk += 1
        let ticket = currentAsk
        speaking = true

        // Asking one is the end of that search: leaving the query in place would answer
        // the question and then still show a filtered list rather than where to go next.
        query = ""
        _ = addTurn(.customer, question.asked)
        // The reply goes up as dots and stays that way until it can actually be heard: the
        // text is ready instantly, the voice is not, and a caption that lands a second or
        // two ahead of the audio reads as the avatar being out of sync with itself.
        let replyId = addTurn(.agent, question.answer, pending: true)
        // The card belongs with the spoken answer, so it waits too.
        card = nil

        Task {
            // Stop whatever is playing before starting this one, or the two overlap:
            // `speak` queues rather than replaces.
            await session.interrupt()
            await session.speak(question.answer)
            await session.waitUntilSpeaking()

            // Interrupted while the audio was on its way — the dots belong to a question
            // the customer has already moved on from, so take them down rather than
            // filling them in.
            guard ticket == currentAsk else {
                dropTurn(replyId)
                return
            }
            settleTurn(replyId)
            card = question.card
            speaking = false
        }
    }

    // MARK: - Live Q&A

    /// Open the mic and let them ask anything.
    ///
    /// The menu answers are canned text read verbatim, which is what keeps them accurate
    /// and fast. This is the other half: the backend switches to the bank persona, the LLM
    /// starts answering what is actually said, and everything in the menu becomes
    /// background the avatar can draw on rather than a list to pick from.
    private func startLive() {
        guard !live else { return }
        live = true

        Task {
            // AVAudioSession rather than AVAudioApplication, which is iOS 17 only — the
            // deployment target is iOS 16.
            let granted = await withCheckedContinuation { continuation in
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
            guard granted else {
                live = false
                return
            }

            // Stop whatever answer is playing first — the transition line should not land
            // on top of a paragraph about transfer limits. Bumping the ticket also stops
            // the interrupted answer settling its own bubble afterwards.
            currentAsk += 1
            speaking = false
            await session.interrupt()

            // Mic first, then the persona, then the line — the order the classroom and the
            // live room use, and it matters. `speak` makes an HTTP round trip while
            // publishMic is a local call, so the other way round they race and the line
            // lands during the audio rebuild that going live triggers.
            await session.publishMic()
            await session.startFreeTalk(persona: "banker")
            await say(t.serviceLiveOpen)
        }
    }

    /// Back to the menu: close the mic and stop the LLM answering.
    private func endLive() {
        guard live else { return }
        live = false
        Task { await session.unpublishMic() }
    }
}

/// One line of the exchange, shown on the panel above the question rows.
struct ServiceTurn: Identifiable {
    enum Who { case customer, agent }

    let id: Int
    let who: Who
    let text: String
    /// True while the reply is on its way: the bubble shows dots instead of the text.
    var pending: Bool
}

/// One bubble in the transcript.
private struct TurnBubble: View {
    let turn: ServiceTurn
    let speakingLabel: String

    var body: some View {
        HStack {
            if turn.who == .customer { Spacer(minLength: 40) }

            Group {
                if turn.pending {
                    TypingDots()
                        .accessibilityLabel(speakingLabel)
                } else {
                    Text(turn.text)
                        .font(.system(size: 14))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.horizontal, 13)
            .padding(.vertical, 9)
            .foregroundStyle(turn.who == .customer ? Color.white : Color(red: 0.10, green: 0.15, blue: 0.25))
            .background(
                turn.who == .customer
                    ? Color(red: 0.12, green: 0.39, blue: 0.82)
                    : Color.white.opacity(0.92),
                in: RoundedRectangle(cornerRadius: 14)
            )

            if turn.who == .agent { Spacer(minLength: 40) }
        }
    }
}

/// Waiting for the voice. Three dots lifting one after another — a bubble that just sits
/// there empty reads as something having gone wrong.
private struct TypingDots: View {
    @State private var lifted = false

    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .frame(width: 6, height: 6)
                    .opacity(lifted ? 0.85 : 0.35)
                    .offset(y: lifted ? -3 : 0)
                    .animation(
                        .easeInOut(duration: 0.55)
                            .repeatForever()
                            .delay(Double(index) * 0.16),
                        value: lifted
                    )
            }
        }
        // Matches a line of text, so the bubble does not change height when the words land.
        .frame(height: 21)
        .onAppear { lifted = true }
    }
}

/// The card that comes with an answer. Blue like the one in a banking app, and clearly a
/// block rather than another bubble.
private struct AnswerCardView: View {
    let card: AnswerCard

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !card.title.isEmpty {
                Text(card.title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Color(red: 0.10, green: 0.15, blue: 0.25))
                    .padding(.bottom, 2)
            }
            ForEach(Array(card.body.enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(.system(size: 13))
                    .foregroundStyle(Color(red: 0.27, green: 0.33, blue: 0.43))
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let action = card.action {
                Text("👉 \(action)")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color(red: 0.12, green: 0.39, blue: 0.82))
                    .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(Color(red: 0.12, green: 0.39, blue: 0.82).opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color(red: 0.12, green: 0.39, blue: 0.82).opacity(0.20), lineWidth: 1)
        )
    }
}

/// A category row carries a name and what is under it, so it is taller than a question row
/// and reads as a heading you open rather than a question you ask.
private struct CategoryTile: View {
    let category: ServiceCategory
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 4) {
                Text(category.icon).font(.system(size: 20))
                // The blurb is dropped at this width — a third of a phone leaves room for
                // the name and nothing more, and a truncated subtitle says less than none.
                Text(category.label)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color(red: 0.10, green: 0.15, blue: 0.25))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: 66)
            .background(Color.white.opacity(0.9), in: RoundedRectangle(cornerRadius: 10))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(Color(red: 0.12, green: 0.39, blue: 0.82).opacity(0.3), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

/// One question. Never disabled while connected — tapping it interrupts whatever is
/// playing.
private struct QuestionTile: View {
    let question: ServiceQuestion
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            Text(question.label)
                .font(.system(size: 12))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 8)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, minHeight: 66)
                .background(
                    Color(red: 0.12, green: 0.39, blue: 0.82),
                    in: RoundedRectangle(cornerRadius: 10)
                )
        }
        .buttonStyle(.plain)
    }
}

/// The listening indicator: a dot with a ring pushing out of it.
private struct PulsingDot: View {
    @State private var expanded = false

    var body: some View {
        Circle()
            .fill(Color(red: 0.12, green: 0.39, blue: 0.82))
            .frame(width: 34, height: 34)
            .overlay(
                Circle()
                    .stroke(Color(red: 0.12, green: 0.39, blue: 0.82).opacity(expanded ? 0 : 0.45), lineWidth: 8)
                    .scaleEffect(expanded ? 1.9 : 1)
                    .animation(.easeOut(duration: 1.4).repeatForever(autoreverses: false), value: expanded)
            )
            .onAppear { expanded = true }
    }
}

