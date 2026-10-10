-- The user domain: identity, sessions, second factors, KYC, API keys and the audit trail.
--
-- Conventions for every table in this schema: enums are VARCHAR with a CHECK, never a MySQL
-- ENUM, because reordering a Java enum must not change the meaning of a row. Timestamps are
-- TIMESTAMP(6) in UTC. Money is BIGINT scaled by 1e8. A DEFAULT is for a column's first rows
-- only: Hibernate writes every column on insert, so defaults for new rows live on the entity.
-- No foreign key crosses a domain, so each domain can move to its own database.
--
-- Credentials (docs/reference/credentials.md): a password is bcrypt; a session token, a
-- recovery code and an emailed code are SHA-256, since they are high-entropy random strings
-- that are only ever compared; an API secret and a TOTP seed are AES-256-GCM, because a
-- signature or a code has to be recomputed from them. Never a raw secret.

CREATE TABLE user_users (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    email               VARCHAR(255) NOT NULL,
    email_verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Null for an account that has only ever signed in through a provider. Readers treat
    -- null as "no password login", never as an empty password.
    password_hash       VARCHAR(255) NULL,
    name                VARCHAR(100) NULL,

    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',

    -- The trading gate's read model (docs/reference/trading-gate.md). Both apps load this
    -- row to authenticate every request, so the gate costs nothing extra. user_kyc_profiles
    -- and user_mfa_methods remain the record; KycService and MfaService write these in the
    -- same transaction as the rows they summarise, because drift in the permissive
    -- direction is a compliance failure, not a stale cache.
    --
    -- kyc_level is the level HELD, NONE unless the profile is approved, never the level
    -- applied for. kyc_verified_until is a timestamp rather than a copied status: KYC
    -- lapses at a wall-clock instant with no write anywhere, so a status would say APPROVED
    -- for months after the verification died. A timestamp expires itself. mfa_enabled is
    -- safe as a boolean because it changes only on enrol, confirm or revoke, never with time.
    kyc_level           VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    kyc_verified_until  TIMESTAMP(6) NULL,
    mfa_enabled         BOOLEAN      NOT NULL DEFAULT FALSE,

    type                VARCHAR(16)  NOT NULL DEFAULT 'PRIMARY',
    parent_user_id      BIGINT       NULL,
    trade_group_id      BIGINT       NULL,

    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                     ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_user_users_email UNIQUE (email),
    CONSTRAINT fk_user_users_parent_user FOREIGN KEY (parent_user_id) REFERENCES user_users (id),
    CONSTRAINT ck_user_users_status    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_user_users_kyc_level CHECK (kyc_level IN ('NONE', 'BASIC', 'INTERMEDIATE', 'ADVANCED')),
    CONSTRAINT ck_user_users_type      CHECK (type IN ('PRIMARY', 'SUB'))
) ENGINE = InnoDB;

CREATE INDEX idx_user_users_parent_user_id ON user_users (parent_user_id);

-- Opaque tokens, not JWT: setting revoked_at kills a session instantly, where a JWT would
-- need a blocklist, which is just this table again.
CREATE TABLE user_sessions (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT        NOT NULL,

    -- SHA-256 of the token, never the token: a leaked dump must not be a pile of working
    -- sessions. Plain SHA-256 rather than bcrypt because the token is 256 bits of
    -- randomness, so there is no dictionary to slow an attacker with, and hashing it on
    -- every request would be a denial of service on ourselves.
    token_hash      VARBINARY(32) NOT NULL,

    -- 45 chars covers IPv6 including the ::ffff:1.2.3.4 form. VARBINARY(16) would allow
    -- subnet math at the cost of being unreadable in a shell and awkward from Java.
    ip_address      VARCHAR(45)   NOT NULL,
    user_agent      VARCHAR(512)  NULL,
    device_id       VARCHAR(128)  NULL,
    country_code    VARCHAR(2)    NULL,

    created_at      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at    TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at      TIMESTAMP(6)  NOT NULL,
    revoked_at      TIMESTAMP(6)  NULL,
    revoked_reason  VARCHAR(32)   NULL,

    CONSTRAINT uq_user_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_user_sessions_user FOREIGN KEY (user_id) REFERENCES user_users (id),
    CONSTRAINT ck_user_sessions_revoked CHECK (
        revoked_reason IS NULL OR revoked_reason IN (
            'LOGOUT', 'EXPIRED', 'PASSWORD_CHANGED',
            'REVOKED_BY_USER', 'REVOKED_BY_ADMIN', 'SUSPICIOUS_ACTIVITY'
        )
    )
) ENGINE = InnoDB;

-- "Show me my active sessions", and the logout-everywhere revoke.
CREATE INDEX idx_user_sessions_user_id_created_at ON user_sessions (user_id, created_at);
-- The expiry sweep.
CREATE INDEX idx_user_sessions_expires_at ON user_sessions (expires_at);

-- Append-only. Nothing may UPDATE or DELETE a row here; that is what makes it evidence.
-- Hence no updated_at, and no CHECK on event_type: the taxonomy grows with every feature,
-- and the Java enum guards the write path.
CREATE TABLE user_auth_events (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    -- Nullable: a failed login against an unregistered address has no user to point at,
    -- and those rows are exactly the ones you need under credential stuffing.
    user_id          BIGINT       NULL,
    email_attempted  VARCHAR(255) NULL,

    -- No foreign key: expired sessions get pruned, and an audit row must outlive the
    -- session it describes rather than block its deletion.
    session_id       BIGINT       NULL,

    event_type       VARCHAR(48)  NOT NULL,
    outcome          VARCHAR(16)  NOT NULL,
    failure_reason   VARCHAR(64)  NULL,

    -- Null when no request was behind the event, such as a KYC approval arriving from a
    -- provider's webhook. Null rather than "0.0.0.0", which someone would one day read as
    -- an address in a compliance export.
    ip_address       VARCHAR(45)  NULL,
    user_agent       VARCHAR(512) NULL,
    country_code     VARCHAR(2)   NULL,

    -- Per-event-type extras that do not deserve their own column.
    metadata         JSON         NULL,

    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- RESTRICT, which is right: a user with an audit trail cannot be hard deleted. Closing
    -- an account is a status change, not a DELETE.
    CONSTRAINT fk_user_auth_events_user FOREIGN KEY (user_id) REFERENCES user_users (id),
    CONSTRAINT ck_user_auth_events_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE'))
) ENGINE = InnoDB;

-- "Everything this user did, newest first": the support and compliance query.
CREATE INDEX idx_user_auth_events_user_id_created_at ON user_auth_events (user_id, created_at);
-- Brute force from one address.
CREATE INDEX idx_user_auth_events_ip_address_created_at ON user_auth_events (ip_address, created_at);
-- Credential stuffing against one address, where user_id is null.
CREATE INDEX idx_user_auth_events_email_attempted_created_at ON user_auth_events (email_attempted, created_at);

-- Programmatic trading credentials. The HMAC secret cannot be hashed, because verifying a
-- request means recomputing its signature, so it is encrypted at rest and secret_key_id
-- records which key encrypted it, for rotation without a flag day. An Ed25519 key avoids
-- the problem: the client signs with a private key we never see. key_type lets both exist.
CREATE TABLE user_api_keys (
    id                BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT         NOT NULL,

    label             VARCHAR(64)    NOT NULL,

    -- The public identifier the client sends in a header. Not a secret, but unique and
    -- looked up on every request, so it carries the index.
    api_key           VARCHAR(64)    NOT NULL,
    key_type          VARCHAR(16)    NOT NULL,

    -- HMAC_SHA256 only.
    secret_encrypted  VARBINARY(255) NULL,
    secret_key_id     VARCHAR(32)    NULL,

    -- ED25519 only. 32 raw bytes; we never hold the private half.
    public_key        VARBINARY(64)  NULL,

    can_read          BOOLEAN        NOT NULL DEFAULT TRUE,
    can_trade         BOOLEAN        NOT NULL DEFAULT FALSE,
    can_withdraw      BOOLEAN        NOT NULL DEFAULT FALSE,

    -- Array of CIDRs. Loaded with the key and checked in memory, never queried across
    -- keys, so it needs no table of its own.
    ip_allowlist      JSON           NULL,

    last_used_at      TIMESTAMP(6)   NULL,
    expires_at        TIMESTAMP(6)   NULL,
    revoked_at        TIMESTAMP(6)   NULL,
    revoked_reason    VARCHAR(32)    NULL,

    created_at        TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                     ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_user_api_keys_api_key UNIQUE (api_key),
    CONSTRAINT fk_user_api_keys_user FOREIGN KEY (user_id) REFERENCES user_users (id),
    CONSTRAINT ck_user_api_keys_key_type CHECK (key_type IN ('HMAC_SHA256', 'ED25519')),

    -- Whichever type it is, the matching credential column is present and the other absent.
    CONSTRAINT ck_user_api_keys_material CHECK (
        (key_type = 'HMAC_SHA256' AND secret_encrypted IS NOT NULL AND public_key IS NULL)
        OR
        (key_type = 'ED25519' AND public_key IS NOT NULL AND secret_encrypted IS NULL)
    ),

    -- A withdrawal-capable key with no IP allowlist is how exchanges lose customer funds.
    CONSTRAINT ck_user_api_keys_withdraw_needs_allowlist CHECK (
        can_withdraw = FALSE OR ip_allowlist IS NOT NULL
    ),

    CONSTRAINT ck_user_api_keys_revoked_reason CHECK (
        revoked_reason IS NULL OR revoked_reason IN (
            'REVOKED_BY_USER', 'REVOKED_BY_ADMIN', 'EXPIRED', 'SUSPICIOUS_ACTIVITY'
        )
    )
) ENGINE = InnoDB;

-- "List my API keys."
CREATE INDEX idx_user_api_keys_user_id_created_at ON user_api_keys (user_id, created_at);

-- One row per enrolled second factor. A user may have several: a TOTP app plus two hardware
-- keys is normal, and more than one is what stops a lost phone becoming a lost account.
CREATE TABLE user_mfa_methods (
    id                BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT         NOT NULL,

    method_type       VARCHAR(16)    NOT NULL,
    label             VARCHAR(64)    NULL,

    -- TOTP only. Encrypted, never hashed: checking a code means recomputing it.
    secret_encrypted  VARBINARY(255) NULL,
    secret_key_id     VARCHAR(32)    NULL,

    -- WEBAUTHN only. sign_count detects a cloned authenticator: it must never go backwards.
    credential_id     VARBINARY(255) NULL,
    public_key        VARBINARY(512) NULL,
    sign_count        BIGINT         NULL,

    -- An enrolled factor is not an active factor: nothing counts until the user has proved
    -- they can produce a code with it, or a mistyped setup locks them out.
    verified_at       TIMESTAMP(6)   NULL,
    last_used_at      TIMESTAMP(6)   NULL,
    -- The RFC 6238 time step this method last authenticated with. A new code must be
    -- strictly greater, which makes each code usable exactly once; without it a code read
    -- off a shoulder is a working second factor for up to 90 seconds. A step number rather
    -- than a timestamp, so it does not depend on the step length never changing.
    last_used_step    BIGINT         NULL,
    revoked_at        TIMESTAMP(6)   NULL,

    created_at        TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                     ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_user_mfa_methods_user FOREIGN KEY (user_id) REFERENCES user_users (id),
    CONSTRAINT ck_user_mfa_methods_type CHECK (
        method_type IN ('TOTP', 'WEBAUTHN', 'SMS', 'EMAIL')
    ),
    CONSTRAINT ck_user_mfa_methods_material CHECK (
        (method_type = 'TOTP' AND secret_encrypted IS NOT NULL)
        OR
        (method_type = 'WEBAUTHN' AND credential_id IS NOT NULL AND public_key IS NOT NULL)
        OR
        method_type IN ('SMS', 'EMAIL')
    )
) ENGINE = InnoDB;

-- A WebAuthn credential id is globally unique and is the lookup key during assertion.
-- Unique over a nullable column permits many NULLs and exactly one of each real value.
CREATE UNIQUE INDEX uq_user_mfa_methods_credential_id ON user_mfa_methods (credential_id);
-- "Which factors does this user have?", run on every login.
CREATE INDEX idx_user_mfa_methods_user_id ON user_mfa_methods (user_id);

-- One-time codes shown once at enrolment. There is no support desk to re-verify a person
-- who lost their authenticator, so without these a lost phone is an account with funds in
-- it that nobody can ever open again. Recovery is part of enabling 2FA, not an extra.
CREATE TABLE user_mfa_recovery_codes (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT        NOT NULL,

    code_hash   VARBINARY(32) NOT NULL,

    -- Single use. Set, never cleared: a spent code stays as evidence of when it was used,
    -- which matters when someone disputes account access.
    used_at     TIMESTAMP(6)  NULL,

    created_at  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                              ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_user_mfa_recovery_codes_user FOREIGN KEY (user_id) REFERENCES user_users (id),
    -- Globally unique: two users holding the same code would mean one can be found by
    -- guessing the other's.
    CONSTRAINT uq_user_mfa_recovery_codes_hash UNIQUE (code_hash)
) ENGINE = InnoDB;

-- Every lookup is "this user's unused codes".
CREATE INDEX idx_user_mfa_recovery_codes_user_used ON user_mfa_recovery_codes (user_id, used_at);

-- Identity verification, one profile per user. Holds as little as possible: the provider
-- keeps the document images and raw data, and we keep only what the platform reasons
-- about. Document numbers are deliberately absent; they are the highest-liability field in
-- an exchange and a second copy only doubles the blast radius. Still the most sensitive
-- table here: it wants column-level encryption on the names, not implemented yet.
CREATE TABLE user_kyc_profiles (
    id                     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id                BIGINT       NOT NULL,

    status                 VARCHAR(16)  NOT NULL DEFAULT 'NOT_STARTED',
    -- Drives withdrawal and trading limits. A user at NONE can deposit but not withdraw.
    level                  VARCHAR(16)  NOT NULL DEFAULT 'NONE',

    legal_first_name       VARCHAR(100) NULL,
    legal_last_name        VARCHAR(100) NULL,
    date_of_birth          DATE         NULL,
    -- ISO 3166-1 alpha-2. VARCHAR, not CHAR: CHAR(2) fails Hibernate's schema validation
    -- for a String.
    nationality            VARCHAR(2)   NULL,
    residence_country      VARCHAR(2)   NULL,

    provider               VARCHAR(32)  NULL,
    provider_applicant_id  VARCHAR(128) NULL,

    submitted_at           TIMESTAMP(6) NULL,
    reviewed_at            TIMESTAMP(6) NULL,
    -- KYC goes stale; most jurisdictions require periodic re-verification.
    expires_at             TIMESTAMP(6) NULL,
    rejection_reason       VARCHAR(255) NULL,

    created_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_user_kyc_profiles_user_id UNIQUE (user_id),
    CONSTRAINT fk_user_kyc_profiles_user FOREIGN KEY (user_id) REFERENCES user_users (id),
    CONSTRAINT ck_user_kyc_profiles_status CHECK (
        status IN ('NOT_STARTED', 'PENDING', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED')
    ),
    CONSTRAINT ck_user_kyc_profiles_level CHECK (
        level IN ('NONE', 'BASIC', 'INTERMEDIATE', 'ADVANCED')
    )
) ENGINE = InnoDB;

-- The compliance queue: what is waiting on review, oldest first.
CREATE INDEX idx_user_kyc_profiles_status_submitted_at ON user_kyc_profiles (status, submitted_at);
-- The re-verification sweep.
CREATE INDEX idx_user_kyc_profiles_expires_at ON user_kyc_profiles (expires_at);

-- Emailed codes, not links (docs/reference/credentials.md). An exchange that emails
-- clickable links teaches its users that a link from us is normal, which is precisely the
-- phishing attack. A six-digit code is one of a million, not one of 2^256, so its safety
-- comes from being short-lived, single-use, scoped to one user and attempt-capped.
CREATE TABLE user_email_verification_tokens (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT        NOT NULL,

    -- No unique constraint: six digits collide constantly between users. Lookup is by user
    -- and then by code, which is also what stops one account's code opening another's.
    code_hash   VARBINARY(32) NOT NULL,
    -- The cap that makes six digits survivable.
    attempts    INT           NOT NULL DEFAULT 0,

    -- Which address this code proves. A code issued for one address must not verify a
    -- different one if the account's email changes in between.
    email       VARCHAR(255)  NOT NULL,

    expires_at  TIMESTAMP(6)  NOT NULL,
    -- Single use. Set, never cleared.
    used_at     TIMESTAMP(6)  NULL,

    created_at  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                              ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_user_email_verification_tokens_user FOREIGN KEY (user_id) REFERENCES user_users (id)
) ENGINE = InnoDB;

-- Resending invalidates whatever is outstanding, so the lookup is "this user's live codes".
CREATE INDEX idx_user_email_verification_tokens_user_used
    ON user_email_verification_tokens (user_id, used_at);

-- Resetting a forgotten password: the same shape as the verification code. Its own table
-- because the two have different consequences, and sharing one would let a verification
-- code reset a password.
CREATE TABLE user_password_reset_codes (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT        NOT NULL,

    code_hash   VARBINARY(32) NOT NULL,
    attempts    INT           NOT NULL DEFAULT 0,

    -- The address the code was sent to. If the account's email changes between issuing and
    -- redeeming, whoever holds the old inbox must not be able to complete a reset.
    email       VARCHAR(255)  NOT NULL,

    expires_at  TIMESTAMP(6)  NOT NULL,
    used_at     TIMESTAMP(6)  NULL,

    created_at  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                              ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_user_password_reset_codes_user FOREIGN KEY (user_id) REFERENCES user_users (id)
) ENGINE = InnoDB;

CREATE INDEX idx_user_password_reset_codes_user_used ON user_password_reset_codes (user_id, used_at);

-- Signing in with a provider. One row per (provider, account at that provider); a table
-- rather than columns on user_users, because a person may link more than one.
CREATE TABLE user_external_identities (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,

    provider      VARCHAR(16)  NOT NULL,

    -- The provider's own immutable id for the person, Google's `sub`. Not the email: people
    -- change their address, and matching on it would hand an account to whoever inherits a
    -- recycled one.
    subject       VARCHAR(255) NOT NULL,

    -- What the provider said the address was when the link was made. For support and
    -- audit, never for lookup.
    email         VARCHAR(255) NULL,

    linked_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_login_at TIMESTAMP(6) NULL,

    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                               ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_user_external_identities_user FOREIGN KEY (user_id) REFERENCES user_users (id),
    -- One provider account opens exactly one exchange account, and one account links a
    -- given provider at most once.
    CONSTRAINT uq_user_external_identities_provider_subject UNIQUE (provider, subject),
    CONSTRAINT uq_user_external_identities_user_provider UNIQUE (user_id, provider),
    CONSTRAINT ck_user_external_identities_provider CHECK (provider IN ('GOOGLE', 'APPLE'))
) ENGINE = InnoDB;
