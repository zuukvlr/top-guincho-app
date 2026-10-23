// Teste automático da camada web do app (roda no CI antes de gerar o APK).
// Falha se houver erro de JavaScript ao abrir, se o login não aparecer ou se os fluxos principais quebrarem.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../app/src/main/assets/www');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.jpg': 'image/jpeg', '.png': 'image/png' };
const server = http.createServer((req, res) => {
  const f = path.join(root, decodeURIComponent(req.url.split('?')[0]).replace(/^\/$/, '/index.html'));
  if (!f.startsWith(root) || !fs.existsSync(f)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': types[path.extname(f)] || 'application/octet-stream' });
  fs.createReadStream(f).pipe(res);
}).listen(0);
const url = `http://localhost:${server.address().port}/index.html`;

const fails = [];
const check = (ok, msg) => { console.log((ok ? 'OK   ' : 'FALHA') + ' ' + msg); if (!ok) fails.push(msg); };

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 375, height: 812 } });
const errors = [];
page.on('pageerror', e => errors.push('pageerror: ' + e.message));
page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push('console: ' + m.text()); });
await page.goto(url);
await page.waitForTimeout(3000);

check(errors.length === 0, 'abre sem erros de JavaScript ' + JSON.stringify(errors));
check(await page.evaluate(() => typeof auth !== 'undefined' && !!auth && !!fs), 'Firebase (auth e firestore) inicializou');
check(await page.evaluate(() => getComputedStyle(document.querySelector('#login')).display === 'flex'), 'tela de login aparece para quem não está logado');
check(await page.evaluate(() => document.querySelector('#lmail').value === ''), 'campo de e-mail do login vem vazio');

// Firestore simulado em memória para exercitar os fluxos sem rede
const flow = await page.evaluate(async () => {
  const store = { recibos: {}, checklists: {}, clientes: {}, meta: {} }, subs = [];
  const col = c => ({
    doc: id => {
      id = id || 'a' + Math.random().toString(36).slice(2, 10);
      if (c === 'meta') return { set: async () => {} };
      return { id, set: async o => { store[c][id] = o; subs.forEach(f => f()); }, delete: async () => { delete store[c][id]; subs.forEach(f => f()); } };
    },
    onSnapshot: (_o, ok) => { const f = () => ok({ docs: Object.values(store[c]).map(d => ({ data: () => d })), metadata: { fromCache: false } }); subs.push(f); f(); return () => {}; },
  });
  fs = { collection: col, runTransaction: async fn => fn({ get: async () => ({ exists: false, data: () => ({}) }), set: () => {} }) };
  startData({ email: 'teste@teste.com' });
  const r = {};
  r.conta = document.querySelector('#acct').textContent;
  r.sync = document.querySelector('#syncTxt').textContent;
  // cliente
  go('clientes'); editCli();
  const fc = document.querySelector('#fCli'); fc.nome.value = 'Maria Silva';
  fc.phone.value = '92991744383'; fc.phone.dispatchEvent(new Event('input', { bubbles: true }));
  fc.doc.value = '12345678901'; fc.doc.dispatchEvent(new Event('input', { bubbles: true }));
  fc.requestSubmit();
  r.cliLista = document.querySelector('#cliList').innerText;
  // recibo com sugestão
  go('recibo'); const f = document.querySelector('#fR');
  f.client.value = 'mar'; f.client.dispatchEvent(new Event('input', { bubbles: true }));
  document.querySelector('.sugg div').dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
  r.sug = [f.client.value, f.doc.value, f.phone.value];
  Object.entries({ stype: 'Reboque', desc: 'x', vtype: 'Carro', vmodel: 'Gol', orig: 'A', dest: 'B', value: '280', disc: '80', sdate: '2026-10-23', notes: 'Teste' }).forEach(([k, v]) => f[k].value = v);
  await saveReceipt({ preventDefault() {}, target: f });
  r.recibo = document.querySelector('#doc').innerText.includes('RECIBO Nº 000001') && document.querySelector('#doc').innerText.includes('R$ 200,00');
  // PDF de uma página
  const b64 = await docToPdf();
  r.paginas = (atob(b64).match(/\/Type\s*\/Page\b/g) || []).length;
  // checklist
  go('check'); const c = document.querySelector('#fC'); c.modelo.value = 'Palio';
  await saveChecklist({ preventDefault() {}, target: c });
  r.check = document.querySelector('#doc').innerText.includes('CHECKLIST Nº 000001');
  r.clientes = clientes.length;
  // sair da conta
  r.logoutBtn = !!document.querySelector('[onclick="logout()"]');
  return r;
});
check(flow.conta === 'teste@teste.com', 'a conta logada aparece na tela inicial');
check(/Sincronizado/.test(flow.sync), 'status de sincronização atualiza');
check(/Maria Silva/.test(flow.cliLista) && /\(92\) 99174-4383/.test(flow.cliLista) && /123\.456\.789-01/.test(flow.cliLista), 'cadastro de cliente com máscaras');
check(flow.sug.join('|') === 'Maria Silva|123.456.789-01|(92) 99174-4383', 'sugestão de cliente preenche o recibo');
check(flow.recibo, 'recibo emitido com numeração e valor final');
check(flow.paginas === 1, 'PDF do recibo tem 1 página (tem ' + flow.paginas + ')');
check(flow.check, 'checklist gerado');
check(flow.clientes === 1, 'cliente não duplica ao emitir recibo');
check(flow.logoutBtn, 'botão de sair da conta existe');

await browser.close();
server.close();
if (fails.length) { console.error('\n' + fails.length + ' verificação(ões) falharam'); process.exit(1); }
console.log('\nTudo certo.');
