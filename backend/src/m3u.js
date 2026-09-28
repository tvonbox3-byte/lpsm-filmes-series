import { createHash } from 'node:crypto';

const CACHE_TTL_MS = 30 * 60 * 1000;
const MAX_ITEMS = Number(process.env.M3U_MAX_ITEMS || 250000);
const cache = new Map();

const hash = value => createHash('sha1').update(String(value || '')).digest('hex').slice(0, 20);
const norm = value => String(value || '').trim();
const lower = value => norm(value).toLocaleLowerCase('pt-BR');

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

function episodeInfo(name) {
  const n = norm(name);
  const patterns = [
    /\bS(\d{1,3})\s*E(\d{1,4})\b/i,
    /\b(\d{1,3})x(\d{1,4})\b/i,
    /\bT(?:EMPORADA)?\s*(\d{1,3})\D+E(?:P(?:IS[ÓO]DIO)?)?\s*(\d{1,4})\b/i,
    /\bTEMPORADA\s*(\d{1,3})\D+EPIS[ÓO]DIO\s*(\d{1,4})\b/i
  ];
  for (const p of patterns) {
    const m = n.match(p);
    if (m) return { season: Number(m[1]) || 1, episode: Number(m[2]) || 1, marker: m[0] };
  }
  return null;
}

function cleanSeriesName(name, ep) {
  let value = norm(name);
  if (ep?.marker) value = value.replace(ep.marker, ' ');
  value = value
    .replace(/\b(?:TEMPORADA|SEASON)\s*\d{1,3}\b/ig, ' ')
    .replace(/\b(?:EPIS[ÓO]DIO|EPISODE|EP)\s*\d{1,4}\b/ig, ' ')
    .replace(/[\s._-]+$/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
  return value || norm(name);
}

function classify(url, group, name) {
  const u = lower(url);
  const g = lower(group);
  const ext = (u.split('?')[0].match(/\.([a-z0-9]{2,5})$/i)?.[1] || '').toLowerCase();
  const ep = episodeInfo(name);

  if (u.includes('/series/') || ep || /\b(s[ée]ries?|temporadas?|novelas?)\b/i.test(g)) return 'series';
  if (u.includes('/movie/') || /\b(filmes?|cinema|vod|lan[çc]amentos?)\b/i.test(g)) return 'movie';
  if (['mp4','mkv','avi','mov','m4v','webm','mpg','mpeg'].includes(ext)) return 'movie';
  if (u.includes('/live/') || ext === 'm3u8' || ext === 'ts') return 'live';
  return 'other';
}

function makeCategory(kind, name) {
  const title = norm(name) || 'Sem categoria';
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
  if (!pending) return false;
  const lowerLine = line.toLowerCase();

  if (lowerLine.startsWith('#extvlcopt:http-user-agent=')) {
    setHeader(pending.headers, 'User-Agent', line.slice(line.indexOf('=') + 1));
    return true;
  }
  if (lowerLine.startsWith('#extvlcopt:http-referrer=') || lowerLine.startsWith('#extvlcopt:http-referer=')) {
    setHeader(pending.headers, 'Referer', line.slice(line.indexOf('=') + 1));
    return true;
  }
  if (lowerLine.startsWith('#kodiprop:inputstream.adaptive.stream_headers=') ||
      lowerLine.startsWith('#kodiprop:inputstream.adaptive.manifest_headers=')) {
    parseHeaderPairs(line.slice(line.indexOf('=') + 1), pending.headers);
    return true;
  }
  if (lowerLine.startsWith('#exthttp:')) {
    const raw = line.slice(line.indexOf(':') + 1).trim();
    try {
      const obj = JSON.parse(raw);
      for (const [k, v] of Object.entries(obj)) setHeader(pending.headers, k, v);
    } catch { /* ignora EXTHTTP inválido */ }
    return true;
  }
  return false;
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
  const timeout = setTimeout(() => controller.abort(), 60_000);
  try {
    const response = await fetch(url, {
      redirect: 'follow',
      signal: controller.signal,
      headers: {
        'user-agent': 'LPSM-VOD-Catalog/1.2.1',
        'accept': 'application/x-mpegURL,text/plain,*/*'
      }
    });
    if (!response.ok) throw new Error(`A lista M3U respondeu ${response.status}`);
    if (!response.body) throw new Error('A lista M3U não retornou conteúdo');

    const movieCategories = new Map();
    const seriesCategories = new Map();
    const moviesByCategory = new Map();
    const seriesByCategory = new Map();
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
          logo: norm(a['tvg-logo']),
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
        if (applyDirective(line, pending)) return;
        return;
      }
      if (!/^https?:\/\//i.test(line)) { pending = null; return; }
      if (!pending) return;
      if (videoCount >= MAX_ITEMS) return;

      const meta = pending;
      pending = null;
      const stream = splitUrlAndHeaders(line, meta.headers);
      const kind = classify(stream.url, meta.group, meta.name);
      if (kind === 'live' || kind === 'other') { ignored++; return; }
      videoCount++;

      if (kind === 'movie') {
        const category = makeCategory('movie', meta.group);
        movieCategories.set(category.id, category);
        const list = getOrCreate(moviesByCategory, category.id, () => []);
        list.push({
          id: hash(`movie|${stream.url}`),
          name: meta.name,
          image: meta.logo || null,
          url: stream.url,
          headers: stream.headers,
          isSeries: false
        });
        return;
      }

      const ep = episodeInfo(meta.name) || { season: 1, episode: 1, marker: '' };
      const seriesName = cleanSeriesName(meta.name, ep);
      const category = makeCategory('series', meta.group);
      seriesCategories.set(category.id, category);
      const seriesId = hash(`series|${category.name}|${seriesName}`);
      let series = seriesIndex.get(seriesId);
      if (!series) {
        series = {
          id: seriesId,
          name: seriesName,
          image: meta.logo || null,
          isSeries: true,
          seasons: new Map()
        };
        seriesIndex.set(seriesId, series);
        const list = getOrCreate(seriesByCategory, category.id, () => []);
        list.push(series);
      } else if (!series.image && meta.logo) {
        series.image = meta.logo;
      }
      const eps = getOrCreate(series.seasons, ep.season, () => []);
      eps.push({
        id: hash(`episode|${stream.url}`),
        title: meta.name,
        number: ep.episode,
        url: stream.url,
        headers: stream.headers
      });
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
      for (const eps of series.seasons.values()) eps.sort((a,b) => (a.number || 0) - (b.number || 0));
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
        movies: [...moviesByCategory.values()].reduce((n, x) => n + x.length, 0),
        series: seriesIndex.size,
        episodes: [...seriesIndex.values()].reduce((n, s) => n + [...s.seasons.values()].reduce((m, e) => m + e.length, 0), 0),
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
    if (existing.promise) return existing.promise;
    if (Date.now() - existing.createdAt < CACHE_TTL_MS) return existing;
  }
  const promise = parseM3u(key);
  cache.set(key, { createdAt: Date.now(), promise });
  try {
    const catalog = await promise;
    cache.set(key, catalog);
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
  if (kind === 'movie') return (catalog.moviesByCategory.get(categoryId) || []).map(x => ({ ...x }));
  return (catalog.seriesByCategory.get(categoryId) || []).map(s => ({ id: s.id, name: s.name, image: s.image, isSeries: true }));
}

export function seriesSeasons(catalog, seriesId) {
  const series = catalog.seriesIndex.get(seriesId);
  if (!series) return null;
  return [...series.seasons.entries()]
    .sort((a,b) => a[0] - b[0])
    .map(([number, episodes]) => ({ number, episodes: episodes.map(e => ({ ...e })) }));
}
