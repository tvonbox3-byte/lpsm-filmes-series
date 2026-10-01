import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('../public/app.js', import.meta.url), 'utf8');
const functions = source.slice(0, source.indexOf('async function api('));

function panel(backup, serverState) {
  let saved = JSON.stringify(backup);
  let restores = 0;
  const context = {
    document: { getElementById: () => null },
    sessionStorage: { getItem: () => null },
    localStorage: { getItem: () => saved, setItem: (_, value) => { saved = value; } },
    api: async path => {
      if (path === '/api/admin/restore') { restores++; return { ok: true }; }
      return serverState;
    }
  };
  vm.createContext(context);
  vm.runInContext(functions + '\nglobalThis.check = maybeAutoRestore; globalThis.configured = hasConfiguredService;', context);
  return { check: context.check, configured: context.configured, count: () => restores };
}

test('aparelho pendente após reinício não bloqueia restauração', async () => {
  const backup = { data: { settings: { defaultSourceUrl: 'https://example.test/get.php' }, clients: [{ mac: 'AA:BB:CC:DD:EE:FF' }] } };
  const empty = { settings: { defaultSourceUrl: '' }, clients: [], pendingDevices: [{ mac: 'AA:BB:CC:DD:EE:FF' }], storage: { durable: false } };
  const p = panel(backup, empty);
  assert.equal(p.configured(empty), false);
  await p.check(empty);
  await p.check(empty);
  assert.equal(p.count(), 2);
});

test('dados já restaurados não são sobrescritos pelo backup', async () => {
  const backup = { data: { settings: { defaultSourceUrl: 'https://example.test/get.php' }, clients: [{ mac: 'AA:BB:CC:DD:EE:FF' }] } };
  const state = { settings: { defaultSourceUrl: 'https://example.test/get.php' }, clients: [{ mac: 'AA:BB:CC:DD:EE:FF' }], storage: { durable: false } };
  const p = panel(backup, state);
  await p.check(state);
  assert.equal(p.count(), 0);
});

 test('backup anterior recupera banco persistente recém-configurado vazio', async () => {
  const backup = { data: { settings: { defaultSourceUrl: 'https://example.test/get.php' }, clients: [{ mac: 'AA:BB:CC:DD:EE:FF' }] } };
  const p = panel(backup, { settings: {}, clients: [], storage: { durable: true } });
  await p.check({ settings: {}, clients: [], storage: { durable: true } });
  assert.equal(p.count(), 1);
});
