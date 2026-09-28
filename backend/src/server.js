import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Store, id } from './store.js';
import { signToken, verifyToken } from './auth.js';

const root = fileURLToPath(new URL('../', import.meta.url));
const config = {
  port: Number(process.env.PORT || 8080),
  dataFile: process.env.DATA_FILE || join(root, 'data/vod.json'),
  adminUser: process.env.ADMIN_USER || 'admin',
  adminPassword: process.env.ADMIN_PASSWORD || 'troque-esta-senha',
  secret: process.env.TOKEN_SECRET || 'troque-este-segredo'
};
const store = new Store(config.dataFile);
await store.load();
const presence = new Map();
const ONLINE_MS = 45_000;

const normMac = value => String(value || '').replace(/[^0-9a-f]/gi, '').toUpperCase().slice(0, 12);
const formatMac = value => (normMac(value).match(/.{1,2}/g) || []).join(':');
const isActive = c => c && c.enabled !== false && (!c.expiresAt || Date.parse(c.expiresAt) > Date.now());

function parseSource(raw) {
  try {
    const u = new URL(String(raw || '').trim());
    const username = u.searchParams.get('username');
    const password = u.searchParams.get('password');
    if (!username || !password) return null;
    return { base: `${u.protocol}//${u.host}`, username, password };
  } catch { return null; }
}

function json(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'access-control-allow-origin': '*' });
  res.end(body);
}

async function body(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const text = Buffer.concat(chunks).toString('utf8');
  return text ? JSON.parse(text) : {};
}

function admin(req) {
  const token = String(req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  return verifyToken(token, config.secret);
}

function publicClient(c) {
  const seen = presence.get(c.id) || null;
  return {
    ...c,
    online: Boolean(seen && Date.now() - Date.parse(seen) <= ONLINE_MS),
    lastSeenAt: seen
  };
}

const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.svg': 'image/svg+xml' };
async function staticFile(pathname, res) {
  const target = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  const safe = normalize(target).replace(/^(\.\.[/\\])+/, '');
  try {
    const data = await readFile(join(root, 'public', safe));
    res.writeHead(200, { 'content-type': mime[extname(safe)] || 'application/octet-stream', 'cache-control': safe.endsWith('.html') ? 'no-store' : 'public, max-age=300' });
    res.end(data);
    return true;
  } catch { return false; }
}

const server = http.createServer(async (req, res) => {
  try {
    const u = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    if (req.method === 'OPTIONS') { res.writeHead(204, { 'access-control-allow-origin': '*', 'access-control-allow-headers': 'content-type,authorization', 'access-control-allow-methods': 'GET,POST,PUT,DELETE,OPTIONS' }); return res.end(); }

    if (u.pathname === '/api/health') return json(res, 200, { ok: true, service: 'lpsm-filmes-series' });

    if (u.pathname === '/api/admin/login' && req.method === 'POST') {
      const b = await body(req);
      if (b.username !== config.adminUser || b.password !== config.adminPassword) return json(res, 401, { error: 'Login inválido' });
      return json(res, 200, { token: signToken({ role: 'admin' }, config.secret) });
    }

    if (u.pathname === '/api/device/config' && req.method === 'GET') {
      const mac = normMac(u.searchParams.get('mac'));
      const client = store.data.clients.find(c => normMac(c.mac) === mac);
      if (!client) return json(res, 200, { active: false, message: 'Aguardando cadastro deste aparelho no painel.' });
      if (!isActive(client)) return json(res, 200, { active: false, message: client.enabled === false ? 'Aparelho pausado no painel.' : 'Ativação expirada.' });
      const source = parseSource(client.sourceUrl);
      if (!source) return json(res, 200, { active: false, message: 'Fonte ainda não configurada no painel.' });
      return json(res, 200, { active: true, name: client.name || '', expiresAt: client.expiresAt || '', source });
    }

    if (u.pathname === '/api/device/presence' && req.method === 'POST') {
      const b = await body(req);
      const mac = normMac(b.mac);
      const client = store.data.clients.find(c => normMac(c.mac) === mac);
      if (client) presence.set(client.id, new Date().toISOString());
      return json(res, 200, { ok: true });
    }

    if (u.pathname.startsWith('/api/admin/')) {
      if (!admin(req)) return json(res, 401, { error: 'Não autorizado' });

      if (u.pathname === '/api/admin/state' && req.method === 'GET') {
        return json(res, 200, { clients: store.data.clients.map(publicClient), audit: store.data.audit.slice(0, 30) });
      }

      if (u.pathname === '/api/admin/clients' && req.method === 'POST') {
        const b = await body(req);
        const mac = formatMac(b.mac);
        if (normMac(mac).length !== 12) return json(res, 400, { error: 'MAC/código inválido' });
        if (store.data.clients.some(c => normMac(c.mac) === normMac(mac))) return json(res, 409, { error: 'Este aparelho já está cadastrado' });
        if (b.sourceUrl && !parseSource(b.sourceUrl)) return json(res, 400, { error: 'URL Xtream inválida' });
        const item = { id: id(), name: String(b.name || '').trim(), mac, sourceUrl: String(b.sourceUrl || '').trim(), enabled: b.enabled !== false, expiresAt: String(b.expiresAt || '') };
        await store.mutate(data => { data.clients.unshift(item); store.audit('client.create', `${item.name} ${item.mac}`); });
        return json(res, 201, publicClient(item));
      }

      const match = u.pathname.match(/^\/api\/admin\/clients\/([^/]+)$/);
      if (match && req.method === 'PUT') {
        const b = await body(req);
        const current = store.data.clients.find(c => c.id === match[1]);
        if (!current) return json(res, 404, { error: 'Cliente não encontrado' });
        const mac = formatMac(b.mac ?? current.mac);
        const sourceUrl = String(b.sourceUrl ?? current.sourceUrl ?? '').trim();
        if (normMac(mac).length !== 12) return json(res, 400, { error: 'MAC/código inválido' });
        if (sourceUrl && !parseSource(sourceUrl)) return json(res, 400, { error: 'URL Xtream inválida' });
        await store.mutate(data => {
          const c = data.clients.find(x => x.id === match[1]);
          Object.assign(c, { name: String(b.name ?? c.name).trim(), mac, sourceUrl, enabled: b.enabled ?? c.enabled, expiresAt: String(b.expiresAt ?? c.expiresAt ?? '') });
          store.audit('client.update', `${c.name} ${c.mac}`);
        });
        return json(res, 200, publicClient(store.data.clients.find(c => c.id === match[1])));
      }

      if (match && req.method === 'DELETE') {
        await store.mutate(data => {
          const before = data.clients.find(x => x.id === match[1]);
          data.clients = data.clients.filter(x => x.id !== match[1]);
          store.audit('client.delete', before ? `${before.name} ${before.mac}` : match[1]);
        });
        return json(res, 200, { ok: true });
      }
    }

    if (req.method === 'GET' && await staticFile(u.pathname, res)) return;
    json(res, 404, { error: 'Não encontrado' });
  } catch (e) {
    console.error(e);
    json(res, 500, { error: 'Erro interno' });
  }
});

server.listen(config.port, () => console.log(`LPSM Filmes & Séries painel em :${config.port}`));
