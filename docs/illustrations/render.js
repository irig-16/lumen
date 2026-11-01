// Renders the README illustrations: node docs/illustrations/render.js
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ deviceScaleFactor: 2, viewport: { width: 820, height: 800 } });
  for (const n of ['hero', 'speed', 'safety', 'secure']) {
    await p.goto('file://' + path.join(__dirname, n + '.html'));
    await p.waitForTimeout(150);
    const card = await p.$('.card');
    await card.screenshot({ path: path.join(__dirname, '..', 'images', n + '.png'), omitBackground: true });
  }
  await b.close();
})();
