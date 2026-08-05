ALTER TABLE memo_revisions
  ADD COLUMN client_recorded_at TIMESTAMPTZ,
  ADD COLUMN source_time_zone VARCHAR(64);

UPDATE memo_revisions revision
   SET client_recorded_at = revision.created_at,
       source_time_zone = settings.time_zone
  FROM user_settings settings
 WHERE settings.user_id = revision.owner_id;

ALTER TABLE memo_revisions
  ALTER COLUMN client_recorded_at SET NOT NULL,
  ALTER COLUMN source_time_zone SET NOT NULL;
