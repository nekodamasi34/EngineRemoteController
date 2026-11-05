import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker from '../src/worker.mjs';

class D1 {
  constructor() { this.sqlite = new DatabaseSync(':memory:'); this.sqlite.exec(readFileSync(new URL('../migrations/0001_initial.sql', import.meta.url),'utf8')); }
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
