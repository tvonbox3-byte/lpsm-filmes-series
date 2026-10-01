import test from 'node:test';
import assert from 'node:assert/strict';
import { adultName, adultCategory, protectedItems } from '../src/adult-policy.js';
function fixture() {
  const normal = { id: 'normal', name: 'Filme de aventura', url: 'https://example.test/a' };
  const adult = { id: 'adult', name: 'Título sem marcador', url: 'https://example.test/b' };
  const alias = { ...adult, id: 'adult-copy' };
  const flagged = { id: 'provider', name: 'Sem marcador também', adult: true };
  return { normal, adult, flagged, catalog: {
    movieCategories: [{ id: 'new', name: 'Lançamentos' }, { id: 'xxx', name: 'ADULTOS XXX' }],
    moviesByCategory: new Map([['new', [normal, adult, alias, flagged]], ['xxx', [adult]]])
  } };
}
test('Lançamentos excludes adult membership, duplicate URLs and provider flags', () => {
  const { catalog, normal } = fixture();
  assert.deepEqual(protectedItems(catalog, 'movie', 'new', catalog.moviesByCategory.get('new')), [{ ...normal, adult: false }]);
});
test('adult section preserves items with metadata for the PIN gate', () => {
  const { catalog, adult } = fixture();
  assert.deepEqual(protectedItems(catalog, 'movie', 'xxx', [adult]), [{ ...adult, adult: true }]);
});
test('search excludes adult titles even if their names are neutral', () => {
  const { catalog, normal, adult, flagged } = fixture();
  assert.deepEqual(protectedItems(catalog, 'movie', null, [normal, adult, flagged]), [{ ...normal, adult: false }]);
});
test('classification recognises accents and markers without broad sex keywords', () => {
  for (const name of ['Adultos', 'XXX', 'Erótico', '18 +', '+18', 'Pornô', 'Hentai']) assert.equal(adultName(name), true, name);
  for (const name of ['Lançamentos', 'Essex', 'Sexo, Amor e Traição', 'Toy Story']) assert.equal(adultName(name), false, name);
  assert.equal(adultCategory({ name: 'Restritos', adult: true }), true);
});
test('series membership and refresh invalidate the adult index correctly', () => {
  const item = { id: 's', name: 'Série neutra', isSeries: true };
  const catalog = { seriesCategories: [{ id: 'drama', name: 'Drama' }, { id: 'restricted', name: 'XXX' }], seriesByCategory: new Map([['drama', [item]], ['restricted', [item]]]) };
  assert.deepEqual(protectedItems(catalog, 'series', 'drama', [item]), []);
  catalog.seriesByCategory = new Map([['drama', [item]]]);
  assert.equal(protectedItems(catalog, 'series', 'drama', [item]).length, 1);
});
test('Xtream adult flags survive parsing and protect neutral provider labels', async () => {
  const { xtreamCatalogFor, xtreamCategoryItems, clearXtreamCache } = await import('../src/xtream.js');
  const previous = globalThis.fetch;
  globalThis.fetch = async input => {
    const action = new URL(input).searchParams.get('action');
    const value = action === 'get_vod_categories'
      ? [{ category_id: '8', category_name: 'Lançamentos' }, { category_id: '9', category_name: 'Restritos', is_adult: '1' }]
      : [{ stream_id: '11', category_id: '9', name: 'Nome neutro' }, { stream_id: '12', category_id: '8', name: 'Outro neutro', is_adult: '1' }];
    return { ok: true, text: async () => JSON.stringify(value) };
  };
  clearXtreamCache();
  try {
    const catalog = await xtreamCatalogFor('https://example.test/get.php?username=test&password=test&type=m3u_plus', 'movie');
    assert.equal(adultCategory(catalog.movieCategories.find(c => c.id === 'x-movie-9')), true);
    assert.deepEqual(protectedItems(catalog, 'movie', 'x-movie-8', xtreamCategoryItems(catalog, 'movie', 'x-movie-8')), []);
    assert.equal(protectedItems(catalog, 'movie', 'x-movie-9', xtreamCategoryItems(catalog, 'movie', 'x-movie-9'))[0].adult, true);
  } finally { globalThis.fetch = previous; clearXtreamCache(); }
});
