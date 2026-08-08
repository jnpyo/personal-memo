CREATE INDEX idx_analysis_applications_owner_proposal_latest
  ON analysis_applications(owner_id, proposal_id, applied_at DESC, id DESC);
