/* Full console capture of hydration warning. Dev-only. */
const puppeteer = require("puppeteer");

(async () => {
  const browser = await puppeteer.launch({
    headless: "new",
    args: ["--no-sandbox", "--disable-setuid-sandbox"],
  });
  const page = await browser.newPage();
  const logs = [];
  page.on("console", (m) => {
    if (m.type() === "error" || m.type() === "warning") logs.push(m.text());
  });
  await page.setViewport({ width: 1440, height: 900 });
  await page.goto("http://localhost:3100", { waitUntil: "domcontentloaded", timeout: 60000 });
  await new Promise((r) => setTimeout(r, 4000));
  const full = logs.join("\n");
  console.log(full.slice(0, 6000));
  await browser.close();
})();
