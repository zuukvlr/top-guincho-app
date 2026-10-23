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
  const store = { recibos: {}, checklists: {}, clientes: {}, meta: {}, config: {} }, subs = [];
  const col = c => ({
    doc: id => {
      id = id || 'a' + Math.random().toString(36).slice(2, 10);
      if (c === 'meta') return { set: async () => {} };
      if (c === 'config') return {
        set: async (o, opt) => { store.config.empresa = { ...(store.config.empresa || {}), ...o }; subs.forEach(f => f()); },
        onSnapshot: (ok) => { const f = () => ok({ exists: !!store.config.empresa, data: () => store.config.empresa }); subs.push(f); f(); return () => {}; },
      };
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
  Object.entries({ stype: 'Reboque', desc: 'x', vtype: 'Carro', vmodel: 'Gol', orig: 'A', dest: 'B', sdate: '2026-10-23', notes: 'Teste' }).forEach(([k, v]) => f[k].value = v);
  const typeIn = (el, v) => { el.value = v; el.dispatchEvent(new Event('input', { bubbles: true })); };
  typeIn(f.value, '28000'); typeIn(f.disc, '8000');
  r.mask = [f.value.value, f.disc.value, document.querySelector('#fin').textContent];
  typeIn(f.recv, '1234567'); r.mask2 = f.recv.value; typeIn(f.recv, '');
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
  const cb = document.querySelector('#fR [name=saveCli]');
  r.checkbox = cb.type === 'checkbox' && !cb.closest('label').classList.contains('chip') && cb.checked;
  r.chips = document.querySelectorAll('#svcChips button').length;
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

check(flow.mask.join('|') === '280,00|80,00|R$ 200,00', 'máscara 0,00 no valor e desconto, valor final correto ' + JSON.stringify(flow.mask));
check(flow.mask2 === '12.345,67', 'máscara com milhar (12.345,67)');
check(flow.checkbox, 'salvar cliente é caixa de seleção (não botão vermelho)');
check(flow.chips >= 6, 'tipos de serviço aparecem no recibo');

const cfg = await page.evaluate(async () => {
  window.confirm = () => true;
  const r = {};
  go('config');
  const f = document.querySelector('#fCfg'), typeIn = (el, v) => { el.value = v; el.dispatchEvent(new Event('input', { bubbles: true })); };
  typeIn(f.cnpj, '11222333000181'); typeIn(f.tel, '92988887777'); f.addr.value = 'Rua A, 10, Manaus';
  r.mask = [f.cnpj.value, f.tel.value];
  f.requestSubmit();
  r.msg = document.querySelector('#cfgMsg').textContent;
  r.co = [CO.cnpj, CO.tel, CO.addr];
  // tipos de serviço: criar, editar, excluir
  const n0 = services.length;
  document.querySelector('#svcNew').value = 'Guincho de moto'; document.querySelector('#svcList').nextElementSibling.requestSubmit();
  r.criou = services.includes('Guincho de moto') && services.length === n0 + 1;
  const i = services.indexOf('Guincho de moto'); document.querySelector('#svc' + i).value = 'Guincho de motocicleta'; svcSave(i);
  r.editou = services.includes('Guincho de motocicleta') && !services.includes('Guincho de moto');
  svcDel(services.indexOf('Troca de pneu'));
  r.excluiu = !services.includes('Troca de pneu');
  go('recibo'); r.chips = [...document.querySelectorAll('#svcChips button')].map(b => b.textContent);
  // documentos: o antigo mantém a empresa da época; o novo usa a configurada
  openDoc('r', 1); r.antigo = document.querySelector('#doc').innerText.includes('22.678.354/0001-22');
  go('recibo'); const g = document.querySelector('#fR');
  Object.entries({ client: 'Joao', stype: 'x', desc: 'x', vtype: 'Carro', vmodel: 'Gol', orig: 'A', dest: 'B', sdate: '2026-10-23' }).forEach(([k, v]) => g[k].value = v);
  typeIn(g.value, '10000'); await saveReceipt({ preventDefault() {}, target: g });
  const t = document.querySelector('#doc').innerText;
  r.novo = t.includes('11.222.333/0001-81') && t.includes('(92) 98888-7777') && t.includes('Rua A, 10, Manaus') && !t.includes('22.678.354/0001-22');
  go('home'); r.home = document.querySelector('#coInfo').innerText;
  return r;
});
check(cfg.mask.join('|') === '11.222.333/0001-81|(92) 98888-7777', 'máscaras de CNPJ e telefone nas configurações');
check(cfg.co.join('|') === '11.222.333/0001-81|(92) 98888-7777|Rua A, 10, Manaus', 'configurações salvas (CNPJ, telefone, endereço)');
check(cfg.criou && cfg.editou && cfg.excluiu, 'tipos de serviço: criar, editar e excluir');
check(cfg.chips.includes('Guincho de motocicleta') && !cfg.chips.includes('Troca de pneu'), 'lista de serviços do recibo reflete as alterações');
check(cfg.antigo, 'recibo antigo mantém o CNPJ da época da emissão');
check(cfg.novo, 'recibo novo usa CNPJ, telefone e endereço atualizados');
check(/11\.222\.333\/0001-81/.test(cfg.home), 'tela inicial mostra os dados atualizados');

await browser.close();
server.close();
if (fails.length) { console.error('\n' + fails.length + ' verificação(ões) falharam'); process.exit(1); }
console.log('\nTudo certo.');
