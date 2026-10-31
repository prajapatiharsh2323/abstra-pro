/* Dev-only inspection helper: captures full page + section screenshots.
 * Not part of the site build. */
const puppeteer = require("puppeteer");
const path = require("path");

(async () => {
  const browser = await puppeteer.launch({
    headless: "new",
    args: ["--no-sandbox", "--disable-setuid-sandbox", "--force-color-profile=srgb"],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 });

  await page.goto("http://localhost:3100", { waitUntil: "networkidle0", timeout: 60000 });
  // let scroll reveals settle
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await new Promise((r) => setTimeout(r, 1200));
  await page.evaluate(() => window.scrollTo(0, 0));
  await new Promise((r) => setTimeout(r, 600));

  const out = path.join(__dirname, "shots");
  await page.screenshot({ path: `${out}/desktop-hero.png` });
  await page.screenshot({ path: `${out}/desktop-full.png`, fullPage: true });

  // Interaction states
  await page.click('button[aria-label="Play"]').catch(() => {});
  await new Promise((r) => setTimeout(r, 900));
  await page.screenshot({ path: `${out}/player-playing.png` });

  // Theme switcher: click 3rd swatch (Cotton Candy light)
  const swatches = await page.$$(".swatch");
  if (swatches[1]) {
    await swatches[1].click();
    await new Promise((r) => setTimeout(r, 900));
    await page.evaluate(() => document.querySelector("#themes")?.scrollIntoView());
    await new Promise((r) => setTimeout(r, 500));
    await page.screenshot({ path: `${out}/theme-cotton-candy.png` });
  }

  // Mobile
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 1 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await new Promise((r) => setTimeout(r, 700));
  await page.screenshot({ path: `${out}/mobile-hero.png` });
  await page.screenshot({ path: `${out}/mobile-full.png`, fullPage: true });

  await browser.close();
  console.log("done");
})();
