const { chromium } = require('/root/deepseek-harness/node_modules/.pnpm/playwright@1.61.1/node_modules/playwright');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 900, height: 1160 }, deviceScaleFactor: 2 });
  p.on('console', m => console.log('[console]', m.type(), m.text()));
  p.on('pageerror', e => console.log('[pageerror]', e.message));
  await p.goto('file:///root/HyperOS3_Enhanced_Brightness/_preview/ui.html');
  await p.waitForTimeout(300);
  const info = await p.evaluate(() => {
    const first = document.querySelector('.phone');
    return {
      bodyChildren: document.body.children.length,
      html: document.body.innerHTML.length,
      phones: document.querySelectorAll('.phone').length,
      firstText: first ? first.innerText.slice(0, 60) : null,
    };
  });
  console.log(JSON.stringify(info, null, 2));
  await b.close();
})();
