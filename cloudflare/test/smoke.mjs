// Integration check against `wrangler dev`; only a simulated local device is used.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const keys = Object.fromEntries(readFileSync(new URL('../.dev.vars', import.meta.url), 'utf8').trim().split(/\r?\n/).map(x => x.split('=')));
const base = 'http://127.0.0.1:8787';
let cookie;
async function call(path, data, role = 'operator') {
  const headers = data === undefined ? {} : {'Content-Type':'application/json',Origin:base};
  if (role === 'operator' && cookie) headers.Cookie = cookie;
  if (role === 'device') headers.Authorization = 'Bearer '+keys.DEVICE_TOKEN;
  return fetch(base+path,{method:data===undefined?'GET':'POST',headers,body:data===undefined?undefined:JSON.stringify(data)});
}
const state = {connected:true,ready:true,busy:false,status:'ローカル検証用の模擬端末',channels:Array(8).fill('OFF')};
const sessionId = crypto.randomUUID();
const poll = results => call('/api/device/poll',{deviceId:'engine-1',sessionId,state,results},'device');
const page = await call('/', undefined, 'none');
assert.equal(page.status,200);
assert.match(await page.text(),/エンジン操作/);
assert.match(page.headers.get('content-security-policy'),/frame-ancestors 'none'/);
assert.equal((await call('/api/status',undefined,'none')).status,401);
const login=await call('/api/login',{token:keys.OPERATOR_TOKEN},'none');
assert.equal(login.status,200);
cookie=login.headers.get('set-cookie').split(';')[0];
assert.equal((await poll([])).status,200);
const id=crypto.randomUUID();
const posted=await call('/api/commands',{id,action:'alarm',durationMs:1000});
assert.equal(posted.status,201,await posted.text());
const reply=await (await poll([])).json();
assert.equal(reply.command.id,id);
assert.equal((await (await poll([])).json()).command,null);
assert.equal((await poll([{id,claimId:reply.command.claimId,state:'succeeded',message:'模擬端末の検証完了'}])).status,200);
const summary=await (await call('/api/status')).json();
assert.equal(summary.commands.find(c=>c.id===id).state,'succeeded');
state.connected=false; state.ready=false; state.status='ローカル検証終了 / Bluetooth未接続';
await poll([]);
console.log('ローカルWorkers/D1: Web配信、認証、指示登録、1回の取得、結果報告、状態表示を検証しました。実機操作は行っていません。');
