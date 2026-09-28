import { createHash } from 'node:crypto';

const CACHE_TTL_MS = 24 * 60 * 60 * 1000;
const MAX_ITEMS = Number(process.env.M3U_MAX_ITEMS || 600000);
const cache = new Map();
const MAX_CACHE_SOURCES = Number(process.env.M3U_CACHE_SOURCES || 1);

const hash = value => createHash('sha1').update(String(value || '')).digest('hex').slice(0, 20);
const norm = value => String(value || '').trim();
const lower = value => norm(value).toLocaleLowerCase('pt-BR');

function rememberCache(key, value) {
  if (cache.has(key)) cache.delete(key);
  cache.set(key, value);
  while (cache.size > MAX_CACHE_SOURCES) {
    const oldest = cache.keys().next().value;
    cache.delete(oldest);
  }
}

function attrs(line) {
  const out = {};
  const re = /([\w-]+)="([^"]*)"/g;
  let m;
  while ((m = re.exec(line))) out[m[1].toLowerCase()] = m[2];
  return out;
}

function displayName(extinf, a) {
  const comma = extinf.indexOf(',');
  const byComma = comma >= 0 ? extinf.slice(comma + 1).trim() : '';
  return norm(a['tvg-name'] || byComma || 'Sem título');
}


function imageScore(url, sourceKey = '') {
  const u = lower(url);
  const k = lower(sourceKey);
  let score = 0;

  if (/poster|cover|capa|w500|w780|original|tmdb/.test(u)) score += 20;
  if (/poster|cover|series-cover|movie-cover/.test(k)) score += 30;
  if (k === 'tvg-logo') score += 10;

  if (/backdrop|fanart|landscape|still|screenshot|episode|thumb/.test(u)) score -= 15;
  if (/backdrop|fanart|thumbnail|thumb/.test(k)) score -= 12;

  return score;
}

function imageFromAttrs(a) {
  const keys = [
    'poster',
    'cover',
    'series-cover',
    'movie-cover',
    'tvg-logo',
    'logo',
    'icon',
    'thumbnail',
    'thumb',
    'backdrop'
  ];

  const candidates = [];

  for (const key of keys) {
    const value = norm(a[key]);
    if (/^https?:\/\//i.test(value)) {
      candidates.push({
        url: value,
        score: imageScore(value, key)
      });
    }
  }

  candidates.sort((x, y) => y.score - x.score);
  return candidates[0]?.url || '';
}

function rememberSeriesImage(series, url) {
  const image = norm(url);
  if (!/^https?:\/\//i.test(image)) return;

  const entry = series.imageCandidates.get(image) || {
    count: 0,
    score: imageScore(image)
  };

  entry.count += 1;
  series.imageCandidates.set(image, entry);
}

function bestSeriesImage(series) {
  let best = null;

  for (const [url, info] of series.imageCandidates.entries()) {
    // Repetir a mesma imagem em muitos episódios é um forte sinal de
    // capa da série; screenshots diferentes costumam aparecer só uma vez.
    const score = info.score + Math.min(info.count, 20) * 4;
    if (!best || score > best.score) best = { url, score };
  }

  return best?.url || series.image || null;
}

function episodeInfo(name, group = '') {
  const n = norm(name);
  const patterns = [
    /\bS\s*(\d{1,3})\s*[.\-_ ]*E\s*(\d{1,4})\b/i,
    /\bT\s*(\d{1,3})\s*[.\-_ ]*E\s*(\d{1,4})\b/i,
    /\b(\d{1,3})\s*x\s*(\d{2,4})\b/i,
    /\b(?:TEMP(?:ORADA)?|SEASON)\s*(\d{1,3})\D+(?:EP(?:IS[ÓO]DIO|ISODE)?|E)\s*(\d{1,4})\b/i,
    /\b(\d{1,3})\s*[ªº]?\s*TEMPORADA\D+(?:EP(?:IS[ÓO]DIO|ISODE)?|E)\s*(\d{1,4})\b/i,
    /\b(?:TEMPORADA|SEASON)\s*(\d{1,3})\D+(?:CAP(?:[ÍI]TULO)?|CHAPTER)\s*(\d{1,4})\b/i,
    /\b(?:S|T)\s*(\d{1,3})\D+(?:EP|E)\s*[.\-_ ]*(\d{1,4})\b/i
  ];

  for (const p of patterns) {
    const m = n.match(p);
    if (m) return {
      season: Number(m[1]) || 1,
      episode: Number(m[2]) || 1,
      marker: m[0]
    };
  }

  const only = n.match(/\b(?:EP(?:IS[ÓO]DIO|ISODE)?|E)\s*[.\-_ ]*(\d{1,4})\b/i);
  if (only) {
    const g = norm(group).match(/\b(?:TEMPORADA|SEASON)\s*(\d{1,3})\b/i);
    return {
      season: g ? (Number(g[1]) || 1) : 1,
      episode: Number(only[1]) || 1,
      marker: only[0]
    };
  }

  return null;
}

function seriesKey(value) {
  return lower(value)
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/\b(?:temporada|season)\s*\d{1,3}\b/ig, ' ')
    .replace(/\b(?:s|t)\s*\d{1,3}\b/ig, ' ')
    .replace(/[\[\](){}._|:\-–—]+/g, ' ')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

function cleanSeriesName(name, ep) {
  const original = norm(name);
  if (!ep?.marker) {
    return original
      .replace(/\s*[-–—:]?\s*(?:TEMPORADA|SEASON)\s*\d{1,3}\s*$/i, '')
      .trim() || original;
  }

  const index = lower(original).indexOf(lower(ep.marker));
  if (index > 0) {
    const before = original
      .slice(0, index)
      .replace(/[\s._|:\-–—[\]()]+$/g, '')
      .trim();
    if (before.length >= 2) return before;
  }

  const value = original
    .replace(ep.marker, ' ')
    .replace(/\b(?:TEMPORADA|SEASON)\s*\d{1,3}\b/ig, ' ')
    .replace(/\b(?:EPIS[ÓO]DIO|EPISODE|EP)\s*\d{1,4}\b/ig, ' ')
    .replace(/[\s._|:\-–—]+$/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim();

  return value || original;
}

function cleanEpisodeTitle(name, ep, seriesName) {
  const original = norm(name);
  if (!ep?.marker) return `Episódio ${ep?.episode || 1}`;

  const index = lower(original).indexOf(lower(ep.marker));
  if (index >= 0) {
    const after = original
      .slice(index + ep.marker.length)
      .replace(/^[\s._|:\-–—[\]()]+/g, '')
      .trim();
    if (after.length >= 2 && lower(after) !== lower(seriesName)) return after;
  }

  return `Episódio ${ep.episode || 1}`;
}

function classify(url, group, name) {
  const u = lower(url);
  const g = lower(group)
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
  const ext = (u.split('?')[0].match(/\.([a-z0-9]{2,5})$/i)?.[1] || '').toLowerCase();
  const ep = episodeInfo(name, group);

  if (u.includes('/series/')) return 'series';
  if (u.includes('/movie/')) return 'movie';
  if (u.includes('/live/')) return 'live';

  if (/\b(series?|seriados?|temporadas?|novelas?|doramas?|animes?|tv\s*shows?)\b/i.test(g)) return 'series';
  if (/\b(filmes?|movies?|cinema|vod|lancamentos?|catalogo\s*vod)\b/i.test(g)) return 'movie';

  if (/\b(canais?|ao\s*vivo|live|tv\s*aberta|esportes?|sports?|futebol|noticias?|news|radios?|ppv|24h|premiere|combate)\b/i.test(g)) {
    return 'live';
  }

  if (ep) return 'series';

  if (['mp4', 'mkv', 'avi', 'mov', 'm4v', 'webm', 'mpg', 'mpeg'].includes(ext)) return 'movie';
  if (ext === 'm3u8' || ext === 'ts') return 'live';

  return 'other';
}

function makeCategory(kind, name) {
  const title = norm(name) || (kind === 'series' ? 'Séries' : 'Filmes');
  return { id: hash(`${kind}|${title}`), name: title };
}

function getOrCreate(map, key, factory) {
  let value = map.get(key);
  if (!value) {
    value = factory();
    map.set(key, value);
  }
  return value;
}

function decodeHeaderValue(value) {
  const raw = String(value || '').trim();
  try { return decodeURIComponent(raw.replace(/\+/g, '%20')); }
  catch { return raw; }
}

function setHeader(headers, key, value) {
  const k = String(key || '').trim();
  const v = decodeHeaderValue(value);
  if (!k || !v) return;
  const normalized = k.toLowerCase() === 'referrer' ? 'Referer' : k;
  headers[normalized] = v;
}

function parseHeaderPairs(text, headers) {
  const raw = String(text || '').trim();
  if (!raw) return;

  for (const part of raw.split('&')) {
    const eq = part.indexOf('=');
    if (eq <= 0) continue;
    setHeader(headers, part.slice(0, eq), part.slice(eq + 1));
  }
}

function applyDirective(line, pending) {
  if (!pending) return;

  const l = line.toLowerCase();

  if (l.startsWith('#extvlcopt:http-user-agent=')) {
    setHeader(pending.headers, 'User-Agent', line.slice(line.indexOf('=') + 1));
  } else if (
    l.startsWith('#extvlcopt:http-referrer=') ||
    l.startsWith('#extvlcopt:http-referer=')
  ) {
    setHeader(pending.headers, 'Referer', line.slice(line.indexOf('=') + 1));
  } else if (
    l.startsWith('#kodiprop:inputstream.adaptive.stream_headers=') ||
    l.startsWith('#kodiprop:inputstream.adaptive.manifest_headers=')
  ) {
    parseHeaderPairs(line.slice(line.indexOf('=') + 1), pending.headers);
  } else if (l.startsWith('#exthttp:')) {
    const raw = line.slice(line.indexOf(':') + 1).trim();
    try {
      const obj = JSON.parse(raw);
      for (const [k, v] of Object.entries(obj)) setHeader(pending.headers, k, v);
    } catch { }
  }
}

function splitUrlAndHeaders(rawUrl, inheritedHeaders = {}) {
  const headers = { ...inheritedHeaders };
  const pipe = rawUrl.indexOf('|');
  if (pipe < 0) return { url: rawUrl, headers };

  const url = rawUrl.slice(0, pipe).trim();
  parseHeaderPairs(rawUrl.slice(pipe + 1), headers);
  return { url, headers };
}

async function parseM3u(url) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 55_000);

  try {
    const response = await fetch(url, {
      redirect: 'follow',
      signal: controller.signal,
      headers: {
        'user-agent': 'LPSM-VOD-Catalog/1.8.0',
        'accept': 'application/x-mpegURL,text/plain,*/*'
      }
    });

    if (!response.ok) throw new Error(`A lista M3U respondeu ${response.status}`);
    if (!response.body) throw new Error('A lista M3U não retornou conteúdo');

    const movieCategories = new Map();
    const seriesCategories = new Map();
    const moviesByCategory = new Map();

    // O(1) para agrupamento: evita os .some() repetidos que deixavam listas
    // grandes extremamente lentas.
    const seriesIdsByCategory = new Map();
    const seriesIndex = new Map();

    let pending = null;
    let fallbackGroup = '';
    let buffer = '';
    let videoCount = 0;
    let ignored = 0;

    const processLine = raw => {
      const line = raw.trim();
      if (!line) return;

      if (line.startsWith('#EXTINF')) {
        const a = attrs(line);
        pending = {
          name: displayName(line, a),
          image: imageFromAttrs(a),
          group: norm(a['group-title']) || fallbackGroup,
          headers: {}
        };
        return;
      }

      if (line.startsWith('#EXTGRP:')) {
        fallbackGroup = norm(line.slice('#EXTGRP:'.length));
        if (pending && !pending.group) pending.group = fallbackGroup;
        return;
      }

      if (line.startsWith('#')) {
        applyDirective(line, pending);
        return;
      }

      if (!/^https?:\/\//i.test(line)) {
        pending = null;
        return;
      }

      if (!pending || videoCount >= MAX_ITEMS) return;

      const meta = pending;
      pending = null;

      const stream = splitUrlAndHeaders(line, meta.headers);
      const kind = classify(stream.url, meta.group, meta.name);

      if (kind !== 'movie' && kind !== 'series') {
        ignored++;
        return;
      }

      videoCount++;

      if (kind === 'movie') {
        const category = makeCategory('movie', meta.group);
        movieCategories.set(category.id, category);

        const list = getOrCreate(moviesByCategory, category.id, () => []);
        list.push({
          id: hash(`movie|${stream.url}`),
          name: meta.name,
          image: meta.image || null,
          url: stream.url,
          headers: stream.headers,
          isSeries: false
        });
        return;
      }

      const ep = episodeInfo(meta.name, meta.group) || {
        season: 1,
        episode: 1,
        marker: ''
      };

      const seriesName = cleanSeriesName(meta.name, ep);
      const normalizedSeriesKey = seriesKey(seriesName) || lower(seriesName);
      const seriesId = hash(`series|${normalizedSeriesKey}`);

      const category = makeCategory('series', meta.group);
      seriesCategories.set(category.id, category);

      let series = seriesIndex.get(seriesId);
      if (!series) {
        series = {
          id: seriesId,
          name: seriesName,
          image: meta.image || null,
          isSeries: true,
          seasons: new Map(),
          seenEpisodeUrls: new Map(),
          imageCandidates: new Map()
        };
        seriesIndex.set(seriesId, series);
      } else if (!series.image && meta.logo) {
        series.image = meta.logo;
      }

      getOrCreate(seriesIdsByCategory, category.id, () => new Set()).add(seriesId);

      const episodes = getOrCreate(series.seasons, ep.season, () => []);
      const seen = getOrCreate(series.seenEpisodeUrls, ep.season, () => new Set());

      if (!seen.has(stream.url)) {
        seen.add(stream.url);
        episodes.push({
          id: hash(`episode|${stream.url}`),
          title: cleanEpisodeTitle(meta.name, ep, seriesName),
          number: ep.episode,
          url: stream.url,
          headers: stream.headers
        });
      }
    };

    for await (const chunk of response.body) {
      buffer += Buffer.from(chunk).toString('utf8');

      let idx;
      while ((idx = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, idx).replace(/\r$/, '');
        buffer = buffer.slice(idx + 1);
        processLine(line);
      }

      if (videoCount >= MAX_ITEMS) break;
    }

    if (buffer.trim() && videoCount < MAX_ITEMS) processLine(buffer);

    for (const series of seriesIndex.values()) {
      for (const eps of series.seasons.values()) {
        eps.sort((a, b) => (a.number || 0) - (b.number || 0));
      }
      series.image = bestSeriesImage(series);
      delete series.seenEpisodeUrls;
      delete series.imageCandidates;
    }

    const seriesByCategory = new Map();
    for (const [categoryId, ids] of seriesIdsByCategory.entries()) {
      seriesByCategory.set(
        categoryId,
        [...ids].map(id => seriesIndex.get(id)).filter(Boolean)
      );
    }

    const totalMovies = [...moviesByCategory.values()]
      .reduce((n, x) => n + x.length, 0);
    const totalSeries = seriesIndex.size;
    const totalEpisodes = [...seriesIndex.values()]
      .reduce(
        (n, s) => n + [...s.seasons.values()]
          .reduce((m, e) => m + e.length, 0),
        0
      );

    if (totalMovies === 0 && totalSeries === 0 && totalEpisodes === 0) {
      throw new Error('Login não está funcionando');
    }

    return {
      createdAt: Date.now(),
      sourceUrl: url,
      movieCategories: [...movieCategories.values()],
      seriesCategories: [...seriesCategories.values()],
      moviesByCategory,
      seriesByCategory,
      seriesIndex,
      stats: {
        movies: totalMovies,
        series: totalSeries,
        episodes: totalEpisodes,
        movieCategories: movieCategories.size,
        seriesCategories: seriesCategories.size,
        ignored,
        capped: videoCount >= MAX_ITEMS,
        maxItems: MAX_ITEMS
      }
    };
  } finally {
    clearTimeout(timeout);
  }
}

export async function catalogFor(sourceUrl, force = false) {
  const key = norm(sourceUrl);
  if (!/^https?:\/\//i.test(key)) throw new Error('URL M3U inválida');

  const existing = cache.get(key);
  if (!force && existing) {
    rememberCache(key, existing);
    if (existing.promise) return existing.promise;
    if (Date.now() - existing.createdAt < CACHE_TTL_MS) return existing;
  }

  const promise = parseM3u(key);
  rememberCache(key, { createdAt: Date.now(), promise });

  try {
    const catalog = await promise;
    rememberCache(key, catalog);
    return catalog;
  } catch (e) {
    cache.delete(key);
    throw e;
  }
}

export function clearCatalogCache(sourceUrl = '') {
  if (sourceUrl) cache.delete(norm(sourceUrl));
  else cache.clear();
}

export function categoryItems(catalog, kind, categoryId) {
  if (kind === 'movie') {
    return (catalog.moviesByCategory.get(categoryId) || []).map(x => ({ ...x }));
  }

  return (catalog.seriesByCategory.get(categoryId) || [])
    .map(s => ({
      id: s.id,
      name: s.name,
      image: s.image,
      isSeries: true
    }));
}


export function searchCatalog(catalog, kind, query, limit = 700) {
  const q = lower(query)
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .trim();

  if (q.length < 2) return [];

  const seen = new Set();
  const scored = [];

  const add = item => {
    if (!item?.id || seen.has(item.id)) return;
    seen.add(item.id);

    const name = lower(item.name)
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '');

    const at = name.indexOf(q);
    if (at < 0) return;

    let score = 1000 - Math.min(at, 100);
    if (name === q) score += 1000;
    else if (name.startsWith(q)) score += 500;

    scored.push({ item, score });
  };

  if (kind === 'series') {
    for (const series of catalog.seriesIndex.values()) {
      add({
        id: series.id,
        name: series.name,
        image: series.image,
        isSeries: true
      });
    }
  } else {
    for (const list of catalog.moviesByCategory.values()) {
      for (const movie of list) add({ ...movie });
    }
  }

  scored.sort((a, b) =>
    b.score - a.score ||
    String(a.item.name).localeCompare(String(b.item.name), 'pt-BR')
  );

  return scored.slice(0, limit).map(x => x.item);
}

export function seriesSeasons(catalog, seriesId) {
  const series = catalog.seriesIndex.get(seriesId);
  if (!series) return null;

  return [...series.seasons.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([number, episodes]) => ({
      number,
      episodes: episodes.map(e => ({ ...e }))
    }));
}
