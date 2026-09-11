const { chromium } = require('/root/deepseek-harness/node_modules/.pnpm/playwright@1.61.1/node_modules/playwright');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 900, height: 1160 }, deviceScaleFactor: 1.5 });
  await page.goto('file:///root/HyperOS3_Enhanced_Brightness/_preview/ui.html');
  await page.waitForTimeout(400);
  await page.screenshot({ path: '/root/HyperOS3_Enhanced_Brightness/_preview/ui.png' });
  await browser.close();
  console.log('shot written');
})();
