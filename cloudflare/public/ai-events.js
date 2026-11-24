let aiCursor = null;
let aiRequest = 0;

function aiDetectionOverlay(event) {
  const { bbox, target, confidence } = event;
  if (!Array.isArray(bbox) || bbox.length !== 4 ||
      !bbox.every(value => Number.isFinite(value) && value >= 0 && value <= 1)) return null;
  const [left, top, right, bottom] = bbox;
  if (!(left < right && top < bottom)) return null;

  const overlay = document.createElement('div');
  overlay.className = 'ai-event-overlay';
  overlay.hidden = true;
  overlay.setAttribute('aria-hidden', 'true');
  if (['animal', 'person', 'vehicle'].includes(target)) overlay.classList.add('ai-event-' + target);
  overlay.style.setProperty('--bbox-left', `${left * 100}%`);
  overlay.style.setProperty('--bbox-top', `${top * 100}%`);
  overlay.style.setProperty('--bbox-width', `${(right - left) * 100}%`);
  overlay.style.setProperty('--bbox-height', `${(bottom - top) * 100}%`);

  const box = document.createElement('div');
  box.className = 'ai-event-box';
  const label = document.createElement('span');
  label.className = 'ai-event-label';
  label.textContent = `${target} ${(confidence * 100).toFixed(1)}%`;
  overlay.append(box, label);
  return overlay;
}

function aiEventImage(event) {
  const frame = document.createElement('div');
  frame.className = 'ai-event-frame';
  const image = document.createElement('img');
  image.className = 'ai-event-image';
  image.loading = 'lazy';
  image.alt = '検知時の画像';
  frame.append(image);
  const overlay = aiDetectionOverlay(event);
  if (overlay) {
    frame.append(overlay);
    image.addEventListener('load', () => { overlay.hidden = false; }, { once: true });
  }
  image.addEventListener('error', () => {
    const message = document.createElement('small');
    message.textContent = '検知画像を取得できませんでした';
    frame.replaceWith(message);
  }, { once: true });
  image.src = `/api/ai/events/${encodeURIComponent(event.eventId)}/image`;
  return frame;
}

function clearAiHistory() {
  aiRequest++;
  aiCursor = null;
  $('ai-events').replaceChildren();
  $('ai-events-more').hidden = true;
  $('ai-events-status').textContent = '';
  $('ai-events-refresh').disabled = false;
  $('ai-events-more').disabled = false;
}

async function loadAiHistory(append) {
  const request = ++aiRequest;
  const cursor = append && aiCursor ? '?' + new URLSearchParams(aiCursor) : '';
  $('ai-events-refresh').disabled = true;
  $('ai-events-more').disabled = true;
  $('ai-events-status').textContent = '検知履歴を取得中';
  try {
    const data = await api('/api/ai/events' + cursor);
    if (request !== aiRequest) return;
    if (!append) $('ai-events').replaceChildren();
    for (const event of data.events) {
      const item = document.createElement('li');
      const info = document.createElement('div');
      const timestamp = new Date(event.capturedAt).toLocaleString('ja-JP');
      info.textContent = `${timestamp} / ${event.target} / ${(event.confidence * 100).toFixed(1)}%`;
      item.append(info);
      if (event.hasImage) item.append(aiEventImage(event));
      $('ai-events').append(item);
    }
    aiCursor = data.next;
    $('ai-events-more').hidden = !aiCursor;
    $('ai-events-status').textContent = $('ai-events').children.length ? '' : '検知履歴はありません';
  } catch (error) {
    if (request !== aiRequest) return;
    if (error.status === 401) loginView(true);
    else $('ai-events-status').textContent = '検知履歴を取得できませんでした';
  } finally {
    if (request === aiRequest) {
      $('ai-events-refresh').disabled = false;
      $('ai-events-more').disabled = false;
    }
  }
}

$('ai-events-refresh').addEventListener('click', () => loadAiHistory(false));
$('ai-events-more').addEventListener('click', () => loadAiHistory(true));
