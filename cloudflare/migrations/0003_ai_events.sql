CREATE TABLE ai_events (
  device_id TEXT NOT NULL,
  id TEXT NOT NULL,
  captured_at INTEGER NOT NULL,
  target TEXT NOT NULL CHECK (target IN ('animal','person','vehicle')),
  confidence REAL NOT NULL CHECK (confidence BETWEEN 0 AND 1),
  bbox TEXT NOT NULL,
  image_key TEXT,
  payload_sha256 TEXT NOT NULL,
  ready INTEGER NOT NULL DEFAULT 0 CHECK (ready IN (0,1)),
  expires_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, id)
);

CREATE INDEX ai_events_history ON ai_events(device_id, ready, captured_at DESC, id DESC);
CREATE INDEX ai_events_expiry ON ai_events(expires_at);
