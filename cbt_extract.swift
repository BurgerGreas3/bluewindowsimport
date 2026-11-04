import Foundation
import WebKit
import AppKit
import Vision

func recognizeText(cgImage: CGImage, fast: Bool = false) -> String {
    var recognizedStrings: [String] = []
    let request = VNRecognizeTextRequest { req, _ in
        guard let observations = req.results as? [VNRecognizedTextObservation] else { return }
        recognizedStrings = observations.compactMap { $0.topCandidates(1).first?.string }
    }
    // Fusion path runs OCR on EVERY math question: .fast (~1s) instead of
    // .accurate (~10s) — DOM text carries the content, OCR only restores
    // superscripts/symbols the scraper flattened.
    request.recognitionLevel = fast ? .fast : .accurate
    if fast {
        request.usesLanguageCorrection = false
    }
    let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
    try? handler.perform([request])
    return recognizedStrings.joined(separator: "\n")
}

func addJPEGComment(jpegData: Data, comment: String) -> Data {
    guard jpegData.count > 2, jpegData[0] == 0xFF, jpegData[1] == 0xD8 else { return jpegData }
    guard let commentData = comment.data(using: .utf8) else { return jpegData }
    let safeLen = min(commentData.count, 65530)
    let truncatedCommentData = commentData.subdata(in: 0..<safeLen)
    let commentLength = UInt16(truncatedCommentData.count + 2)
    var markerData = Data([0xFF, 0xFE, UInt8(commentLength >> 8), UInt8(commentLength & 0xFF)])
    markerData.append(truncatedCommentData)
    var result = Data()
    result.append(contentsOf: [0xFF, 0xD8])
    result.append(markerData)
    result.append(jpegData.subdata(in: 2..<jpegData.count))
    return result
}

class CBTHybridExtractor: NSObject, WKNavigationDelegate {
    var webView: WKWebView!
    var isFinished = false
    let htmlURL: URL
    let batchDir: URL
    let startTime = Date()
    var totalQuestions = 0
    var imageCount = 0
    var textCount = 0
    var audioCount = 0
    var fusedCount = 0
    var forcedImages: Set<Int> = []

    /** OCR novelty: skip fusion when OCR repeats the DOM (avoids txt bloat).
     * Counts OCR tokens absent from DOM text; formulas (x² vs x2) always differ. */
    func ocrAddsValue(domText: String, ocrText: String) -> Bool {
        let tok: (String) -> Set<String> = { s in
            Set(s.lowercased().components(separatedBy: CharacterSet.alphanumerics.inverted)
                .filter { $0.count > 1 })
        }
        let dom = tok(domText)
        let novel = tok(ocrText).subtracting(dom)
        return novel.count >= 5 || ocrText.count > max(60, domText.count / 4)
    }

    /** Shared snapshot→OCR runner. saveJpg=false = fusion: OCR enriches the .txt,
     * nothing heavy is written (formulas ride as <1KB text, not 300KB jpgs). */
    func snapshotOCR(index: Int, fileNum: String, txtURL: URL, baseContent: String,
                     saveJpg: Bool, isForcedImage: Bool) {
        let switchJS = #"""
        (function() {
            if (typeof tampilSoal === 'function') {
                try { tampilSoal(\#(index)); } catch (e) {}
            }
            const containers = document.querySelectorAll('.question-container, .soal-container, [id^="soal-"], .box-soal, .card-soal, .soal_konten');
            containers.forEach(el => el.classList.remove('active'));
            const zb = !!document.getElementById('soal-0');
            const target = document.getElementById('soal-' + (\#(index) + (zb ? 0 : 1))) ||
                          containers[\#(index)];
            if (target) {
                target.classList.add('active');
                target.style.display = 'block';
                try { target.scrollIntoView(); } catch (e) {}
            }
            if (window.MathJax && window.MathJax.typesetPromise) {
                try { window.MathJax.typesetPromise(); } catch (e) {}
            }
        })()
        """#
        self.webView.evaluateJavaScript(switchJS) { [weak self] (_, _) in
            guard let self = self else { return }
            // 0.8s: lets MathJax typesetPromise + webfonts settle (was 0.35: raw-TeX shots)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                self.webView.takeSnapshot(with: nil) { [weak self] (image, error) in
                    guard let self = self else { return }
                    var ocrText = ""
                    var savedKB = 0
                    if let image = image,
                       let tiff = image.tiffRepresentation,
                       let rep = NSBitmapImageRep(data: tiff),
                       let cgImage = rep.cgImage {
                        ocrText = recognizeText(cgImage: cgImage, fast: !saveJpg)
                        if self.ocrAddsValue(domText: baseContent, ocrText: ocrText) {
                            let enriched = baseContent + "\n\n[OCR formula]:\n" + ocrText
                            try? enriched.data(using: .utf8)?.write(to: txtURL)
                        }
                        if saveJpg,
                           let rawJpg = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.80]) {
                            let commentedJpg = addJPEGComment(jpegData: rawJpg, comment: baseContent)
                            let filename = "\(fileNum)_soal.jpg"
                            let fileURL = self.batchDir.appendingPathComponent(filename)
                            try? commentedJpg.write(to: fileURL)
                            savedKB = commentedJpg.count / 1024
                            let tagStr = isForcedImage ? "[FORCED IMAGE + TEXT]" : "[IMAGE + TEXT]"
                            print(String(format: "  [%02d/%02d] -> %@ %@ (%d KB) & %@",
                                         index + 1, self.totalQuestions, tagStr, filename, savedKB, txtURL.lastPathComponent))
                        } else if !saveJpg {
                            self.fusedCount += 1
                            let bytes = (try? Data(contentsOf: txtURL).count) ?? 0
                            print(String(format: "  [%02d/%02d] -> [FUSION + TEXT] %@ (%d bytes, jpg skipped)",
                                         index + 1, self.totalQuestions, txtURL.lastPathComponent, bytes))
                        } else {
                            print(String(format: "  [%02d/%02d] -> [!] JPEG encode failed for %@",
                                         index + 1, self.totalQuestions, txtURL.lastPathComponent))
                        }
                    }
                    self.processQuestion(index: index + 1)
                }
            }
        }
    }

    init(htmlURL: URL, batchDir: URL, forcedImages: Set<Int> = []) {
        self.htmlURL = htmlURL
        self.batchDir = batchDir
        self.forcedImages = forcedImages
        super.init()

        let config = WKWebViewConfiguration()
        config.preferences.setValue(true, forKey: "allowFileAccessFromFileURLs")
        self.webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 1280, height: 950), configuration: config)
        self.webView.navigationDelegate = self
    }

    func start() {
        print("=======================================================")
        print("     CBT Smart Hybrid Question Extractor (Text/Img/Audio)")
        print("=======================================================")
        print("[*] HTML Source  : \(htmlURL.path)")
        print("[*] Batch Output : \(batchDir.path)")
        if !self.forcedImages.isEmpty {
            let sortedImgs = self.forcedImages.sorted().map { String(format: "#%02d", $0) }.joined(separator: ", ")
            print("[*] Forced Image Overrides (`): [\(sortedImgs)]")
        }
        print("[*] Loading WebKit headless engine...")
        webView.loadFileURL(htmlURL, allowingReadAccessTo: htmlURL.deletingLastPathComponent())
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let elapsed = Date().timeIntervalSince(self.startTime)
        print(String(format: "[✓] Page & assets loaded in %.2f seconds.", elapsed))
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            self.detectAndProcess()
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        print("[!] Load Error: \(error)")
        self.isFinished = true
    }

    func detectAndProcess() {
        let countJS = """
        (function() {
            const list = document.querySelectorAll('.question-container, .soal-container, [id^="soal-"], .box-soal, .card-soal, .soal_konten');
            return list.length;
        })()
        """
        webView.evaluateJavaScript(countJS) { [weak self] (result, error) in
            guard let self = self else { return }
            self.totalQuestions = (result as? NSNumber)?.intValue ?? 0
            print("[*] Total questions detected in DOM: \(self.totalQuestions)")

            if self.totalQuestions == 0 {
                // Fallback: generic radio-group / question-like heuristic for unknown templates
                print("[*] Known selectors empty. Trying generic fallback...")
                let fallbackJS = """
                (function() {
                    const radios = document.querySelectorAll('input[type="radio"], input[type="checkbox"]').length;
                    const like = document.querySelectorAll('[class*="question"], [class*="soal"], [id*="question"]').length;
                    return Math.max(radios > 0 ? radios : 0, like);
                })()
                """
                self.webView.evaluateJavaScript(fallbackJS) { [weak self] (r2, _) in
                    guard let self = self else { return }
                    self.totalQuestions = (r2 as? NSNumber)?.intValue ?? 0
                    print("[*] Fallback detected: \(self.totalQuestions)")
                    if self.totalQuestions == 0 {
                        print("[!] No questions detected across known + fallback selectors. Aborting batch.")
                        self.isFinished = true
                        return
                    }
                    self.processQuestion(index: 0)
                }
                return
            }

            self.processQuestion(index: 0)
        }
    }

    func processQuestion(index: Int) {
        if index >= self.totalQuestions {
            self.finishBatch()
            return
        }

        let inspectJS = #"""
        (function() {
            const containers = document.querySelectorAll('.question-container, .soal-container, [id^="soal-"], .box-soal, .card-soal, .soal_konten');
            // Id base auto-detect (once per page): 1-based DOMs (soal-1..) must NOT
            // match 'soal-'+index first or Q1 gets extracted twice and Q2 is lost.
            const zeroBased = !!document.getElementById('soal-0');
            const container = document.getElementById('soal-' + (\#(index) + (zeroBased ? 0 : 1))) ||
                              containers[\#(index)];
            if (!container) return null;

            // Visual question number seen by the student on screen (1, 2, ..., N).
            // NEVER use data-nomor-asli or data-nomor as those are teacher database bank IDs that randomize order!
            let realNum = \#(index + 1);
            const dataUrut = container.getAttribute('data-urut');
            if (dataUrut && !isNaN(parseInt(dataUrut, 10))) {
                realNum = parseInt(dataUrut, 10);
            } else {
                const nomorAsli = container.getAttribute('data-nomor-asli') || container.getAttribute('data-nomor');
                if (nomorAsli) {
                    const navBtn = document.querySelector('.nav-btn[data-nomor="' + nomorAsli + '"], button[data-nomor="' + nomorAsli + '"]');
                    if (navBtn) {
                        const btnUrut = navBtn.getAttribute('data-urut') || navBtn.innerText.trim();
                        if (btnUrut && !isNaN(parseInt(btnUrut, 10))) {
                            realNum = parseInt(btnUrut, 10);
                        }
                    }
                }
            }

            const imgs = Array.from(container.querySelectorAll('img')).filter(img => {
                const src = img.getAttribute('src') || '';
                const dataSrc = img.getAttribute('data-src') || '';
                if (src.startsWith('data:image/svg') && !dataSrc) return false;
                return true;
            });

            const mathEls = Array.from(container.querySelectorAll('sup, sub, mjx-container, mjx-math, .MathJax, math, svg, canvas, .katex, [class*=math]'));
            const innerHtml = container.innerHTML || '';
            const hasLaTeX = /\\\(|\\\[|\$\$|\\frac|\\sqrt|\\text/i.test(innerHtml);
            const hasMath = mathEls.length > 0 || hasLaTeX;

            const audios = Array.from(container.querySelectorAll('audio'));
            let audioSrc = '';
            if (audios.length > 0) {
                audioSrc = audios[0].getAttribute('src') || audios[0].getAttribute('data-src') || '';
                if (!audioSrc) {
                    const srcTag = audios[0].querySelector('source');
                    if (srcTag) {
                        audioSrc = srcTag.getAttribute('src') || srcTag.getAttribute('data-src') || '';
                    }
                }
            }

            // FIGURE vs MATH split (fusion design): real photos/diagrams need a
            // screenshot; typeset math (MathJax/KaTeX/SVG-text) only needs OCR
            // fused into the .txt — no 300KB jpg over Bluetooth for formulas.
            const hasFigure = imgs.length > 0;
            const hasImgs = hasFigure;
            const hasAudio = audioSrc.length > 0;

            const badge = container.querySelector('.badge')?.innerText?.trim() || 'Pilihan Ganda';
            
            function cleanFormulaText(el) {
                if (!el) return '';
                const clone = el.cloneNode(true);
                clone.querySelectorAll('sup').forEach(s => s.replaceWith('^' + s.innerText));
                clone.querySelectorAll('sub').forEach(s => s.replaceWith('_' + s.innerText));
                clone.querySelectorAll('mjx-container').forEach(mjx => {
                    const alt = mjx.getAttribute('aria-label') || mjx.getAttribute('alt') || '';
                    if (alt) {
                        mjx.replaceWith(' ' + alt + ' ');
                    }
                });
                return clone.innerText.trim();
            }

            const qTextEl = container.querySelector('.question-text');
            const qText = qTextEl ? cleanFormulaText(qTextEl) : '';

            let optionsText = [];
            const matchingRows = container.querySelectorAll('.matching-table tr');
            if (matchingRows.length > 0) {
                matchingRows.forEach((row, rIdx) => {
                    const statement = cleanFormulaText(row.querySelector('td:first-child')) || '';
                    if (statement) {
                        optionsText.push('  [' + (rIdx + 1) + '] ' + statement + ' -> (Pilihan: Benar / Salah)');
                    }
                });
            } else {
                const optionLabels = container.querySelectorAll('label.option-circle');
                optionLabels.forEach(lbl => {
                    const letter = lbl.querySelector('span')?.innerText?.trim() || '';
                    const clone = lbl.cloneNode(true);
                    clone.querySelectorAll('span, input').forEach(el => el.remove());
                    const text = cleanFormulaText(clone);
                    if (letter && text) {
                        optionsText.push(letter + '. ' + text);
                    } else if (text) {
                        optionsText.push(text);
                    }
                });
            }

            const realQStr = String(realNum).padStart(2, '0');
            const lines = [
                '[SOAL ' + realQStr + ']',
                'Tipe: ' + badge,
                'Pertanyaan:',
                qText,
                '',
                optionsText.length > 0 ? 'Pilihan Jawaban:' : '',
                ...optionsText
            ];

            let qType = 'TEXT';
            if (hasImgs) {
                qType = 'IMAGE';
            } else if (hasMath) {
                qType = 'FUSION';
            } else if (hasAudio) {
                qType = 'AUDIO';
            }

            return {
                realNum: realNum,
                type: qType,
                imgCount: imgs.length + (hasMath ? 1 : 0),
                hasMath: hasMath,
                hasFigure: hasFigure,
                audioSrc: audioSrc,
                content: lines.join('\n')
            };
        })()
        """#

        webView.evaluateJavaScript(inspectJS) { [weak self] (res, _) in
            guard let self = self else { return }
            guard let dict = res as? [String: Any], let detectedType = dict["type"] as? String else {
                print("  [!] Failed inspecting question \(index + 1)")
                self.processQuestion(index: index + 1)
                return
            }

            let realNum = dict["realNum"] as? Int ?? (index + 1)
            let fileNum = String(format: "%02d", realNum)
            if realNum != index + 1 {
                print("  [map] DOM index \(index + 1) -> realNum \(realNum) (file \(fileNum))")
            }
            let isForcedImage = self.forcedImages.contains(realNum) || self.forcedImages.contains(index + 1)
            let qType = isForcedImage ? "IMAGE" : detectedType

            let baseContent = dict["content"] as? String ?? ""

            if qType == "TEXT" {
                // 1. Pure text question: save .txt ONLY (saves token, time, bandwidth)
                self.textCount += 1
                let filename = "\(fileNum)_soal.txt"
                let fileURL = self.batchDir.appendingPathComponent(filename)
                try? baseContent.data(using: .utf8)?.write(to: fileURL)
                let bytes = baseContent.utf8.count
                print(String(format: "  [%02d/%02d] -> [TEXT ONLY]  %@ (%d bytes)", index + 1, self.totalQuestions, filename, bytes))
                self.processQuestion(index: index + 1)

            } else if qType == "AUDIO" {
                // 2. Audio question: save audio if exists + save .txt
                self.audioCount += 1
                let audioSrc = dict["audioSrc"] as? String ?? ""
                // Try locating audio file on disk (local, <source>, data: URIs)
                var audioFound = false
                if audioSrc.hasPrefix("data:audio") {
                    // Embedded data URI: decode straight to file
                    if let comma = audioSrc.firstIndex(of: ","),
                       let raw = Data(base64Encoded: String(audioSrc[audioSrc.index(after: comma)...])) {
                        let destURL = self.batchDir.appendingPathComponent("\(fileNum)_soal.mp3")
                        try? raw.write(to: destURL)
                        audioFound = true
                        print(String(format: "  [%02d/%02d] -> [AUDIO FILE] %@ decoded from data URI", index + 1, self.totalQuestions, destURL.lastPathComponent))
                    }
                } else if !audioSrc.isEmpty && !audioSrc.hasPrefix("blob:") && !audioSrc.hasPrefix("http") {
                    let possibleURLs = [
                        URL(fileURLWithPath: audioSrc, relativeTo: self.htmlURL).standardizedFileURL,
                        self.htmlURL.deletingLastPathComponent().appendingPathComponent(audioSrc),
                        self.htmlURL.deletingLastPathComponent().appendingPathComponent(URL(fileURLWithPath: audioSrc).lastPathComponent)
                    ]
                    for aURL in possibleURLs {
                        if FileManager.default.fileExists(atPath: aURL.path) {
                            let ext = aURL.pathExtension.isEmpty ? "mp3" : aURL.pathExtension
                            let destURL = self.batchDir.appendingPathComponent("\(fileNum)_soal.\(ext)")
                            try? FileManager.default.copyItem(at: aURL, to: destURL)
                            audioFound = true
                            print(String(format: "  [%02d/%02d] -> [AUDIO FILE] %@ copied", index + 1, self.totalQuestions, destURL.lastPathComponent))
                            break
                        }
                    }
                }

                if !audioFound {
                    // Check if user manually saved/downloaded any audio file into the batch or HTML directory
                    let parentDir = self.htmlURL.deletingLastPathComponent()
                    let audioExts = ["mp3", "m4a", "wav", "aac", "ogg"]
                    if let files = try? FileManager.default.contentsOfDirectory(at: parentDir, includingPropertiesForKeys: nil) {
                        let audios = files.filter { audioExts.contains($0.pathExtension.lowercased()) }
                        if let match = audios.first {
                            let ext = match.pathExtension.isEmpty ? "mp3" : match.pathExtension
                            let destURL = self.batchDir.appendingPathComponent("\(fileNum)_soal.\(ext)")
                            if !FileManager.default.fileExists(atPath: destURL.path) {
                                try? FileManager.default.copyItem(at: match, to: destURL)
                            }
                            audioFound = true
                            print(String(format: "  [%02d/%02d] -> [AUDIO FILE] %@ matched from folder (%@)", index + 1, self.totalQuestions, destURL.lastPathComponent, match.lastPathComponent))
                        }
                    }
                }

                if !audioFound {
                    print(String(format: "  [%02d/%02d] -> [!] AUDIO MISSING (src: %@) — solver will guess from text!", index + 1, self.totalQuestions, audioSrc.isEmpty ? "none" : audioSrc))
                }

                var fullAudioText = "[SOAL AUDIO / LISTENING]\n"
                if !audioFound {
                    fullAudioText += "[AUDIO HILANG: file tidak ditemukan — jawab dari teks sebisanya]\n"
                }
                if !audioSrc.isEmpty {
                    fullAudioText += "Sumber Audio: \(audioSrc)\n\n"
                }
                fullAudioText += baseContent

                let txtName = "\(fileNum)_soal.txt"
                let txtURL = self.batchDir.appendingPathComponent(txtName)
                try? fullAudioText.data(using: .utf8)?.write(to: txtURL)
                print(String(format: "  [%02d/%02d] -> [AUDIO TEXT] %@ (%d bytes)", index + 1, self.totalQuestions, txtName, fullAudioText.utf8.count))
                self.processQuestion(index: index + 1)

            } else if qType == "FUSION" {
                // 3. Typeset math, no real figure: OCR-fuse into .txt, NO jpg saved.
                // Formulas ride as <1KB text instead of 300KB over Bluetooth.
                let txtFilename = "\(fileNum)_soal.txt"
                let txtURL = self.batchDir.appendingPathComponent(txtFilename)
                try? baseContent.data(using: .utf8)?.write(to: txtURL)
                self.snapshotOCR(index: index, fileNum: fileNum, txtURL: txtURL,
                                 baseContent: baseContent, saveJpg: false,
                                 isForcedImage: false)
            } else {
                // 4. Real figure (photo/diagram) or forced mark: .txt AND .jpg.
                self.imageCount += 1

                // Save clean DOM text prompt for 100% crisp OCR-free text
                let txtFilename = "\(fileNum)_soal.txt"
                let txtURL = self.batchDir.appendingPathComponent(txtFilename)
                try? baseContent.data(using: .utf8)?.write(to: txtURL)
                self.snapshotOCR(index: index, fileNum: fileNum, txtURL: txtURL,
                                 baseContent: baseContent, saveJpg: true,
                                 isForcedImage: isForcedImage)
            }
        }
    }

    func finishBatch() {
        let totalElapsed = Date().timeIntervalSince(self.startTime)
        print("=======================================================")
        print("                   EXTRACTION COMPLETE                 ")
        print("=======================================================")
        print(String(format: "  Total Items Extracted : %d questions", self.totalQuestions))
        print(String(format: "  ├─ Pure Text Questions : %d (.txt only)", self.textCount))
        print(String(format: "  ├─ Fusion (OCR math)   : %d (.txt enriched, no jpg)", self.fusedCount))
        print(String(format: "  ├─ Image Questions     : %d (.jpg screenshot only)", self.imageCount))
        print(String(format: "  └─ Audio Questions     : %d (.txt / audio)", self.audioCount))
        print(String(format: "  Total Execution Time   : %.2f seconds", totalElapsed))
        print("  Batch Directory        : \(self.batchDir.path)")
        print("=======================================================")
        self.isFinished = true
    }
}

// CLI Argument parsing
// Usage: cbt_extract <path-to-html> [output-batch-dir] [--force-imgs 1,2,3]
let args = CommandLine.arguments
if args.count < 2 {
    print("Usage: cbt_extract <path-to-html> [output-batch-dir] [--force-imgs 1,2,3]")
    exit(1)
}

let inputHtml = args[1]
let htmlURL = URL(fileURLWithPath: inputHtml)

var batchDirArg: String? = nil
var forcedSet = Set<Int>()

var argIdx = 2
while argIdx < args.count {
    let a = args[argIdx]
    if a == "--force-imgs" && argIdx + 1 < args.count {
        let listStr = args[argIdx + 1]
        for part in listStr.components(separatedBy: ",") {
            if let n = Int(part.trimmingCharacters(in: .whitespaces)) {
                forcedSet.insert(n)
            }
        }
        argIdx += 2
        continue
    } else if !a.starts(with: "--") && batchDirArg == nil {
        batchDirArg = a
        argIdx += 1
        continue
    }
    argIdx += 1
}

// Also check /tmp/blue_forced_imgs.json if present
let forcedJsonPath = "/tmp/blue_forced_imgs.json"
if FileManager.default.fileExists(atPath: forcedJsonPath),
   let fData = try? Data(contentsOf: URL(fileURLWithPath: forcedJsonPath)),
   let raw = try? JSONSerialization.jsonObject(with: fData) {
    // Accept [Int]/["1"]/mixed, or {"forced":[...]} — never silently drop backtick marks
    var arr: [Any] = (raw as? [Any]) ?? []
    if arr.isEmpty, let obj = raw as? [String: Any], let inner = obj["forced"] as? [Any] {
        arr = inner
    }
    var applied = 0
    for el in arr {
        if let n = el as? Int { forcedSet.insert(n); applied += 1 }
        else if let s = el as? String, let n = Int(s.trimmingCharacters(in: .whitespaces)) {
            forcedSet.insert(n); applied += 1
        }
    }
    print("[*] Forced marks applied from bridge file: \(applied)/\(arr.count)")
}

let batchURL: URL
if let customBatch = batchDirArg {
    batchURL = URL(fileURLWithPath: customBatch)
} else {
    let now = Date()
    let formatter = DateFormatter()
    formatter.dateFormat = "yyyyMMdd_HHmmss"
    let batchName = "batch_" + formatter.string(from: now)
    let currentDir = URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent().path
    let batchesBase = (currentDir as NSString).appendingPathComponent("batches")
    batchURL = URL(fileURLWithPath: batchesBase).appendingPathComponent(batchName)
}

try? FileManager.default.createDirectory(at: batchURL, withIntermediateDirectories: true)

let extractor = CBTHybridExtractor(htmlURL: htmlURL, batchDir: batchURL, forcedImages: forcedSet)
extractor.start()

let loop = RunLoop.main
let deadline = Date(timeIntervalSinceNow: 300) // 5-min global timeout: hanging remote assets can't freeze the pipeline
while !extractor.isFinished && Date() < deadline && loop.run(mode: .default, before: Date(timeIntervalSinceNow: 0.05)) {}
if !extractor.isFinished {
    print("[!] Extraction timed out after 300s (remote assets hanging offline?). Partial batch kept.")
    exit(1)
}


