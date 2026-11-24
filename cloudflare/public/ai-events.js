let aiCursor = null;
let aiRequest = 0;

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
      if (event.hasImage) {
        const image = document.createElement('img');
        image.className = 'ai-event-image';
        image.loading = 'lazy';
        image.alt = '検知時の画像';
        image.src = `/api/ai/events/${encodeURIComponent(event.eventId)}/image`;
        item.append(image);
      }
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
