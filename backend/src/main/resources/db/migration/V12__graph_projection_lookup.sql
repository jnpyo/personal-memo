CREATE INDEX idx_analysis_applications_owner_memo_applied_latest
  ON analysis_applications(owner_id, memo_id, applied_at DESC, id DESC)
  WHERE status = 'APPLIED';

CREATE INDEX idx_memo_items_owner_memo_active_application
  ON memo_items(owner_id, memo_id, application_id)
  WHERE archived_at IS NULL;
