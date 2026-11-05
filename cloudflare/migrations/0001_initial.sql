CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  last_seen INTEGER NOT NULL,
  connected INTEGER NOT NULL,
  ready INTEGER NOT NULL,
  busy INTEGER NOT NULL,
  status TEXT NOT NULL,
  channels TEXT NOT NULL
);
CREATE TABLE commands (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  action TEXT NOT NULL,
  duration_ms INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('queued','claimed','succeeded','failed','cancelled','expired','unknown')),
  claim_id TEXT,
  claimed_at INTEGER,
  finished_at INTEGER,
  result TEXT
);
CREATE INDEX command_queue ON commands(device_id, state, expires_at, created_at);
