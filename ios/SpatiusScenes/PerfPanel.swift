import AvatarKit
import AvatarKitRTC
import SwiftUI

/// Frame rate and playback readout, behind a toggle. Mirrors the Web and Android clients.
///
/// Two sources, deliberately kept apart on screen because they answer different
/// questions. The frame rate monitor is the SDK's own, and measures how fast this device
/// renders. The playback stats come from the RTC player, and say how much of what the
/// network sent arrived in time. A session can render at a healthy rate while dropping
/// frames, and vice versa; merging them into one "performance" number would hide exactly
/// the case worth seeing.
///
/// The monitor is off until switched on, which is also how the SDK ships it — enabling it
/// installs per-frame bookkeeping, so a demo left running should not pay for it.
struct PerfPanel: View {
    let session: AvatarRtcSession

    @ObservedObject private var localization = Localization.shared
    @State private var open = false
    @State private var info: FrameRateMonitor.FrameRateInfo?
    @State private var playback: AnimationSessionSummary?
    @State private var power: PowerMonitor.Snapshot?
    /// The SDK fires the callback on every rendered frame, not once per window, so binding
    /// it straight to the UI repaints the numbers 25 times a second and they are
    /// unreadable. The values are 2-second aggregates anyway — sampling them twice a
    /// second loses nothing and gives the eye something it can actually follow.
    @State private var lastShown = Date.distantPast

    /// Playback counters are cumulative totals with no callback, so they are polled.
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var t: Strings { localization.t }

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            if open {
                panel
            }
            toggle
        }
        .onDisappear {
            // The session outlives this view, so a monitor left on would keep running
            // with nothing reading it.
            session.setPerfMonitor(false)
        }
        .onReceive(tick) { _ in
            guard open else { return }
            playback = session.playbackStats
            power = session.samplePower()
        }
    }

    private var toggle: some View {
        Button {
            open.toggle()
            if open {
                session.setPerfMonitor(true) { next in
                    Task { @MainActor in
                        guard Date().timeIntervalSince(lastShown) >= 0.5 else { return }
                        lastShown = Date()
                        info = next
                    }
                }
            } else {
                session.setPerfMonitor(false)
                info = nil
                playback = nil
                power = nil
            }
        } label: {
            Text(t.perfTitle)
                .font(.caption)
                .padding(.horizontal, 12)
                .padding(.vertical, 5)
                .background(open ? Color.accentColor : Color(.secondarySystemBackground))
                .foregroundStyle(open ? .white : .secondary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let info {
                row(t.perfFps, String(format: "%.0f", info.fps), lead: true)
                row(t.perfPresentationFps, String(format: "%.0f", info.presentationFps))
                row(t.perfJank, String(format: "%.0f%%", info.jankRatioPercent))
                row(t.perfFrameTime, String(format: "%.1f ms", info.averageFrameTimeMs))
                row(t.perfIntervalP95, String(format: "%.1f ms", info.frameIntervalP95Ms))
                row(t.perfCpu, String(format: "%.0f%%", info.cpuUsagePercent))
            } else {
                Text(t.perfWaiting)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if let playback {
                Text(t.perfPlayback)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
                    .padding(.bottom, 2)
                row(t.perfFramesTotal, "\(playback.totalFrames)")
                row(t.perfDropped, "\(playback.totalDropped)")
                row(t.perfLost, "\(playback.totalLost)")
                row(t.perfStarved, "\(playback.jitterStarved)")
            }

            if let power {
                Text(t.perfDevice)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
                    .padding(.bottom, 2)
                row(t.perfThermal, thermalLabel(power.thermalState))
                if power.batteryLevel >= 0 {
                    row(t.perfBattery, "\(Int(power.batteryLevel * 100))%")
                }
            }

            Text(t.perfNote)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .padding(.top, 8)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(width: 210, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.secondary.opacity(0.25), lineWidth: 1)
        )
    }

    /// The system's own four-step verdict. Left as its own words rather than a number:
    /// iOS does not report a temperature, and inventing one from the step would read as a
    /// measurement it never made.
    private func thermalLabel(_ state: ProcessInfo.ThermalState) -> String {
        switch state {
        case .nominal: return t.perfThermalNominal
        case .fair: return t.perfThermalFair
        case .serious: return t.perfThermalSerious
        case .critical: return t.perfThermalCritical
        @unknown default: return "—"
        }
    }

    private func row(_ label: String, _ value: String, lead: Bool = false) -> some View {
        HStack {
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(value)
                .font(lead ? .subheadline : .caption2)
                .fontWeight(.semibold)
                // Tabular figures so the numbers stop jittering sideways as they update.
                .monospacedDigit()
        }
    }
}
