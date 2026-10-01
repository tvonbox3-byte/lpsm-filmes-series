import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.js';

test('migra ativações locais e conserva banco existente nos próximos inícios', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'lpsm-store-'));
  try {
    const file = join(dir, 'vod.json');
    const original = { settings: { defaultSourceUrl: 'https://example.test/list' }, clients: [{ mac: 'BF:D0:2E:97:EB:C0', enabled: true, expiresAt: '2030-01-01' }] };
    await writeFile(file, JSON.stringify(original));
    let row;
    const connect = store => {
      store.supabaseUrl = 'https://example.test'; store.supabaseKey = 'test';
      store.supabase = async (_, options) => {
        if (options.method === 'GET') return row ? [{ data: structuredClone(row) }] : [];
        assert.equal(options.method, 'POST');
        row = JSON.parse(options.body).data;
      };
    };
    const first = new Store(file); connect(first); await first.load();
    assert.deepEqual(row.clients, original.clients);
    await first.mutate(data => { data.clients[0].name = 'Cliente'; });
    await writeFile(file, JSON.stringify({ clients: [] }));
    const next = new Store(file); connect(next); await next.load();
    assert.equal(next.data.clients[0].name, 'Cliente');
    assert.equal(next.data.clients[0].enabled, true);
    assert.equal(next.data.clients[0].expiresAt, '2030-01-01');
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('falha na gravação reverte alteração e permite tentar novamente', async () => {
  const store = new Store('unused');
  let fail = true;
  store.save = async () => { if (fail) throw new Error('offline'); };
  await assert.rejects(store.mutate(data => { data.clients.push({ mac: 'test' }); }), /offline/);
  assert.equal(store.data.clients.length, 0);
  fail = false;
  await store.mutate(data => { data.clients.push({ mac: 'test', enabled: true }); });
  assert.equal(store.data.clients.length, 1);
});

 test('não aceita configuração parcial nem esconde erro do banco', async () => {
  const names = ['SUPABASE_URL', 'SUPABASE_SECRET_KEY', 'SUPABASE_SERVICE_ROLE_KEY'];
  const previous = Object.fromEntries(names.map(name => [name, process.env[name]]));
  try {
    for (const name of names) delete process.env[name];
    process.env.SUPABASE_URL = 'https://example.test';
    assert.throws(() => new Store('unused'), /Persistência incompleta/);
    process.env.SUPABASE_SECRET_KEY = 'test';
    const store = new Store('unused');
    store.supabase = async () => { throw new Error('banco indisponível'); };
    await assert.rejects(store.load(), /banco indisponível/);
    assert.equal(store.useSupabase, true);
  } finally {
    for (const name of names) {
      if (previous[name] === undefined) delete process.env[name];
      else process.env[name] = previous[name];
    }
  }
});
