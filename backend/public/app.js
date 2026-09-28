const $ = id => document.getElementById(id);
let token = sessionStorage.getItem('vodToken') || '';
let state = { clients: [] };

async function api(path, options = {}) {
  const r = await fetch(path, {
    ...options,
    headers: { 'content-type':'application/json', ...(token ? { authorization:`Bearer ${token}` } : {}), ...(options.headers || {}) }
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || `Erro ${r.status}`);
  return data;
}

function showDashboard(on) {
  $('loginCard').classList.toggle('hidden', on);
  $('dashboard').classList.toggle('hidden', !on);
  $('logout').classList.toggle('hidden', !on);
}

$('login').onclick = async () => {
  try {
    const data = await api('/api/admin/login', { method:'POST', body:JSON.stringify({ username:$('user').value, password:$('pass').value }) });
    token = data.token; sessionStorage.setItem('vodToken', token); $('loginMsg').textContent=''; showDashboard(true); await refresh();
  } catch(e) { $('loginMsg').textContent = e.message; }
};
$('logout').onclick = () => { token=''; sessionStorage.removeItem('vodToken'); showDashboard(false); };
$('refresh').onclick = refresh;

function escapeHtml(s='') { return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function fmtDate(v) { if (!v) return 'Sem validade'; const d=new Date(v); return isNaN(d) ? v : d.toLocaleString('pt-BR'); }
function active(c) { return c.enabled !== false && (!c.expiresAt || new Date(c.expiresAt) > new Date()); }

async function refresh() {
  try {
    state = await api('/api/admin/state');
    $('total').textContent = state.clients.length;
    $('online').textContent = state.clients.filter(c => c.online).length;
    $('active').textContent = state.clients.filter(active).length;
    $('rows').innerHTML = state.clients.map(c => `
      <tr>
        <td><span class="status"><span class="dot ${c.online?'on':''}"></span>${c.online?'ONLINE':'offline'}</span></td>
        <td>${escapeHtml(c.name || 'Sem nome')}<br><small>${c.enabled === false ? 'PAUSADO' : active(c) ? 'Ativo' : 'Expirado'}</small></td>
        <td><code>${escapeHtml(c.mac)}</code></td>
        <td>${escapeHtml(fmtDate(c.expiresAt))}</td>
        <td><div class="source" title="${escapeHtml(c.sourceUrl || '')}">${escapeHtml(c.sourceUrl || 'Não configurada')}</div></td>
        <td><div class="rowActions"><button onclick="editClient('${c.id}')">Editar</button><button class="danger" onclick="deleteClient('${c.id}')">Excluir</button></div></td>
      </tr>`).join('');
  } catch(e) {
    if (/autorizado/i.test(e.message)) { token=''; sessionStorage.removeItem('vodToken'); showDashboard(false); }
  }
}

window.editClient = id => {
  const c = state.clients.find(x => x.id === id); if (!c) return;
  $('clientId').value=c.id; $('name').value=c.name||''; $('mac').value=c.mac||''; $('sourceUrl').value=c.sourceUrl||''; $('enabled').checked=c.enabled!==false;
  $('expiresAt').value = c.expiresAt ? new Date(c.expiresAt).toISOString().slice(0,16) : '';
  $('formTitle').textContent='Editar aparelho'; $('cancel').classList.remove('hidden'); window.scrollTo({top:0,behavior:'smooth'});
};
window.deleteClient = async id => { if (!confirm('Excluir este aparelho?')) return; await api(`/api/admin/clients/${id}`, {method:'DELETE'}); await refresh(); };
$('cancel').onclick = clearForm;
function clearForm(){ $('clientId').value=''; $('name').value=''; $('mac').value=''; $('sourceUrl').value=''; $('expiresAt').value=''; $('enabled').checked=true; $('formTitle').textContent='Cadastrar aparelho'; $('cancel').classList.add('hidden'); $('formMsg').textContent=''; }
$('save').onclick = async () => {
  const id=$('clientId').value;
  const payload={ name:$('name').value, mac:$('mac').value, sourceUrl:$('sourceUrl').value, enabled:$('enabled').checked, expiresAt:$('expiresAt').value ? new Date($('expiresAt').value).toISOString() : '' };
  try { await api(id ? `/api/admin/clients/${id}` : '/api/admin/clients', { method:id?'PUT':'POST', body:JSON.stringify(payload) }); $('formMsg').textContent='Salvo.'; clearForm(); await refresh(); }
  catch(e){ $('formMsg').textContent=e.message; }
};

if (token) { showDashboard(true); refresh(); } else showDashboard(false);
setInterval(() => { if (token) refresh(); }, 8000);
