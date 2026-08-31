ALTER TABLE users
  ADD CONSTRAINT ck_users_claimed_identity
    CHECK (
      status = 'LEGACY_UNCLAIMED'
      OR (
        primary_email IS NOT NULL
        AND primary_email_normalized IS NOT NULL
        AND display_name IS NOT NULL
      )
    );
