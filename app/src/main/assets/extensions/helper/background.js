// OpenAudioStreamExtractor helper: background script.
// Talks to the Android app through a native port named "oase".
"use strict";

const NATIVE_APP = "oase";
let port = null;
let config = { capture: false, active: false, mute: false };
const seen = new Set();

const AUDIO_EXT = /\.(mp3|aac|aacp|m3u8|m3u|pls|ogg|oga|opus|flac|wav|m4a|xspf|asx)(\?|#|$)/i;
const SEGMENT_EXT = /\.(ts|m4s|mp4|webm|m4v|jpg|jpeg|png|gif|svg|webp|css|js|woff2?)(\?|#|$)/i;
const STREAM_PATH = /(\/;stream|\/;\?type=http|\/stream(\.mp3|\.aac)?(\?|$)|\/listen(\?|$)|\/live(\.mp3|\.aac)?(\?|$)|\/icecast|\/shoutcast)/i;
const AUDIO_TYPE = /^(audio\/|application\/ogg|application\/vnd\.apple\.mpegurl|application\/x-mpegurl|application\/pls|application\/x-scpls|application\/xspf)/i;

function send(msg) {
  try {
    if (port) port.postMessage(msg);
  } catch (e) {
    port = null;
  }
}

function connect() {
  if (port) return;
  try {
    const p = browser.runtime.connectNative(NATIVE_APP);
    p.onMessage.addListener((m) => {
      if (m && m.type === "config") {
        config = Object.assign({ capture: false, active: false, mute: false }, m.config || {});
        if (config.capture === false) seen.clear();
        if (config.reset) seen.clear();
      }
    });
    p.onDisconnect.addListener(() => {
      port = null;
    });
    port = p;
    send({ type: "hello" });
  } catch (e) {
    port = null;
  }
}
connect();
setInterval(connect, 2000);

function headerMap(headers) {
  const out = {};
  for (const h of headers || []) out[h.name.toLowerCase()] = h.value;
  return out;
}

function report(url, via, extra) {
  if (!config.capture || !url || !/^https?:/i.test(url)) return;
  const key = via + "|" + url;
  if (seen.has(key)) return;
  seen.add(key);
  send(Object.assign({ type: "candidate", url, via }, extra || {}));
}

browser.webRequest.onHeadersReceived.addListener(
  (details) => {
    if (!config.capture) return;
    const h = headerMap(details.responseHeaders);
    const ct = (h["content-type"] || "").split(";")[0].trim();
    const isIcy = Object.keys(h).some((k) => k.startsWith("icy-"));
    const byType = AUDIO_TYPE.test(ct);
    const byUrl = (AUDIO_EXT.test(details.url) || STREAM_PATH.test(details.url)) && !SEGMENT_EXT.test(details.url);
    const mediaType = details.type === "media";
    if (byType || isIcy || (mediaType && !SEGMENT_EXT.test(details.url)) || (byUrl && details.type !== "image")) {
      report(details.url, "net", {
        contentType: ct,
        icyName: h["icy-name"] || null,
        icyBr: h["icy-br"] || null,
        icyGenre: h["icy-genre"] || null,
        requestType: details.type
      });
    }
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders"]
);

browser.runtime.onMessage.addListener((msg, sender) => {
  if (!msg) return;
  if (msg.type === "getConfig") return Promise.resolve(config);
  if (msg.type === "dom") {
    report(msg.url, "dom", { contentType: msg.contentType || "", note: msg.note || "" });
  }
});
