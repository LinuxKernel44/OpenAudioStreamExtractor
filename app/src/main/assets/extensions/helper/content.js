// OpenAudioStreamExtractor helper: content script (all frames).
// Only acts while the app has an extraction running (config.capture === true).
"use strict";

(function () {
  const w = window.wrappedJSObject;
  let config = { capture: false, active: false, mute: false };
  let clicked = new WeakSet();
  let ticks = 0;

  function reportUrl(url, note, contentType) {
    if (!url || typeof url !== "string") return;
    let abs;
    try {
      abs = new URL(url, document.baseURI).href;
    } catch (e) {
      return;
    }
    if (!/^https?:/i.test(abs)) return;
    browser.runtime.sendMessage({ type: "dom", url: abs, note: note || "", contentType: contentType || "" }).catch(() => {});
  }

  // Keep extraction sessions silent: mute every media element and hook play().
  try {
    const proto = w.HTMLMediaElement.prototype;
    const origPlay = proto.play;
    proto.play = exportFunction(function () {
      try {
        if (config.mute) {
          this.muted = true;
          this.volume = 0;
        }
        if (config.capture) reportUrl(this.currentSrc || this.src, "play-hook");
      } catch (e) {}
      return origPlay.apply(this, arguments);
    }, w);
  } catch (e) {}

  function scanMedia() {
    document.querySelectorAll("audio, video, source").forEach((el) => {
      if (config.mute && el.tagName !== "SOURCE") {
        try {
          el.muted = true;
          el.volume = 0;
        } catch (e) {}
      }
      const src = el.currentSrc || el.src || el.getAttribute("src");
      const type = el.getAttribute("type") || "";
      if (src && !src.startsWith("blob:")) reportUrl(src, el.tagName.toLowerCase(), type);
    });
    document.querySelectorAll("a[href], link[href], [data-stream], [data-src], [data-url], [data-audio], [data-radio]").forEach((el) => {
      for (const attr of ["href", "data-stream", "data-src", "data-url", "data-audio", "data-radio"]) {
        const v = el.getAttribute(attr);
        if (v && /\.(mp3|aac|m3u8|pls|m3u|ogg|opus)(\?|#|$)|\/;stream|icecast|shoutcast/i.test(v)) reportUrl(v, "attr:" + attr);
      }
    });
  }

  const ACCEPT_RE = /^(accept|accept all|agree|i agree|ok|got it|allow all|accepter|tout accepter|j'accepte|continuer|consent)/i;
  const PLAY_RE = /(^|\b)(play|listen|live|écouter|ecouter|lecture|start|on air|en direct)(\b|$)/i;
  const PLAY_SELECTORS = [
    "audio", "[class*='play' i]", "[id*='play' i]", "[aria-label*='play' i]", "[aria-label*='listen' i]",
    "[aria-label*='écouter' i]", "[title*='play' i]", "[title*='écouter' i]", "[data-action*='play' i]",
    ".jp-play", ".mejs-play", ".vjs-play-control", ".fa-play", ".icon-play", "button", "[role='button']"
  ];

  function visible(el) {
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function clickLikeUser(el) {
    try {
      el.dispatchEvent(new MouseEvent("mousedown", { bubbles: true, cancelable: true, view: window }));
      el.dispatchEvent(new MouseEvent("mouseup", { bubbles: true, cancelable: true, view: window }));
      el.click();
    } catch (e) {}
  }

  function dismissConsent() {
    document.querySelectorAll("button, a[role='button'], [role='button'], input[type='button']").forEach((el) => {
      if (clicked.has(el) || !visible(el)) return;
      const t = (el.innerText || el.value || el.getAttribute("aria-label") || "").trim();
      if (t.length && t.length < 30 && ACCEPT_RE.test(t)) {
        clicked.add(el);
        clickLikeUser(el);
      }
    });
  }

  function pressPlay() {
    let count = 0;
    for (const sel of PLAY_SELECTORS) {
      let nodes;
      try {
        nodes = document.querySelectorAll(sel);
      } catch (e) {
        continue;
      }
      for (const el of nodes) {
        if (count >= 4) return;
        if (clicked.has(el)) continue;
        if (el.tagName === "AUDIO" || el.tagName === "VIDEO") {
          clicked.add(el);
          try {
            el.muted = true;
            el.volume = 0;
            el.play();
          } catch (e) {}
          continue;
        }
        if (!visible(el)) continue;
        const label = [el.innerText, el.getAttribute("aria-label"), el.getAttribute("title"), el.className && String(el.className), el.id].join(" ");
        if (PLAY_RE.test(label) && !/(playlist|player-?bar|display|replay)/i.test(label)) {
          clicked.add(el);
          clickLikeUser(el);
          count++;
        }
      }
    }
  }

  async function tick() {
    try {
      config = await browser.runtime.sendMessage({ type: "getConfig" });
    } catch (e) {
      return;
    }
    if (!config || !config.capture) return;
    ticks++;
    scanMedia();
    if (config.active) {
      if (ticks > 1) dismissConsent();
      if (ticks > 2) pressPlay();
    }
  }

  setInterval(tick, 1000);
  tick();
})();
