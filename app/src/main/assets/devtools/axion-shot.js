// Used by axion-shot: phone-sized screenshot + console errors, page errors and failed requests.
const puppeteer = require('puppeteer-core');
const [url, out, w, h] = process.argv.slice(2);
(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.PUPPETEER_EXECUTABLE_PATH || '/usr/local/bin/chromium',
    headless: true, args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--hide-scrollbars'] });
  const page = await browser.newPage();
  await page.setViewport({ width: +w || 412, height: +h || 915, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  const errs = [];
  page.on('console', m => { if (m.type() === 'error') errs.push('CONSOLE-ERROR: ' + m.text()); });
  page.on('pageerror', e => errs.push('CONSOLE-ERROR: ' + (e && e.message ? e.message : e)));
  page.on('requestfailed', r => errs.push('CONSOLE-ERROR: failed to load ' + r.url() + ' (' + (r.failure() || {}).errorText + ')'));
  page.on('response', r => { if (r.status() >= 400) errs.push('CONSOLE-ERROR: ' + r.status() + ' for ' + r.url()); });
  try { await page.goto(url, { waitUntil: 'networkidle0', timeout: 30000 }); } catch (e) { errs.push('CONSOLE-ERROR: page did not finish loading: ' + e.message); }
  await new Promise(r => setTimeout(r, 600));
  await page.screenshot({ path: out });
  await browser.close();
  [...new Set(errs)].slice(0, 20).forEach(e => console.log(e));
  console.log('SCREENSHOT: ' + out);
})().catch(e => { console.log('Screenshot failed: ' + e.message); process.exit(1); });
