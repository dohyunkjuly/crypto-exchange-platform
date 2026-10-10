-- Staff. Its own tables, never a role on user_users: a customer row cannot become staff by
-- one column changing, and nothing that reads customers ever meets a staff row.
-- docs/reference/admin.md.

CREATE TABLE admin_users (
    id                     BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email                  VARCHAR(255)  NOT NULL,
    password_hash          VARCHAR(255)  NOT NULL,
    role                   VARCHAR(16)   NOT NULL,
    disabled_at            TIMESTAMP(6)  NULL,

    -- TOTP is mandatory, enrolled at first login. The seed is AES-GCM under
    -- MFA_ENCRYPTION_KEY (docs/reference/credentials.md), confirmed once the first code is
    -- typed, and last_used_step keeps a code from being replayed inside its drift window.
    totp_secret_encrypted  VARBINARY(64) NULL,
    totp_confirmed_at      TIMESTAMP(6)  NULL,
    totp_last_used_step    BIGINT        NULL,

    created_at             TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_admin_users_email UNIQUE (email),
    CONSTRAINT ck_admin_users_role CHECK (role IN ('VIEWER', 'OPERATOR', 'SUPERADMIN'))
);

-- Opaque tokens, hashed, as user_sessions. A session is PENDING between the password and
-- the TOTP code (or the enrolment, on first login): a stolen password alone opens nothing.
CREATE TABLE admin_sessions (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    admin_id        BIGINT        NOT NULL,
    token_hash      VARBINARY(32) NOT NULL,
    state           VARCHAR(16)   NOT NULL,
    ip_address      VARCHAR(45)   NOT NULL,
    user_agent      VARCHAR(512)  NULL,
    created_at      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at    TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at      TIMESTAMP(6)  NOT NULL,
    revoked_at      TIMESTAMP(6)  NULL,

    CONSTRAINT uq_admin_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_admin_sessions_admin FOREIGN KEY (admin_id) REFERENCES admin_users (id),
    CONSTRAINT ck_admin_sessions_state CHECK (state IN ('PENDING', 'ACTIVE'))
);

-- Every write an admin makes, in the same transaction as the write. Logins too, so the
-- failed ones can be counted: admin_id is null when nobody was identified.
CREATE TABLE admin_audit_log (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    admin_id        BIGINT        NULL,
    action          VARCHAR(48)   NOT NULL,
    target_type     VARCHAR(32)   NULL,
    target_id       VARCHAR(64)   NULL,
    -- JSON of the fields that changed, not the whole entity.
    before_json     TEXT          NULL,
    after_json      TEXT          NULL,
    ip_address      VARCHAR(45)   NOT NULL,
    created_at      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    INDEX idx_admin_audit_admin (admin_id, created_at),
    INDEX idx_admin_audit_action (action, created_at),
    INDEX idx_admin_audit_target (target_type, target_id)
);
