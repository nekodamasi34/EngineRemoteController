const DAY = 86_400_000;
const MAX_IMAGE = 128 * 1024;
const UUID = /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/;
const query = (env, sql, ...args) => env.DB.prepare(sql).bind(...args);

async function readImage(request, fail) {
  const type = request.headers.get('content-type');
  if (!['image/jpeg', 'application/octet-stream'].includes(type)) fail(415, 'JPEG形式を確認してください');
  const limit = type === 'image/jpeg' ? MAX_IMAGE : 0;
  if (Number(request.headers.get('content-length')) > limit) fail(413, '画像が大きすぎます');
  const reader = request.body?.getReader();
  const image = new Uint8Array(limit);
  let size = 0;
  if (reader) {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (size + value.length > limit) {
        await reader.cancel();
        fail(413, '画像が大きすぎます');
      }
      image.set(value, size);
      size += value.length;
    }
  }
  if (limit && (size < 4 || image[0] !== 255 || image[1] !== 216 || image[size - 2] !== 255 || image[size - 1] !== 217)) {
    fail(415, '不正なJPEGです');
  }
  return image.subarray(0, size);
}

export async function saveAiEvent(request, env, fail) {
  const raw = request.headers.get('x-ai-event') || '';
  if (raw.length > 1024) fail(400, '検知情報が大きすぎます');
  let data;
  try { data = JSON.parse(raw); } catch { fail(400, '検知情報が不正です'); }
  if (!data || typeof data.eventId !== 'string' || !UUID.test(data.eventId) || data.deviceId !== env.DEVICE_ID ||
      !Number.isSafeInteger(data.capturedAt) || !['animal', 'person', 'vehicle'].includes(data.target) ||
      !Number.isFinite(data.confidence) || data.confidence < 0 || data.confidence > 1 ||
      !Array.isArray(data.bbox) || data.bbox.length !== 4 || !data.bbox.every(v => Number.isFinite(v) && v >= 0 && v <= 1) ||
      data.bbox[0] >= data.bbox[2] || data.bbox[1] >= data.bbox[3]) fail(400, '検知情報が不正です');
  const { eventId, deviceId, capturedAt, target, confidence, bbox } = data;
  const now = Date.now();
  if (capturedAt > now + 300_000) fail(400, '端末の撮影時刻を確認してください');
  if (capturedAt < now - 7 * DAY) fail(410, '検知イベントの再送期限を過ぎています');
  const configured = Number(env.AI_EVENT_RETENTION_DAYS);
  const days = Number.isInteger(configured) && configured >= 1 && configured <= 365 ? configured : 30;
  const expires = capturedAt + days * DAY;
  if (expires < now + 60_000) fail(410, '検知イベントの保存期限を過ぎています');
  const image = await readImage(request, fail);
  const imageKey = image.length ? `ai-events/${deviceId}/${eventId}.jpg` : null;
  const metadata = new TextEncoder().encode(JSON.stringify([capturedAt, target, confidence, bbox]));
  const payload = new Uint8Array(metadata.length + image.length);
  payload.set(metadata);
  payload.set(image, metadata.length);
  const checksum = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', payload)), b => b.toString(16).padStart(2, '0')).join('');
  const [, lookup] = await env.DB.batch([
    query(env, `INSERT OR IGNORE INTO ai_events(device_id,id,captured_at,target,confidence,bbox,image_key,payload_sha256,ready,expires_at)
      VALUES(?,?,?,?,?,?,?,?,?,?)`, deviceId, eventId, capturedAt, target, confidence, JSON.stringify(bbox), imageKey,
      checksum, imageKey ? 0 : 1, expires),
    query(env, 'SELECT payload_sha256,ready,expires_at FROM ai_events WHERE device_id=? AND id=?', deviceId, eventId)
  ]);
  const row = lookup.results[0];
  if (row.payload_sha256 !== checksum) fail(409, '同じイベントIDで異なる検知情報は登録できません');
  if (row.expires_at < now + 60_000) fail(410, '検知イベントの保存期限を過ぎています');
  if (!row.ready) {
    if (!env.CAMERA_BUCKET) fail(503, 'R2の設定が未完了です');
    // The reservation fixes the hash before R2 I/O. Concurrent retries can only write identical bytes.
    await env.CAMERA_BUCKET.put(imageKey, image, { httpMetadata: { contentType: 'image/jpeg', cacheControl: 'private, no-store' } });
    const [ready] = await env.DB.batch([
      query(env, 'UPDATE ai_events SET ready=1 WHERE device_id=? AND id=? AND expires_at>? RETURNING id', deviceId, eventId, Date.now())
    ]);
    if (!ready.results.length) {
      await env.CAMERA_BUCKET.delete(imageKey);
      fail(410, '検知イベントの保存期限を過ぎています');
    }
  }
  return { eventId };
}

export async function listAiEvents(env, url, fail) {
  const before = url.searchParams.get('before');
  const id = url.searchParams.get('id');
  const time = before ? Number(before) : Number.MAX_SAFE_INTEGER;
  if ((before || id) && (!Number.isSafeInteger(time) || time <= 0 || !UUID.test(id || '') || !before)) fail(400, '履歴カーソルが不正です');
  const rows = (await query(env, `SELECT id,captured_at,target,confidence,bbox,image_key FROM ai_events
    WHERE device_id=? AND ready=1 AND expires_at>? AND (captured_at < ? OR (captured_at = ? AND id < ?))
    ORDER BY captured_at DESC,id DESC LIMIT 21`, env.DEVICE_ID, Date.now(), time, time, id || '').all()).results;
  const page = rows.slice(0, 20);
  return {
    events: page.map(r => ({
      eventId: r.id, deviceId: env.DEVICE_ID, capturedAt: r.captured_at, target: r.target,
      confidence: r.confidence, bbox: JSON.parse(r.bbox), hasImage: !!r.image_key
    })),
    next: rows.length > 20 ? { before: page.at(-1).captured_at, id: page.at(-1).id } : null
  };
}

export async function getAiImage(env, id, fail) {
  if (!UUID.test(id)) fail(404, '検知画像がありません');
  const r = await query(env, 'SELECT image_key FROM ai_events WHERE device_id=? AND id=? AND ready=1 AND expires_at>?',
    env.DEVICE_ID, id, Date.now()).first();
  const object = r?.image_key && await env.CAMERA_BUCKET?.get(r.image_key);
  if (!object) fail(404, '検知画像がありません');
  return new Response(object.body, { headers: {
    'Content-Type': 'image/jpeg', 'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff'
  } });
}

export async function cleanupAiEvents(env, now = Date.now()) {
  const rows = (await query(env, 'SELECT device_id,id,image_key FROM ai_events WHERE expires_at<=? ORDER BY expires_at LIMIT 50', now).all()).results;
  const images = rows.map(row => row.image_key).filter(Boolean);
  if (images.length) await env.CAMERA_BUCKET.delete(images);
  if (rows.length) await env.DB.batch(rows.map(row =>
    query(env, 'DELETE FROM ai_events WHERE device_id=? AND id=? AND expires_at<=?', row.device_id, row.id, now)));
}
