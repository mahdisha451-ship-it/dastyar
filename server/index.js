import express from "express";
import rateLimit from "express-rate-limit";
import crypto from "node:crypto";

const { ANTHROPIC_API_KEY, APP_TOKEN, MODEL = "claude-sonnet-5-5", PORT = 3000 } = process.env;
if (!ANTHROPIC_API_KEY || !APP_TOKEN) {
  console.error("ANTHROPIC_API_KEY and APP_TOKEN must be set.");
  process.exit(1);
}

const SYSTEM = `You control an Android phone on behalf of the user through an accessibility service.
The user speaks Persian (Farsi). Understand Persian commands, including colloquial speech.

Each request gives: the user's goal, the foreground app package, a compact list of on-screen nodes, and a history of your previous actions.
Node format, one per line:  id|kind|label     (kind: btn, edit, scroll, chk, txt)

Reply with EXACTLY ONE JSON object and nothing else (no markdown, no prose):
{"type": "...", ...}

Allowed types and fields:
- click      {"type":"click","id":<node id>}
- type       {"type":"type","id":<edit node id>,"text":"...","enter":true|false}   (enter:true presses the keyboard search/enter key after typing)
- scroll     {"type":"scroll","dir":"down"|"up"}
- back       {"type":"back"}
- home       {"type":"home"}
- open_app   {"type":"open_app","name":"<app name, Persian or English>"}
- open_url   {"type":"open_url","url":"https://..."}
- wait       {"type":"wait","ms":800}
- ask_user   {"type":"ask_user","message":"<short Persian message>"}
- done       {"type":"done","message":"<short Persian summary>"}

Rules:
1. Use only node ids that appear in the current screen list. Never invent ids.
2. One step at a time. Look at the history to avoid repeating a failed action; if something failed twice, try another way (scroll, back, other node).
3. If the foreground package is com.dastyar.app (the assistant itself) or the needed app is not open, start with open_app or open_url.
4. For dropdowns/lists (province, city, dealership, car model): click the field, then click the matching option; scroll if the option is not visible.
5. For any CAPTCHA, image-verification, SMS code, OTP, password or login that needs the user: reply ask_user. Never try to solve or bypass a CAPTCHA.
6. For clicks that pay, buy, delete, send or otherwise cannot be undone, add "sensitive":true to the click action.
7. When the goal is fully achieved, reply done. If it cannot be achieved, reply done and explain why in Persian.
8. Keep "message" fields short and in Persian.`;

const ALLOWED = new Set(["click", "type", "scroll", "back", "home", "open_app", "open_url", "wait", "ask_user", "done"]);

function parseAction(text) {
  const m = text.match(/\{[\s\S]*\}/);
  if (!m) throw new Error("model returned no JSON");
  const a = JSON.parse(m[0]);
  if (!ALLOWED.has(a.type)) throw new Error("invalid action type");
  return a;
}

const cache = new Map(); // short-lived cache for identical requests
const TTL = 5 * 60 * 1000;

const app = express();
app.set("trust proxy", 1);
app.use(express.json({ limit: "1mb" }));
app.use(rateLimit({ windowMs: 60_000, max: 120, standardHeaders: true, legacyHeaders: false }));

app.get("/health", (_req, res) => res.send("ok"));

function auth(req, res, next) {
  const t = Buffer.from(req.get("x-app-token") || "");
  const k = Buffer.from(APP_TOKEN);
  if (t.length !== k.length || !crypto.timingSafeEqual(t, k)) {
    return res.status(401).json({ error: "unauthorized" });
  }
  next();
}

app.post("/plan", auth, async (req, res) => {
  try {
    const { command, screen = "", history = [], package: pkg = "" } = req.body || {};
    if (typeof command !== "string" || !command.trim()) {
      return res.status(400).json({ error: "command required" });
    }
    const content =
      `Goal: ${command}\nForeground package: ${pkg}\n\nHistory:\n` +
      (history.length ? history.slice(-14).map((h, i) => `${i + 1}. ${h}`).join("\n") : "(none)") +
      `\n\nScreen:\n${String(screen).slice(0, 12000)}`;

    const key = crypto.createHash("sha1").update(content).digest("hex");
    const hit = cache.get(key);
    if (hit && Date.now() - hit.t < TTL) return res.json({ action: hit.a, cached: true });

    const r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify({
        model: MODEL,
        max_tokens: 300,
        system: SYSTEM,
        messages: [{ role: "user", content }],
      }),
    });
    if (!r.ok) {
      const body = await r.text();
      console.error("Anthropic error", r.status, body.slice(0, 300));
      return res.status(502).json({ error: "upstream", status: r.status });
    }
    const data = await r.json();
    const text = (data.content || []).filter((b) => b.type === "text").map((b) => b.text).join("");
    const action = parseAction(text);

    cache.set(key, { a: action, t: Date.now() });
    if (cache.size > 500) cache.delete(cache.keys().next().value);
    res.json({ action });
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: String(e.message || e) });
  }
});

app.listen(PORT, () => console.log(`proxy listening on ${PORT}`));
