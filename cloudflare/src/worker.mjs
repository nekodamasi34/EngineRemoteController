import { demand, camera } from './camera.mjs';
const encoder = new TextEncoder();
const ONLINE_MS = 20_000;
const COMMAND_TTL_MS = 15_000;
const CLAIM_TIMEOUT_MS = 30_000;
const COOKIE = '__Host-engine_session';
export const ACTIONS = {
  alarm: { title: 'アラーム', channel: 3 },
  ignition: { title: 'イグニッション', channel: 1 },
  engine_stop: { title: 'エンジン切', channel: 2 },
  choke_a: { title: 'チョークA', channel: 5 },
  choke_b: { title: 'チョークB', channel: 6 },
  all_off: { title: '全リレーOFF', channel: null }
};
class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
function fail(status, message) { throw new ApiError(status, message); }
function allowed(env) {
  return (env.ALLOWED_ACTIONS || 'alarm,all_off').split(',').map(s => s.trim()).filter(a => Object.hasOwn(ACTIONS, a));
}
function json(data, status = 200, extra = {}) {
  return new Response(JSON.stringify(data), { status, headers: {
    'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', ...extra
  }});
}
async function body(request) {
  if (!request.headers.get('content-type')?.startsWith('application/json')) fail(415, 'JSONで送信してください');
  if (Number(request.headers.get('content-length')) > 8192) fail(413, 'データが大きすぎます');
  const raw = await request.text();
  if (raw.length > 8192) fail(413, 'データが大きすぎます');
  try {
    const value = JSON.parse(raw);
    if (!value || typeof value !== 'object' || Array.isArray(value)) fail(400, '不正なデータです');
    return value;
  } catch (e) { if (e instanceof ApiError) throw e; fail(400, 'JSONが不正です'); }
}
function id(value) { return typeof value === 'string' && /^[a-zA-Z0-9_-]{1,80}$/.test(value); }
async function equalSecret(a, b) {
  const [x, y] = await Promise.all([a, b].map(s => crypto.subtle.digest('SHA-256', encoder.encode(s))));
  const aa = new Uint8Array(x), bb = new Uint8Array(y);
  let diff = 0; for (let i = 0; i < aa.length; i++) diff |= aa[i] ^ bb[i];
  return diff === 0;
}
async function signature(payload, secret) {
  const key = await crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const signed = await crypto.subtle.sign('HMAC', key, encoder.encode(payload));
  return Array.from(new Uint8Array(signed), x => x.toString(16).padStart(2, '0')).join('');
}
function origin(request) {
  if (request.headers.get('origin') !== new URL(request.url).origin) fail(403, '同じサイトから操作してください');
}
async function operator(request, env) {
  const cookies = request.headers.get('cookie') || '';
  const value = cookies.split(';').map(x => x.trim()).find(x => x.startsWith(COOKIE + '='))?.slice(COOKIE.length + 1);
  if (!value) fail(401, 'ログインしてください');
  const parts = value.split('.');
  if (parts.length !== 3 || !/^\d+$/.test(parts[0]) || Number(parts[0]) <= Date.now() || !id(parts[1])) fail(401, 'ログインの有効期限が切れました');
  if (!await equalSecret(parts[2], await signature(parts.slice(0, 2).join('.'), env.OPERATOR_TOKEN))) fail(401, 'ログインしてください');
  if (request.method !== 'GET') origin(request);
}
async function device(request, env) {
  const token = request.headers.get('authorization') || '';
  if (!token.startsWith('Bearer ') || !await equalSecret(token.slice(7), env.DEVICE_TOKEN)) fail(401, '端末の認証に失敗しました');
}
function statement(env, sql, ...args) { return env.DB.prepare(sql).bind(...args); }

async function status(env) {
  const now = Date.now();
  const d = await statement(env, 'SELECT * FROM devices WHERE id = ?', env.DEVICE_ID).first();
  const rows = await statement(env, 'SELECT id,action,duration_ms,created_at,expires_at,state,claimed_at,result FROM commands WHERE device_id=? ORDER BY created_at DESC LIMIT 20', env.DEVICE_ID).all();
  return json({ serverTime: now, device: d ? {
    id: d.id, online: now - d.last_seen <= ONLINE_MS, lastSeen: d.last_seen,
    connected: !!d.connected, ready: !!d.ready, busy: !!d.busy,
    status: d.status, channels: JSON.parse(d.channels)
  } : { id: env.DEVICE_ID, online: false, connected: false, ready: false, busy: false, channels: [] },
  actions: Object.entries(ACTIONS).map(([key, value]) => ({ key, ...value, enabled: allowed(env).includes(key) })),
  commands: rows.results.map(c => ({ ...c,
    state: c.state === 'queued' && c.expires_at <= now ? 'expired' : c.state === 'claimed' && c.claimed_at + CLAIM_TIMEOUT_MS <= now ? 'unknown' : c.state
  })) });
}

async function createCommand(request, env) {
  const b = await body(request);
  if (!id(b.id) || !allowed(env).includes(b.action)) fail(400, '操作または指示IDが不正・無効です');
  if (!Number.isInteger(b.durationMs) || (b.action !== 'all_off' && (b.durationMs < 100 || b.durationMs > 5000)) || (b.action === 'all_off' && b.durationMs !== 0)) fail(400, '操作時間は0.1〜5秒です');
  const existing = await statement(env, 'SELECT * FROM commands WHERE id=?', b.id).first();
  if (existing) {
    if (existing.action !== b.action || existing.duration_ms !== b.durationMs || existing.device_id !== env.DEVICE_ID) fail(409, '同じIDで異なる指示は送れません');
    return json({ id: existing.id, state: existing.state });
  }
  const now = Date.now();
  const queries = [
    statement(env, "UPDATE commands SET state='expired' WHERE device_id=? AND state='queued' AND expires_at<=?", env.DEVICE_ID, now),
    statement(env, "UPDATE commands SET state='unknown',result='結果未確認' WHERE device_id=? AND state='claimed' AND claimed_at<=?", env.DEVICE_ID, now - CLAIM_TIMEOUT_MS)
  ];
  if (b.action === 'all_off') queries.push(statement(env, "UPDATE commands SET state='cancelled',result='全OFF指示により取消' WHERE device_id=? AND state='queued' AND id<>?", env.DEVICE_ID, b.id));
  queries.push(statement(env, `INSERT OR IGNORE INTO commands(id,device_id,session_id,action,duration_ms,created_at,expires_at,state)
    SELECT ?,id,session_id,?,?,?,?, 'queued' FROM devices
    WHERE id=? AND last_seen>=? AND connected=1
    ${b.action === 'all_off' ? '' : "AND ready=1 AND NOT EXISTS(SELECT 1 FROM commands WHERE device_id=? AND state IN ('queued','claimed'))"}`,
    b.id, b.action, b.durationMs, now, now + COMMAND_TTL_MS, env.DEVICE_ID, now - ONLINE_MS,
    ...(b.action === 'all_off' ? [] : [env.DEVICE_ID])));
  const results = await env.DB.batch(queries);
  if (!results.at(-1).meta.changes) {
    const duplicate = await statement(env, 'SELECT action,duration_ms,state FROM commands WHERE id=? AND device_id=?', b.id, env.DEVICE_ID).first();
    if (duplicate && duplicate.action === b.action && duplicate.duration_ms === b.durationMs) return json({id:b.id,state:duplicate.state});
    fail(409, '現地端末が未接続、操作中、または状態不明です');
  }
  return json({ id: b.id, state: 'queued' }, 201);
}

async function poll(request, env) {
  const b = await body(request), s = b.state;
  if (b.deviceId !== env.DEVICE_ID || !id(b.sessionId) || !s ||
      !['connected', 'ready', 'busy'].every(k => typeof s[k] === 'boolean') ||
      typeof s.status !== 'string' || s.status.length > 160 ||
      !Array.isArray(s.channels) || s.channels.length !== 8 || !s.channels.every(v => ['ON','OFF','UNKNOWN'].includes(v))) fail(400, '端末の状態が不正です');
  const reports = b.results || [];
  if (!Array.isArray(reports) || reports.length > 10 || reports.some(r => !id(r.id) || !id(r.claimId) || !['succeeded','failed','cancelled','unknown'].includes(r.state) || typeof r.message !== 'string' || r.message.length > 160)) fail(400, '実行結果が不正です');
  const now = Date.now(), claimId = crypto.randomUUID();
  const queries = [statement(env, `INSERT INTO devices(id,session_id,last_seen,connected,ready,busy,status,channels) VALUES(?,?,?,?,?,?,?,?)
    ON CONFLICT(id) DO UPDATE SET session_id=excluded.session_id,last_seen=excluded.last_seen,connected=excluded.connected,ready=excluded.ready,busy=excluded.busy,status=excluded.status,channels=excluded.channels`,
    env.DEVICE_ID, b.sessionId, now, +s.connected, +(s.ready && s.connected && !s.busy && s.channels.every(c => c === 'OFF')), +s.busy, s.status, JSON.stringify(s.channels)),
    statement(env, "UPDATE commands SET state='cancelled',result='端末の待受が再開されたため取消' WHERE device_id=? AND state='queued' AND session_id<>?", env.DEVICE_ID, b.sessionId),
    statement(env, "UPDATE commands SET state='unknown',result='端末再起動または結果未確認' WHERE device_id=? AND state='claimed' AND (session_id<>? OR claimed_at<=?)", env.DEVICE_ID, b.sessionId, now - CLAIM_TIMEOUT_MS),
    statement(env, "UPDATE commands SET state='expired' WHERE device_id=? AND state='queued' AND expires_at<=?", env.DEVICE_ID, now)
  ];
  for (const r of reports) queries.push(statement(env, `UPDATE commands SET state=?,result=?,finished_at=? WHERE id=? AND device_id=? AND session_id=? AND claim_id=? AND state IN ('claimed','unknown')`, r.state, r.message, now, r.id, env.DEVICE_ID, b.sessionId, r.claimId));
  // UPDATE...RETURNING claims a command once, including concurrent requests and a lost HTTP response.
  queries.push(statement(env, `UPDATE commands SET state='claimed',claim_id=?,claimed_at=? WHERE id=(
    SELECT id FROM commands WHERE device_id=? AND session_id=? AND state='queued' AND expires_at>?
      AND (?=1 AND (action='all_off' OR ?=1))
      ORDER BY CASE WHEN action='all_off' THEN 0 ELSE 1 END,created_at LIMIT 1
    ) AND state='queued' RETURNING id,action,duration_ms,expires_at,claim_id`,
    claimId, now, env.DEVICE_ID, b.sessionId, now, +s.connected,
    +(s.ready && !s.busy && s.channels.every(c => c === 'OFF'))));
  const results = await env.DB.batch(queries);
  const c = results.at(-1).results?.[0];
  return json({ serverTime: now, pollAfterMs: 5000, command: c ? {
    id: c.id, action: c.action, durationMs: c.duration_ms, expiresAt: c.expires_at, claimId: c.claim_id
  } : null, camera: await demand(env).catch(() => ({mode:"off",expiresAt:0,detailUntil:0})) });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith('/api/')) return env.ASSETS.fetch(request);
    try {
      if (url.pathname === '/api/health' && request.method === 'GET') return json({ ok: true });
      if (!env.DB || !id(env.DEVICE_ID) || !env.OPERATOR_TOKEN || !env.DEVICE_TOKEN ||
          env.OPERATOR_TOKEN.length < 32 || env.DEVICE_TOKEN.length < 32 || env.OPERATOR_TOKEN === env.DEVICE_TOKEN) fail(503, 'サーバーの接続設定が未完了です');
      if (url.pathname === '/api/login' && request.method === 'POST') {
        origin(request);
        const b = await body(request);
        if (typeof b.token !== 'string' || !await equalSecret(b.token, env.OPERATOR_TOKEN)) fail(401, '操作キーが違います');
        const payload = `${Date.now() + 8 * 60 * 60 * 1000}.${crypto.randomUUID()}`;
        const cookie = `${COOKIE}=${payload}.${await signature(payload, env.OPERATOR_TOKEN)}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=28800`;
        return json({ ok: true }, 200, { 'Set-Cookie': cookie });
      }
      if (url.pathname === '/api/device/poll' && request.method === 'POST') { await device(request, env); return await poll(request, env); }
      if (url.pathname === '/api/device/camera/frame' && request.method === 'POST') {
        await device(request, env);
        try { return json(await camera(request,env,url.pathname,body)); } catch(e) { if(e.status) return json({error:e.message},e.status); throw e; }
      }
      await operator(request, env);
      if ((url.pathname === '/api/camera/watch' && request.method === 'POST') || (['/api/camera/frame','/api/camera/status'].includes(url.pathname) && request.method === 'GET')) {
        try { const r=await camera(request,env,url.pathname,body); return r instanceof Response ? r : json(r); } catch(e) { if(e.status) return json({error:e.message},e.status); throw e; }
      }
      if (url.pathname === '/api/logout' && request.method === 'POST') return json({ ok: true }, 200, { 'Set-Cookie': `${COOKIE}=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0` });
      if (url.pathname === '/api/status' && request.method === 'GET') return await status(env);
      if (url.pathname === '/api/commands' && request.method === 'POST') return await createCommand(request, env);
      return json({ error: 'APIが見つかりません' }, 404);
    } catch (e) {
      if (!(e instanceof ApiError)) console.error('API処理失敗');
      return json({ error: e instanceof ApiError ? e.message : '処理に失敗しました。状態を再確認してください' }, e instanceof ApiError ? e.status : 500);
    }
  }
};
