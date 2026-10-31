/* Console capture + close-up checks. Dev-only. */
const puppeteer = require("puppeteer");

(async () => {
  const browser = await puppeteer.launch({
    headless: "new",
    args: ["--no-sandbox", "--disable-setuid-sandbox"],
  });
  const page = await browser.newPage();
  const logs = [];
  page.on("console", (m) => logs.push(`[${m.type()}] ${m.text()}`));
  page.on("pageerror", (e) => logs.push(`[pageerror] ${e.message}`));
  await page.setViewport({ width: 1440, height: 900 });
  await page.goto("http://localhost:3100", { waitUntil: "domcontentloaded", timeout: 60000 });
  await new Promise((r) => setTimeout(r, 3000));
  console.log("=== CONSOLE ===");
  for (const l of logs.slice(0, 20)) console.log(l);

  // zoom into equalizer card
  const eq = await page.$("#experience + section, [class*=rounded-\\[26px\\]]") ??
    (await page.$$("section")) ;
  await page.evaluate(() => {
    const el = [...document.querySelectorAll("p")].find((p) => p.textContent.includes("DSP Audio Effects"));
    el?.closest("div[class]").parentElement?.scrollIntoView({ block: "center" });
  });
  await new Promise((r) => setTimeout(r, 700));
  await page.screenshot({ path: "scripts/shots/eq-zoom.png" });

  // theme switching to Amoled Black (last swatch)
  const swatches = await page.$$(".swatch");
  await swatches[swatches.length - 1]?.click();
  await page.evaluate(() => document.querySelector("#themes")?.scrollIntoView({ block: "center" }));
  await new Promise((r) => setTimeout(r, 900));
  await page.screenshot({ path: "scripts/shots/theme-amoled.png" });

  await browser.close();
})();
