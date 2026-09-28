import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';

const seed = { clients: [], audit: [] };

export class Store {
  constructor(file) {
    this.file = file;
    this.data = structuredClone(seed);
    this.queue = Promise.resolve();
    this.supabaseUrl = String(process.env.SUPABASE_URL || '').replace(/\/+$/, '');
    this.supabaseKey = String(process.env.SUPABASE_SECRET_KEY || '');
  }

  get useSupabase() { return Boolean(this.supabaseUrl && this.supabaseKey); }

  async supabase(path, options = {}) {
    const r = await fetch(`${this.supabaseUrl}${path}`, {
      ...options,
      headers: { apikey: this.supabaseKey, 'content-type': 'application/json', ...(options.headers || {}) }
    });
    if (!r.ok) throw new Error(`Supabase ${r.status}: ${await r.text()}`);
    if (r.status === 204) return null;
    const text = await r.text();
    return text ? JSON.parse(text) : null;
  }

  async load() {
    if (!this.useSupabase) return this.loadLocal();
    const rows = await this.supabase('/rest/v1/lpsm_state?id=eq.vod&select=data', { method: 'GET' });
    if (Array.isArray(rows) && rows[0]?.data) {
      this.data = { ...structuredClone(seed), ...rows[0].data };
      return;
    }
    this.data = structuredClone(seed);
    await this.supabase('/rest/v1/lpsm_state', {
      method: 'POST',
      headers: { Prefer: 'resolution=merge-duplicates,return=minimal' },
      body: JSON.stringify({ id: 'vod', data: this.data, updated_at: new Date().toISOString() })
    });
  }

  async save() {
    if (!this.useSupabase) return this.saveLocal();
    await this.supabase('/rest/v1/lpsm_state?id=eq.vod', {
      method: 'PATCH',
      headers: { Prefer: 'return=minimal' },
      body: JSON.stringify({ data: this.data, updated_at: new Date().toISOString() })
    });
  }

  async loadLocal() {
    try { this.data = { ...structuredClone(seed), ...JSON.parse(await readFile(this.file, 'utf8')) }; }
    catch (e) { if (e.code !== 'ENOENT') throw e; await this.saveLocal(); }
  }

  async saveLocal() {
    await mkdir(dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    await writeFile(tmp, JSON.stringify(this.data, null, 2));
    await rename(tmp, this.file);
  }

  mutate(fn) {
    this.queue = this.queue.then(async () => { const result = fn(this.data); await this.save(); return result; });
    return this.queue;
  }

  audit(action, detail = '') {
    this.data.audit.unshift({ id: randomUUID(), at: new Date().toISOString(), action, detail });
    this.data.audit = this.data.audit.slice(0, 100);
  }
}

export const id = () => randomUUID();
