import Foundation
import WebKit
import AppKit

class FullCBTRenderer: NSObject, WKNavigationDelegate {
    var webView: WKWebView!
    var isFinished = false
    let htmlURL: URL
    let outputDir: URL
    var totalQuestions = 0
    var startTime: Date!
    var questionTimes: [Double] = []

    init(htmlURL: URL, outputDir: URL) {
        self.htmlURL = htmlURL
        self.outputDir = outputDir
        super.init()

        let config = WKWebViewConfiguration()
        config.preferences.setValue(true, forKey: "allowFileAccessFromFileURLs")
        // Standard desktop viewport matching Chrome full screen
        self.webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 1280, height: 950), configuration: config)
        self.webView.navigationDelegate = self
    }

    func start() {
        self.startTime = Date()
        print("=======================================================")
        print("  CBT Off-Screen WebKit Question Screenshot Pipeline   ")
        print("=======================================================")
        print("[*] Start Time: \(DateFormatter.localizedString(from: self.startTime, dateStyle: .none, timeStyle: .medium))")
        print("[*] HTML Source: \(htmlURL.path)")
        print("[*] Output Dir : \(outputDir.path)")
        print("[*] Loading WebKit headless engine...")
        webView.loadFileURL(htmlURL, allowingReadAccessTo: htmlURL.deletingLastPathComponent())
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let loadElapsed = Date().timeIntervalSince(self.startTime)
        print(String(format: "[✓] Page & assets loaded in %.2f seconds.", loadElapsed))

        // Wait 0.4s for full font & bootstrap initialization
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            self.detectQuestions()
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        print("[!] Navigation error: \(error)")
        self.isFinished = true
    }

    func detectQuestions() {
        let js = """
        (function() {
            var containers = document.querySelectorAll('.question-container, .soal-container, [id^="soal-"], .box-soal, .card-soal, .soal_konten');
            return containers.length;
        })()
        """
        webView.evaluateJavaScript(js) { [weak self] (result, error) in
            guard let self = self else { return }
            self.totalQuestions = (result as? NSNumber)?.intValue ?? 0
            print("[*] Detected questions in CBT DOM: \(self.totalQuestions)")

            if self.totalQuestions == 0 {
                self.captureSinglePage()
            } else {
                self.processQuestion(index: 0)
            }
        }
    }

    func processQuestion(index: Int) {
        if index >= self.totalQuestions {
            self.finishAll()
            return
        }

        let qStartTime = Date()

        // Robust CBT container switch and MathJax typeset
        let switchJS = """
        (function() {
            if (typeof tampilSoal === 'function') {
                try { tampilSoal(\(index)); } catch (e) {}
            }
            var containers = document.querySelectorAll('.question-container, .soal-container, [id^="soal-"], .box-soal, .card-soal, .soal_konten');
            containers.forEach(function(el) { el.classList.remove('active'); });
            var zb = !!document.getElementById('soal-0');
            var target = document.getElementById('soal-' + (\(index) + (zb ? 0 : 1))) || containers[\(index)];
            if (target) {
                target.classList.add('active');
                target.style.display = 'block';
                try { target.scrollIntoView(); } catch (e) {}
            }
            if (window.MathJax && window.MathJax.typesetPromise) {
                try { window.MathJax.typesetPromise(); } catch (e) {}
            }
        })()
        """
        webView.evaluateJavaScript(switchJS) { [weak self] (_, _) in
            guard let self = self else { return }

            // 0.25s paint settling delay for compositor repaint & MathJax
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
                self.webView.takeSnapshot(with: nil) { [weak self] (image, error) in
                    guard let self = self else { return }

                    if let image = image,
                       let tiff = image.tiffRepresentation,
                       let rep = NSBitmapImageRep(data: tiff) {
                        let jpgData = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.80])
                        let filename = String(format: "soal_%02d.jpg", index + 1)
                        let fileURL = self.outputDir.appendingPathComponent(filename)
                        try? jpgData?.write(to: fileURL)

                        let qElapsed = Date().timeIntervalSince(qStartTime)
                        self.questionTimes.append(qElapsed)
                        let sizeKB = (jpgData?.count ?? 0) / 1024
                        print(String(format: "  [Soal %02d/%02d] -> %@ (%4d KB) [took %.2fs]", index + 1, self.totalQuestions, filename, sizeKB, qElapsed))
                    } else {
                        print("  [!] Failed snapshot for Soal \(index + 1): \(String(describing: error))")
                    }

                    self.processQuestion(index: index + 1)
                }
            }
        }
    }

    func captureSinglePage() {
        self.webView.takeSnapshot(with: nil) { [weak self] (image, _) in
            guard let self = self else { return }
            if let image = image, let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff) {
                let jpgData = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.80])
                let fileURL = self.outputDir.appendingPathComponent("soal_01.jpg")
                try? jpgData?.write(to: fileURL)
            }
            self.finishAll()
        }
    }

    func finishAll() {
        let totalElapsed = Date().timeIntervalSince(self.startTime)
        let avgPerScreen = totalElapsed / Double(max(self.totalQuestions, 1))

        print("=======================================================")
        print("               PIPELINE EXECUTION SUMMARY              ")
        print("=======================================================")
        print(String(format: "  Total Screenshots : %d questions captured", self.totalQuestions))
        print(String(format: "  Total Time Spent  : %.2f seconds", totalElapsed))
        print(String(format: "  Average per Image : %.2f seconds / image", avgPerScreen))
        print("  Resolution        : 1280 x 950 (Hi-DPI Desktop)")
        print("  Engine            : Apple WebKit (Headless RAM, NO Chrome)")
        print("  Destination       : \(self.outputDir.path)")
        print("=======================================================")
        self.isFinished = true
    }
}

// CLI Argument parsing
let cliArgs = CommandLine.arguments
if cliArgs.count < 2 {
    print("Usage: cbt_screens <html_file> [output_dir]")
    exit(1)
}

let htmlPath = cliArgs[1]
let outPath = cliArgs.count > 2 ? cliArgs[2] : (htmlPath as NSString).deletingPathExtension + "_screens"


let fileManager = FileManager.default
try? fileManager.removeItem(atPath: outPath)
try? fileManager.createDirectory(atPath: outPath, withIntermediateDirectories: true)

let htmlURL = URL(fileURLWithPath: htmlPath)
let outURL = URL(fileURLWithPath: outPath)

let renderer = FullCBTRenderer(htmlURL: htmlURL, outputDir: outURL)
renderer.start()

let loop = RunLoop.main
let deadline = Date(timeIntervalSinceNow: 120)
while !renderer.isFinished && Date() < deadline && loop.run(mode: .default, before: Date(timeIntervalSinceNow: 0.05)) {}
if !renderer.isFinished {
    print("[!] Screenshot extraction timed out after 120s.")
    exit(1)
}
