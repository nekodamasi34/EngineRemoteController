import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker from '../src/worker.mjs';
import { demand } from '../src/camera.mjs';
import { cleanupAiEvents } from '../src/ai-events.mjs';

class D1 {
  constructor() {
    this.sqlite = new DatabaseSync(':memory:');
    for (const name of ['0001_initial', '0002_camera', '0003_ai_events']) {
      this.sqlite.exec(readFileSync(new URL(`../migrations/${name}.sql`, import.meta.url), 'utf8'));
    }
  }
  prepare(sql) {
    const s = this.sqlite.prepare(sql);
    return { bind: (...args) => ({
      first: async () => s.get(...args) || null,
      all: async () => ({results:s.all(...args)}),
      execute: () => {
        if (/^\s*SELECT\b/i.test(sql) || /\bRETURNING\b/i.test(sql)) { const results=s.all(...args); return {results, meta:{changes:this.sqlite.prepare('SELECT changes() AS n').get().n}}; }
        const r = s.run(...args); return {results:[],meta:{changes:Number(r.changes)}};
      }
    }) };
  }
  async batch(queries) {
    this.sqlite.exec('BEGIN');
    try { const result=queries.map(q => q.execute()); this.sqlite.exec('COMMIT'); return result; }
    catch(e) { this.sqlite.exec('ROLLBACK'); throw e; }
  }
}
let env, cookie;
const ready = {connected:true,ready:true,busy:false,status:'接続中',channels:Array(8).fill('OFF')};
const sessionId = 'session-1';
beforeEach(async () => {
  env = {DB:new D1(),DEVICE_ID:'engine-1',ALLOWED_ACTIONS:'alarm,all_off',OPERATOR_TOKEN:'o'.repeat(40),DEVICE_TOKEN:'d'.repeat(40)};
  const r=await call('/api/login', {token:env.OPERATOR_TOKEN},false);
  cookie=r.headers.get('set-cookie').split(';')[0];
});
function call(path, data, auth=true, extra={}) {
  return worker.fetch(new Request('https://remote.example'+path, {method:data===undefined?'GET':'POST',headers:{...(data===undefined?{}:{'Content-Type':'application/json',Origin:'https://remote.example'}),...(auth?{Cookie:cookie}:{}),...extra},body:data===undefined?undefined:JSON.stringify(data)}),env);
}
function poll(results=[],state=ready,session=sessionId) {
  return call('/api/device/poll',{deviceId:'engine-1',sessionId:session,state,results},false,{Authorization:'Bearer '+env.DEVICE_TOKEN});
}
function command(action='alarm',id='command-1',durationMs=1000) { return call('/api/commands',{action,id,durationMs}); }
test('requires operator authentication and separates device credentials', async()=>{
  assert.equal((await call('/api/status',undefined,false)).status,401);
  assert.equal((await call('/api/status',undefined,false,{Authorization:'Bearer '+env.DEVICE_TOKEN})).status,401);
  assert.equal((await call('/api/device/poll',{},true)).status,401);
});
test('secure login cookie and cross-origin rejection',async()=>{
  const r=await call('/api/login',{token:env.OPERATOR_TOKEN},false);
  assert.match(r.headers.get('set-cookie'),/Secure; HttpOnly; SameSite=Strict/);
  assert.equal((await call('/api/commands',{id:'x',action:'alarm',durationMs:1000},true,{Origin:'https://evil.example'})).status,403);
});
test('cannot register an action while offline or with unknown channels',async()=>{
  assert.equal((await command()).status,409);
  await poll([], {...ready,channels:Array(8).fill('UNKNOWN')});
  assert.equal((await command()).status,409);
  assert.equal((await command('all_off','off',0)).status,201);
});
test('restricts actions and duration',async()=>{
  await poll();
  assert.equal((await command('ignition')).status,400);
  assert.equal((await command('alarm','x',5001)).status,400);
  assert.equal((await command('all_off','x',1000)).status,400);
});
test('claims once, then records an authenticated result',async()=>{
  await poll(); assert.equal((await command()).status,201);
  const p=await (await poll()).json(); assert.equal(p.command.id,'command-1');
  assert.equal((await (await poll()).json()).command,null);
  await poll([{id:p.command.id,claimId:p.command.claimId,state:'succeeded',message:'アラーム完了'}]);
  const data=await (await call('/api/status')).json(); assert.equal(data.commands[0].state,'succeeded');
});
test('idempotent registration and rejects conflicting reuse',async()=>{
  await poll(); await command(); assert.equal((await command()).status,200);
  assert.equal((await command('alarm','command-1',2000)).status,409);
});
test('blocks a second normal action until the first result',async()=>{
  await poll(); await command(); assert.equal((await command('alarm','second')).status,409);
  await poll(); assert.equal((await command('alarm','second')).status,409);
});
test('all-off cancels an unsent ON and takes priority',async()=>{
  await poll(); await command(); await command('all_off','off',0);
  const p=await (await poll()).json(); assert.equal(p.command.action,'all_off');
  assert.equal(env.DB.sqlite.prepare('SELECT state FROM commands WHERE id=?').get('command-1').state,'cancelled');
});
test('expired and previous-session commands are never executed',async()=>{
  await poll(); await command(); env.DB.sqlite.exec('UPDATE commands SET expires_at=0');
  assert.equal((await (await poll()).json()).command,null);
  await command('alarm','second'); assert.equal((await (await poll([],ready,'session-2')).json()).command,null);
});
test('a claimed action is never replayed after a lost reply or restart',async()=>{
  await poll(); await command(); await poll();
  await poll([],ready,'session-2');
  assert.equal(env.DB.sqlite.prepare('SELECT state FROM commands WHERE id=?').get('command-1').state,'unknown');
  assert.equal((await (await poll([],ready,'session-2')).json()).command,null);
});
test('forged result cannot finish a claimed command',async()=>{
  await poll(); await command(); await poll();
  await poll([{id:'command-1',claimId:'wrong',state:'succeeded',message:'偽の結果'}]);
  assert.equal(env.DB.sqlite.prepare('SELECT state FROM commands WHERE id=?').get('command-1').state,'claimed');
});
test('stale heartbeat refuses command registration',async()=>{
  await poll(); env.DB.sqlite.exec('UPDATE devices SET last_seen=0');
  assert.equal((await command()).status,409);
});
test('concurrent duplicate registration creates only one command',async()=>{
  await poll();
  const replies=await Promise.all([command(),command()]);
  assert.ok(replies.every(r=>r.status===200 || r.status===201));
  assert.equal(env.DB.sqlite.prepare('SELECT count(*) AS n FROM commands').get().n,1);
});
test('concurrent polls only claim a command once',async()=>{
  await poll(); await command();
  const replies=await Promise.all([poll(),poll()]);
  const bodies=await Promise.all(replies.map(r=>r.json()));
  assert.equal(bodies.filter(b=>b.command).length,1);
});
test('can claim all-off during a timed action',async()=>{
  await poll(); await command(); await poll();
  await poll([], {...ready,ready:false,busy:true,channels:['ON',...Array(7).fill('OFF')]});
  assert.equal((await command('all_off','off',0)).status,201);
  const p=await (await poll([], {...ready,ready:false,busy:true})).json();
  assert.equal(p.command.action,'all_off');
});

function bucket() {
  let saved;
  return { writes:0, async put(key,bytes,options) { this.writes++; saved={bytes,options}; },
    async get() { return saved ? {customMetadata:saved.options.customMetadata,body:new Blob([saved.bytes]).stream()} : null; } };
}
const watch=(mode='normal',viewerId='viewer-1234567890')=>call('/api/camera/watch',{viewerId,mode});
function frame(bytes=new Uint8Array([255,216,255,217]),headers={}) {
  return worker.fetch(new Request('https://remote.example/api/device/camera/frame',{method:'POST',headers:{'Content-Type':'image/jpeg',Authorization:'Bearer '+env.DEVICE_TOKEN,'X-Captured-At':String(Date.now()),...headers},body:bytes}),env);
}
test('camera OFF rejects uploads; no camera dependency for relay polling',async()=>{
  env.CAMERA_BUCKET=bucket(); assert.equal((await frame()).status,409);
  assert.equal((await (await poll()).json()).camera.mode,'off');
  env.DB.sqlite.exec('DROP TABLE camera_viewers');
  assert.equal((await poll()).status,200);
});
test('camera uses session auth, device auth and Origin checks',async()=>{
  env.CAMERA_BUCKET=bucket();
  for(const p of ['/api/camera/frame','/api/camera/status']) assert.equal((await call(p,undefined,false)).status,401);
  assert.equal((await call('/api/camera/watch',{viewerId:'viewer-1234567890',mode:'normal'},true,{Origin:'https://evil.test'})).status,403);
  assert.equal((await frame(undefined,{Authorization:'Bearer bad'})).status,401);
});
test('multiple viewers stop only after last lease expires or releases',async()=>{
  env.CAMERA_BUCKET=bucket(); await watch(); await watch('normal','viewer-abcdefghij'); await watch('off');
  assert.equal((await demand(env)).mode,'normal');
  env.DB.sqlite.exec('UPDATE camera_viewers SET expires_at=0');
  assert.equal((await demand(env)).mode,'off');assert.equal((await frame()).status,409);
});
test('detail lease cannot be extended by heartbeats, downgrades at 120 seconds',async()=>{
  env.CAMERA_BUCKET=bucket(); await watch('detail');
  const deadline=()=>env.DB.sqlite.prepare('SELECT detail_until FROM camera_viewers').get().detail_until;
  const first={detailUntil:deadline()};await watch('detail');assert.equal(deadline(),first.detailUntil);
  // A live lease at the detail deadline becomes normal.
  env.DB.sqlite.exec(`UPDATE camera_viewers SET expires_at=${first.detailUntil+20000}`);
  assert.equal((await demand(env,first.detailUntil)).mode,'normal');
});
test('rejects invalid MIME, oversized streamed requests, invalid JPEG and stale capture',async()=>{
  env.CAMERA_BUCKET=bucket();await watch();
  assert.equal((await frame(undefined,{'Content-Type':'text/plain'})).status,415);
  assert.equal((await frame(new Uint8Array(131073))).status,413);
  assert.equal((await frame(new Uint8Array([0,1,2,3]))).status,415);
  assert.equal((await frame(undefined,{'X-Captured-At':'1'})).status,400);
  assert.equal(env.CAMERA_BUCKET.writes,0);
});
test('normal and detail frame throttling, authenticated no-cache delivery and stale refusal',async()=>{
  env.CAMERA_BUCKET=bucket();await watch();assert.equal((await frame()).status,200);
  assert.equal((await frame()).status,429);
  env.DB.sqlite.exec(`UPDATE camera_frames SET accepted_at=${Date.now()-2000}`);
  assert.equal((await frame()).status,429);
  await watch('detail');assert.equal((await frame()).status,200);
  assert.equal((await frame()).status,429);
  const response=await call('/api/camera/frame');assert.equal(response.status,200);assert.match(response.headers.get('cache-control'),/no-store/);
  env.CAMERA_BUCKET.get=async()=>({customMetadata:{capturedAt:'1'},body:new Blob(['old']).stream()});
  assert.equal((await call('/api/camera/frame')).status,404);
  assert.equal((await (await call('/api/camera/status')).json()).online,false);
});
test('concurrent uploads reserve a single frame slot',async()=>{
  env.CAMERA_BUCKET=bucket();await watch('detail');
  const replies=await Promise.all([frame(),frame()]);assert.deepEqual(replies.map(r=>r.status).sort(),[200,429]);assert.equal(env.CAMERA_BUCKET.writes,1);
});
const eventJpeg = new Uint8Array([255,216,4,255,217]);
function aiEvent(overrides = {}) {
  return {eventId:crypto.randomUUID(),deviceId:'engine-1',capturedAt:Date.now(),target:'animal',confidence:0.83,bbox:[0.1,0.2,0.7,0.8],...overrides};
}
function postEvent(event, bytes = eventJpeg, token = env.DEVICE_TOKEN) {
  return worker.fetch(new Request('https://remote.example/api/device/ai/events',{
    method:'POST',
    headers:{Authorization:'Bearer '+token,'X-AI-Event':JSON.stringify(event),'Content-Type':bytes?'image/jpeg':'application/octet-stream'},
    body:bytes
  }),env);
}
function eventBucket() {
  const images = new Map();
  return env.CAMERA_BUCKET = {
    images,
    async put(key, value) { images.set(key, Uint8Array.from(value)); },
    async get(key) { return images.has(key) ? {body:images.get(key)} : null; },
    async delete(keys) { for (const key of [keys].flat()) images.delete(key); }
  };
}
test('AI uses separate authentication, private images and idempotent history', async () => {
  const bucket = eventBucket(), event = aiEvent();
  const path = '/api/ai/events/' + event.eventId + '/image';
  assert.equal((await postEvent(event,eventJpeg,env.OPERATOR_TOKEN)).status,401);
  assert.equal((await call('/api/ai/events',undefined,false)).status,401);
  assert.equal((await postEvent(event)).status,200);
  assert.equal((await postEvent(event)).status,200);
  assert.equal((await postEvent({...event,confidence:0.88})).status,409);
  assert.deepEqual([...bucket.images.keys()],[`ai-events/engine-1/${event.eventId}.jpg`]);
  assert.equal((await call(path,undefined,false)).status,401);
  const image = await call(path);
  assert.match(image.headers.get('cache-control'),/private, no-store/);
  assert.deepEqual(new Uint8Array(await image.arrayBuffer()),eventJpeg);
  assert.deepEqual((await (await call('/api/ai/events')).json()).events,[{...event,hasImage:true}]);
});
test('AI retries a pending R2 write and supports metadata-only history', async () => {
  const event = aiEvent(), bucket = eventBucket(), put = bucket.put;
  bucket.put = async () => { throw new Error('offline'); };
  assert.equal((await postEvent(event)).status,500);
  assert.equal((await (await call('/api/ai/events')).json()).events.length,0);
  bucket.put = put;
  assert.equal((await postEvent(event)).status,200);
  const noImage = aiEvent();
  assert.equal((await postEvent(noImage,null)).status,200);
  assert.equal((await call('/api/ai/events/'+noImage.eventId+'/image')).status,404);
  assert.equal(bucket.images.size,1);
});
test('competing AI uploads cannot alter the content reserved by the event ID', async () => {
  const event = aiEvent(), bucket = eventBucket();
  const alternate = new Uint8Array([255,216,9,255,217]);
  const [first, second] = await Promise.all([postEvent(event,eventJpeg),postEvent(event,alternate)]);
  assert.deepEqual([first.status,second.status].sort(),[200,409]);
  assert.deepEqual([...bucket.images.values()],[first.status === 200 ? eventJpeg : alternate]);
});
test('AI validates metadata and image size and expires D1 and R2 together', async () => {
  const bucket = eventBucket(), event = aiEvent();
  env.AI_EVENT_RETENTION_DAYS = '1';
  assert.equal((await postEvent({...event,deviceId:'other'})).status,400);
  assert.equal((await postEvent({...event,bbox:[0.7,0.2,0.1,0.8]})).status,400);
  assert.equal((await postEvent(event,new Uint8Array(131073))).status,413);
  assert.equal((await postEvent(event)).status,200);
  await cleanupAiEvents(env,event.capturedAt + 86400000);
  assert.equal(bucket.images.size,0);
  assert.equal((await (await call('/api/ai/events')).json()).events.length,0);
});
test('AI history pages deterministically when timestamps match', async () => {
  const capturedAt = Date.now();
  for (let i = 0; i < 21; i++) assert.equal((await postEvent(aiEvent({capturedAt}),null)).status,200);
  const page = await (await call('/api/ai/events')).json();
  const next = await (await call('/api/ai/events?'+new URLSearchParams(page.next))).json();
  assert.equal(page.events.length,20);
  assert.equal(next.events.length,1);
  assert.equal(next.next,null);
  assert.ok(next.events[0].eventId < page.events.at(-1).eventId);
});
