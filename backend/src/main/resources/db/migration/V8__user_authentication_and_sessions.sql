ALTER TABLE users
  ADD COLUMN primary_email VARCHAR(254),
  ADD COLUMN primary_email_normalized VARCHAR(254),
  ADD COLUMN display_name VARCHAR(80),
  ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'LEGACY_UNCLAIMED';

ALTER TABLE users
  ADD CONSTRAINT ck_users_email_pair
    CHECK ((primary_email IS NULL) = (primary_email_normalized IS NULL)),
  ADD CONSTRAINT ck_users_primary_email_length
    CHECK (primary_email IS NULL OR length(btrim(primary_email)) BETWEEN 3 AND 254),
  ADD CONSTRAINT ck_users_primary_email_normalized
    CHECK (primary_email_normalized IS NULL OR (
      length(primary_email_normalized) BETWEEN 3 AND 254
      AND primary_email_normalized = lower(btrim(primary_email))
    )),
  ADD CONSTRAINT ck_users_display_name
    CHECK (display_name IS NULL OR length(btrim(display_name)) BETWEEN 1 AND 80),
  ADD CONSTRAINT ck_users_status
    CHECK (status IN ('ACTIVE', 'DISABLED', 'LEGACY_UNCLAIMED'));

CREATE UNIQUE INDEX uq_users_primary_email_normalized
  ON users(primary_email_normalized)
  WHERE primary_email_normalized IS NOT NULL;

CREATE TABLE local_credentials (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  password_hash VARCHAR(255) NOT NULL,
  failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
  locked_until TIMESTAMPTZ,
  password_changed_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_local_credentials_password_hash
    CHECK (length(password_hash) BETWEEN 20 AND 255)
);

CREATE TABLE external_identities (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider VARCHAR(32) NOT NULL,
  provider_subject VARCHAR(255) NOT NULL,
  email_at_login VARCHAR(254) NOT NULL,
  email_verified BOOLEAN NOT NULL,
  linked_at TIMESTAMPTZ NOT NULL,
  last_login_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_external_identities_provider CHECK (provider IN ('GOOGLE')),
  CONSTRAINT ck_external_identities_verified CHECK (email_verified),
  CONSTRAINT ck_external_identities_subject CHECK (length(btrim(provider_subject)) BETWEEN 1 AND 255),
  CONSTRAINT ck_external_identities_email CHECK (length(btrim(email_at_login)) BETWEEN 3 AND 254),
  CONSTRAINT uq_external_identities_provider_subject UNIQUE(provider, provider_subject),
  CONSTRAINT uq_external_identities_user_provider UNIQUE(user_id, provider)
);

CREATE INDEX idx_external_identities_user ON external_identities(user_id);

-- Spring Session JDBC schema is migration-owned; framework auto-initialization stays disabled.
CREATE TABLE SPRING_SESSION (
  PRIMARY_ID CHAR(36) NOT NULL,
  SESSION_ID CHAR(36) NOT NULL,
  CREATION_TIME BIGINT NOT NULL,
  LAST_ACCESS_TIME BIGINT NOT NULL,
  MAX_INACTIVE_INTERVAL INTEGER NOT NULL,
  EXPIRY_TIME BIGINT NOT NULL,
  PRINCIPAL_NAME VARCHAR(100),
  CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
  SESSION_PRIMARY_ID CHAR(36) NOT NULL,
  ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
  ATTRIBUTE_BYTES BYTEA NOT NULL,
  CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
  CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
    REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
