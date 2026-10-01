import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';

const seed = { settings: { defaultSourceUrl: '' }, clients: [], pendingDevices: [], audit: [] };

export class Store {
  constructor(file) {
    this.file = file;
    this.data = structuredClone(seed);
    this.queue = Promise.resolve();
    this.supabaseUrl = String(process.env.SUPABASE_URL || '').replace(/\/+$/, '');
    this.supabaseKey = String(process.env.SUPABASE_SECRET_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY || '');
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
      this.data = { ...structuredClone(seed), ...rows[0].data, settings: { ...structuredClone(seed.settings), ...(rows[0].data?.settings || {}) }, pendingDevices: Array.isArray(rows[0].data?.pendingDevices) ? rows[0].data.pendingDevices : [] };
      return;
    }
    // Migra os cadastros existentes antes de inicializar o banco persistente.
    await this.loadLocal();
    await this.supabase('/rest/v1/lpsm_state', {
      method: 'POST',
      headers: { Prefer: 'resolution=merge-duplicates,return=minimal' },
      body: JSON.stringify({ id: 'vod', data: this.data, updated_at: new Date().toISOString() })
    });
  }

  async save() {
    if (!this.useSupabase) return this.saveLocal();
    await this.supabase('/rest/v1/lpsm_state', {
      method: 'POST',
      headers: { Prefer: 'resolution=merge-duplicates,return=minimal' },
      body: JSON.stringify({ id: 'vod', data: this.data, updated_at: new Date().toISOString() })
    });
  }

  async loadLocal() {
    try { const saved = JSON.parse(await readFile(this.file, 'utf8')); this.data = { ...structuredClone(seed), ...saved, settings: { ...structuredClone(seed.settings), ...(saved?.settings || {}) }, pendingDevices: Array.isArray(saved?.pendingDevices) ? saved.pendingDevices : [] }; }
    catch (e) { if (e.code !== 'ENOENT') throw e; await this.saveLocal(); }
  }

  async saveLocal() {
    await mkdir(dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    await writeFile(tmp, JSON.stringify(this.data, null, 2));
    await rename(tmp, this.file);
  }

  snapshot() {
    return structuredClone({
      settings: this.data.settings || { defaultSourceUrl: '' },
      clients: Array.isArray(this.data.clients) ? this.data.clients : [],
      pendingDevices: Array.isArray(this.data.pendingDevices) ? this.data.pendingDevices : [],
      audit: Array.isArray(this.data.audit) ? this.data.audit : []
    });
  }

  replace(next) {
    const safe = next && typeof next === 'object' ? next : {};
    this.data = {
      ...structuredClone(seed),
      settings: {
        ...structuredClone(seed.settings),
        ...(safe.settings || {})
      },
      clients: Array.isArray(safe.clients) ? safe.clients : [],
      pendingDevices: Array.isArray(safe.pendingDevices) ? safe.pendingDevices : [],
      audit: Array.isArray(safe.audit) ? safe.audit.slice(0, 100) : []
    };
  }

  mutate(fn) {
    const operation = this.queue.then(async () => {
      const previous = structuredClone(this.data);
      try {
        const result = await fn(this.data);
        await this.save();
        return result;
      } catch (error) {
        this.data = previous;
        throw error;
      }
    });
    // Uma falha temporária não pode bloquear todas as gravações seguintes.
    this.queue = operation.catch(() => {});
    return operation;
  }

  audit(action, detail = '') {
    this.data.audit.unshift({ id: randomUUID(), at: new Date().toISOString(), action, detail });
    this.data.audit = this.data.audit.slice(0, 100);
  }
}

export const id = () => randomUUID();
