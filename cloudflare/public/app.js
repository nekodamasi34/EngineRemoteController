const $ = id => document.getElementById(id);
const stateNames = { queued:'受付待ち', claimed:'実行結果待ち', succeeded:'送信完了', failed:'失敗', cancelled:'取消', expired:'期限切れ', unknown:'結果未確認' };
let current, submitting = false, timer;
async function api(path, data) {
  const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const r = await fetch(path, { method:data === undefined ? 'GET':'POST', credentials:'same-origin', cache:'no-store', signal:controller.signal,
      headers:data === undefined ? {} : {'Content-Type':'application/json'}, body:data === undefined ? undefined : JSON.stringify(data) });
    const b = await r.json();
    if (!r.ok) { const e = new Error(b.error || '通信に失敗しました'); e.status = r.status; throw e; }
    return b;
  } finally { clearTimeout(timeout); }
}
function message(text = '') { $('message').textContent = text; }
function loginView(visible) { $('login-panel').hidden = !visible; $('dashboard').hidden = visible; $('logout').hidden = visible; }
function disableActions() { $('actions').querySelectorAll('button').forEach(b => b.disabled = true); $('all-off').disabled = true; }
function render(data) {
  current = data; const d = data.device;
  $('connection').textContent = !d.online ? 'オフライン' : d.connected ? 'Bluetooth接続中' : 'Bluetooth未接続';
  $('device-status').textContent = d.online ? d.status : '現地端末からの応答がありません';
  $('last-seen').textContent = d.lastSeen ? new Date(d.lastSeen).toLocaleTimeString('ja-JP') : '—';
  $('channels').replaceChildren(...Array.from({length:8}, (_,i) => {
    const c = document.createElement('div'), value = d.online ? d.channels[i] || 'UNKNOWN' : 'UNKNOWN';
    c.className = 'channel ' + value.toLowerCase(); c.textContent = `CH${i+1} ${value === 'UNKNOWN' ? '不明' : value}`; return c;
  }));
  const pending = data.commands.some(c => c.state === 'queued' || c.state === 'claimed');
  $('actions').replaceChildren(...data.actions.filter(a => a.key !== 'all_off').map(a => {
    const button = document.createElement('button'); button.type = 'button'; button.className = 'action' + (['ignition','engine_stop'].includes(a.key) ? ' danger' : '');
    const title = document.createElement('strong'), detail = document.createElement('small');
    title.textContent = a.title; detail.textContent = a.enabled ? `CH${a.channel} / 指定時間だけON` : '現地動作確認後に有効化';
    button.append(title,detail); button.disabled = submitting || !a.enabled || !d.online || !d.connected || !d.ready || pending;
    button.addEventListener('click', () => send(a)); return button;
  }));
  $('all-off').disabled = submitting || !d.online || !d.connected || !data.actions.find(a => a.key === 'all_off')?.enabled;
  $('setup-note').hidden = data.actions.every(a => a.enabled);
  $('empty-history').hidden = data.commands.length > 0;
  $('history').replaceChildren(...data.commands.map(c => {
    const li = document.createElement('li'), info = document.createElement('div'), time = document.createElement('small'), badge = document.createElement('span');
    info.textContent = data.actions.find(a => a.key === c.action)?.title || c.action;
    time.textContent = `${new Date(c.created_at).toLocaleString('ja-JP')} / ${c.result || (c.action === 'all_off' ? '全OFF' : c.duration_ms/1000+'秒')}`;
    info.append(time); badge.className = 'badge '+c.state; badge.textContent = stateNames[c.state] || c.state; li.append(info,badge); return li;
  }));
}
async function refresh() {
  clearTimeout(timer);
  try { render(await api('/api/status')); loginView(false); }
  catch(e) {
    disableActions(); current = null;
    if(e.status === 401) { loginView(true); return; }
    message('接続状態を確認できません。'+e.message);
  }
  timer = setTimeout(refresh, document.hidden ? 20000 : 5000);
}
async function send(action) {
  if (submitting) return;
  const seconds = Number($('seconds').value), durationMs = action.key === 'all_off' ? 0 : Math.round(seconds*1000);
  if (action.key !== 'all_off' && (!Number.isFinite(seconds) || seconds < 0.1 || seconds > 5)) return message('動作時間を0.1〜5秒で入力してください。');
  if (['ignition','engine_stop'].includes(action.key) && !confirm(`${action.title}を${durationMs/1000}秒実行しますか？`)) return;
  submitting = true; disableActions(); message('指示を送信しています…');
  try { await api('/api/commands', {id:crypto.randomUUID(),action:action.key,durationMs}); message('指示を登録しました。現地端末からの実行結果を確認してください。'); }
  catch(e) { message(e.name === 'AbortError' ? '受付結果が未確認です。最近の操作を確認してから次の指示を送ってください。' : e.message); }
  finally { submitting = false; await refresh(); }
}
$('login-form').addEventListener('submit', async e => {
  e.preventDefault(); const button = e.target.querySelector('button'); button.disabled = true;
  try { await api('/api/login', {token:$('token').value}); $('token').value = ''; message(); await refresh(); }
  catch(e) { message(e.message); } finally { button.disabled = false; }
});
$('logout').addEventListener('click', async () => {
  try { await api('/api/logout', {}); clearTimeout(timer); current = null; loginView(true); message(); }
  catch(e) { message(e.message); }
});
$('all-off').addEventListener('click', () => current && send(current.actions.find(a => a.key === 'all_off')));
document.addEventListener('visibilitychange', () => { if(!document.hidden && !$('dashboard').hidden) refresh(); });
refresh();
