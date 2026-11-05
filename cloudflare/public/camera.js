// Per-page lease: no persistent token, no extra requests while OFF.
const viewerId=crypto.randomUUID();
let cameraMode='off', cameraTimer, leaseTimer, cameraAbort, cameraUrl, imageExpiry;
function clearImage() {
  clearTimeout(imageExpiry); $('camera-image').hidden=true; $('camera-image').removeAttribute('src');
  if(cameraUrl) URL.revokeObjectURL(cameraUrl); cameraUrl=null;
}
function stopCamera() {
  cameraMode='off'; clearTimeout(cameraTimer); clearInterval(leaseTimer); cameraAbort?.abort(); clearImage();
  $('camera-status').textContent='監視OFF';
}
async function cameraLease() {
  const r=await api('/api/camera/watch',{viewerId,mode:cameraMode});
  if(cameraMode==='detail' && r.detailUntil<=r.serverTime) cameraMode='normal';
}
async function cameraFrame() {
  clearTimeout(cameraTimer);
  if(cameraMode==='off' || document.hidden || $('dashboard').hidden || cameraAbort) return;
  const controller=new AbortController(); cameraAbort=controller;
  const timeout=setTimeout(()=>controller.abort(),7000);
  try {
    const r=await fetch('/api/camera/frame',{cache:'no-store',credentials:'same-origin',signal:controller.signal});
    if(!r.ok) throw new Error('映像が更新されていません / カメラオフライン');
    const captured=Number(r.headers.get('X-Captured-At'));
    const received=performance.now(), ttl=Number(r.headers.get('X-Frame-Ttl-Ms'));
    // Server validates capture age. Expire displayed image even when further requests hang.
    const blob=await r.blob();
    if(controller.signal.aborted || cameraMode==='off' || document.hidden) return;
    clearImage(); cameraUrl=URL.createObjectURL(blob); $('camera-image').src=cameraUrl; $('camera-image').hidden=false;
    $('camera-time').textContent=new Date(captured).toLocaleString('ja-JP');
    $('camera-status').textContent='カメラオンライン / '+(cameraMode==='detail'?'詳細監視':'通常監視');
    imageExpiry=setTimeout(()=>{clearImage();$('camera-status').textContent='映像が更新されていません';},Math.max(0,Math.min(15000,ttl)-(performance.now()-received)));
  } catch(e) { clearImage(); if(cameraMode!=='off') $('camera-status').textContent='映像が更新されていません / 画像更新失敗'; }
  finally {
    clearTimeout(timeout); if(cameraAbort===controller) cameraAbort=null;
    if(cameraMode!=='off' && !document.hidden) cameraTimer=setTimeout(cameraFrame,cameraMode==='detail'?1000:5000);
  }
}
document.querySelectorAll('[data-camera]').forEach(button=>button.addEventListener('click',async()=>{
  clearTimeout(cameraTimer);clearInterval(leaseTimer);cameraAbort?.abort();
  cameraMode=button.dataset.camera;
  try {
    await cameraLease();
    if(cameraMode==='off') stopCamera();
    else { leaseTimer=setInterval(()=>cameraLease().catch(()=>{stopCamera();$('camera-status').textContent='監視要求を更新できません';}),8000); cameraFrame(); }
  } catch(e) { stopCamera();$('camera-status').textContent=e.message; }
}));
$('camera-refresh').addEventListener('click',cameraFrame);
$('camera-image').addEventListener('click',()=>{ $('camera-image').requestFullscreen?.().catch(()=>{}); });
function releaseCamera() {
  if(cameraMode==='off') return;
  // Origin is supplied by the browser; expiry remains the fallback when delivery fails.
  fetch('/api/camera/watch',{method:'POST',credentials:'same-origin',keepalive:true,headers:{'Content-Type':'application/json'},body:JSON.stringify({viewerId,mode:'off'})}).catch(()=>{});
}
let resumeCamera='off';
document.addEventListener('visibilitychange',async()=>{
  if(document.hidden) { resumeCamera=cameraMode==='detail'?'normal':cameraMode;releaseCamera();stopCamera(); }
  else if(resumeCamera!=='off' && !$('dashboard').hidden) {
    cameraMode=resumeCamera;resumeCamera='off';
    try {await cameraLease();leaseTimer=setInterval(()=>cameraLease().catch(stopCamera),8000);cameraFrame();} catch(e){stopCamera();}
  }
});
window.addEventListener('pagehide',()=>{releaseCamera();stopCamera();});
