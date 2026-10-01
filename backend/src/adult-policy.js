const cache = new WeakMap();
export const adultFlag = value => value === true || value === 1 || value === '1' || value === 'true';
export function adultName(value) {
  const name = String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  return /\b(?:adultos?|adults?|xxx|porn\w*|erotic\w*|hentai|onlyfans)\b|18\s*\+|\+\s*18/.test(name);
}
export const adultCategory = category => adultFlag(category?.adult) || adultName(category?.name);
function index(catalog, kind) {
  const categories = kind === 'series' ? catalog.seriesCategories : catalog.movieCategories;
  const groups = kind === 'series' ? catalog.seriesByCategory : catalog.moviesByCategory;
  let entries = cache.get(catalog);
  if (!entries) { entries = new Map(); cache.set(catalog, entries); }
  const previous = entries.get(kind);
  if (previous?.groups === groups && previous?.categories === categories) return previous;
  const ids = new Set(), urls = new Set();
  const adultGroups = new Set((categories || []).filter(adultCategory).map(c => c.id));
  for (const [group, items] of groups || []) {
    for (const item of items) {
      if (adultGroups.has(group) || adultFlag(item.adult) || adultName(item.name)) {
        if (item.id) ids.add(item.id);
        if (item.url) urls.add(item.url);
      }
    }
  }
  const result = { groups, categories, ids, urls, adultGroups };
  entries.set(kind, result);
  return result;
}
export function protectedItems(catalog, kind, categoryId, items) {
  const flags = index(catalog, kind);
  const adultSection = flags.adultGroups.has(categoryId);
  return items.map(item => ({ ...item, adult: adultFlag(item.adult) || adultName(item.name) || flags.ids.has(item.id) || (Boolean(item.url) && flags.urls.has(item.url)) }))
    .filter(item => adultSection || !item.adult);
}
