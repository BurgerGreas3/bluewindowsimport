// ==============================================================================
//                   BLUE NOTCH - NATIVE macOS SLIDE-OUT OVERLAY              
// ==============================================================================
// 100% Stealth: COMPLETELY HIDDEN (alpha = 0, offscreen) when cursor is away.
// Smoothly slides out on left-edge hover to display active CBT question answers.
// Never steals focus, floats across all spaces, zero distraction.
// ==============================================================================

import Cocoa
import Foundation

class NotchPanel: NSPanel {
    override var canBecomeKey: Bool { return false }
    override var canBecomeMain: Bool { return false }
}

class NotchView: NSView {
    private var containerView: NSView!

    private var qBadgeLabel: NSTextField!
    private var answerLabel: NSTextField!

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        setupViews()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupViews()
    }

    private func setupViews() {
        wantsLayer = true
        layer?.masksToBounds = false
        shadow = nil

        // NO BACKGROUND - completely clear
        containerView = NSView(frame: bounds)
        containerView.autoresizingMask = [.width, .height]
        containerView.wantsLayer = true
        containerView.layer?.backgroundColor = NSColor.clear.cgColor
        containerView.layer?.borderWidth = 0
        addSubview(containerView)

        // Compact SOAL Badge (top row) - White color with 50% opacity
        qBadgeLabel = NSTextField(frame: NSRect(x: 0, y: 13, width: bounds.width, height: 13))
        qBadgeLabel.isBezeled = false
        qBadgeLabel.isEditable = false
        qBadgeLabel.isSelectable = false
        qBadgeLabel.drawsBackground = false
        qBadgeLabel.backgroundColor = .clear
        qBadgeLabel.textColor = NSColor(white: 1.0, alpha: 0.50)
        qBadgeLabel.font = NSFont.systemFont(ofSize: 8.5, weight: .semibold)
        qBadgeLabel.stringValue = "SOAL #01"
        containerView.addSubview(qBadgeLabel)

        // Compact ANSWER (bottom row) - White color with 50% opacity
        answerLabel = NSTextField(frame: NSRect(x: 0, y: 0, width: bounds.width, height: 14))
        answerLabel.isBezeled = false
        answerLabel.isEditable = false
        answerLabel.isSelectable = false
        answerLabel.drawsBackground = false
        answerLabel.backgroundColor = .clear
        answerLabel.textColor = NSColor(white: 1.0, alpha: 0.50)
        answerLabel.font = NSFont.systemFont(ofSize: 10.0, weight: .bold)
        answerLabel.cell?.wraps = false
        answerLabel.cell?.lineBreakMode = .byTruncatingTail
        answerLabel.stringValue = "-"
        containerView.addSubview(answerLabel)
    }

    func updateContent(qNum: Int, answerText: String, reasonText: String, hasAnswer: Bool) {
        let qStr = String(format: "%02d", qNum)
        qBadgeLabel.stringValue = "SOAL #\(qStr)"
        if hasAnswer {
            answerLabel.stringValue = answerText.isEmpty ? "-" : answerText
        } else {
            // Explicit placeholder text (e.g. no-answers/solving) wins over "-".
            answerLabel.stringValue = answerText.isEmpty ? "-" : answerText
        }
    }
}

// Chatbot answer view: right edge, same minimal black-on-transparent style.
// Short answers fit one glance; long ones wrap downward inside a centered panel.
/** Shared display safety net: residual LaTeX ($…$, \frac…) → readable Unicode.
 * Phone already normalizes; this covers old answer files. Pure display. */
func stripLatexTop(_ s: String) -> String {
    var t = s
    t = t.replacingOccurrences(of: "$$", with: "")
    t = t.replacingOccurrences(of: "$", with: "")
    t = t.replacingOccurrences(of: "\\(", with: "")
    t = t.replacingOccurrences(of: "\\)", with: "")
    t = t.replacingOccurrences(of: "\\[", with: "")
    t = t.replacingOccurrences(of: "\\]", with: "")
    if let re = try? NSRegularExpression(pattern: #"\\frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}"#) {
        for _ in 0..<3 {
            let n = re.stringByReplacingMatches(in: t, range: NSRange(t.startIndex..., in: t), withTemplate: "($1)/($2)")
            if n == t { break }
            t = n
        }
    }
    if let re = try? NSRegularExpression(pattern: #"\\sqrt\s*\{([^{}]*)\}"#) {
        t = re.stringByReplacingMatches(in: t, range: NSRange(t.startIndex..., in: t), withTemplate: "√($1)")
    }
    if let re = try? NSRegularExpression(pattern: #"\\[a-zA-Z]+\s*\{([^{}]*)\}"#) {
        for _ in 0..<3 {
            let n = re.stringByReplacingMatches(in: t, range: NSRange(t.startIndex..., in: t), withTemplate: "$1")
            if n == t { break }
            t = n
        }
    }
    let sym: [(String, String)] = [
        ("\\pm", "±"), ("\\times", "×"), ("\\cdot", "·"), ("\\div", "÷"),
        ("\\leq", "≤"), ("\\geq", "≥"), ("\\neq", "≠"), ("\\approx", "≈"),
        ("\\infty", "∞"), ("\\to", "→"), ("\\pi", "π"), ("\\theta", "θ"),
        ("\\alpha", "α"), ("\\beta", "β"), ("\\sqrt", "√"),
    ]
    for (k, v) in sym { t = t.replacingOccurrences(of: k, with: v) }
    if let re = try? NSRegularExpression(pattern: #"\\([a-zA-Z]+)"#) {
        t = re.stringByReplacingMatches(in: t, range: NSRange(t.startIndex..., in: t), withTemplate: "$1")
    }
    t = t.replacingOccurrences(of: "^2", with: "²").replacingOccurrences(of: "^3", with: "³")
    return t
}

class ChatView: NSView {
    private var scrollView: NSScrollView!
    private var textView: NSTextView!
    private var lastText: String = ""
    private var lastHistory: [String] = []

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        setupViews()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupViews()
    }

    private func setupViews() {
        wantsLayer = true
        layer?.masksToBounds = false
        shadow = nil

        scrollView = NSScrollView(frame: NSRect(x: 2, y: 1, width: bounds.width - 4, height: bounds.height - 2))
        scrollView.autoresizingMask = [.width, .height]
        scrollView.drawsBackground = false
        scrollView.borderType = .noBorder
        scrollView.hasVerticalScroller = false
        scrollView.hasHorizontalScroller = false

        textView = NSTextView(frame: NSRect(origin: .zero, size: scrollView.contentSize))
        textView.isEditable = false
        textView.isSelectable = false
        textView.drawsBackground = false
        textView.backgroundColor = .clear
        textView.textColor = NSColor(white: 1.0, alpha: 0.50)
        textView.textContainerInset = NSSize(width: 0, height: 0)
        textView.textContainer?.lineFragmentPadding = 0
        textView.textContainer?.widthTracksTextView = true
        textView.isHorizontallyResizable = false
        textView.isVerticallyResizable = true
        textView.autoresizingMask = [.width]
        scrollView.documentView = textView
        addSubview(scrollView)
        render()
    }

    func scrollDown() {
        let clipView = scrollView.contentView
        guard let docView = scrollView.documentView else { return }
        let maxScroll = max(0, docView.frame.height - clipView.bounds.height)
        var origin = clipView.bounds.origin
        origin.y = min(maxScroll, origin.y + 24)
        clipView.scroll(to: origin)
        scrollView.reflectScrolledClipView(clipView)
    }

    func scrollUp() {
        let clipView = scrollView.contentView
        var origin = clipView.bounds.origin
        origin.y = max(0, origin.y - 24)
        clipView.scroll(to: origin)
        scrollView.reflectScrolledClipView(clipView)
    }

    private func render() {
        let body = NSMutableAttributedString()
        let main = lastText.isEmpty ? "-" : lastText
        let para = NSMutableParagraphStyle()
        para.lineSpacing = 1.0
        para.lineBreakMode = .byWordWrapping

        // Parse **bold** markdown tags
        let parts = main.components(separatedBy: "**")
        for (idx, part) in parts.enumerated() {
            if part.isEmpty { continue }
            let isBold = (idx % 2 == 1)
            let font = isBold ? NSFont.boldSystemFont(ofSize: 9.5) : NSFont.systemFont(ofSize: 9.5, weight: .regular)
            let color = isBold ? NSColor(white: 1.0, alpha: 0.75) : NSColor(white: 1.0, alpha: 0.50)
            body.append(NSAttributedString(string: part, attributes: [
                .font: font,
                .foregroundColor: color,
                .paragraphStyle: para
            ]))
        }

        let hist = lastHistory.prefix(2)
        if !hist.isEmpty {
            body.append(NSAttributedString(string: " | " + hist.joined(separator: " | "), attributes: [
                .font: NSFont.systemFont(ofSize: 8.5, weight: .regular),
                .foregroundColor: NSColor(white: 1.0, alpha: 0.35),
                .paragraphStyle: para
            ]))
        }
        textView.textStorage?.setAttributedString(body)
    }

    func updateAnswer(_ text: String, history: [String] = []) {
        lastText = text
        lastHistory = history
        render()
        // Reset scroll position to top on new text
        scrollView.contentView.scroll(to: .zero)
        scrollView.reflectScrolledClipView(scrollView.contentView)
    }

    func updateAnswerStripped(_ text: String, history: [String] = []) {
        updateAnswer(stripLatexTop(text), history: history.map { stripLatexTop($0) })
    }
}

class NotchAppController: NSObject {
    private var window: NotchPanel!
    private var notchView: NotchView!
    
    private var hudSize = NSSize(width: 170, height: 28)
    private var hudFrame: NSRect = .zero
    private var isShown: Bool = false
    private var autoHideAt: Date? = nil

    // Chatbot HUD (bottom ribbon above dock)
    private var chatWindow: NotchPanel!
    private var chatView: ChatView!
    private var chatSize = NSSize(width: 680, height: 28)
    private var chatFrame: NSRect = .zero
    private var chatShown: Bool = false
    private var lastChatFileMTime: Date = Date(timeIntervalSince1970: 0)
    private var chatAutoHideAt: Date? = nil

    private static var projectDir: String = {
        if let env = ProcessInfo.processInfo.environment["BLUE_DIR"] {
            return env
        }
        let exe = CommandLine.arguments[0]
        let dir = URL(fileURLWithPath: exe).deletingLastPathComponent().path
        if FileManager.default.fileExists(atPath: (dir as NSString).appendingPathComponent("answers")) {
            return dir
        }
        return (NSHomeDirectory() as NSString).appendingPathComponent("Documents/blue")
    }()

    private let chatFile = (NotchAppController.projectDir as NSString).appendingPathComponent("answers/chat_latest.json")
    private let chatHistFile = (NotchAppController.projectDir as NSString).appendingPathComponent("answers/chat_history.json")
    private var chatCurrent: String? = nil
    private var chatHistory: [String] = []

    private func saveChatHistory() {
        let arr: [[String: String]] = ([chatCurrent].compactMap { $0 } + chatHistory)
            .prefix(3).map { ["answer": $0] }
        if let data = try? JSONSerialization.data(withJSONObject: arr) {
            try? data.write(to: URL(fileURLWithPath: chatHistFile))
        }
    }

    private func loadChatHistory() {
        if let data = try? Data(contentsOf: URL(fileURLWithPath: chatHistFile)),
           let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: String]] {
            let all = arr.compactMap { $0["answer"] }.filter { !$0.isEmpty && $0 != "-" }
            chatCurrent = all.first
            chatHistory = Array(all.dropFirst().prefix(2))
            if let cur = chatCurrent { chatView.updateAnswerStripped(cur, history: chatHistory) }
        }
        if chatCurrent == nil, FileManager.default.fileExists(atPath: chatFile),
           let data = try? Data(contentsOf: URL(fileURLWithPath: chatFile)),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let ans = json["answer"] as? String, !ans.isEmpty && ans != "-" {
            chatCurrent = ans
            chatView.updateAnswerStripped(ans, history: [])
        }
        if chatCurrent == nil {
            let defaultPreset1 = """
**Dialogue : Chez le Médecin**

1. **Le médecin :** Bonjour, entrez s'il vous plaît. **Que puis-je vous aider** ?
2. **Le patient :** Bonjour docteur, je viens parce que je ne me sens pas bien du tout.
3. **Le médecin :** D'accord, asseyez-vous. Quels sont exactement vos symptômes ?
4. **Le patient :** J'ai de la fièvre et je commence à **avoir la toux**.
5. **Le médecin :** Est-ce que votre nez coule et avez-vous mal à la gorge ?
6. **Le patient :** Oui, c'est très douloureux et je suis très fatigué.
7. **Le médecin :** Au début, je pensais à un simple **rhume**, mais c'est la **grippe**.
8. **Le patient :** C'est contagieux ? Qu'est-ce que je dois faire maintenant ?
9. **Le médecin :** Oui, donc **vous devriez** rester à la maison et bien vous reposer.
10. **Le patient :** Avez-vous un traitement ou des médicaments pour me soigner ?
11. **Le médecin :** Pour calmer la fièvre, **vous pourriez** prendre du paracétamol chaque matin.
12. **Le médecin :** Buvez aussi beaucoup d'eau tiède pendant toute la semaine.
13. **Le patient :** Merci beaucoup pour votre aide et vos conseils, docteur.
"""
            chatCurrent = defaultPreset1
            chatView.updateAnswerStripped(defaultPreset1, history: [])
        }
    }
    private let pausedFile = "/tmp/blue_paused"

    private enum HudDisplayMode {
        case main
        case alpha
    }
    private var hudDisplayMode: HudDisplayMode = .main
    private var alphaAnswersCache: [String: Any] = [:]
    private var lastAlphaFileMTime: Date = Date(timeIntervalSince1970: 0)
    private let alphaLatestFile = (NotchAppController.projectDir as NSString).appendingPathComponent("answers/alpha_latest_answers.json")

    private var currentQNum: Int = 1
    private var answersCache: [String: Any] = [:]
    private var sessionStartTime: Date = Date()
    private var lastAnswersFileMTime: Date = Date()
    private var didFirstAnswersLoad: Bool = false
    
    private let activeQFile = "/tmp/blue_active_q.txt"
    private let latestAnswersFile = (NotchAppController.projectDir as NSString).appendingPathComponent("answers/latest_answers.json")
    private let answersDir = (NotchAppController.projectDir as NSString).appendingPathComponent("answers")
    
    private var mouseMonitorTimer: Timer?
    private var edgeDwellStart: Date? = nil // calm trigger: must STICK at edge to pop
    private var hideDwellStart: Date? = nil // hide-grace: brief leaves don't kill a shown HUD
    private var chatDwellStart: Date? = nil // right edge: same calm rule for hover reveal
    private var chatHideStart: Date? = nil // right edge hide-grace, mirrors left
    private var syncTimer: Timer?

    private func isPaused() -> Bool {
        return FileManager.default.fileExists(atPath: pausedFile)
    }

    private func computeFrames() {
        guard let screen = NSScreen.main ?? NSScreen.screens.first else { return }
        let screenFrame = screen.visibleFrame
        let hudY = screenFrame.minY + 4
        let hudH: CGFloat = 28
        let hudW: CGFloat = 195
        hudSize = NSSize(width: hudW, height: hudH)

        // Orange HUD (main answer: bottom-left above dock, covering v.1.0)
        let orangeX = screenFrame.minX + 8
        hudFrame = NSRect(x: orangeX, y: hudY, width: hudW, height: hudH)

        // Green HUD (chat: bottom-center/right above dock, 2 full lines tall: 34px)
        let chatH: CGFloat = 34
        let greenX = orangeX + hudW + 12
        let availableW = max(300, screenFrame.maxX - greenX - 16)
        let greenW = min(680, availableW)
        chatSize = NSSize(width: greenW, height: chatH)
        chatFrame = NSRect(x: greenX, y: hudY, width: greenW, height: chatH)
    }

    func start() {
        computeFrames()
        setupWindow()
        setupChatWindow()
        // Brand new state - never load old answers on startup
        updateViewWithCurrentQuestion()
        startMouseMonitoring()
        startSyncTimer()
    }

    private func setupChatWindow() {
        computeFrames()
        chatWindow = NotchPanel(
            contentRect: chatFrame,
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        chatWindow.isOpaque = false
        chatWindow.backgroundColor = .clear
        chatWindow.level = .statusBar
        chatWindow.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary]
        chatWindow.hasShadow = false
        chatWindow.ignoresMouseEvents = false
        chatWindow.alphaValue = 0.0

        chatView = ChatView(frame: NSRect(origin: .zero, size: chatFrame.size))
        chatWindow.contentView = chatView
        chatWindow.orderFrontRegardless()
        loadChatHistory()
    }

    private func showChat() {
        guard !chatShown, !isPaused(), let window = self.chatWindow else { return }
        computeFrames()
        chatShown = true
        window.setFrame(chatFrame, display: true)
        window.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.02
            window.animator().alphaValue = 1.0
        }
    }

    private func hideChat() {
        guard chatShown, let window = self.chatWindow else { return }
        chatShown = false
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.02
            window.animator().alphaValue = 0.0
        }
    }

    private func setupWindow() {
        computeFrames()
        window = NotchPanel(
            contentRect: hudFrame,
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        window.isOpaque = false
        window.backgroundColor = .clear
        window.level = .statusBar
        window.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary]
        window.hasShadow = false
        window.ignoresMouseEvents = false
        window.alphaValue = 0.0

        notchView = NotchView(frame: NSRect(origin: .zero, size: hudFrame.size))
        window.contentView = notchView
        window.orderFrontRegardless()
    }

    private func startMouseMonitoring() {
        mouseMonitorTimer = Timer.scheduledTimer(withTimeInterval: 0.04, repeats: true) { [weak self] _ in
            guard let self = self, let screen = NSScreen.main else { return }

            // If paused (/ key), instantly hide both overlays and cancel all timers
            if self.isPaused() {
                self.autoHideAt = nil
                self.chatAutoHideAt = nil
                self.hideDwellStart = nil
                self.chatHideStart = nil
                if self.isShown { self.hideHUD() }
                if self.chatShown { self.hideChat() }
                self.window?.alphaValue = 0.0
                self.chatWindow?.alphaValue = 0.0
                return
            }

            let mouseLoc = NSEvent.mouseLocation
            let screenFrame = screen.visibleFrame
            let fullFrame = screen.frame

            // STRICT BOTTOM CORNER TRIGGERS ONLY:
            // The cursor MUST reach the actual corner trigger zones.
            // Hovering over the middle of the screen or above the dock will NEVER pop the HUDs.
            let inBottomY = (mouseLoc.y >= fullFrame.minY && mouseLoc.y <= screenFrame.minY + 45)

            // 1. LEFT HUD (Main Answer)
            let isAtLeftCorner = (mouseLoc.x <= screenFrame.minX + 45) && inBottomY

            // 2. MIDDLE BOTTOM (ALPHA DeepSeek Answer) - unique new spot near the middle!
            let isAtMiddleBottom = (abs(mouseLoc.x - screenFrame.midX) <= 60) && inBottomY

            let isOverHUD = inBottomY && self.hudFrame.insetBy(dx: -6, dy: -6).contains(mouseLoc)
            let shouldShowHUD = isAtLeftCorner || isAtMiddleBottom
            let shouldKeepHUD = (self.hudDisplayMode == .main && isAtLeftCorner) ||
                                (self.hudDisplayMode == .alpha && isAtMiddleBottom) ||
                                isOverHUD

            if isAtLeftCorner && self.hudDisplayMode != .main {
                self.hudDisplayMode = .main
                self.updateViewWithCurrentQuestion()
            } else if isAtMiddleBottom && self.hudDisplayMode != .alpha {
                self.hudDisplayMode = .alpha
                self.updateViewWithCurrentQuestion()
            }

            if self.isShown {
                if shouldKeepHUD {
                    self.hideDwellStart = nil
                    self.autoHideAt = nil
                } else {
                    if let hsince = self.hideDwellStart {
                        if Date().timeIntervalSince(hsince) >= 0.06 {
                            self.hideDwellStart = nil
                            self.autoHideAt = nil
                            self.hideHUD()
                        }
                    } else {
                        self.hideDwellStart = Date()
                    }
                    if let until = self.autoHideAt, Date() >= until {
                        self.autoHideAt = nil
                        self.hideHUD()
                    }
                }
            } else {
                self.hideDwellStart = nil
                if shouldShowHUD {
                    self.showHUD()
                }
            }

            // 2. RIGHT HUD (DeepSeek Chat '\' & Presets)
            // ONLY triggered when cursor is at the bottom-right corner!
            let isAtRightCorner = (mouseLoc.x >= screenFrame.maxX - 55) && inBottomY
            let isOverChat = inBottomY && self.chatFrame.insetBy(dx: -6, dy: -6).contains(mouseLoc)
            let shouldShowChat = isAtRightCorner
            let shouldKeepChat = isAtRightCorner || isOverChat

            if self.chatShown {
                if shouldKeepChat {
                    self.chatHideStart = nil
                    self.chatAutoHideAt = nil
                } else {
                    // Mouse moved outside the trigger zone: hide quickly (0.06s), never lock user out!
                    if let hsince = self.chatHideStart {
                        if Date().timeIntervalSince(hsince) >= 0.06 {
                            self.chatHideStart = nil
                            self.chatAutoHideAt = nil
                            self.hideChat()
                        }
                    } else {
                        self.chatHideStart = Date()
                    }
                    // Secondary safety timeout: auto-hide if untouched
                    if let until = self.chatAutoHideAt, Date() >= until {
                        self.chatAutoHideAt = nil
                        self.hideChat()
                    }
                }
            } else {
                self.chatHideStart = nil
                if shouldShowChat {
                    self.showChat()
                }
            }

            // Keyboard scrolling (= / -) IPC dispatched from Chrome / supervisor
            let scrollCmdFile = "/tmp/blue_chat_scroll"
            if FileManager.default.fileExists(atPath: scrollCmdFile) {
                if let cmd = try? String(contentsOfFile: scrollCmdFile, encoding: .utf8) {
                    try? FileManager.default.removeItem(atPath: scrollCmdFile)
                    let trimmed = cmd.trimmingCharacters(in: .whitespacesAndNewlines)
                    self.showChat()
                    self.chatAutoHideAt = Date().addingTimeInterval(4.0)
                    if trimmed == "down" {
                        self.chatView.scrollDown()
                    } else if trimmed == "up" {
                        self.chatView.scrollUp()
                    }
                }
            }
        }
    }

    private func showHUD() {
        guard !isShown, !isPaused(), let window = self.window else { return }
        computeFrames()
        isShown = true
        window.setFrame(hudFrame, display: true)
        window.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.02
            window.animator().alphaValue = 1.0
        }
    }

    private func hideHUD() {
        guard isShown, let window = self.window else { return }
        isShown = false
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.02
            window.animator().alphaValue = 0.0
        }
    }

    private func startSyncTimer() {
        syncTimer = Timer.scheduledTimer(withTimeInterval: 0.35, repeats: true) { [weak self] _ in
            self?.syncState()
        }
    }

    private func syncState() {
        // Paused (/ key): everything stays hidden silently
        if isPaused() {
            if isShown { hideHUD() }
            if chatShown { hideChat() }
            return
        }

        // 1. Check for Active Question updates
        if FileManager.default.fileExists(atPath: activeQFile),
           let content = try? String(contentsOfFile: activeQFile, encoding: .utf8) {
            let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
            if let num = Int(trimmed), num != currentQNum && num > 0 {
                currentQNum = num
                updateViewWithCurrentQuestion()
            }
        }

        // 2. Check for Answer File updates
        checkAndReloadAnswersIfModified()
        checkAndReloadAlphaAnswersIfModified()

        // 3. Chatbot answer file: load + auto-show 8s on arrival
        if FileManager.default.fileExists(atPath: chatFile),
           let attrs = try? FileManager.default.attributesOfItem(atPath: chatFile),
           let modDate = attrs[.modificationDate] as? Date,
           modDate > lastChatFileMTime {
            lastChatFileMTime = modDate
            if let data = try? Data(contentsOf: URL(fileURLWithPath: chatFile)),
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                let ans = (json["answer"] as? String) ?? "-"
                // Simple memory: keep previous 2 answers (+ file backup, capped).
                var hist = chatHistory
                if let cur = chatCurrent, !cur.isEmpty, cur != ans { hist.insert(cur, at: 0) }
                hist = Array(hist.prefix(2))
                chatHistory = hist
                chatCurrent = ans
                saveChatHistory()
                chatView.updateAnswerStripped(ans, history: hist)
                // No auto-pop on arrival: the answer waits silently until the
                // user hovers the right-edge zone to reveal it.
            }
        }
    }

    private func checkAndReloadAnswersIfModified() {
        var targetFile = latestAnswersFile
        if !FileManager.default.fileExists(atPath: targetFile) {
            if let recent = findMostRecentAnswersFile() {
                targetFile = recent
            } else {
                return
            }
        }

        if let attrs = try? FileManager.default.attributesOfItem(atPath: targetFile),
           let modDate = attrs[.modificationDate] as? Date {
            // First load bypasses the session gate: launching AFTER a solve (or
            // after ./blue fetch) must show existing answers, not a blank HUD.
            if !didFirstAnswersLoad || (modDate > lastAnswersFileMTime && modDate >= sessionStartTime) {
                didFirstAnswersLoad = true
                lastAnswersFileMTime = modDate
                loadAnswersFromPath(targetFile)
            }
        }
    }

    private func findMostRecentAnswersFile() -> String? {
        guard let files = try? FileManager.default.contentsOfDirectory(atPath: answersDir) else { return nil }
        var newestDate = Date.distantPast
        var newestFile: String? = nil

        for f in files {
            if (f.hasSuffix("_answers.json") || f.hasSuffix("_live.json")) && !f.hasPrefix(".") && !f.hasPrefix("alpha_") {
                let fullPath = (answersDir as NSString).appendingPathComponent(f)
                if let attrs = try? FileManager.default.attributesOfItem(atPath: fullPath),
                   let m = attrs[.modificationDate] as? Date {
                    if m > newestDate {
                        newestDate = m
                        newestFile = fullPath
                    }
                }
            }
        }
        return newestFile
    }

    private func loadLatestAnswers() {
        checkAndReloadAnswersIfModified()
    }

    private func loadAnswersFromPath(_ path: String) {
        guard let data = try? Data(contentsOf: URL(fileURLWithPath: path)),
              let json = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] else {
            return
        }

        if let rawAnswers = json["answers"] as? [[String: Any]] {
            var map: [String: Any] = [:]
            for item in rawAnswers {
                let no = item["nomor"] as? Int ?? item["number"] as? Int ?? item["num"] as? Int ?? 0
                if no > 0 {
                    let key = String(format: "%02d", no)
                    map[key] = item
                }
            }
            self.answersCache = map
        } else {
            self.answersCache = json
        }

        updateViewWithCurrentQuestion()
    }

    private func checkAndReloadAlphaAnswersIfModified() {
        guard FileManager.default.fileExists(atPath: alphaLatestFile),
              let attrs = try? FileManager.default.attributesOfItem(atPath: alphaLatestFile),
              let modDate = attrs[.modificationDate] as? Date else { return }
        if modDate > lastAlphaFileMTime {
            lastAlphaFileMTime = modDate
            loadAlphaAnswersFromPath(alphaLatestFile)
        }
    }

    private func loadAlphaAnswersFromPath(_ path: String) {
        guard let data = try? Data(contentsOf: URL(fileURLWithPath: path)),
              let json = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] else {
            return
        }
        if let ansMap = json["answers"] as? [String: Any] {
            self.alphaAnswersCache = ansMap
        } else if let rawArr = json["answers"] as? [[String: Any]] {
            var map: [String: Any] = [:]
            for item in rawArr {
                let no = item["nomor"] as? Int ?? item["number"] as? Int ?? item["nomor_tertera"] as? Int ?? 0
                if no > 0 {
                    let key = String(format: "%02d", no)
                    map[key] = item
                }
            }
            self.alphaAnswersCache = map
        }
        if self.hudDisplayMode == .alpha {
            updateViewWithCurrentQuestion()
        }
    }

    private func updateViewWithCurrentQuestion() {
        let qStr = String(format: "%02d", currentQNum)
        var ansText = ""
        var reasonText = ""
        var hasAns = false

        // Alpha Display Mode (DeepSeek Vision)
        if self.hudDisplayMode == .alpha {
            if let qObj = alphaAnswersCache[qStr] as? [String: Any] {
                hasAns = true
                let ans = (qObj["jawaban"] as? String) ?? (qObj["answer"] as? String) ?? "-"
                let rsn = (qObj["penjelasan"] as? String) ?? (qObj["reasoning"] as? String) ?? ""
                let qType = (qObj["tipe"] as? String) ?? ""
                let tag = qType.isEmpty ? "" : " [\(qType)]"
                notchView.updateContent(qNum: currentQNum, answerText: ans,
                                        reasonText: (rsn.isEmpty ? "(Alpha DeepSeek)" : rsn) + tag, hasAnswer: hasAns)
                return
            } else {
                notchView.updateContent(qNum: currentQNum, answerText: "…",
                                        reasonText: "No Alpha answer for Q\(currentQNum) yet (Press 9 to solve)", hasAnswer: false)
                return
            }
        }

        // Solving-state (written the instant a new batch starts): show it in the
        // answer slot itself — never the previous exam's answers. No new UI.
        if let st = answersCache["_status"] as? String, st == "solving" {
            notchView.updateContent(qNum: currentQNum, answerText: "…",
                                    reasonText: "solving…", hasAnswer: false)
            return
        }

        if let qObj = answersCache[qStr] as? [String: Any] {
            hasAns = true

            // Helper to extract clean single-letter verdict (B, S, T, F, V, ?)
            func parseVerdict(_ str: String) -> String {
                let l = str.lowercased()
                if l.contains("benar") && (l.contains("salah") || l.contains("false")) { return "?" }
                if l.contains("tidak benar") || l.contains("salah") { return "S" }
                if l.contains("false") || l.contains("faux") { return "F" }
                if l.contains("true") { return "T" }
                if l.contains("vrai") { return "V" }
                if l.contains("benar") { return "B" }
                if l.contains("tidak") || l.contains("undetermined") { return "?" }
                let t = str.trimmingCharacters(in: .whitespaces)
                return t.isEmpty ? "?" : String(t.prefix(1)).uppercased()
            }

            // 1. PRIORITY: Check "statements" array (Benar/Salah / True/False matrix)
            if let stmts = qObj["statements"] as? [[String: Any]], !stmts.isEmpty {
                var tfList: [String] = []
                var fullList: [String] = []
                for (i, stmt) in stmts.enumerated() {
                    let idx = stmt["index"] as? Int ?? (i + 1)
                    let rawStatus = (stmt["status"] as? String ?? "?").trimmingCharacters(in: .whitespaces)
                    let v = parseVerdict(rawStatus)
                    tfList.append("\(idx).\(v)")
                    let txt = stmt["text"] as? String ?? ""
                    fullList.append("\(idx). \(rawStatus)\(txt.isEmpty ? "" : " - " + txt)")
                }
                ansText = tfList.joined(separator: " ")
                reasonText = fullList.joined(separator: "\n")
            }
            // 2. Check "answer" field
            else if let a = qObj["answer"] {
                if let arr = a as? [String] {
                    // Check if this array is a Benar/Salah or True/False statement list (e.g. "1. Benar - ...")
                    let isStmt = arr.contains { s in
                        let l = s.lowercased()
                        return l.contains("benar") || l.contains("salah") || l.contains("true") || l.contains("false") || l.contains("vrai") || l.contains("faux")
                    }
                    if isStmt {
                        var tfList: [String] = []
                        for (i, s) in arr.enumerated() {
                            let sTrim = s.trimmingCharacters(in: .whitespaces)
                            var idxStr = "\(i + 1)"
                            if let re = try? NSRegularExpression(pattern: #"^\d+"#),
                               let m = re.firstMatch(in: sTrim, range: NSRange(sTrim.startIndex..., in: sTrim)) {
                                idxStr = (sTrim as NSString).substring(with: m.range)
                            }
                            let v = parseVerdict(sTrim)
                            tfList.append("\(idxStr).\(v)")
                        }
                        ansText = tfList.joined(separator: " ")
                        reasonText = arr.joined(separator: "\n")
                    } else {
                        // Multi-select (Pilihan Ganda Kompleks): show "A, C, D" and full text in reason
                        let letters = arr.compactMap { s -> String? in
                            let t = s.trimmingCharacters(in: .whitespaces)
                            return t.isEmpty ? nil : String(t.prefix(1)).uppercased()
                        }
                        ansText = letters.joined(separator: ", ")
                        reasonText = arr.joined(separator: "\n")
                    }
                } else if let arr = a as? [Any] {
                    let strArr = arr.map { "\($0)" }
                    let isStmt = strArr.contains { s in
                        let l = s.lowercased()
                        return l.contains("benar") || l.contains("salah") || l.contains("true") || l.contains("false") || l.contains("vrai") || l.contains("faux")
                    }
                    if isStmt {
                        var tfList: [String] = []
                        for (i, s) in strArr.enumerated() {
                            let sTrim = s.trimmingCharacters(in: .whitespaces)
                            var idxStr = "\(i + 1)"
                            if let re = try? NSRegularExpression(pattern: #"^\d+"#),
                               let m = re.firstMatch(in: sTrim, range: NSRange(sTrim.startIndex..., in: sTrim)) {
                                idxStr = (sTrim as NSString).substring(with: m.range)
                            }
                            let v = parseVerdict(sTrim)
                            tfList.append("\(idxStr).\(v)")
                        }
                        ansText = tfList.joined(separator: " ")
                        reasonText = strArr.joined(separator: "\n")
                    } else {
                        let letters = strArr.compactMap { e -> String? in
                            let t = e.trimmingCharacters(in: .whitespaces)
                            return t.isEmpty ? nil : String(t.prefix(1)).uppercased()
                        }
                        ansText = letters.joined(separator: ", ")
                        reasonText = strArr.joined(separator: "\n")
                    }
                } else if let s = a as? String {
                    ansText = formatAnswer(s)
                }
            } else if let o = qObj["option"] as? String, let oa = qObj["option_answer"] as? String {
                ansText = "\(o). \(oa)"
            } else if let o = qObj["option"] as? String {
                ansText = o
            } else if let j = qObj["jawaban"] {
                ansText = formatAnswer(j)
            }

            // Only override reasonText with AI reason if we didn't already set it from multi-select options
            if reasonText.isEmpty {
                if let r = qObj["reason"] as? String, !r.isEmpty {
                    reasonText = r
                } else if let al = qObj["alasan"] as? String, !al.isEmpty {
                    reasonText = al
                } else if let an = qObj["analysis"] as? String, !an.isEmpty {
                    reasonText = an
                }
            }

            // Never present an auto-filled guess as solved
            if let conf = qObj["confidence"] as? String, conf == "auto-filled" {
                ansText = "? " + ansText
            }
        }

        // No data at all (never Ctrl+S): explicit placeholder in the answer slot
        // so an empty hover reads as "working, nothing yet" — never confusion.
        var showAns = ansText, showRsn = reasonText
        if !hasAns {
            showAns = "(no answers yet)"
            showRsn = "Ctrl+S the exam page"
        }
        notchView.updateContent(qNum: currentQNum, answerText: stripLatexTop(showAns),
                                reasonText: stripLatexTop(showRsn), hasAnswer: hasAns)
    }

    private func formatAnswer(_ obj: Any) -> String {
        if let str = obj as? String {
            // Benar/Salah / True/False style: "1. Benar\n2. Salah" → compact "1.B 2.S"
            if str.contains("\n") {
                let rows = str.components(separatedBy: "\n").filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
                let compact = rows.map { row -> String in
                    let rTrim = row.trimmingCharacters(in: .whitespaces)
                    var num = ""
                    if let re = try? NSRegularExpression(pattern: #"^\d+"#),
                       let match = re.firstMatch(in: rTrim, range: NSRange(rTrim.startIndex..., in: rTrim)) {
                        num = (rTrim as NSString).substring(with: match.range)
                    }
                    if num.isEmpty {
                        return rTrim
                    }
                    let l = rTrim.lowercased()
                    let tag: String
                    if l.contains("benar") && (l.contains("salah") || l.contains("false")) { tag = "?" }
                    else if l.contains("tidak benar") || l.contains("salah") { tag = "S" }
                    else if l.contains("false") || l.contains("faux") { tag = "F" }
                    else if l.contains("true") { tag = "T" }
                    else if l.contains("vrai") { tag = "V" }
                    else if l.contains("benar") { tag = "B" }
                    else if l.contains("tidak") || l.contains("?") { tag = "?" }
                    else { tag = "" }
                    
                    if tag.isEmpty { return rTrim }
                    let cleanNum = num.trimmingCharacters(in: CharacterSet(charactersIn: "."))
                    return "\(cleanNum).\(tag)"
                }.joined(separator: " ")
                return compact
            }
            return str
        }
        if let arr = obj as? [String] {
            return arr.joined(separator: ", ")
        }
        if let arr = obj as? [Any] {
            return arr.map { "\($0)" }.joined(separator: ", ")
        }
        return "\(obj)"
    }
}

// Entry point
let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let controller = NotchAppController()
controller.start()
app.run()
