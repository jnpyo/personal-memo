-- Recipient calendar feeds are explicit, owner-managed projections. The bearer secret is never
-- stored: token_verifier contains only a domain-separated SHA-256 digest. Existing EVENT rows are
-- deliberately not backfilled or shared.
ALTER TABLE event_details
  ADD CONSTRAINT uq_event_details_item_owner_kind
    UNIQUE (memo_item_id, owner_id, item_kind);

CREATE TABLE calendar_feeds (
  id UUID PRIMARY KEY,
  owner_id UUID NOT NULL REFERENCES users(id),
  display_name VARCHAR(80) NOT NULL CHECK (btrim(display_name) <> ''),
  disclosure_mode VARCHAR(16) NOT NULL
    CHECK (disclosure_mode IN ('TITLE', 'BUSY_ONLY')),
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE', 'REVOKED')),
  version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
  token_verifier CHAR(64) NOT NULL UNIQUE
    CHECK (token_verifier ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  rotated_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  CONSTRAINT uq_calendar_feeds_id_owner UNIQUE (id, owner_id),
  CONSTRAINT ck_calendar_feed_revocation CHECK (
    (status = 'ACTIVE' AND revoked_at IS NULL)
    OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
  )
);

CREATE INDEX idx_calendar_feeds_owner_updated
  ON calendar_feeds(owner_id, updated_at DESC, id);

CREATE TABLE calendar_feed_entries (
  id UUID PRIMARY KEY,
  feed_id UUID NOT NULL,
  owner_id UUID NOT NULL,
  source_event_hash CHAR(64) NOT NULL
    CHECK (source_event_hash ~ '^[0-9a-f]{64}$'),
  public_uid VARCHAR(96) NOT NULL UNIQUE
    CHECK (public_uid ~ '^pm-feed-v1-[A-Za-z0-9_-]{43}@personal-memo[.]invalid$'),
  active_memo_item_id UUID,
  active_owner_id UUID,
  active_item_kind VARCHAR(24),
  state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'CANCELLED')),
  sequence INTEGER NOT NULL DEFAULT 0 CHECK (sequence >= 0),
  schedule_kind VARCHAR(16) NOT NULL
    CHECK (schedule_kind IN ('TIMED', 'ALL_DAY')),
  start_at_utc TIMESTAMPTZ,
  end_at_utc TIMESTAMPTZ,
  start_local_date DATE,
  end_local_date_exclusive DATE,
  source_time_zone VARCHAR(64) NOT NULL CHECK (btrim(source_time_zone) <> ''),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  cancelled_at TIMESTAMPTZ,
  CONSTRAINT fk_calendar_feed_entry_feed_owner
    FOREIGN KEY (feed_id, owner_id) REFERENCES calendar_feeds(id, owner_id),
  CONSTRAINT fk_calendar_feed_entry_active_event_owner
    FOREIGN KEY (active_memo_item_id, active_owner_id, active_item_kind)
    REFERENCES event_details(memo_item_id, owner_id, item_kind)
    MATCH FULL,
  CONSTRAINT uq_calendar_feed_entry_source UNIQUE (feed_id, source_event_hash),
  CONSTRAINT ck_calendar_feed_entry_state CHECK (
    coalesce(
      (
        state = 'ACTIVE'
        AND active_memo_item_id IS NOT NULL
        AND active_owner_id = owner_id
        AND active_item_kind = 'EVENT'
        AND cancelled_at IS NULL
      )
      OR
      (
        state = 'CANCELLED'
        AND active_memo_item_id IS NULL
        AND active_owner_id IS NULL
        AND active_item_kind IS NULL
        AND cancelled_at IS NOT NULL
      ),
      FALSE
    )
  ),
  CONSTRAINT ck_calendar_feed_entry_temporal_shape CHECK (
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
  CONSTRAINT ck_calendar_feed_entry_timed_range CHECK (
    coalesce(end_at_utc IS NULL OR end_at_utc > start_at_utc, FALSE)
  ),
  CONSTRAINT ck_calendar_feed_entry_all_day_range CHECK (
    coalesce(
      end_local_date_exclusive IS NULL
        OR end_local_date_exclusive > start_local_date,
      FALSE
    )
  )
);

CREATE INDEX idx_calendar_feed_entries_feed_state
  ON calendar_feed_entries(feed_id, owner_id, state, created_at, id);

CREATE INDEX idx_calendar_feed_entries_active_event
  ON calendar_feed_entries(active_memo_item_id, active_owner_id)
  WHERE active_memo_item_id IS NOT NULL;
