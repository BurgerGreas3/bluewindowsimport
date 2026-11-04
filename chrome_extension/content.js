// ==============================================================================
//     Blue CBT Active Question Tracker & Quick Marker (100% Silent Mode)
// ==============================================================================

(function() {
    'use strict';

    const BRIDGE_URL = 'http://127.0.0.1:20261';
    let currentActiveQuestion = 1;
    let forcedImages = new Set();

    // 1. DETECT CURRENT ACTIVE QUESTION
    // Pierces one level of open shadow roots (modern CBT frameworks)
    function deepQuery(sel) {
        let out = Array.from(document.querySelectorAll(sel));
        try {
            document.querySelectorAll('*').forEach(el => {
                if (el.shadowRoot) {
                    out = out.concat(Array.from(el.shadowRoot.querySelectorAll(sel)));
                }
            });
        } catch (e) {}
        return out;
    }
    function detectActiveQuestion() {
        // Only run detection in frames that actually contain CBT question elements
        const hasCBT = document.querySelector('.question-container, .soal-container, .card-soal, .box-soal, .soal_konten, .nav-btn, .btn-nomor, #currentQuestionNumber, [id^="soal-"]');
        if (!hasCBT) return;

        let detected = null;
        let confident = false; // explicit active markers switch instantly; guesses need confirmation

        // Check 0: Direct Question Number indicator (e.g. #currentQuestionNumber)
        const qNumEl = document.getElementById('currentQuestionNumber') || document.querySelector('.current-question-number, .current-soal-no, .no-soal-aktif, #noSoal');
        if (qNumEl) {
            const num = parseInt(qNumEl.innerText.trim(), 10);
            if (!isNaN(num) && num > 0) {
                detected = num;
                confident = true;
            }
        }

        // Check 1: Active pagination or number button (e.g. .nav-btn.active, .btn-nomor.active)
        if (!detected) {
            const activeBtn = document.querySelector('.nav-btn.active, .btn-nomor.active, .nomor-soal.active, .page-item.active a, .question-nav.active, .item-soal.active');
            if (activeBtn) {
                const urut = activeBtn.getAttribute('data-urut');
                const text = activeBtn.innerText.trim();
                const num = (urut && !isNaN(parseInt(urut, 10))) ? parseInt(urut, 10) : parseInt(text, 10);
                if (!isNaN(num) && num > 0) {
                    detected = num;
                    confident = true;
                }
            }
        }

        // Check 2: Active question container (Multi-vendor CBT templates)
        if (!detected) {
            const activeContainer = deepQuery('.question-container.active, .soal-container.active, .card-soal.active, .box-soal.active, .soal_konten.active')[0] || null;
            if (activeContainer) {
                const dataUrut = activeContainer.getAttribute('data-urut');
                if (dataUrut && !isNaN(parseInt(dataUrut, 10))) {
                    detected = parseInt(dataUrut, 10);
                    confident = true;
                } else {
                    const nomorAsli = activeContainer.getAttribute('data-nomor-asli') || activeContainer.getAttribute('data-nomor');
                    if (nomorAsli) {
                        const navBtn = document.querySelector('.nav-btn[data-nomor="' + nomorAsli + '"], button[data-nomor="' + nomorAsli + '"]');
                        if (navBtn) {
                            const btnUrut = navBtn.getAttribute('data-urut') || navBtn.innerText.trim();
                            if (btnUrut && !isNaN(parseInt(btnUrut, 10))) {
                                detected = parseInt(btnUrut, 10);
                                confident = true;
                            }
                        }
                    }
                    if (!detected && activeContainer.id) {
                        const match = activeContainer.id.match(/(?:soal|question)[_-]?(\d+)/i);
                        if (match) {
                            const idVal = parseInt(match[1], 10);
                            const zeroBased = !!document.getElementById('soal-0');
                            detected = idVal + (zeroBased ? 1 : 0);
                            confident = true;
                        }
                    }
                }
            }
        }

        // Check 3: visible container — ONLY when exactly ONE is visible.
        // During page transitions (or paginated templates with repeated ids like
        // soal-0..5) several containers are visible at once; grabbing the first
        // one reports a random-looking number and the HUD shows the wrong answer.
        // Ambiguous => hold last known (never guess).
        if (!detected) {
            const containers = deepQuery('.question-container, .soal-container, .card-soal, .box-soal, [id^="soal-"]');
            const visible = [];
            for (let c of containers) {
                try {
                    const style = window.getComputedStyle(c);
                    if (style.display !== 'none' && style.visibility !== 'hidden' && c.offsetHeight > 0) {
                        visible.push(c);
                    }
                } catch (e) {}
            }
            if (visible.length === 1) {
                const c = visible[0];
                const dataUrut = c.getAttribute('data-urut');
                if (dataUrut && !isNaN(parseInt(dataUrut, 10))) {
                    detected = parseInt(dataUrut, 10);
                } else {
                    const nomorAsli = c.getAttribute('data-nomor-asli') || c.getAttribute('data-nomor');
                    if (nomorAsli) {
                        const navBtn = document.querySelector('.nav-btn[data-nomor="' + nomorAsli + '"], button[data-nomor="' + nomorAsli + '"]');
                        if (navBtn) {
                            const btnUrut = navBtn.getAttribute('data-urut') || navBtn.innerText.trim();
                            if (btnUrut && !isNaN(parseInt(btnUrut, 10))) {
                                detected = parseInt(btnUrut, 10);
                            }
                        }
                    }
                    if (!detected && c.id) {
                        const match = (c.id || '').match(/(?:soal|question)[_-]?(\d+)/i);
                        if (match) {
                            const idVal = parseInt(match[1], 10);
                            const zeroBased = !!document.getElementById('soal-0');
                            detected = idVal + (zeroBased ? 1 : 0);
                        }
                    }
                }
            }
        }

        // Check 4: URL hash (e.g. #soal-2 or #q-2) — explicit navigation signal.
        if (!detected && window.location.hash) {
            const match = window.location.hash.match(/(?:soal|q|nomor)[_-]?(\d+)/i);
            if (match) {
                detected = parseInt(match[1], 10);
                confident = true;
            }
        }

        if (detected && detected !== currentActiveQuestion) {
            currentActiveQuestion = detected;
            notifyActiveQuestion(currentActiveQuestion, confident);
        }
    }

    // 2. BEAM ACTIVE QUESTION TO LOCAL BRIDGE (c=1: explicit marker, switch now)
    function notifyActiveQuestion(qNum, confident) {
        const url = `${BRIDGE_URL}/active?q=${encodeURIComponent(qNum)}${confident ? '&c=1' : ''}`;
        fetch(url, { method: 'GET', mode: 'no-cors' }).catch(() => {});
    }

    // 3. BEAM FORCED IMAGE OVERRIDE TO LOCAL BRIDGE (SILENT - ZERO DOM IMPACT)
    function notifyMarker(qNum, mode) {
        const url = `${BRIDGE_URL}/mark?q=${encodeURIComponent(qNum)}&mode=${encodeURIComponent(mode)}`;
        fetch(url, { method: 'GET', mode: 'no-cors' }).catch(() => {});
    }

    // 4. RESET NOTES (ON RELOAD / PAGE UNLOAD)
    // keepalive:true survives page unload; top frame only (iframes must not wipe marks)
    function notifyResetNotes() {
        if (window.top !== window) return;
        const url = `${BRIDGE_URL}/reset_notes`;
        try {
            fetch(url, { method: 'GET', mode: 'no-cors', keepalive: true }).catch(() => {});
        } catch (e) {}
    }

    function bridgeGet(path) {
        try {
            fetch(`${BRIDGE_URL}${path}`, { method: 'GET', mode: 'no-cors', keepalive: true }).catch(() => {});
        } catch (e) {}
    }

    // 4b. CHATBOT hidden capture + WHOLE-RESET [ + FULL-STOP /
    // \ starts capture (silent) -> type question (nothing shown) -> \ submits, ] cancels.
    // [ = whole reset (top frame only). / = pause/resume toggle (top frame only).
    let chatCapturing = false;
    let chatBuffer = '';

    function isTypingTarget(el) {
        if (!el) return false;
        if ((el.tagName === 'INPUT' && !['checkbox', 'radio', 'button', 'submit'].includes(el.type)) ||
            el.tagName === 'TEXTAREA' ||
            el.isContentEditable) {
            return true;
        }
        if (el.getAttribute && ['textbox', 'combobox', 'searchbox'].includes(el.getAttribute('role'))) {
            return true;
        }
        if (el.closest && el.closest('.mq-editable-field, .CodeMirror, [contenteditable="true"], [role="textbox"]')) {
            return true;
        }
        return false;
    }

    // Chat capture auto-cancel: a stray \ must never trap the keyboard.
    let captureTimer = null;
    function captureStop() {
        chatCapturing = false;
        chatBuffer = '';
        if (captureTimer) { clearTimeout(captureTimer); captureTimer = null; }
    }
    function captureArm() {
        if (captureTimer) clearTimeout(captureTimer);
        captureTimer = setTimeout(() => {
            if (chatCapturing) captureStop(); // 20s silence -> release keys silently
        }, 20000);
    }

    window.addEventListener('keydown', (e) => {
        // Only ONE document receives a keypress (the focused frame), so chat keys
        // are safe in iframes. [ reset and / pause stay top-frame only: they are
        // pipeline-global and must not fire from an embedded frame by accident.
        const isTop = (window.top === window);
        const focusEl = e.target || document.activeElement;

        if (chatCapturing) {
            if (isTypingTarget(focusEl)) {
                captureStop();
                return;
            }
            // Capture mode owns keys outside inputs; ] or Escape cancels.
            e.preventDefault();
            e.stopPropagation();
            const k = e.key;
            if (k === '\\') {
                const q = chatBuffer.trim();
                captureStop();
                if (q) bridgeGet(`/chatask?q=${encodeURIComponent(q)}`);
            } else if (k === ']' || k === 'Escape') {
                captureStop();
                // ] = full chat reset (memory to 0, in-flight dead, HUD cleaned).
                // Esc = local release only.
                if (k === ']') bridgeGet('/chatreset');
            } else if (k === 'Backspace' || k === 'Delete') {
                chatBuffer = chatBuffer.slice(0, -1);
                captureArm();
            } else if (k && k.length === 1) {
                chatBuffer += k;
                captureArm();
            }
            return;
        }
        if (isTypingTarget(focusEl)) return;
        const k = e.key;

        if (k === '\\') {
            e.preventDefault();
            e.stopPropagation();
            chatCapturing = true;
            chatBuffer = '';
            captureArm();
        } else if (k === '[') {
            e.preventDefault();
            e.stopPropagation();
            bridgeGet('/reset_all');
        } else if (k === '/' || e.code === 'Slash' || e.keyCode === 191) {
            e.preventDefault();
            e.stopPropagation();
            bridgeGet('/pause_toggle');
        } else if (k === '=' || k === '+') {
            e.preventDefault();
            e.stopPropagation();
            bridgeGet('/chat_scroll?dir=down');
        } else if (k === '-' || k === '_') {
            e.preventDefault();
            e.stopPropagation();
            bridgeGet('/chat_scroll?dir=up');
        } else if (k === '.') {
            // Sudden total kill: supervisor + HUDs + links exit at once.
            e.preventDefault();
            e.stopPropagation();
            bridgeGet('/kill_all');
        } else if (k === ',') {
            // Wake: overlays back on (works whenever the supervisor is alive).
            e.preventDefault();
            e.stopPropagation();
            bridgeGet('/wake');
        } else if (k === '0') {
            // Single-question re-solve: re-runs Gemini for the current question only.
            // Same tier as \ (chat). Main Ctrl+S batch is untouched.
            e.preventDefault();
            e.stopPropagation();
            detectActiveQuestion();
            const q = currentActiveQuestion || 1;
            bridgeGet('/solve_one?num=' + q);
        } else if (k >= '1' && k <= '8') {
            // Preset keys 1-8: show saved essay / math formulas in chat HUD
            e.preventDefault();
            e.stopPropagation();
            bridgeGet('/preset?num=' + k);
        } else if (k === '9') {
            // "9" key feature: ALPHA SOLVER (DeepSeek Vision - All questions as images, 10 chunks x 3)
            e.preventDefault();
            e.stopPropagation();
            bridgeGet('/alpha_solve');
        }
    }, true);

    // 5. BACKTICK KEY LISTENER (Toggle ` - 100% SILENT)
    window.addEventListener('keydown', (e) => {
        // Support standard Backquote, backtick key, and universal keyCode 192
        const isBacktick = (e.code === 'Backquote' || e.key === '`' || e.keyCode === 192 || e.which === 192);
        if (isBacktick) {
            if (isTypingTarget(document.activeElement)) {
                return;
            }

            e.preventDefault();
            e.stopPropagation();

            // Any focused frame may mark: exam content often lives in an iframe,
            // and only the focused document receives the keypress (no duplicates).
            // The SERVER owns toggle truth (mode=toggle); the local Set is just
            // a mirror so split-brain frames can never desync marks.
            detectActiveQuestion();
            const q = currentActiveQuestion || 1;
            if (forcedImages.has(q)) forcedImages.delete(q);
            else forcedImages.add(q);
            notifyMarker(q, 'toggle');
        }
    }, true);

    // 6. OBSERVERS & EVENT LISTENERS (debounced: max 1 detect per 500ms)
    let lastDetect = 0;
    function detectThrottled() {
        const now = Date.now();
        if (now - lastDetect < 500) return;
        lastDetect = now;
        detectActiveQuestion();
    }
    // Navigation / click / keyboard listeners
    window.addEventListener('click', () => setTimeout(detectThrottled, 50));
    window.addEventListener('keyup', () => setTimeout(detectThrottled, 50));
    window.addEventListener('hashchange', detectThrottled);

    // MutationObserver to catch dynamic DOM changes
    const observer = new MutationObserver(() => {
        detectThrottled();
    });

    if (document.body) {
        observer.observe(document.body, { attributes: true, subtree: true, attributeFilter: ['class', 'style'] });
    } else {
        document.addEventListener('DOMContentLoaded', () => {
            observer.observe(document.body, { attributes: true, subtree: true, attributeFilter: ['class', 'style'] });
        });
    }

    // Periodic heartbeat (top frame only, 800ms): re-detect AND re-report the
    // current question every tick. This is the tracker's heartbeat: it keeps the
    // Mac's switch-hysteresis confirmed (flaps die, real moves confirm in ~1.6s)
    // and proves the extension is alive (kills false "extension disabled" warns).
    // Reporting is idempotent — the Mac prints only on real switches.
    if (window.top === window) {
        setInterval(() => {
            const hasCBT = document.querySelector('.question-container, .soal-container, .card-soal, .box-soal, .soal_konten, .nav-btn, .btn-nomor, #currentQuestionNumber, [id^="soal-"]');
            if (hasCBT) {
                detectActiveQuestion();
                notifyActiveQuestion(currentActiveQuestion);
            }
        }, 800);
    }

    // Reset notes on page reload / unload
    window.addEventListener('beforeunload', () => {
        forcedImages.clear();
        notifyResetNotes();
    });

    // Initial detection on script load (top frame resets once; iframes must not)
    if (window.top === window) {
        notifyResetNotes();
    }
    setTimeout(detectActiveQuestion, 100);

    console.log('[Blue CBT] Silent Tracker active.');
})();
