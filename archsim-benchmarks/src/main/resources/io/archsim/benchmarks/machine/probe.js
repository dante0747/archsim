
const S = Number(process.env.PROBE_S), DUTY = Number(process.env.DUTY), AT = Number(process.env.START_AT);
const PERIOD = 10;
const item = { id: 12345, name: "item-12345", price: 19.99, tags: ["a", "b", "c"], at: "2026-11-01T00:00:00Z", nested: { x: 1, y: [1, 2, 3] } };
let sink = 0, units = 0;
const unit = () => { for (let i = 0; i < 20; i++) { const s = JSON.stringify(item); sink += JSON.parse(s).id + s.length; } };
for (let i = 0; i < 20000; i++) unit();
setTimeout(() => {
  const t0 = performance.now(), c0 = process.cpuUsage(), end = t0 + S * 1000;
  units = 0;
  const burst = () => {
    const start = performance.now();
    const stop = DUTY >= 1 ? end : Math.min(end, start + PERIOD * DUTY);
    do { for (let k = 0; k < 10; k++) unit(); units += 10; } while (performance.now() < stop);
    const now = performance.now();
    if (now >= end) {
      const c = process.cpuUsage(c0);
      console.log(JSON.stringify({ units, cpuMs: (c.user + c.system) / 1000, wallMs: now - t0, lateMs: Math.round(Date.now() - (now - t0) - AT), sink: sink % 7 }));
      return;
    }
    setTimeout(burst, Math.max(0, start + PERIOD - now));
  };
  burst();
}, Math.max(0, AT - Date.now()));
