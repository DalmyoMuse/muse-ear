// Fake voice-relay (mini PC voice_relay.py) for testing 미나 귀 without touching the real one.
//   node tools/fake_relay.js [port]        (default 8097)
//   adb reverse tcp:8097 tcp:8097          then the app's relay address is http://127.0.0.1:8097
// POST /stt  WAV → {"ok":true,"text":"(가짜) ..."}  and saves it as tools/last.wav so you can listen to what was sent
// POST /ask  {"text"} → {"ok":true,"reply":"..."} after 1.5 s (the real Mina takes 7–14 s)
const http = require("http");
const fs = require("fs");
const path = require("path");

const port = Number(process.argv[2] || 8097);

http.createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    const body = Buffer.concat(chunks);
    const send = (obj) => {
      res.writeHead(200, { "Content-Type": "application/json; charset=utf-8" });
      res.end(JSON.stringify(obj));
    };
    const t = new Date().toTimeString().slice(0, 8);
    if (req.method === "POST" && req.url === "/stt") {
      fs.writeFileSync(path.join(__dirname, "last.wav"), body);
      const sec = Math.max(0, body.length - 44) / 32000;
      console.log(`${t} /stt ${body.length} bytes = ${sec.toFixed(2)}s → tools/last.wav`);
      return send({ ok: true, text: `(가짜) ${sec.toFixed(1)}초 들었어요` });
    }
    if (req.method === "POST" && req.url === "/ask") {
      const text = JSON.parse(body.toString() || "{}").text || "";
      console.log(`${t} /ask '${text}'`);
      return setTimeout(() => send({ ok: true, text, reply: "가짜 미나: 알겠어요" }), 1500);
    }
    res.writeHead(404); res.end();
  });
}).listen(port, () => console.log(`fake voice-relay on :${port}`));
