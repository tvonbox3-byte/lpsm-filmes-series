import test from 'node:test';
import assert from 'node:assert/strict';
import { xtreamCatalogFor, xtreamSeriesSeasons, clearXtreamCache } from '../src/xtream.js';

const sourceUrl = 'https://example.test/get.php?username=user&password=pass&type=m3u_plus';
const realFetch = globalThis.fetch;

function mockApi({ emptyOnce = false } = {}) {
  let detailCalls = 0;
  globalThis.fetch = async input => {
    const u = new URL(input);
    const action = u.searchParams.get('action');
    if (action === 'get_series_info') {
      detailCalls++;
      const episodes = emptyOnce && detailCalls === 1 ? {} : { '1': [{ id: 44, title: 'Episódio 1', episode_num: 1, container_extension: 'mkv', direct_source: 'https://cdn.example.test/44.mkv' }] };
      return { ok: true, text: async () => JSON.stringify({ episodes }) };
    }
    const value = action === 'get_series_categories' ? [{ category_id: '1', category_name: 'Drama' }]
      : action === 'get_series' ? [{ series_id: '12', category_id: '1', name: 'Série Teste' }]
      : action === 'get_vod_categories' ? [{ category_id: '2', category_name: 'Filmes' }]
      : action === 'get_vod_streams' ? [] : [];
    await new Promise(resolve => setTimeout(resolve, action === 'get_series' ? 25 : 5));
    return { ok: true, text: async () => JSON.stringify(value) };
  };
  return () => detailCalls;
}

test('carregamentos simultâneos de filmes e séries preservam o índice de séries', async () => {
  clearXtreamCache();
  mockApi();
  try {
    const [movie, series] = await Promise.all([
      xtreamCatalogFor(sourceUrl, 'movie'),
      xtreamCatalogFor(sourceUrl, 'series')
    ]);
    assert.equal(movie.seriesIndex.size, 1);
    assert.equal(series.seriesIndex.size, 1);
    const id = series.seriesIndex.keys().next().value;
    const seasons = await xtreamSeriesSeasons(series, id);
    assert.equal(seasons[0].episodes[0].number, 1);
    assert.equal(seasons[0].episodes[0].url, 'https://cdn.example.test/44.mkv');
    assert.match(seasons[0].episodes[0].alternateUrl, /\/series\/user\/pass\/44\.mkv$/);
  } finally { globalThis.fetch = realFetch; clearXtreamCache(); }
});

test('resposta vazia temporária não elimina episódios', async () => {
  clearXtreamCache();
  const calls = mockApi({ emptyOnce: true });
  try {
    const catalog = await xtreamCatalogFor(sourceUrl, 'series');
    const seasons = await xtreamSeriesSeasons(catalog, catalog.seriesIndex.keys().next().value);
    assert.equal(seasons.length, 1);
    assert.equal(calls(), 2);
  } finally { globalThis.fetch = realFetch; clearXtreamCache(); }
});
