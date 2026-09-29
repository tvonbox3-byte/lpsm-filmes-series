import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Store, id } from './store.js';
import { signToken, verifyToken } from './auth.js';
import { catalogFor, clearCatalogCache, categoryItems, seriesSeasons, searchCatalog } from './m3u.js';
import {
  xtreamSource,
  xtreamCatalogFor,
  xtreamCategoryItems,
  xtreamSearch,
  xtreamSeriesSeasons,
  clearXtreamCache
} from './xtream.js';

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

function decodeHeaderValue(value) {
  const raw = String(value || '').trim();
  try { return decodeURIComponent(raw.replace(/\+/g, '%20')); }
  catch { return raw; }
}

function splitSourceSpec(rawValue) {
  const raw = cleanUrl(rawValue);
  const pipe = raw.indexOf('|');
  if (pipe < 0) return { url: raw, headers: {} };

  const headers = {};
  const suffix = raw.slice(pipe + 1);
  for (const part of suffix.split('&')) {
    const eq = part.indexOf('=');
    if (eq <= 0) continue;
    let key = part.slice(0, eq).trim();
    if (key.toLowerCase() === 'referrer') key = 'Referer';
    const value = decodeHeaderValue(part.slice(eq + 1));
    if (key && value) headers[key] = value;
  }

  return { url: raw.slice(0, pipe).trim(), headers };
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
  if (!isM3uUrl(sourceUrl)) return { ok: false, status: 409, message: 'Login não está funcionando' };
  return { ok: true, client, sourceUrl };
}


async function sourceCatalog(sourceUrl, kind = 'all', force = false) {
  const xtream = xtreamSource(sourceUrl);

  if (xtream) {
    try {
      return await xtreamCatalogFor(sourceUrl, kind, force);
    } catch (error) {
      console.warn('Xtream indisponível, usando parser M3U:', error?.message || error);
    }
  }

  return catalogFor(sourceUrl, force);
}

function sourceCategoryItems(catalog, kind, categoryId) {
  return catalog?.provider === 'xtream'
    ? xtreamCategoryItems(catalog, kind, categoryId)
    : categoryItems(catalog, kind, categoryId);
}

function sourceSearch(catalog, kind, query) {
  return catalog?.provider === 'xtream'
    ? xtreamSearch(catalog, kind, query)
    : searchCatalog(catalog, kind, query);
}

async function sourceSeriesSeasons(
  sourceUrl,
  catalog,
  seriesId
) {
  if (catalog?.provider !== 'xtream') {
    return seriesSeasons(catalog, seriesId);
  }

  // Não processa a M3U gigante dentro da mesma requisição.
  // Isso evitava que a tela ficasse esperando até o Android desistir.
  return xtreamSeriesSeasons(catalog, seriesId);
}

function clearSourceCache(sourceUrl = '') {
  clearCatalogCache(sourceUrl);
  clearXtreamCache(sourceUrl);
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
        return json(res, 200, {
          active: true,
          sourceReady: false,
          pending: false,
          name: client.name || '',
          expiresAt: client.expiresAt || '',
          message: 'Login não está funcionando'
        });
      }
      return json(res, 200, {
        active: true,
        name: client.name || '',
        expiresAt: client.expiresAt || '',
        sourceType: 'm3u',
        // A box salva esta fonte localmente. Depois da primeira carga o catálogo
        // não depende do painel/Render para abrir.
        sourceUrl
      });
    }

    // Proxy leve da M3U: valida o aparelho e apenas retransmite os bytes.
    // Não processa filmes/séries no Render, evitando os timeouts do catálogo.
    if (u.pathname === '/api/device/source' && req.method === 'GET') {
      const access = deviceAccess(u.searchParams.get('mac'));
      if (!access.ok) {
        return json(res, 403, { active: false, message: access.message });
      }

      const source = splitSourceSpec(access.sourceUrl);
      if (!/^https?:\/\//i.test(source.url)) {
        return json(res, 409, { error: 'Lista M3U inválida.' });
      }

      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 5 * 60 * 1000);

      try {
        const headers = {
          'user-agent': source.headers['User-Agent'] || source.headers['user-agent'] || 'LPSM-VOD-Proxy/1.8.5',
          'accept': 'application/x-mpegURL,text/plain,*/*'
        };

        for (const [key, value] of Object.entries(source.headers)) {
          if (key.toLowerCase() !== 'user-agent') headers[key] = value;
        }

        const upstream = await fetch(source.url, {
          redirect: 'follow',
          signal: controller.signal,
          headers
        });

        if (!upstream.ok) {
          const detail = (await upstream.text().catch(() => '')).slice(0, 300);
          return json(res, 502, {
            error: `A lista M3U respondeu HTTP ${upstream.status}`,
            detail
          });
        }

        if (!upstream.body) {
          return json(res, 502, { error: 'A lista M3U não retornou conteúdo.' });
        }

        res.writeHead(200, {
          'content-type': upstream.headers.get('content-type') || 'application/x-mpegURL; charset=utf-8',
          'cache-control': 'no-store',
          'access-control-allow-origin': '*',
          'x-lpsm-source': 'm3u-proxy'
        });

        for await (const chunk of upstream.body) {
          if (res.destroyed) break;
          res.write(Buffer.from(chunk));
        }

        if (!res.destroyed) res.end();
        return;
      } catch (error) {
        if (!res.headersSent) {
          return json(res, 502, {
            error: error?.name === 'AbortError'
              ? 'Tempo esgotado ao acessar a lista M3U.'
              : `Falha ao acessar a lista M3U: ${error?.message || 'erro desconhecido'}`
          });
        }
        if (!res.destroyed) res.end();
        return;
      } finally {
        clearTimeout(timeout);
      }
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
      if (!access.ok) {
        return json(res, 409, {
          active: true,
          sourceReady: false,
          message: 'Login não está funcionando',
          categories: []
        });
      }

      const kind = u.searchParams.get('kind') === 'series' ? 'series' : 'movie';

      try {
        const catalog = await sourceCatalog(access.sourceUrl, kind);
        const categories = kind === 'series' ? catalog.seriesCategories : catalog.movieCategories;

        if (!categories.length) {
          return json(res, 503, {
            active: true,
            sourceReady: false,
            message: 'Login não está funcionando',
            categories: []
          });
        }

        return json(res, 200, {
          active: true,
          sourceReady: true,
          kind,
          categories,
          stats: catalog.stats
        });
      } catch (error) {
        console.error('catalog.categories', error?.message || error);
        return json(res, 503, {
          active: true,
          sourceReady: false,
          message: 'Login não está funcionando',
          categories: []
        });
      }
    }

    if (u.pathname === '/api/device/catalog/items' && req.method === 'GET') {
      const access = deviceAccess(u.searchParams.get('mac'));
      if (!access.ok) {
        return json(res, 409, {
          active: true,
          sourceReady: false,
          message: 'Login não está funcionando',
          items: []
        });
      }

      const kind = u.searchParams.get('kind') === 'series' ? 'series' : 'movie';
      const categoryId = String(u.searchParams.get('categoryId') || '');

      try {
        const catalog = await sourceCatalog(access.sourceUrl, kind);
        return json(res, 200, {
          active: true,
          sourceReady: true,
          items: sourceCategoryItems(catalog, kind, categoryId)
        });
      } catch (error) {
        console.error('catalog.items', error?.message || error);
        return json(res, 503, {
          active: true,
          sourceReady: false,
          message: 'Login não está funcionando',
          items: []
        });
      }
    }


    if (u.pathname === '/api/device/catalog/search' && req.method === 'GET') {
      const access = deviceAccess(u.searchParams.get('mac'));
      if (!access.ok) {
        return json(res, 409, {
          active: true,
          sourceReady: false,
          message: 'Login não está funcionando',
          items: []
        });
      }

      const kind = u.searchParams.get('kind') === 'series' ? 'series' : 'movie';
      const query = String(u.searchParams.get('q') || '').trim();

      if (query.length < 2) {
        return json(res, 200, {
          active: true,
          sourceReady: true,
          items: []
        });
      }

      try {
        const catalog = await sourceCatalog(access.sourceUrl, kind);
        return json(res, 200, {
          active: true,
          sourceReady: true,
          items: sourceSearch(catalog, kind, query)
        });
      } catch (error) {
        console.error('catalog.search', error?.message || error);
        return json(res, 503, {
          active: true,
          sourceReady: false,
          message: 'Login não está funcionando',
          items: []
        });
      }
    }

    if (u.pathname === '/api/device/catalog/series' && req.method === 'GET') {
      const access = deviceAccess(u.searchParams.get('mac'));
      if (!access.ok) {
        return json(res, 409, {
          active: true,
          sourceReady: false,
          message: 'Login não está funcionando',
          seasons: []
        });
      }

      const seriesId = String(u.searchParams.get('seriesId') || '');

      try {
        const catalog = await sourceCatalog(access.sourceUrl, 'series');
        const seasons = await sourceSeriesSeasons(access.sourceUrl, catalog, seriesId);
        if (!seasons) return json(res, 404, { error: 'Série não encontrada' });
        return json(res, 200, {
          active: true,
          sourceReady: true,
          seasons
        });
      } catch (error) {
        console.error('catalog.series', error?.message || error);
        return json(res, 503, {
          active: true,
          sourceReady: false,
          temporary: true,
          message: 'Servidor de episódios temporariamente indisponível',
          seasons: []
        });
      }
    }

    if (u.pathname.startsWith('/api/admin/')) {
      if (!admin(req)) return json(res, 401, { error: 'Não autorizado' });

      if (u.pathname === '/api/admin/state' && req.method === 'GET') {
        return json(res, 200, {
          settings: store.data.settings || { defaultSourceUrl: '' },
          clients: store.data.clients.map(publicClient),
          pendingDevices: store.data.pendingDevices || [],
          audit: store.data.audit.slice(0, 30),
          storage: {
            durable: store.useSupabase,
            mode: store.useSupabase
              ? 'supabase'
              : 'render-local-browser-backup'
          }
        });
      }

      if (u.pathname === '/api/admin/backup' && req.method === 'GET') {
        return json(res, 200, {
          version: 1,
          savedAt: new Date().toISOString(),
          data: store.snapshot()
        });
      }

      if (u.pathname === '/api/admin/restore' && req.method === 'POST') {
        const b = await body(req);
        const incoming =
          b?.data && typeof b.data === 'object'
            ? b.data
            : b;

        const defaultSourceUrl =
          cleanUrl(incoming?.settings?.defaultSourceUrl || '');

        if (
          defaultSourceUrl &&
          !isM3uUrl(defaultSourceUrl)
        ) {
          return json(res, 400, {
            error: 'Backup contém uma Lista M3U principal inválida.'
          });
        }

        const clients = [];
        const usedMacs = new Set();

        for (const raw of Array.isArray(incoming?.clients) ? incoming.clients : []) {
          const mac = formatMac(raw?.mac);
          const macKey = normMac(mac);

          if (macKey.length !== 12 || usedMacs.has(macKey)) continue;

          const sourceUrl = cleanUrl(raw?.sourceUrl || '');
          if (sourceUrl && !isM3uUrl(sourceUrl)) continue;

          usedMacs.add(macKey);

          clients.push({
            id: String(raw?.id || id()),
            name: String(raw?.name || '').trim(),
            mac,
            sourceUrl,
            enabled: raw?.enabled !== false,
            expiresAt: String(raw?.expiresAt || '')
          });
        }

        const pendingDevices = [];
        const usedPending = new Set();

        for (const raw of Array.isArray(incoming?.pendingDevices) ? incoming.pendingDevices : []) {
          const mac = formatMac(raw?.mac);
          const macKey = normMac(mac);

          if (
            macKey.length !== 12 ||
            usedMacs.has(macKey) ||
            usedPending.has(macKey)
          ) continue;

          usedPending.add(macKey);

          pendingDevices.push({
            id: String(raw?.id || id()),
            mac,
            firstSeenAt: String(raw?.firstSeenAt || new Date().toISOString()),
            lastSeenAt: String(raw?.lastSeenAt || new Date().toISOString()),
            userAgent: String(raw?.userAgent || '')
          });
        }

        const previousAudit =
          Array.isArray(store.data.audit)
            ? store.data.audit
            : [];

        await store.mutate(data => {
          data.settings = { defaultSourceUrl };
          data.clients = clients;
          data.pendingDevices = pendingDevices;
          data.audit = previousAudit.slice(0, 99);

          store.audit(
            'panel.restore',
            `Backup restaurado: ${clients.length} aparelhos`
          );
        });

        clearSourceCache();

        return json(res, 200, {
          ok: true,
          restored: {
            clients: clients.length,
            pendingDevices: pendingDevices.length,
            defaultSource: Boolean(defaultSourceUrl)
          }
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
        if (previous) clearSourceCache(previous);
        if (defaultSourceUrl) {
          clearSourceCache(defaultSourceUrl);
          // Pré-carrega o catálogo em segundo plano para o app abrir as categorias mais rápido.
          sourceCatalog(defaultSourceUrl, 'series').catch(error => console.error('Pré-carga catálogo:', error.message));
        }
        return json(res, 200, { ok: true, settings: store.data.settings });
      }

      if (u.pathname === '/api/admin/catalog/test' && req.method === 'POST') {
        const b = await body(req);
        const sourceUrl = cleanUrl(b.sourceUrl || store.data.settings?.defaultSourceUrl || '');
        if (!isM3uUrl(sourceUrl)) return json(res, 400, { error: 'Informe primeiro uma URL M3U válida.' });
        const catalog = await sourceCatalog(sourceUrl, 'series', true);
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
        if (oldSource && oldSource !== sourceUrl) clearSourceCache(oldSource);
        if (sourceUrl) clearSourceCache(sourceUrl);
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

server.listen(config.port, () => {
  console.log(`LPSM Filmes & Séries painel M3U em :${config.port}`);

  // No Render Free, o serviço pode acordar sem cache em memória.
  // Pré-carrega apenas o índice leve de séries; episódios Xtream são
  // buscados somente quando a série é aberta.
  const defaultSource = cleanUrl(store.data.settings?.defaultSourceUrl || '');
  if (defaultSource) {
    setTimeout(() => {
      sourceCatalog(defaultSource, 'series')
        .then(catalog => {
          console.log(`Catálogo de séries pronto: ${catalog.stats?.series || 0} séries`);
        })
        .catch(error => {
          console.error('Pré-carga inicial:', error?.message || error);
        });
    }, 1200);
  }
});
