import { createHash } from 'node:crypto';

const CATALOG_TTL_MS = 6 * 60 * 60 * 1000;
const DETAIL_TTL_MS = 6 * 60 * 60 * 1000;
const MAX_CATALOG_SOURCES = Number(process.env.XTREAM_CACHE_SOURCES || 2);
const MAX_SERIES_DETAILS = Number(process.env.XTREAM_SERIES_DETAIL_CACHE || 60);

const catalogCache = new Map();
const detailCache = new Map();

const norm = value => String(value || '').trim();
const lower = value => norm(value).toLocaleLowerCase('pt-BR');
const hash = value => createHash('sha1').update(String(value || '')).digest('hex').slice(0, 20);

function touch(map, key, value, max) {
  if (map.has(key)) map.delete(key);
  map.set(key, value);
  while (map.size > max) {
    const oldest = map.keys().next().value;
    map.delete(oldest);
  }
}

function decodeHeaderValue(value) {
  const raw = String(value || '').trim();
  try { return decodeURIComponent(raw.replace(/\+/g, '%20')); }
  catch { return raw; }
}

function splitSourceSpec(rawValue) {
  const raw = norm(rawValue);
  const pipe = raw.indexOf('|');
  if (pipe < 0) return { url: raw, headers: {} };

  const headers = {};
  for (const part of raw.slice(pipe + 1).split('&')) {
    const eq = part.indexOf('=');
    if (eq <= 0) continue;
    let key = part.slice(0, eq).trim();
    if (key.toLowerCase() === 'referrer') key = 'Referer';
    const value = decodeHeaderValue(part.slice(eq + 1));
    if (key && value) headers[key] = value;
  }

  return { url: raw.slice(0, pipe).trim(), headers };
}

export function xtreamSource(rawValue) {
  try {
    const spec = splitSourceSpec(rawValue);
    const u = new URL(spec.url);
    const username = u.searchParams.get('username') || u.searchParams.get('user');
    const password = u.searchParams.get('password') || u.searchParams.get('pass');

    if (!username || !password) return null;

    const file = u.pathname.split('/').pop() || '';
    if (!/^(get|playlist|m3u)\.php$/i.test(file) && !/get\.php/i.test(u.pathname)) {
      // Ainda tentamos quando há username/password porque muitos painéis
      // personalizam o nome do endpoint M3U.
    }

    const pathParts = u.pathname.split('/');
    pathParts[pathParts.length - 1] = 'player_api.php';

    const api = new URL(u.toString());
    api.pathname = pathParts.join('/');
    api.search = '';
    api.hash = '';

    const basePathParts = pathParts.slice(0, -1).filter(Boolean);
    const prefix = basePathParts.length ? `/${basePathParts.join('/')}` : '';
    const streamBase = `${u.protocol}//${u.host}${prefix}`;

    return {
      key: `${u.protocol}//${u.host}${prefix}|${username}`,
      username,
      password,
      apiUrl: api.toString(),
      streamBase,
      headers: spec.headers
    };
  } catch {
    return null;
  }
}

async function apiJson(source, action, extra = {}, timeoutMs = 35_000) {
  const url = new URL(source.apiUrl);
  url.searchParams.set('username', source.username);
  url.searchParams.set('password', source.password);
  if (action) url.searchParams.set('action', action);
  for (const [k, v] of Object.entries(extra)) {
    if (v !== undefined && v !== null && String(v) !== '') {
      url.searchParams.set(k, String(v));
    }
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const headers = {
      'user-agent': source.headers['User-Agent'] || source.headers['user-agent'] || 'LPSM-VOD/1.8.3',
      'accept': 'application/json,*/*'
    };
    for (const [k, v] of Object.entries(source.headers)) {
      if (k.toLowerCase() !== 'user-agent') headers[k] = v;
    }

    const r = await fetch(url, {
      redirect: 'follow',
      signal: controller.signal,
      headers
    });

    if (!r.ok) throw new Error(`Xtream HTTP ${r.status}`);
    const text = await r.text();
    if (!text.trim()) throw new Error('Xtream sem resposta');

    const parsed = JSON.parse(text);
    return parsed;
  } finally {
    clearTimeout(timeout);
  }
}


function normalizedSeriesKey(value) {
  return lower(value)
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/\b(?:temporada|season)\s*\d{1,3}\b/ig, ' ')
    .replace(/\b(?:s|t)\s*\d{1,3}\b/ig, ' ')
    .replace(/[\[\](){}._|:\-–—]+/g, ' ')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

function stableSeriesId(name) {
  const key = normalizedSeriesKey(name) || lower(name);
  return hash(`series|${key}`);
}

function legacyXtreamSeriesId(source, providerId) {
  return hash(`xtream-series|${source.key}|${providerId}`);
}

function categoriesMap(raw, kind) {
  const list = Array.isArray(raw) ? raw : [];
  const categories = [];
  const byId = new Map();

  for (const row of list) {
    const originalId = norm(row?.category_id);
    if (!originalId) continue;
    const id = `x-${kind}-${originalId}`;
    const item = {
      id,
      name: norm(row?.category_name) || (kind === 'series' ? 'Séries' : 'Filmes'),
      providerId: originalId
    };
    categories.push({ id: item.id, name: item.name });
    byId.set(originalId, item);
  }

  return { categories, byId };
}

function bestImage(row, series = false) {
  const candidates = series
    ? [row?.cover_big, row?.cover, row?.movie_image, row?.stream_icon]
    : [row?.stream_icon, row?.movie_image, row?.cover_big, row?.cover];

  for (const value of candidates) {
    const v = norm(value);
    if (/^https?:\/\//i.test(v)) return v;
  }
  return null;
}

function streamUrl(source, kind, id, extension) {
  const ext = norm(extension).replace(/^\./, '') || 'mp4';
  return `${source.streamBase}/${kind}/${encodeURIComponent(source.username)}/${encodeURIComponent(source.password)}/${id}.${ext}`;
}

function emptyCatalog(sourceUrl, source) {
  return {
    provider: 'xtream',
    sourceUrl,
    source,
    createdAt: Date.now(),
    seriesLoaded: false,
    moviesLoaded: false,
    seriesCategories: [],
    movieCategories: [],
    seriesByCategory: new Map(),
    moviesByCategory: new Map(),
    seriesIndex: new Map(),
    stats: {
      provider: 'xtream',
      movies: 0,
      series: 0,
      episodes: -1,
      movieCategories: 0,
      seriesCategories: 0
    }
  };
}

async function ensureSeries(catalog, force = false) {
  if (catalog.seriesLoaded && !force) return catalog;

  const [rawCats, rawSeries] = await Promise.all([
    apiJson(catalog.source, 'get_series_categories'),
    apiJson(catalog.source, 'get_series', {}, 50_000)
  ]);

  const { categories, byId } = categoriesMap(rawCats, 'series');
  const byCategory = new Map();
  const seriesIndex = new Map();

  const uncategorizedId = 'x-series-0';
  let needsUncategorized = false;

  for (const row of Array.isArray(rawSeries) ? rawSeries : []) {
    const providerId = norm(row?.series_id);
    if (!providerId) continue;

    const categoryProviderId = norm(row?.category_id);
    const categoryId = byId.has(categoryProviderId)
      ? byId.get(categoryProviderId).id
      : uncategorizedId;

    if (categoryId === uncategorizedId) needsUncategorized = true;

    const seriesName = norm(row?.name) || `Série ${providerId}`;
    const id = stableSeriesId(seriesName);
    const item = {
      id,
      providerId,
      name: seriesName,
      image: bestImage(row, true),
      isSeries: true
    };

    seriesIndex.set(id, item);
    const list = byCategory.get(categoryId) || [];
    list.push(item);
    byCategory.set(categoryId, list);
  }

  if (needsUncategorized && !categories.some(x => x.id === uncategorizedId)) {
    categories.push({ id: uncategorizedId, name: 'Outras Séries' });
  }

  for (const list of byCategory.values()) {
    list.sort((a, b) => a.name.localeCompare(b.name, 'pt-BR'));
  }

  catalog.seriesCategories = categories;
  catalog.seriesByCategory = byCategory;
  catalog.seriesIndex = seriesIndex;
  catalog.seriesLoaded = true;
  catalog.stats.series = seriesIndex.size;
  catalog.stats.seriesCategories = categories.length;
  catalog.stats.episodes = -1;
  catalog.createdAt = Date.now();

  return catalog;
}

async function ensureMovies(catalog, force = false) {
  if (catalog.moviesLoaded && !force) return catalog;

  const [rawCats, rawMovies] = await Promise.all([
    apiJson(catalog.source, 'get_vod_categories'),
    apiJson(catalog.source, 'get_vod_streams', {}, 50_000)
  ]);

  const { categories, byId } = categoriesMap(rawCats, 'movie');
  const byCategory = new Map();

  const uncategorizedId = 'x-movie-0';
  let needsUncategorized = false;
  let movieCount = 0;

  for (const row of Array.isArray(rawMovies) ? rawMovies : []) {
    const providerId = norm(row?.stream_id);
    if (!providerId) continue;

    const categoryProviderId = norm(row?.category_id);
    const categoryId = byId.has(categoryProviderId)
      ? byId.get(categoryProviderId).id
      : uncategorizedId;

    if (categoryId === uncategorizedId) needsUncategorized = true;

    const ext = norm(row?.container_extension) || 'mp4';
    const item = {
      id: hash(`xtream-movie|${catalog.source.key}|${providerId}`),
      name: norm(row?.name) || `Filme ${providerId}`,
      image: bestImage(row, false),
      url: norm(row?.direct_source) || streamUrl(catalog.source, 'movie', providerId, ext),
      headers: {},
      isSeries: false
    };

    const list = byCategory.get(categoryId) || [];
    list.push(item);
    byCategory.set(categoryId, list);
    movieCount++;
  }

  if (needsUncategorized && !categories.some(x => x.id === uncategorizedId)) {
    categories.push({ id: uncategorizedId, name: 'Outros Filmes' });
  }

  for (const list of byCategory.values()) {
    list.sort((a, b) => a.name.localeCompare(b.name, 'pt-BR'));
  }

  catalog.movieCategories = categories;
  catalog.moviesByCategory = byCategory;
  catalog.moviesLoaded = true;
  catalog.stats.movies = movieCount;
  catalog.stats.movieCategories = categories.length;
  catalog.createdAt = Date.now();

  return catalog;
}

export async function xtreamCatalogFor(sourceUrl, kind = 'all', force = false) {
  const source = xtreamSource(sourceUrl);
  if (!source) throw new Error('Fonte não reconhecida como Xtream');

  const cacheKey = norm(sourceUrl);
  let entry = catalogCache.get(cacheKey);

  if (!force && entry?.promise) return entry.promise;

  let catalog = entry?.catalog;
  if (!catalog || Date.now() - catalog.createdAt > CATALOG_TTL_MS) {
    catalog = emptyCatalog(sourceUrl, source);
  }

  const promise = (async () => {
    if (kind === 'series') await ensureSeries(catalog, force);
    else if (kind === 'movie') await ensureMovies(catalog, force);
    else {
      await ensureSeries(catalog, force);
      await ensureMovies(catalog, force);
    }

    touch(catalogCache, cacheKey, { catalog, createdAt: Date.now() }, MAX_CATALOG_SOURCES);
    return catalog;
  })();

  touch(catalogCache, cacheKey, { catalog, promise, createdAt: Date.now() }, MAX_CATALOG_SOURCES);

  try {
    return await promise;
  } catch (e) {
    catalogCache.delete(cacheKey);
    throw e;
  }
}

export function xtreamCategoryItems(catalog, kind, categoryId) {
  if (kind === 'series') {
    return (catalog.seriesByCategory.get(categoryId) || []).map(x => ({
      id: x.id,
      name: x.name,
      image: x.image,
      isSeries: true
    }));
  }
  return (catalog.moviesByCategory.get(categoryId) || []).map(x => ({ ...x }));
}

function folded(value) {
  return lower(value)
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
}

export function xtreamSearch(catalog, kind, query, limit = 700) {
  const q = folded(query).trim();
  if (q.length < 2) return [];

  const sourceItems = kind === 'series'
    ? [...catalog.seriesIndex.values()]
    : [...catalog.moviesByCategory.values()].flat();

  const scored = [];
  for (const item of sourceItems) {
    const name = folded(item.name);
    const at = name.indexOf(q);
    if (at < 0) continue;

    let score = 1000 - Math.min(at, 100);
    if (name === q) score += 1000;
    else if (name.startsWith(q)) score += 500;

    scored.push({ item, score });
  }

  scored.sort((a, b) =>
    b.score - a.score ||
    String(a.item.name).localeCompare(String(b.item.name), 'pt-BR')
  );

  return scored.slice(0, limit).map(x => ({
    ...x.item,
    isSeries: kind === 'series'
  }));
}

function seriesDetailKey(catalog, seriesId) {
  return `${catalog.source.key}|${seriesId}`;
}

export async function xtreamSeriesSeasons(catalog, seriesId) {
  let item = catalog.seriesIndex.get(seriesId);

  // Compatibilidade com APK/cache anterior da 1.8.0/1.8.2.
  // O ID antigo usava providerId; o novo usa o mesmo ID do parser M3U.
  if (!item) {
    for (const candidate of catalog.seriesIndex.values()) {
      if (
        legacyXtreamSeriesId(catalog.source, candidate.providerId) === seriesId
      ) {
        item = candidate;
        break;
      }
    }
  }

  if (!item) return null;

  const key = seriesDetailKey(catalog, item.providerId);
  const cached = detailCache.get(key);

  if (cached && Date.now() - cached.createdAt < DETAIL_TTL_MS) {
    touch(detailCache, key, cached, MAX_SERIES_DETAILS);
    return cached.seasons;
  }

  let detail;
  let lastError;

  // Alguns servidores Xtream falham esporadicamente no primeiro pedido.
  // Repetimos somente o detalhe da série, sem recarregar 3.000+ capas.
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      detail = await apiJson(
        catalog.source,
        'get_series_info',
        { series_id: item.providerId },
        45_000
      );
      lastError = null;
      break;
    } catch (error) {
      lastError = error;
      if (attempt === 0) {
        await new Promise(resolve => setTimeout(resolve, 700));
      }
    }
  }

  if (!detail) throw lastError || new Error('Detalhes da série indisponíveis');

  const seasonsMap = new Map();

  const addEpisode = (rawEp, fallbackSeason, fallbackNumber) => {
    const ep = rawEp || {};
    const providerEpisodeId = norm(
      ep.id ||
      ep.stream_id ||
      ep.episode_id ||
      ep?.info?.id ||
      ep?.info?.stream_id
    );

    if (!providerEpisodeId) return;

    const seasonNumber =
      Number(ep.season) ||
      Number(ep.season_number) ||
      Number(ep?.info?.season) ||
      Number(fallbackSeason) ||
      1;

    const episodeNumber =
      Number(ep.episode_num) ||
      Number(ep.episode) ||
      Number(ep.episode_number) ||
      Number(ep?.info?.episode_num) ||
      Number(ep?.info?.episode) ||
      Number(fallbackNumber) ||
      1;

    const ext =
      norm(
        ep.container_extension ||
        ep?.info?.container_extension
      ) || 'mp4';

    const direct = norm(
      ep.direct_source ||
      ep?.info?.direct_source
    );

    const title =
      norm(ep.title) ||
      norm(ep.name) ||
      norm(ep?.info?.title) ||
      norm(ep?.info?.name) ||
      `Episódio ${episodeNumber}`;

    const episodes = seasonsMap.get(seasonNumber) || [];

    if (!episodes.some(x => x.id === providerEpisodeId)) {
      episodes.push({
        id: hash(
          `xtream-episode|${catalog.source.key}|${providerEpisodeId}`
        ),
        title,
        number: episodeNumber,
        url:
          direct ||
          streamUrl(
            catalog.source,
            'series',
            providerEpisodeId,
            ext
          ),
        headers: {}
      });
    }

    seasonsMap.set(seasonNumber, episodes);
  };

  const rawEpisodes = detail?.episodes;

  if (Array.isArray(rawEpisodes)) {
    // Alguns painéis devolvem um array único de episódios.
    rawEpisodes.forEach((ep, index) => {
      addEpisode(ep, ep?.season || 1, index + 1);
    });
  } else if (rawEpisodes && typeof rawEpisodes === 'object') {
    // Formato Xtream mais comum: { "1": [...], "2": [...] }.
    for (const [seasonKey, rawList] of Object.entries(rawEpisodes)) {
      if (Array.isArray(rawList)) {
        rawList.forEach((ep, index) => {
          addEpisode(ep, seasonKey, index + 1);
        });
      } else if (rawList && typeof rawList === 'object') {
        // Compatibilidade com painéis que usam objeto indexado.
        Object.values(rawList).forEach((ep, index) => {
          addEpisode(ep, seasonKey, index + 1);
        });
      }
    }
  }

  // Fallback adicional: alguns servidores colocam episódios em "series".
  if (seasonsMap.size === 0 && Array.isArray(detail?.series)) {
    detail.series.forEach((ep, index) => {
      addEpisode(ep, ep?.season || 1, index + 1);
    });
  }

  const seasons = [...seasonsMap.entries()]
    .map(([number, episodes]) => ({
      number: Number(number) || 1,
      episodes: episodes.sort((a, b) => a.number - b.number)
    }))
    .filter(x => x.episodes.length > 0)
    .sort((a, b) => a.number - b.number);

  touch(
    detailCache,
    key,
    { createdAt: Date.now(), seasons },
    MAX_SERIES_DETAILS
  );

  return seasons;
}

export function clearXtreamCache(sourceUrl = '') {
  if (!sourceUrl) {
    catalogCache.clear();
    detailCache.clear();
    return;
  }

  catalogCache.delete(norm(sourceUrl));
  const source = xtreamSource(sourceUrl);
  if (!source) return;

  for (const key of [...detailCache.keys()]) {
    if (key.startsWith(`${source.key}|`)) detailCache.delete(key);
  }
}
