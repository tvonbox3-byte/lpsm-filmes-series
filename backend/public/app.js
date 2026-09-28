const $ = id => document.getElementById(id);
let token = sessionStorage.getItem('vodToken') || '';
let state = { settings: { defaultSourceUrl: '' }, clients: [], pendingDevices: [] };

async function api(path, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 90000);
  try {
    const r = await fetch(path, {
      ...options,
      signal: options.signal || controller.signal,
      headers: {
        'content-type':'application/json',
        ...(token ? { authorization:`Bearer ${token}` } : {}),
        ...(options.headers || {})
      }
    });
    const raw = await r.text();
    let data = {};
    try { data = raw ? JSON.parse(raw) : {}; } catch { data = { error: raw || `Erro ${r.status}` }; }
    if (!r.ok) throw new Error(data.error || `Erro ${r.status}`);
    return data;
  } finally {
    clearTimeout(timer);
  }
}

function showDashboard(on) {
  $('loginCard').classList.toggle('hidden', on);
  $('dashboard').classList.toggle('hidden', !on);
  $('logout').classList.toggle('hidden', !on);
}

$('login').onclick = async () => {
  try {
    const data = await api('/api/admin/login', { method:'POST', body:JSON.stringify({ username:$('user').value, password:$('pass').value }) });
    token = data.token;
    sessionStorage.setItem('vodToken', token);
    $('loginMsg').textContent='';
    showDashboard(true);
    await refresh();
  } catch(e) { $('loginMsg').textContent = e.message; }
};
$('logout').onclick = () => { token=''; sessionStorage.removeItem('vodToken'); showDashboard(false); };
$('refresh').onclick = refresh;

function esc(s='') { return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function fmtDate(v) { if (!v) return 'Sem validade'; const d=new Date(v); return isNaN(d) ? v : d.toLocaleString('pt-BR'); }
function fmtDateTime(v) { if (!v) return ''; const d=new Date(v); return isNaN(d) ? v : d.toLocaleString('pt-BR'); }
function active(c) { return c.enabled !== false && (!c.expiresAt || new Date(c.expiresAt) > new Date()); }
function sourceLabel(c) { return c.sourceUrl ? 'M3U específica' : (state.settings?.defaultSourceUrl ? 'M3U principal' : 'Sem lista'); }

$('saveSource').onclick = saveSource;
async function saveSource() {
  const defaultSourceUrl = $('defaultSourceUrl').value.trim();
  const r = await api('/api/admin/settings', { method:'PUT', body:JSON.stringify({ defaultSourceUrl }) });
  state.settings = r.settings || { defaultSourceUrl };
  $('sourceMsg').textContent = defaultSourceUrl ? 'Lista M3U principal salva.' : 'Lista principal removida.';
  await refresh();
}

$('testSource').onclick = async () => {
  try {
    $('sourceMsg').textContent = 'Salvando e analisando a lista M3U...';
    await saveSource();
    const r = await api('/api/admin/catalog/test', { method:'POST', body:JSON.stringify({ sourceUrl:$('defaultSourceUrl').value.trim() }) });
    const s = r.stats || {};
    $('catalogStats').innerHTML = `
      <strong>Catálogo reconhecido</strong>
      <span>${s.movies || 0} filmes</span>
      <span>${s.movieCategories || 0} categorias de filmes</span>
      <span>${s.series || 0} séries</span>
      <span>${s.episodes || 0} episódios</span>
      <span>${s.seriesCategories || 0} categorias de séries</span>
    `;
    $('catalogStats').classList.remove('hidden');
    $('sourceMsg').textContent = s.capped ? 'Lista carregada até o limite de segurança do servidor.' : 'Lista M3U lida com sucesso.';
  } catch(e) { $('sourceMsg').textContent = e.message; }
};

$('clearSource').onclick = async () => {
  if (!confirm('Remover a lista M3U principal?')) return;
  $('defaultSourceUrl').value = '';
  await saveSource();
  $('catalogStats').classList.add('hidden');
};

async function refresh() {
  try {
    state = await api('/api/admin/state');
    const def = state.settings?.defaultSourceUrl || '';
    $('defaultSourceUrl').value = def;
    $('sourceBadge').textContent = def ? 'M3U configurada' : 'Não configurada';
    $('sourceBadge').classList.toggle('ok', Boolean(def));
    $('total').textContent = state.clients.length;
    $('online').textContent = state.clients.filter(c => c.online).length;
    $('active').textContent = state.clients.filter(active).length;
    $('pending').textContent = (state.pendingDevices || []).length;
    $('pendingCount').textContent = `${(state.pendingDevices || []).length} aguardando`;
    renderPending();
    $('rows').innerHTML = state.clients.map(c => `
      <tr>
        <td><span class="status"><span class="dot ${c.online?'on':''}"></span>${c.online?'ONLINE':'offline'}</span></td>
        <td>${esc(c.name || 'Sem nome')}<br><small>${c.enabled === false ? 'PAUSADO' : active(c) ? 'Ativo' : 'Expirado'}</small></td>
        <td><code>${esc(c.mac)}</code></td>
        <td>${esc(fmtDate(c.expiresAt))}</td>
        <td><span class="badge ${c.sourceUrl || def ? 'ok' : ''}">${esc(sourceLabel(c))}</span></td>
        <td><div class="rowActions"><button onclick="editClient('${c.id}')">Editar</button><button class="danger" onclick="deleteClient('${c.id}')">Excluir</button></div></td>
      </tr>`).join('');
  } catch(e) {
    if (/autorizado/i.test(e.message)) { token=''; sessionStorage.removeItem('vodToken'); showDashboard(false); }
  }
}

function renderPending() {
  const list = state.pendingDevices || [];
  $('pendingList').innerHTML = list.length ? list.map(d => `
    <article class="pendingItem">
      <div>
        <strong>Novo aparelho</strong>
        <code>${esc(d.mac)}</code>
        <small>Detectado: ${esc(fmtDateTime(d.firstSeenAt))}</small>
        <small>Último contato: ${esc(fmtDateTime(d.lastSeenAt))}</small>
      </div>
      <div class="rowActions">
        <button onclick="activatePending('${esc(d.mac)}')">Ativar</button>
        <button class="ghost" onclick="removePending('${encodeURIComponent(d.mac)}')">Ocultar</button>
      </div>
    </article>`).join('') : '<p class="hint">Nenhum aparelho aguardando. Ao abrir o app em um aparelho novo, o MAC aparecerá aqui automaticamente.</p>';
}

window.activatePending = mac => {
  clearForm();
  $('mac').value = mac;
  const exp = new Date(); exp.setFullYear(exp.getFullYear()+1);
  $('expiresAt').value = exp.toISOString().slice(0,16);
  $('formTitle').textContent = 'Ativar aparelho pendente';
  $('name').focus();
  window.scrollTo({ top: document.querySelector('.grid2').offsetTop - 20, behavior:'smooth' });
};
window.removePending = async mac => { await api(`/api/admin/pending/${mac}`, { method:'DELETE' }); await refresh(); };

window.editClient = id => {
  const c = state.clients.find(x => x.id === id); if (!c) return;
  $('clientId').value=c.id;
  $('name').value=c.name||'';
  $('mac').value=c.mac||'';
  $('sourceUrl').value=c.sourceUrl||'';
  $('enabled').checked=c.enabled!==false;
  $('expiresAt').value = c.expiresAt ? new Date(c.expiresAt).toISOString().slice(0,16) : '';
  $('formTitle').textContent='Editar aparelho';
  $('cancel').classList.remove('hidden');
  window.scrollTo({top:document.querySelector('.grid2').offsetTop-20,behavior:'smooth'});
};
window.deleteClient = async id => { if (!confirm('Excluir este aparelho?')) return; await api(`/api/admin/clients/${id}`, {method:'DELETE'}); await refresh(); };
$('cancel').onclick = clearForm;
function clearForm(){ $('clientId').value=''; $('name').value=''; $('mac').value=''; $('sourceUrl').value=''; $('expiresAt').value=''; $('enabled').checked=true; $('formTitle').textContent='Cadastrar aparelho'; $('cancel').classList.add('hidden'); $('formMsg').textContent=''; }
$('save').onclick = async () => {
  const id=$('clientId').value;
  const payload={
    name:$('name').value,
    mac:$('mac').value,
    sourceUrl:$('sourceUrl').value,
    enabled:$('enabled').checked,
    expiresAt:$('expiresAt').value ? new Date($('expiresAt').value).toISOString() : ''
  };
  try {
    await api(id ? `/api/admin/clients/${id}` : '/api/admin/clients', { method:id?'PUT':'POST', body:JSON.stringify(payload) });
    $('formMsg').textContent='Salvo.';
    clearForm();
    await refresh();
  } catch(e){ $('formMsg').textContent=e.message; }
};

if (token) { showDashboard(true); refresh(); } else showDashboard(false);
setInterval(() => { if (token && document.visibilityState === 'visible') refresh(); }, 5000);
