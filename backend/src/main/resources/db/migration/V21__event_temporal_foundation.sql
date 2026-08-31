-- Canonical EVENT schedules are separate from TASK due values. Existing title-only EVENT items
-- remain unchanged: this migration deliberately performs no backfill.
ALTER TABLE memo_items
  ADD CONSTRAINT uq_memo_items_id_owner_kind UNIQUE (id, owner_id, kind);

CREATE TABLE event_details (
  memo_item_id UUID PRIMARY KEY,
  owner_id UUID NOT NULL,
  item_kind VARCHAR(24) NOT NULL DEFAULT 'EVENT'
    CHECK (item_kind = 'EVENT'),
  schedule_kind VARCHAR(16) NOT NULL
    CHECK (schedule_kind IN ('TIMED', 'ALL_DAY')),
  start_at_utc TIMESTAMPTZ,
  end_at_utc TIMESTAMPTZ,
  start_local_date DATE,
  end_local_date_exclusive DATE,
  source_time_zone VARCHAR(64) NOT NULL
    CHECK (btrim(source_time_zone) <> ''),
  CONSTRAINT fk_event_detail_event_item_owner
    FOREIGN KEY (memo_item_id, owner_id, item_kind)
    REFERENCES memo_items(id, owner_id, kind),
  CONSTRAINT ck_event_detail_temporal_shape CHECK (
    coalesce(
      (
        schedule_kind = 'TIMED'
        AND start_at_utc IS NOT NULL
        AND start_local_date IS NULL
        AND end_local_date_exclusive IS NULL
      )
      OR
      (
        schedule_kind = 'ALL_DAY'
        AND start_local_date IS NOT NULL
        AND start_at_utc IS NULL
        AND end_at_utc IS NULL
      ),
      FALSE
    )
  ),
  CONSTRAINT ck_event_detail_timed_range CHECK (
    coalesce(end_at_utc IS NULL OR end_at_utc > start_at_utc, FALSE)
  ),
  CONSTRAINT ck_event_detail_all_day_range CHECK (
    coalesce(
      end_local_date_exclusive IS NULL
        OR end_local_date_exclusive > start_local_date,
      FALSE
    )
  )
);

CREATE INDEX idx_event_details_owner_schedule_start
  ON event_details(owner_id, schedule_kind, start_at_utc, start_local_date);
