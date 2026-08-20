import AVFoundation
import SwiftUI

/// Minimum interval between two speak requests, to avoid hammering the backend.
private let speakIntervalNanos: UInt64 = 2_000_000_000

/// Gap between the two video tiles; kept in sync with VideoPanel's own value.
private let videoGap: CGFloat = 12

/// Upper bound on a video tile's side length, so it does not eat too much of a large
/// screen.
private let maxVideoTileSize: CGFloat = 360

/// Encouragement after a wrong answer, picked at random.
///
/// Not a fixed line: the same question can be re-answered repeatedly, and hearing the
/// identical phrase every time breaks the illusion.

/// The classroom's main screen: a two-column landscape layout, content left and video
/// right.
struct ClassroomView: View {
    let onExit: () -> Void

    @StateObject private var session = AvatarRtcSession()
    @State private var questionIndex = 0
    /// One answer recorded per question, kept across navigation: questions can be paged
    /// through freely and previously chosen options stay highlighted.
    @ObservedObject private var localization = Localization.shared
    private var t: Strings { localization.t }
    /// The question set for the current language. Switching languages swaps the whole
    /// set; the count and the answer indices match across both, so answers already given
    /// do not shift.
    private var questions: [Question] { sampleQuestions(for: localization.lang) }

    @State private var selections = [Int?](repeating: nil, count: 2)
    private var selectedIndex: Int? { selections[questionIndex] }
    /// Students can turn off their own camera. This only affects the local preview — the
    /// student's video is never sent upstream in the first place.
    @State private var cameraOn = true
    /// Text queued to be spoken, **keeping only the most recent line**: when the student
    /// taps repeatedly, later text overwrites earlier text; a queue would make the avatar
    /// read out stale feedback one line after another.
    @State private var pendingSpeech: String?
    @State private var speakLoopStarted = false

    /// Free talk: the student goes live and talks with the teacher. The mic is a toggle —
    /// tapping it again hangs up.
    @State private var freeTalk = false
    @State private var micGranted = false
    /// After a denial we stop prompting automatically (the system only offers once
    /// anyway); the mic goes grey to point the user at Settings.
    @State private var micDenied = false

    private var question: Question { questions[questionIndex] }

    /// All answers correct — the gate for free talk.
    private var allCorrect: Bool {
        questions.indices.allSatisfy { selections[$0] == questions[$0].answerIndex }
    }

    var body: some View {
        // Landscape: content left, video right. Portrait: video on top, content below.
        // Decided by the actual aspect ratio rather than sizeClass — sizeClass gets it
        // wrong in iPad split view.
        //
        // ⚠️ The container must be switched with AnyLayout; it cannot be written as an
        // if/else returning HStack / VStack separately: that produces two different view
        // trees, so rotation rebuilds the entire subtree, AvatarView is destroyed and
        // recreated, and the RTC session drops with it — it shows up as the avatar
        // cutting out mid-sentence.
        GeometryReader { geo in
            let isLandscape = geo.size.width > geo.size.height
            let layout = isLandscape
                ? AnyLayout(HStackLayout(spacing: 12))
                : AnyLayout(VStackLayout(spacing: 12))

            // Two squares plus the gap have to fit exactly along one edge: stacked
            // vertically in landscape they divide the height, laid out horizontally in
            // portrait they divide the width. The side length is computed once here and
            // passed down rather than derived back out of constraints by the child —
            // that way the two cannot disagree.
            let tileSize = min(
                (isLandscape ? geo.size.height : geo.size.width) - videoGap,
                maxVideoTileSize * 2
            ) / 2

            layout {
                if isLandscape {
                    questionPanel
                    videoPanel(axis: .vertical, tileSize: tileSize)
                } else {
                    videoPanel(axis: .horizontal, tileSize: tileSize)
                    questionPanel
                }
            }
        }
        .padding(12)
        .background(Color(.systemGroupedBackground))
        // The language toggle floats in the top-left. It uses an overlay rather than
        // being placed inside the layout tree above: that tree relies on AnyLayout to
        // preserve view identity across rotation, and adding nodes to it can easily take
        // AvatarView down with it.
        .overlay(alignment: .topLeading) {
            LangToggle().padding(.leading, 16).padding(.top, 8)
        }
        // Swipe right from the left edge to go back, in place of an on-screen "switch
        // teacher" button.
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
        .task {
            guard !speakLoopStarted else { return }
            speakLoopStarted = true
            await runSpeakLoop()
        }
        // Read the current question out once RTC is connected. The single-parameter form
        // keeps this compatible with iOS 16.
        .onChange(of: session.isConnected) { connected in
            if connected { presentCurrentQuestion() }
        }
        // Changing questions follows the same rule: read the stem for unanswered ones,
        // interrupt for ones already answered correctly.
        .onChange(of: questionIndex) { _ in
            guard session.isConnected else { return }
            presentCurrentQuestion()
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

    private func videoPanel(axis: Axis, tileSize: CGFloat) -> some View {
        VideoPanel(
            session: session,
            axis: axis,
            tileSize: tileSize,
            freeTalk: freeTalk,
            micGranted: micGranted,
            micDenied: micDenied,
            onToggleFreeTalk: toggleFreeTalk,
            cameraOn: cameraOn,
            onToggleCamera: { cameraOn.toggle() }
        )
    }

    /// The mic toggle: enter free talk and go live, or hang up and just mute.
    private func toggleFreeTalk() {
        if freeTalk {
            // Hanging up only mutes; the prompt is already the free-talk one, so
            // re-entering does not need to switch it again.
            freeTalk = false
            Task { await session.unpublishMic() }
            return
        }
        // No mic until the questions are done: free talk is the reward for finishing,
        // and opening the mic midway distracts the student and robs the "all correct"
        // line of its meaning.
        guard allCorrect else {
            speak(t.sayFinishFirst)
            return
        }
        freeTalk = true
        Task {
            // Get the mic live before sending the greeting: speak makes an HTTP round
            // trip while publishMic is a local call, so the other order lets them race
            // and the greeting tends to land during the audio rebuild that going live
            // triggers. (That rebuild itself is not fixed yet — see the notes in
            // SpatiusScenesApp.)
            await requestMicAndPublish()
            await session.startFreeTalk()
            // The client sends the transition line: the server only switches the prompt
            // and no longer speaks on its own.
            speak(t.sayFreeTalkOpen)
        }
    }

    /// Request the recording permission and publish the mic. On denial the mic goes grey
    /// and we stop prompting automatically.
    ///
    /// Uses AVAudioSession rather than AVAudioApplication, which is iOS 17 only — the
    /// deployment target is iOS 16.
    private func requestMicAndPublish() async {
        let granted = await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
        micGranted = granted
        micDenied = !granted
        if granted { await session.publishMic() }
    }

    private var questionPanel: some View {
        QuestionPanel(
            question: question,
            questionIndex: questionIndex,
            totalCount: questions.count,
            selectedIndex: selectedIndex,
            onSelect: handleSelect,
            // Paging only changes the index; whether to speak is decided in one place,
            // the onChange for questionIndex.
            onPrev: {
                guard questionIndex > 0 else { return }
                questionIndex -= 1
            },
            onNext: {
                guard questionIndex < questions.count - 1 else { return }
                questionIndex += 1
            }
        )
    }

    private func handleSelect(_ index: Int) {
        // A wrong answer can be changed and retried — that is the core interaction of a
        // tutoring scenario and must not lock after one attempt. Once correct we stop
        // responding, to avoid speaking the same feedback again.
        guard selectedIndex != question.answerIndex else { return }
        selections[questionIndex] = index
        let correct = index == question.answerIndex
        // Free talk starts only when everything is correct: finishing the whole set is
        // exactly when a student wants to follow up. Getting one question right along the
        // way earns feedback only, so the working rhythm is not broken.
        let finished = questions.indices.allSatisfy {
            selections[$0] == questions[$0].answerIndex
        }
        if finished && !freeTalk {
            // Feedback and opening line are merged into one, so two messages do not reach
            // the agent back to back and interrupt each other.
            speak(t.sayAllCorrect)
            freeTalk = true
            Task {
                await session.startFreeTalk()
                await requestMicAndPublish()
            }
        } else if finished {
            // All correct but the mic is already live: no need to walk them into opening
            // it, so just wrap up and invite follow-up questions.
            speak(t.sayCorrectThenChat)
        } else if correct {
            speak(t.sayCorrect)
        } else {
            speak(t.wrongReplies.randomElement() ?? t.sayCorrect)
        }
    }

    /// Present the current question: read the stem if it is not answered correctly yet;
    /// if it is, only interrupt and do not re-read it.
    ///
    /// Paging back to a question already answered correctly is just reviewing, and
    /// reading it again would interrupt the ongoing conversation; but the interrupt has
    /// to be explicit, otherwise the previous question's stem is read to the end and no
    /// longer matches the screen.
    private func presentCurrentQuestion() {
        if selections[questionIndex] == question.answerIndex {
            // Clear the pending slot so the throttling loop does not resend the very
            // line we just interrupted.
            pendingSpeech = nil
            Task { await session.interrupt() }
        } else {
            speak(question.readAloudText(number: questionIndex + 1))
        }
    }

    private func speak(_ text: String) {
        pendingSpeech = text
    }

    /// A single send loop: send whatever is pending, waiting only for the remainder of
    /// the interval if the last send was too recent. The first line to arrive after an
    /// idle period goes out immediately; text arriving during the wait just overwrites
    /// the slot.
    private func runSpeakLoop() async {
        var lastSentAt = DispatchTime.now().uptimeNanoseconds &- speakIntervalNanos
        while !Task.isCancelled {
            guard pendingSpeech != nil else {
                try? await Task.sleep(nanoseconds: 100_000_000)
                continue
            }

            let elapsed = DispatchTime.now().uptimeNanoseconds &- lastSentAt
            if elapsed < speakIntervalNanos {
                try? await Task.sleep(nanoseconds: speakIntervalNanos - elapsed)
            }

            // It may have been overwritten while we waited, so take the latest value now.
            guard let text = pendingSpeech else { continue }
            pendingSpeech = nil
            lastSentAt = DispatchTime.now().uptimeNanoseconds
            await session.speak(text)
        }
    }
}
