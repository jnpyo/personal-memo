-- Backfill explicit ownership before tightening relationships. These updates are safe for
-- pre-V5 data because every row already has a canonical parent with an owner.
ALTER TABLE memo_revisions ADD COLUMN owner_id UUID;
UPDATE memo_revisions revision
   SET owner_id = memo.owner_id
  FROM memos memo
 WHERE memo.id = revision.memo_id;
ALTER TABLE memo_revisions ALTER COLUMN owner_id SET NOT NULL;

ALTER TABLE analysis_proposals ADD COLUMN owner_id UUID;
UPDATE analysis_proposals proposal
   SET owner_id = run.owner_id
  FROM analysis_runs run
 WHERE run.id = proposal.analysis_run_id;
ALTER TABLE analysis_proposals ALTER COLUMN owner_id SET NOT NULL;

ALTER TABLE task_details ADD COLUMN owner_id UUID;
UPDATE task_details task
   SET owner_id = item.owner_id
  FROM memo_items item
 WHERE item.id = task.memo_item_id;
ALTER TABLE task_details ALTER COLUMN owner_id SET NOT NULL;

ALTER TABLE item_tags ADD COLUMN owner_id UUID;
UPDATE item_tags link
   SET owner_id = item.owner_id
  FROM memo_items item
 WHERE item.id = link.memo_item_id;
ALTER TABLE item_tags ALTER COLUMN owner_id SET NOT NULL;

-- PostgreSQL composite foreign keys need an explicit matching unique key. The globally
-- unique UUID primary keys remain the public identities; these keys exist to bind ownership.
ALTER TABLE memos
  ADD CONSTRAINT uq_memos_id_owner UNIQUE (id, owner_id);
ALTER TABLE memo_revisions
  ADD CONSTRAINT uq_memo_revisions_identity_owner UNIQUE (memo_id, revision, owner_id);
ALTER TABLE analysis_runs
  ADD CONSTRAINT uq_analysis_runs_id_owner UNIQUE (id, owner_id);
ALTER TABLE analysis_proposals
  ADD CONSTRAINT uq_analysis_proposals_id_owner UNIQUE (id, owner_id);
ALTER TABLE analysis_applications
  ADD CONSTRAINT uq_analysis_applications_id_owner UNIQUE (id, owner_id),
  ADD CONSTRAINT uq_analysis_applications_context_owner
    UNIQUE (id, memo_id, memo_revision, owner_id);
ALTER TABLE tags
  ADD CONSTRAINT uq_tags_id_owner UNIQUE (id, owner_id);
ALTER TABLE memo_items
  ADD CONSTRAINT uq_memo_items_id_owner UNIQUE (id, owner_id),
  ADD CONSTRAINT uq_memo_items_application_owner UNIQUE (id, application_id, owner_id);

-- Replace owner-blind parent foreign keys with composite keys. User/owner foreign keys stay
-- single-column because the user UUID is itself the ownership boundary.
ALTER TABLE memo_revisions
  DROP CONSTRAINT memo_revisions_memo_id_fkey,
  ADD CONSTRAINT fk_memo_revision_memo_owner
    FOREIGN KEY (memo_id, owner_id) REFERENCES memos(id, owner_id);

ALTER TABLE analysis_runs
  DROP CONSTRAINT analysis_runs_memo_id_fkey,
  ADD CONSTRAINT fk_analysis_run_memo_owner
    FOREIGN KEY (memo_id, owner_id) REFERENCES memos(id, owner_id),
  ADD CONSTRAINT fk_analysis_run_revision_owner
    FOREIGN KEY (memo_id, memo_revision, owner_id)
    REFERENCES memo_revisions(memo_id, revision, owner_id);

ALTER TABLE analysis_proposals
  DROP CONSTRAINT analysis_proposals_analysis_run_id_fkey,
  ADD CONSTRAINT fk_analysis_proposal_run_owner
    FOREIGN KEY (analysis_run_id, owner_id) REFERENCES analysis_runs(id, owner_id);

ALTER TABLE analysis_applications
  DROP CONSTRAINT analysis_applications_proposal_id_fkey,
  DROP CONSTRAINT analysis_applications_memo_id_fkey,
  ADD CONSTRAINT fk_analysis_application_proposal_owner
    FOREIGN KEY (proposal_id, owner_id) REFERENCES analysis_proposals(id, owner_id),
  ADD CONSTRAINT fk_analysis_application_memo_owner
    FOREIGN KEY (memo_id, owner_id) REFERENCES memos(id, owner_id),
  ADD CONSTRAINT fk_analysis_application_revision_owner
    FOREIGN KEY (memo_id, memo_revision, owner_id)
    REFERENCES memo_revisions(memo_id, revision, owner_id);

ALTER TABLE tags
  DROP CONSTRAINT tags_created_by_application_id_fkey,
  ADD CONSTRAINT fk_tag_creator_application_owner
    FOREIGN KEY (created_by_application_id, owner_id)
    REFERENCES analysis_applications(id, owner_id);

ALTER TABLE tag_aliases
  DROP CONSTRAINT tag_aliases_tag_id_fkey,
  ADD CONSTRAINT fk_tag_alias_tag_owner
    FOREIGN KEY (tag_id, owner_id) REFERENCES tags(id, owner_id);

ALTER TABLE memo_items
  DROP CONSTRAINT memo_items_memo_id_fkey,
  DROP CONSTRAINT memo_items_application_id_fkey,
  ADD CONSTRAINT fk_memo_item_memo_owner
    FOREIGN KEY (memo_id, owner_id) REFERENCES memos(id, owner_id),
  ADD CONSTRAINT fk_memo_item_revision_owner
    FOREIGN KEY (memo_id, memo_revision, owner_id)
    REFERENCES memo_revisions(memo_id, revision, owner_id),
  ADD CONSTRAINT fk_memo_item_application_owner
    FOREIGN KEY (application_id, memo_id, memo_revision, owner_id)
    REFERENCES analysis_applications(id, memo_id, memo_revision, owner_id);

ALTER TABLE task_details
  DROP CONSTRAINT task_details_memo_item_id_fkey,
  ADD CONSTRAINT fk_task_detail_item_owner
    FOREIGN KEY (memo_item_id, owner_id) REFERENCES memo_items(id, owner_id);

ALTER TABLE item_tags
  DROP CONSTRAINT item_tags_memo_item_id_fkey,
  DROP CONSTRAINT item_tags_tag_id_fkey,
  DROP CONSTRAINT item_tags_application_id_fkey,
  ADD CONSTRAINT fk_item_tag_item_application_owner
    FOREIGN KEY (memo_item_id, application_id, owner_id)
    REFERENCES memo_items(id, application_id, owner_id),
  ADD CONSTRAINT fk_item_tag_tag_owner
    FOREIGN KEY (tag_id, owner_id) REFERENCES tags(id, owner_id);

CREATE INDEX idx_analysis_proposals_owner_created
  ON analysis_proposals(owner_id, created_at DESC);
CREATE INDEX idx_task_details_owner_status_due
  ON task_details(owner_id, status, due_at_utc, due_local_date);
CREATE INDEX idx_item_tags_owner_tag
  ON item_tags(owner_id, tag_id);
