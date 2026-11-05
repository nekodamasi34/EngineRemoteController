CREATE TABLE camera_viewers (
 device_id TEXT NOT NULL, viewer_id TEXT NOT NULL, mode TEXT NOT NULL,
 expires_at INTEGER NOT NULL, detail_until INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(device_id, viewer_id)
);
CREATE INDEX camera_viewers_expiry ON camera_viewers(device_id, expires_at);
CREATE TABLE camera_frames (
 device_id TEXT PRIMARY KEY, accepted_at INTEGER NOT NULL DEFAULT 0
);
