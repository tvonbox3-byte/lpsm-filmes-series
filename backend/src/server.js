import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Store, id } from './store.js';
import { signToken, verifyToken } from './auth.js';
import { catalogFor, clearCatalogCache, categoryItems, seriesSeasons } from './m3u.js';

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
const cleanUrl = value => String(value || '').trim();
const isM3uUrl = value => /^https?:\/\//i.test(cleanUrl(value));

function json(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'access-control-allow-origin': '*'
  });
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

function sourceForClient(client) {
  return cleanUrl(client?.sourceUrl || store.data.settings?.defaultSourceUrl || '');
}

function clientByMac(mac) {
  const target = normMac(mac);
  return store.data.clients.find(c => normMac(c.mac) === target) || null;
}

function publicClient(c) {
  const seen = presence.get(c.id) || null;
  return {
    ...c,
    sourceMode: c.sourceUrl ? 'specific' : 'default',
    online: Boolean(seen && Date.now() - Date.parse(seen) <= ONLINE_MS),
    lastSeenAt: seen
  };
}

async function rememberPending(req, macValue) {
  const target = normMac(macValue);
  if (target.length !== 12 || clientByMac(target)) return;
  const now = new Date().toISOString();
  const existing = (store.data.pendingDevices || []).find(x => normMac(x.mac) === target);
  if (existing && Date.now() - Date.parse(existing.lastSeenAt || 0) < 30_000) return;

  await store.mutate(data => {
    data.pendingDevices = Array.isArray(data.pendingDevices) ? data.pendingDevices : [];
    const p = data.pendingDevices.find(x => normMac(x.mac) === target);
    if (p) {
      p.lastSeenAt = now;
      p.userAgent = String(req.headers['user-agent'] || '');
    } else {
      data.pendingDevices.unshift({
        id: id(),
        mac: formatMac(target),
        firstSeenAt: now,
        lastSeenAt: now,
        userAgent: String(req.headers['user-agent'] || '')
      });
      data.pendingDevices = data.pendingDevices.slice(0, 500);
      store.audit('device.pending', formatMac(target));
    }
  });
}

async function removePending(macValue) {
  const target = normMac(macValue);
  await store.mutate(data => {
    data.pendingDevices = (data.pendingDevices || []).filter(x => normMac(x.mac) !== target);
  });
}

function deviceAccess(macValue) {
  const client = clientByMac(macValue);
  if (!client) return { ok: false, status: 404, message: 'Aguardando ativação no painel.' };
  if (!isActive(client)) {
    return {
      ok: false,
      status: 403,
      message: client.enabled === false ? 'Aparelho pausado no painel.' : 'Ativação expirada.'
    };
  }
  const sourceUrl = sourceForClient(client);
  if (!isM3uUrl(sourceUrl)) return { ok: false, status: 409, message: 'Lista M3U ainda não configurada no painel.' };
  return { ok: true, client, sourceUrl };
}

const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml'
};

async function staticFile(pathname, res) {
  const target = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  const safe = normalize(target).replace(/^(\.\.[/\\])+/, '');
  try {
    const data = await readFile(join(root, 'public', safe));
    res.writeHead(200, {
      'content-type': mime[extname(safe)] || 'application/octet-stream',
      'cache-control': safe.endsWith('.html') ? 'no-store' : 'public, max-age=300'
    });
    res.end(data);
    return true;
  } catch {
    return false;
  }
}

const server = http.createServer(async (req, res) => {
  try {
    const u = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'access-control-allow-origin': '*',
        'access-control-allow-headers': 'content-type,authorization',
        'access-control-allow-methods': 'GET,POST,PUT,DELETE,OPTIONS'
      });
      return res.end();
    }

    if (u.pathname === '/api/health') {
      return json(res, 200, { ok: true, service: 'lpsm-filmes-series', source: 'm3u' });
    }

    if (u.pathname === '/api/admin/login' && req.method === 'POST') {
      const b = await body(req);
      if (b.username !== config.adminUser || b.password !== config.adminPassword) {
        return json(res, 401, { error: 'Login inválido' });
      }
      return json(res, 200, { token: signToken({ role: 'admin' }, config.secret) });
    }

    // O simples contato do APK já faz o MAC aparecer como pendente no painel.
    if (u.pathname === '/api/device/config' && req.method === 'GET') {
      const mac = normMac(u.searchParams.get('mac'));
      if (mac.length !== 12) return json(res, 400, { active: false, message: 'Código do aparelho inválido.' });
      const client = clientByMac(mac);
      if (!client) {
        await rememberPending(req, mac);
        return json(res, 200, { active: false, pending: true, mac: formatMac(mac), message: 'Aguardando ativação no painel.' });
      }
      if (!isActive(client)) {
        return json(res, 200, {
          active: false,
          pending: false,
          message: client.enabled === false ? 'Aparelho pausado no painel.' : 'Ativação expirada.'
        });
      }
      const sourceUrl = sourceForClient(client);
      if (!isM3uUrl(sourceUrl)) {
        return json(res, 200, { active: false, pending: false, message: 'Lista M3U ainda não configurada no painel.' });
      }
      return json(res, 200, {
        active: true,
        name: client.name || '',
        expiresAt: client.expiresAt || '',
        sourceType: 'm3u'
      });
    }

    if (u.pathname === '/api/device/presence' && req.method === 'POST') {
      const b = await body(req);
      const mac = normMac(b.mac);
      if (mac.length !== 12) return json(res, 400, { ok: false });
      const client = clientByMac(mac);
      if (client) presence.set(client.id, new Date().toISOString());
      else await rememberPending(req, mac);
      return json(res, 200, { ok: true, pending: !client });
    }

    if (u.pathname === '/api/device/catalog/categories' && req.method === 'GET') {
      const access = deviceAccess(u.searchParams.get('mac'));
      if (!access.ok) return json(res, 200, { active: false, message: access.message, categories: [] });
      const kind = u.searchParams.get('kind') === 'series' ? 'series' : 'movie';
      const catalog = await catalogFor(access.sourceUrl);
      const categories = kind === 'series' ? catalog.seriesCategories : catalog.movieCategories;
      return json(res, 200, { active: true, kind, categories, stats: catalog.stats });
    }

    if (u.pathname === '/api/device/catalog/items' && req.method === 'GET') {
      const access = deviceAccess(u.searchParams.get('mac'));
      if (!access.ok) return json(res, 200, { active: false, message: access.message, items: [] });
      const kind = u.searchParams.get('kind') === 'series' ? 'series' : 'movie';
      const categoryId = String(u.searchParams.get('categoryId') || '');
      const catalog = await catalogFor(access.sourceUrl);
      return json(res, 200, { active: true, items: categoryItems(catalog, kind, categoryId) });
    }

    if (u.pathname === '/api/device/catalog/series' && req.method === 'GET') {
      const access = deviceAccess(u.searchParams.get('mac'));
      if (!access.ok) return json(res, 200, { active: false, message: access.message, seasons: [] });
      const seriesId = String(u.searchParams.get('seriesId') || '');
      const catalog = await catalogFor(access.sourceUrl);
      const seasons = seriesSeasons(catalog, seriesId);
      if (!seasons) return json(res, 404, { error: 'Série não encontrada' });
      return json(res, 200, { active: true, seasons });
    }

    if (u.pathname.startsWith('/api/admin/')) {
      if (!admin(req)) return json(res, 401, { error: 'Não autorizado' });

      if (u.pathname === '/api/admin/state' && req.method === 'GET') {
        return json(res, 200, {
          settings: store.data.settings || { defaultSourceUrl: '' },
          clients: store.data.clients.map(publicClient),
          pendingDevices: store.data.pendingDevices || [],
          audit: store.data.audit.slice(0, 30)
        });
      }

      if (u.pathname === '/api/admin/settings' && req.method === 'PUT') {
        const b = await body(req);
        const defaultSourceUrl = cleanUrl(b.defaultSourceUrl);
        if (defaultSourceUrl && !isM3uUrl(defaultSourceUrl)) {
          return json(res, 400, { error: 'Informe uma URL M3U http:// ou https:// válida.' });
        }
        const previous = store.data.settings?.defaultSourceUrl || '';
        await store.mutate(data => {
          data.settings = { ...(data.settings || {}), defaultSourceUrl };
          store.audit('settings.m3u', defaultSourceUrl ? 'Lista M3U principal atualizada' : 'Lista M3U principal removida');
        });
        if (previous) clearCatalogCache(previous);
        if (defaultSourceUrl) {
          clearCatalogCache(defaultSourceUrl);
          // Pré-carrega o catálogo em segundo plano para o app abrir as categorias mais rápido.
          catalogFor(defaultSourceUrl).catch(error => console.error('Pré-carga M3U:', error.message));
        }
        return json(res, 200, { ok: true, settings: store.data.settings });
      }

      if (u.pathname === '/api/admin/catalog/test' && req.method === 'POST') {
        const b = await body(req);
        const sourceUrl = cleanUrl(b.sourceUrl || store.data.settings?.defaultSourceUrl || '');
        if (!isM3uUrl(sourceUrl)) return json(res, 400, { error: 'Informe primeiro uma URL M3U válida.' });
        const catalog = await catalogFor(sourceUrl, true);
        return json(res, 200, { ok: true, stats: catalog.stats });
      }

      if (u.pathname === '/api/admin/clients' && req.method === 'POST') {
        const b = await body(req);
        const mac = formatMac(b.mac);
        if (normMac(mac).length !== 12) return json(res, 400, { error: 'MAC/código inválido' });
        if (store.data.clients.some(c => normMac(c.mac) === normMac(mac))) {
          return json(res, 409, { error: 'Este aparelho já está cadastrado' });
        }
        const sourceUrl = cleanUrl(b.sourceUrl);
        if (sourceUrl && !isM3uUrl(sourceUrl)) return json(res, 400, { error: 'URL M3U inválida' });
        const item = {
          id: id(),
          name: String(b.name || '').trim(),
          mac,
          sourceUrl,
          enabled: b.enabled !== false,
          expiresAt: String(b.expiresAt || '')
        };
        await store.mutate(data => {
          data.clients.unshift(item);
          data.pendingDevices = (data.pendingDevices || []).filter(x => normMac(x.mac) !== normMac(mac));
          store.audit('client.create', `${item.name || 'Cliente'} ${item.mac}`);
        });
        return json(res, 201, publicClient(item));
      }

      const match = u.pathname.match(/^\/api\/admin\/clients\/([^/]+)$/);
      if (match && req.method === 'PUT') {
        const b = await body(req);
        const current = store.data.clients.find(c => c.id === match[1]);
        if (!current) return json(res, 404, { error: 'Cliente não encontrado' });
        const mac = formatMac(b.mac ?? current.mac);
        const sourceUrl = cleanUrl(b.sourceUrl ?? current.sourceUrl ?? '');
        if (normMac(mac).length !== 12) return json(res, 400, { error: 'MAC/código inválido' });
        if (sourceUrl && !isM3uUrl(sourceUrl)) return json(res, 400, { error: 'URL M3U inválida' });
        const oldSource = current.sourceUrl || '';
        await store.mutate(data => {
          const c = data.clients.find(x => x.id === match[1]);
          Object.assign(c, {
            name: String(b.name ?? c.name).trim(),
            mac,
            sourceUrl,
            enabled: b.enabled ?? c.enabled,
            expiresAt: String(b.expiresAt ?? c.expiresAt ?? '')
          });
          store.audit('client.update', `${c.name || 'Cliente'} ${c.mac}`);
        });
        if (oldSource && oldSource !== sourceUrl) clearCatalogCache(oldSource);
        if (sourceUrl) clearCatalogCache(sourceUrl);
        return json(res, 200, publicClient(store.data.clients.find(c => c.id === match[1])));
      }

      if (match && req.method === 'DELETE') {
        await store.mutate(data => {
          const before = data.clients.find(x => x.id === match[1]);
          data.clients = data.clients.filter(x => x.id !== match[1]);
          store.audit('client.delete', before ? `${before.name || 'Cliente'} ${before.mac}` : match[1]);
        });
        return json(res, 200, { ok: true });
      }

      const pendingMatch = u.pathname.match(/^\/api\/admin\/pending\/([^/]+)$/);
      if (pendingMatch && req.method === 'DELETE') {
        await removePending(decodeURIComponent(pendingMatch[1]));
        return json(res, 200, { ok: true });
      }
    }

    if (req.method === 'GET' && await staticFile(u.pathname, res)) return;
    return json(res, 404, { error: 'Não encontrado' });
  } catch (e) {
    console.error(e);
    return json(res, 500, { error: e?.message || 'Erro interno' });
  }
});

server.listen(config.port, () => console.log(`LPSM Filmes & Séries painel M3U em :${config.port}`));
