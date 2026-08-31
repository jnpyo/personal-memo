CREATE TABLE memo_item_relations (
  application_id UUID NOT NULL,
  proposal_relation_index SMALLINT NOT NULL,
  owner_id UUID NOT NULL REFERENCES users(id),
  source_memo_item_id UUID NOT NULL,
  target_type VARCHAR(16) NOT NULL,
  target_memo_id UUID,
  target_tag_id UUID,
  relation_type VARCHAR(24) NOT NULL,
  confirmed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (application_id, proposal_relation_index),
  CONSTRAINT ck_memo_item_relation_proposal_index
    CHECK (proposal_relation_index BETWEEN 0 AND 9),
  CONSTRAINT ck_memo_item_relation_target_type
    CHECK (target_type IN ('MEMO', 'TAG')),
  CONSTRAINT ck_memo_item_relation_type
    CHECK (relation_type IN ('RELATED_TO', 'CONTINUES', 'DEPENDS_ON', 'REFERENCES')),
  CONSTRAINT ck_memo_item_relation_target
    CHECK (
      (target_type = 'MEMO' AND target_memo_id IS NOT NULL AND target_tag_id IS NULL)
      OR
      (target_type = 'TAG' AND target_memo_id IS NULL AND target_tag_id IS NOT NULL)
    ),
  CONSTRAINT fk_memo_item_relation_application_owner
    FOREIGN KEY (application_id, owner_id)
    REFERENCES analysis_applications(id, owner_id),
  CONSTRAINT fk_memo_item_relation_source_application_owner
    FOREIGN KEY (source_memo_item_id, application_id, owner_id)
    REFERENCES memo_items(id, application_id, owner_id),
  CONSTRAINT fk_memo_item_relation_target_memo_owner
    FOREIGN KEY (target_memo_id, owner_id)
    REFERENCES memos(id, owner_id),
  CONSTRAINT fk_memo_item_relation_target_tag_owner
    FOREIGN KEY (target_tag_id, owner_id)
    REFERENCES tags(id, owner_id)
);

CREATE INDEX idx_memo_item_relations_owner
  ON memo_item_relations(owner_id);

CREATE INDEX idx_memo_item_relations_application_owner
  ON memo_item_relations(application_id, owner_id);

CREATE INDEX idx_memo_item_relations_source_application_owner
  ON memo_item_relations(source_memo_item_id, application_id, owner_id);

CREATE INDEX idx_memo_item_relations_target_memo_owner
  ON memo_item_relations(target_memo_id, owner_id)
  WHERE target_memo_id IS NOT NULL;

CREATE INDEX idx_memo_item_relations_target_tag_owner
  ON memo_item_relations(target_tag_id, owner_id)
  WHERE target_tag_id IS NOT NULL;

CREATE UNIQUE INDEX uq_memo_item_relations_directed_memo
  ON memo_item_relations(source_memo_item_id, relation_type, target_memo_id)
  WHERE target_memo_id IS NOT NULL;

CREATE UNIQUE INDEX uq_memo_item_relations_directed_tag
  ON memo_item_relations(source_memo_item_id, relation_type, target_tag_id)
  WHERE target_tag_id IS NOT NULL;
