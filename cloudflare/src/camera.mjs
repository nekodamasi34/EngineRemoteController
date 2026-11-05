const MAX = 128 * 1024;
const query = (env, sql, ...args) => env.DB.prepare(sql).bind(...args);
function reject(status, message) { const e = new Error(message); e.status = status; throw e; }
export async function demand(env, now = Date.now()) {
  if (!env.CAMERA_BUCKET) return { mode:'off', expiresAt:0, detailUntil:0 };
  const r = await query(env, `SELECT MAX(expires_at) AS expiry, MAX(CASE WHEN mode='detail' THEN MIN(detail_until,expires_at) ELSE 0 END) AS detail FROM camera_viewers WHERE device_id=? AND expires_at>?`, env.DEVICE_ID, now).first();
  return {mode: !r?.expiry ? 'off' : r.detail > now ? 'detail' : 'normal', expiresAt:r?.expiry || 0, detailUntil:r?.detail || 0};
}
export async function camera(request, env, path, readJson) {
  if (!env.CAMERA_BUCKET) reject(503, 'R2の設定が未完了です');
  const now = Date.now(), key = `${env.DEVICE_ID}/latest.jpg`;
  if (path === '/api/camera/watch') {
    const b = await readJson(request);
    if (!/^[a-zA-Z0-9_-]{16,80}$/.test(b.viewerId) || !['off','normal','detail'].includes(b.mode)) reject(400,'監視要求が不正です');
    await env.DB.batch([
      query(env,'DELETE FROM camera_viewers WHERE expires_at<=?',now),
      b.mode === 'off' ? query(env,'DELETE FROM camera_viewers WHERE device_id=? AND viewer_id=?',env.DEVICE_ID,b.viewerId) :
      query(env,`INSERT INTO camera_viewers VALUES(?,?,?,?,?) ON CONFLICT(device_id,viewer_id) DO UPDATE SET mode=excluded.mode,expires_at=excluded.expires_at,detail_until=CASE WHEN excluded.mode='detail' AND camera_viewers.mode='detail' THEN camera_viewers.detail_until ELSE excluded.detail_until END`,env.DEVICE_ID,b.viewerId,b.mode,now+20000,b.mode==='detail'?now+120000:0)
    ]);
    return { ...await demand(env,now), serverTime:now };
  }
  const d = await demand(env,now);
  if (path === '/api/device/camera/frame') {
    if (d.mode === 'off') reject(409,'監視は停止中です');
    if (request.headers.get('content-type') !== 'image/jpeg') reject(415,'JPEGのみ送信できます');
    if (Number(request.headers.get('content-length')) > MAX) reject(413,'画像が大きすぎます');
    const capturedAt = Number(request.headers.get('x-captured-at'));
    if (!Number.isSafeInteger(capturedAt) || capturedAt < now-15000 || capturedAt > now+5000) reject(400,'撮影時刻が古いか不正です');
    // Bound streamed bodies even without Content-Length.
    const reader = request.body?.getReader(); if (!reader) reject(400,'画像がありません');
    const chunks=[]; let size=0;
    while (true) { const {done,value}=await reader.read(); if(done) break; size+=value.length; if(size>MAX) { await reader.cancel(); reject(413,'画像が大きすぎます'); } chunks.push(value); }
    const bytes=new Uint8Array(size); let offset=0; for(const c of chunks) {bytes.set(c,offset);offset+=c.length;}
    if(size<4 || bytes[0]!==255 || bytes[1]!==216 || bytes[size-2]!==255 || bytes[size-1]!==217) reject(415,'JPEGが不正です');
    // Atomic reservation also prevents concurrent writes of the fixed R2 key.
    const reservation=await env.DB.batch([query(env,`INSERT INTO camera_frames VALUES(?,?) ON CONFLICT(device_id) DO UPDATE SET accepted_at=excluded.accepted_at WHERE camera_frames.accepted_at<=? RETURNING accepted_at`,env.DEVICE_ID,now,now-(d.mode==='detail'?1000:5000))]);
    if(!reservation[0].results.length) reject(429,'画像送信が頻繁すぎます');
    await env.CAMERA_BUCKET.put(key,bytes,{httpMetadata:{contentType:'image/jpeg',cacheControl:'no-store'},customMetadata:{capturedAt:String(capturedAt),receivedAt:String(now)}});
    return {ok:true};
  }
  const object = d.mode === 'off' ? null : await env.CAMERA_BUCKET.get(key);
  const capturedAt = Number(object?.customMetadata?.capturedAt || 0);
  const fresh = !!object && now-capturedAt<=15000 && capturedAt<=now+5000;
  if(path === '/api/camera/status') { if(object) await object.body.cancel(); return {...d,serverTime:now,capturedAt,online:fresh}; }
  if(!fresh) { if(object) await object.body.cancel(); reject(404,'映像が更新されていません'); }
  return new Response(object.body,{headers:{'Content-Type':'image/jpeg','Cache-Control':'no-store, private, max-age=0','X-Content-Type-Options':'nosniff','X-Captured-At':String(capturedAt),'X-Frame-Ttl-Ms':String(Math.min(15000,15000-(now-capturedAt)))}});
}
