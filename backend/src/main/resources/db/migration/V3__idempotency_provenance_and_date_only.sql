ALTER TABLE task_details
  ADD COLUMN due_local_date DATE;

ALTER TABLE task_details
  ADD CONSTRAINT ck_task_details_one_due_representation
  CHECK (due_at_utc IS NULL OR due_local_date IS NULL);

ALTER TABLE tags
  ADD COLUMN created_by_application_id UUID REFERENCES analysis_applications(id);

CREATE INDEX idx_tasks_status_local_due
  ON task_details(status, due_local_date);

CREATE INDEX idx_tags_created_by_application
  ON tags(created_by_application_id)
  WHERE created_by_application_id IS NOT NULL;
