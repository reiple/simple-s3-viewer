// 헤드리스 Chrome 으로 UI 를 확인한다. zenika/alpine-chrome:with-puppeteer 컨테이너 안에서 실행.
// 환경 변수: URL(기본 http://localhost:18090/), BUCKET(필수), KEY(미리보기할 객체 키, 선택), AUTH(user:pass, 선택)
const puppeteer = require('puppeteer');
(async () => {
  const url = process.env.URL || 'http://localhost:18090/';
  const bucket = process.env.BUCKET;
  const key = process.env.KEY;
  const b = await puppeteer.launch({ executablePath: '/usr/bin/chromium-browser', args: ['--no-sandbox'] });
  const p = await b.newPage();
  await p.setViewport({ width: 1300, height: 700 });
  const errs = [];
  p.on('console', m => { if (m.type() === 'error' || m.type() === 'warning') errs.push(m.text()); });
  p.on('pageerror', e => errs.push('pageerror: ' + e.message));
  if (process.env.AUTH) {
    const [username, password] = process.env.AUTH.split(':');
    await p.authenticate({ username, password });
  }
  const prefix = key && key.includes('/') ? key.slice(0, key.lastIndexOf('/') + 1) : '';
  await p.goto(url + '#b=' + encodeURIComponent(bucket) + (prefix ? '&p=' + encodeURIComponent(prefix) : ''),
    { waitUntil: 'networkidle0' });
  await p.waitForFunction(() => document.getElementById('summary').textContent &&
    !document.getElementById('summary').textContent.includes('불러오는'), { timeout: 10000 });
  console.log('breadcrumb:', await p.evaluate(() => document.getElementById('breadcrumb').textContent));
  console.log('summary   :', await p.evaluate(() => document.getElementById('summary').textContent));
  console.log('message   :', await p.evaluate(() => document.getElementById('message').textContent || '(없음)'));
  await p.screenshot({ path: '/out/list.png' });
  if (key) {
    const name = key.slice(prefix.length);
    const found = await p.evaluate(n => {
      const tr = [...document.querySelectorAll('tbody tr')].find(t => t.textContent.includes(n));
      if (tr) tr.click();
      return !!tr;
    }, name);
    if (!found) {
      console.log('FAIL: 목록에서 "' + name + '" 을(를) 찾지 못함 (위 message 확인)');
      await b.close();
      process.exit(1);
    }
    await new Promise(r => setTimeout(r, 1500));
    console.log('detail    :', await p.evaluate(() => document.getElementById('detailMeta').innerText.replace(/\n/g, ' | ')));
    console.log('preview   :', await p.evaluate(() => {
      const pv = document.getElementById('preview');
      const img = pv.querySelector('img');
      if (img) return 'img naturalWidth=' + img.naturalWidth;
      const pre = pv.querySelector('pre');
      return pre ? pre.textContent.slice(0, 200) : pv.innerText || '(없음)';
    }));
    await p.screenshot({ path: '/out/detail.png' });
  }
  console.log('console errors:', JSON.stringify(errs));
  await b.close();
})();
