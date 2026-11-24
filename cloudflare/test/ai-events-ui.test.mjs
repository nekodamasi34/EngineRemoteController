import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { runInNewContext } from 'node:vm';

const script = readFileSync(new URL('../public/ai-events.js', import.meta.url), 'utf8');
const detection = {
  eventId: 'one',
  target: 'animal',
  confidence: 0.925,
  bbox: [0.125, 0.25, 0.75, 0.875],
  capturedAt: 123456,
  hasImage: true
};

class Element {
  children = [];
  handlers = new Map();
  style = new Map();
  classList = new Set();

  constructor(tag) {
    this.tag = tag;
    this.style.setProperty = this.style.set.bind(this.style);
  }

  append(...children) {
    for (const child of children) child.parent = this;
    this.children.push(...children);
  }

  replaceChildren() { this.children = []; }
  replaceWith(node) { this.parent.children[this.parent.children.indexOf(this)] = node; }
  addEventListener(name, handler) { this.handlers.set(name, handler); }
  setAttribute() {}
  fire(name) { return this.handlers.get(name)(); }
}

function historyHarness(api) {
  const nodes = new Map(['ai-events', 'ai-events-more', 'ai-events-status', 'ai-events-refresh']
    .map(id => [id, new Element(id)]));
  const logins = [];
  const ui = {
    $: id => nodes.get(id),
    api,
    loginView: arg => logins.push(arg),
    document: { createElement: tag => new Element(tag) },
    URLSearchParams
  };
  runInNewContext(script, ui);
  return { ui, nodes, logins };
}

test('image overlay uses percentages, safe text and the detection class', () => {
  const { ui } = historyHarness();
  const frame = ui.aiEventImage(detection);
  const [image, overlay] = frame.children;
  assert.equal(image.src, '/api/ai/events/one/image');
  assert.equal(overlay.hidden, true);
  image.fire('load');
  assert.equal(overlay.hidden, false);
  assert.equal(overlay.children[1].textContent, 'animal 92.5%');
  assert.deepEqual([...overlay.style], [
    ['--bbox-left', '12.5%'],
    ['--bbox-top', '25%'],
    ['--bbox-width', '62.5%'],
    ['--bbox-height', '62.5%']
  ]);
  for (const target of ['animal', 'person', 'vehicle']) {
    assert.ok(ui.aiDetectionOverlay({ ...detection, target }).classList.has(`ai-event-${target}`));
  }
  const target = '<img src=x onerror=alert(1)>';
  const unrecognized = ui.aiDetectionOverlay({ ...detection, target });
  assert.equal(unrecognized.classList.size, 0);
  assert.equal(unrecognized.children[1].textContent, `${target} 92.5%`);
});

test('missing or invalid coordinates preserve the image and image failure preserves the event', async () => {
  const { ui, nodes } = historyHarness(async () => ({
    events: [{ ...detection, hasImage: false }, { ...detection, bbox: null }],
    next: null
  }));
  for (const bbox of [null, [0, 0, 1], [0, 0, 1, 1, 1], [0, 0, 2, 1], [0, 0, 0, 1],
    [0, 1, 1, 0], ['0', 0, 1, 1], [NaN, 0, 1, 1], [0, 0, 1, Infinity], [, 0, 1, 1]]) {
    assert.equal(ui.aiDetectionOverlay({ ...detection, bbox }), null);
  }
  await ui.loadAiHistory(false);
  const [withoutImage, withImage] = nodes.get('ai-events').children;
  assert.equal(withoutImage.children.length, 1);
  assert.match(withoutImage.children[0].textContent, /animal/);
  const frame = withImage.children[1];
  assert.equal(frame.children.length, 1);
  frame.children[0].fire('error');
  assert.equal(withImage.children[1].textContent, '検知画像を取得できませんでした');
  assert.match(withImage.children[0].textContent, /animal/);
});

test('history pagination, refresh and login expiration still work', async () => {
  const requests = [];
  const { nodes, logins } = historyHarness(async path => {
    requests.push(path);
    if (requests.length === 3) throw { status: 401 };
    return {
      events: [{ ...detection, eventId: String(requests.length) }],
      next: requests.length === 1 ? { before: 123456, id: 'one' } : null
    };
  });
  const more = nodes.get('ai-events-more');
  const refresh = nodes.get('ai-events-refresh');
  await refresh.fire('click');
  assert.equal(more.hidden, false);
  await more.fire('click');
  assert.equal(more.hidden, true);
  assert.equal(nodes.get('ai-events').children.length, 2);
  await refresh.fire('click');
  assert.deepEqual(requests, ['/api/ai/events', '/api/ai/events?before=123456&id=one', '/api/ai/events']);
  assert.deepEqual(logins, [true]);
  assert.equal(refresh.disabled, false);
});
