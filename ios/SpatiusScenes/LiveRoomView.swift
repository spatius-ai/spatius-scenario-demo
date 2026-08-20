import AVFoundation
import SwiftUI

/// The live room: the stream on top with danmaku drifting over it, the chat list below,
/// and the gift bar and composer at the foot.
///
/// Stacked rather than side by side, which is the Web layout: a phone in portrait has no
/// width to spare for a column beside the video, and a stream is watched upright.
///
/// The audience runs itself. Viewers arrive in bursts with a message already paired to
/// the host's reply (see ChatData), and now and then one sends a gift. Replies are read
/// aloud through `speak`, which is verbatim TTS and never involves the LLM — canned text
/// is what keeps a reply inside a second, fast enough to still be answering the message
/// the viewer can see on screen.
struct LiveRoomView: View {
    let onExit: () -> Void

    @StateObject private var session = AvatarRtcSession()
    @ObservedObject private var localization = Localization.shared

    @State private var messages: [ChatMessage] = []
    @State private var danmaku: [DanmakuItem] = []
    @State private var viewers = Int.random(in: 1200...2000)
    @State private var micState: MicState = .idle
    @State private var draft = ""

    /// What the host may pick up. Cleared once something is chosen, so a line that has
    /// scrolled past stays unanswered — which is what happens in a real room.
    @State private var answerable: [ChatMessage] = []
    @State private var gifted: ChatMessage?

    private var t: Strings { localization.t }
    private var lang: Lang { localization.lang }

    var body: some View {
        // AnyLayout rather than an if/else over two stacks: switching between separate
        // view trees on rotation gives the subtree a new identity, which destroys
        // AvatarView and drops the RTC session with it — the avatar cuts out mid-sentence.
        // Swapping only the layout keeps every child in place. Same reasoning as the
        // classroom.
        GeometryReader { geo in
            let isLandscape = geo.size.width > geo.size.height
            let layout = isLandscape
                ? AnyLayout(HStackLayout(spacing: 0))
                : AnyLayout(VStackLayout(spacing: 0))

            layout {
                LiveStage(
                    session: session,
                    danmaku: danmaku,
                    viewers: viewers,
                    micState: micState,
                    onMic: requestMic
                )
                // A square either way, sized off whichever edge is the tight one: the
                // height in landscape, the width in portrait.
                .frame(
                    width: isLandscape ? geo.size.height : nil,
                    height: isLandscape ? geo.size.height : nil
                )

                VStack(spacing: 0) {
                    ChatList(messages: messages)

                    GiftBar(lang: lang, enabled: session.isConnected) { gift in
                        post(ChatData.giftMessage(for: lang, gift: gift, from: ChatData.randomViewer(for: lang)))
                    }

                    Composer(draft: $draft, enabled: session.isConnected, onSend: sendDraft)
                }
            }
        }
        .background(Color(.systemBackground))
        // Swipe right from the left edge to leave, matching the classroom — a phone has
        // no room for a permanent back button over the video, and the gesture is what
        // people reach for anyway.
        .gesture(
            DragGesture(minimumDistance: 30)
                .onEnded { value in
                    let fromLeftEdge = value.startLocation.x < 40
                    let movedRight = value.translation.width > 80
                    if fromLeftEdge && movedRight {
                        Task { await session.stop() }
                        onExit()
                    }
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

    // MARK: - The room

    private func run() async {
        // AvatarStage owns startup; this waits for it rather than calling prepare again.
        // The room is worth showing either way, so a failure leaves the overlay up with
        // its reason instead of tearing everything down.
        while !session.isConnected, !session.hasFailed, !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        guard session.isConnected else { return }

        // The audience starts arriving immediately — a room that opens with nobody in the
        // chat looks dead, and viewers turning up while the host is still introducing
        // themselves is exactly what happens.
        Task { await runAudience() }
        Task { await driftViewerCount() }

        // The replies are what has to wait. Started alongside the introduction, the first
        // message picked up would speak over it and cut it off partway through.
        await session.speak(t.greeting)
        await session.waitUntilSilent()
        await runHost()
    }

    /// The audience, arriving in bursts rather than on a beat. A real room surges and then
    /// goes quiet; a steady interval reads as a machine dropping text on a timer.
    private func runAudience() async {
        var burstLeft = 0
        while !Task.isCancelled {
            let delay: Double = burstLeft > 0
                ? Double.random(in: 0.25...0.75)
                : Double.random(in: 1.5...4.5)
            if burstLeft > 0 {
                burstLeft -= 1
            } else if Double.random(in: 0...1) < 0.45 {
                burstLeft = Int.random(in: 3...10)
            }
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))

            // Gifts are the rare event, which is what makes one worth breaking off to
            // thank. At burst rate even a small share arrives constantly.
            if Double.random(in: 0...1) < 0.02 {
                post(ChatData.giftMessage(for: lang, gift: ChatData.randomGift(for: lang)))
            } else {
                post(ChatData.randomChat(for: lang))
            }
        }
    }

    /// Speak one line, pause, then look again — the loop the host runs for the session.
    private func runHost() async {
        while !Task.isCancelled {
            // Silent while a viewer has the mic, or is waiting to be let in: reading canned
            // lines over a real conversation talks across the person who just got
            // permission to speak, and the free-talk persona is answering them at the same
            // time — two voices from one avatar, interrupting each other.
            if micState == .idle {
                let next = gifted ?? answerable.randomElement()
                gifted = nil
                if let reply = next?.reply {
                    answerable.removeAll()
                    await session.speak(reply)
                    await session.waitUntilSilent()
                }
            }
            try? await Task.sleep(nanoseconds: UInt64(Double.random(in: 0...3) * 1_000_000_000))
        }
    }

    private func driftViewerCount() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            viewers = max(800, viewers + Int.random(in: -8...12))
        }
    }

    private func post(_ message: ChatMessage) {
        messages.append(message)
        if messages.count > 80 { messages.removeFirst(messages.count - 80) }

        let seconds = Double.random(in: 5...9)
        danmaku.append(
            DanmakuItem(
                id: message.id,
                text: message.gift.map { "\($0.icon) \(message.text)" } ?? message.text,
                lane: Int.random(in: 0..<7),
                hue: message.viewer.hue,
                seconds: seconds,
                // Small spread only. Past roughly this much the big ones read as emphasis
                // the sender never intended.
                fontSize: Double.random(in: 13...18)
            )
        )
        if danmaku.count > 14 { danmaku.removeFirst(danmaku.count - 14) }
        // Removed on its own schedule, since each line crosses at its own speed.
        Task {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            danmaku.removeAll { $0.id == message.id }
        }

        guard message.reply != nil else { return }
        if message.gift != nil {
            gifted = message
        } else {
            answerable.append(message)
            // Only the last few are still on screen; answering something from a minute ago
            // reads as the host being out of step with the room.
            if answerable.count > 12 { answerable.removeFirst() }
        }
    }

    // MARK: - Actions

    private func requestMic() {
        guard micState == .idle else {
            micState = .idle
            Task { await session.unpublishMic() }
            return
        }
        micState = .pending
        Task {
            // AVAudioSession rather than AVAudioApplication, which is iOS 17 only — the
            // deployment target is iOS 16.
            let granted = await withCheckedContinuation { continuation in
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
            guard granted else {
                micState = .idle
                return
            }
            // Mic first, then the persona, then the line — the order the classroom uses,
            // and it matters. `speak` makes an HTTP round trip while publishMic is a local
            // call, so the other way round they race and the greeting lands during the
            // audio rebuild that going live triggers. Recognition then receives audio that
            // carries a voice but transcribes to nothing, and the avatar never answers.
            await session.publishMic()
            await session.startFreeTalk(persona: "host")
            await session.speak(t.micWelcome)
            await session.waitUntilSilent()
            if micState == .pending { micState = .live }
        }
    }

    /// Send what the viewer typed. It joins the pool the host picks from, like any other
    /// message — but with no canned reply, since nobody wrote one for it.
    private func sendDraft() {
        let text = draft.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        draft = ""
        post(
            ChatMessage(
                id: Int(Date().timeIntervalSince1970 * 1000),
                viewer: Viewer(name: t.you, hue: 265),
                text: text,
                reply: nil,
                gift: nil
            )
        )
    }
}

enum MicState {
    case idle, pending, live
}

struct DanmakuItem: Identifiable {
    let id: Int
    let text: String
    let lane: Int
    let hue: Double
    let seconds: Double
    let fontSize: Double
}

/// The stream, with everything laid over it: danmaku, the badges, the exit and mic
/// controls.
///
/// Square, matching the Web client. A phone could give it more height, but the avatar is
/// framed for a square and the room below needs the space more.
private struct LiveStage: View {
    @ObservedObject var session: AvatarRtcSession
    let danmaku: [DanmakuItem]
    let viewers: Int
    let micState: MicState
    let onMic: () -> Void

    @ObservedObject private var localization = Localization.shared
    private var t: Strings { localization.t }

    var body: some View {
        ZStack {
            Image("LiveRoomBackground")
                .resizable()
                .scaledToFill()

            AvatarStage(session: session)

            // Danmaku sits over the video rather than beside it: that overlap is what
            // makes a stream read as live, and the list below is the record for anything
            // that drifts past too fast to catch.
            GeometryReader { geo in
                ForEach(danmaku) { item in
                    DanmakuLine(item: item, width: geo.size.width)
                }
            }
            .allowsHitTesting(false)

            VStack {
                HStack(spacing: 6) {
                    Text(t.liveBadge)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 3)
                        .background(Color(red: 0.88, green: 0.14, blue: 0.37), in: Capsule())
                    Text(t.viewerCount(viewers))
                        .font(.system(size: 11))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 3)
                        .background(.black.opacity(0.5), in: Capsule())
                    Spacer()
                }
                Spacer()
                HStack {
                    Spacer()
                    // Over the video, bottom right: asking to speak is something you do to
                    // the stream, so the control belongs on it rather than below with the
                    // chat.
                    Button(action: onMic) {
                        HStack(spacing: 5) {
                            Image(systemName: micState == .live ? "circle.fill" : "mic.fill")
                                .font(.system(size: 10))
                            Text(
                                micState == .idle ? t.micIdle
                                    : micState == .pending ? t.micPending : t.micLive
                            )
                            .font(.system(size: 12))
                        }
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(
                            micState == .live
                                ? Color(red: 0.88, green: 0.14, blue: 0.37)
                                : Color.black.opacity(0.55),
                            in: Capsule()
                        )
                    }
                    .disabled(!session.isConnected)
                }
            }
            .padding(10)

            if !session.isConnected {
                ZStack {
                    Color.black.opacity(0.72)
                    VStack(spacing: 12) {
                        if !session.hasFailed {
                            ProgressView().tint(.white)
                        }
                        Text(session.hasFailed ? session.status : t.enteringLive)
                            .font(.system(size: 13))
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

/// One line of danmaku crossing the video, at its own speed and size — uniform ones move
/// like a marquee and read as one animation rather than many people typing.
private struct DanmakuLine: View {
    let item: DanmakuItem
    let width: CGFloat

    @State private var offset: CGFloat = 0

    var body: some View {
        Text(item.text)
            .font(.system(size: item.fontSize, weight: .medium))
            .foregroundStyle(Color(hue: item.hue / 360, saturation: 0.5, brightness: 1))
            // An outline rather than a panel: danmaku has to stay readable over whatever
            // the video happens to be showing, and a background band would cover it.
            .shadow(color: .black.opacity(0.9), radius: 2, x: 0, y: 1)
            .lineLimit(1)
            .fixedSize()
            .offset(x: offset, y: CGFloat(6 + item.lane * 11))
            .onAppear {
                offset = width
                withAnimation(.linear(duration: item.seconds)) {
                    offset = -width
                }
            }
    }
}

/// The chat list. The record of everything said, including what drifted past unread.
private struct ChatList: View {
    let messages: [ChatMessage]

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(messages) { message in
                        HStack(alignment: .top, spacing: 8) {
                            Text(String(message.viewer.name.prefix(1)))
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 24, height: 24)
                                .background(
                                    Color(hue: message.viewer.hue / 360, saturation: 0.6, brightness: 0.75),
                                    in: Circle()
                                )
                            VStack(alignment: .leading, spacing: 1) {
                                Text(message.viewer.name)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                Text(message.gift.map { "\($0.icon) \(message.text)" } ?? message.text)
                                    .font(.footnote)
                            }
                            Spacer(minLength: 0)
                        }
                        // Gift messages are tinted so they stand out in a fast-moving
                        // list — they are the ones the host reacts to first.
                        .padding(message.gift == nil ? 0 : 6)
                        .background(
                            message.gift == nil
                                ? Color.clear
                                : Color.accentColor.opacity(0.12),
                            in: RoundedRectangle(cornerRadius: 8)
                        )
                        .id(message.id)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
            }
            // The two-parameter form; the newer one is iOS 17 only.
            .onChange(of: messages.count) { _ in
                guard let last = messages.last else { return }
                withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
            }
            // A surface of its own, so the list reads as the room's chat rather than
            // text spilling out from under the video with nothing holding it.
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color(.separator), lineWidth: 0.5)
            )
            .padding(.horizontal, 12)
            .padding(.top, 10)
        }
    }
}

/// Gifts, scrolling horizontally under the chat.
private struct GiftBar: View {
    let lang: Lang
    let enabled: Bool
    let onSend: (Gift) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(ChatData.gifts(for: lang)) { gift in
                    Button { onSend(gift) } label: {
                        VStack(spacing: 1) {
                            Text(gift.icon).font(.system(size: 19))
                            Text(gift.name).font(.system(size: 10)).lineLimit(1)
                            Text("\(gift.value)")
                                .font(.system(size: 9))
                                .foregroundStyle(.secondary)
                        }
                        .frame(width: 52)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 6)
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .stroke(Color(.separator), lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(!enabled)
                }
            }
            .padding(.horizontal, 12)
        }
        .padding(.vertical, 6)
    }
}

/// Where the viewer types. Their lines join the pool the host picks from, like anyone
/// else's — but with no canned reply, since nobody wrote one for it.
private struct Composer: View {
    @Binding var draft: String
    let enabled: Bool
    let onSend: () -> Void

    @ObservedObject private var localization = Localization.shared

    var body: some View {
        HStack(spacing: 8) {
            TextField(localization.t.chatPlaceholder, text: $draft)
                .textFieldStyle(.roundedBorder)
                .font(.footnote)
                .submitLabel(.send)
                .onSubmit(onSend)
                .disabled(!enabled)
            Button(localization.t.send, action: onSend)
                .font(.footnote)
                .disabled(!enabled || draft.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }
}
