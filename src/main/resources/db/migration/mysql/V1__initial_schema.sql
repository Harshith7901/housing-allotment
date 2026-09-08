-- Housing allotment scheme: initial schema (MySQL 8).
--
-- Only the MySQL dialect is maintained as migrations. The zero-dependency `dev` profile
-- runs on in-memory H2 with Hibernate generating the schema from the same entities, which
-- avoids maintaining a second hand-written dialect whose divergence from this one would be
-- silent. That trade is recorded in the README.
--
-- Conventions used throughout:
--   * every hash is 64 hex characters (SHA-256)
--   * DATETIME(6) everywhere, always stored as UTC
--   * no ON DELETE CASCADE anywhere: nothing in this system is ever deleted, and a
--     cascade is a loaded gun pointed at an audit trail

CREATE TABLE scheme (
    scheme_code               VARCHAR(32)  NOT NULL,
    name                      VARCHAR(200) NOT NULL,
    phase                     VARCHAR(32)  NOT NULL,
    active_rule_set_version   VARCHAR(64)  NULL,
    active_roll_id            VARCHAR(64)  NULL,
    created_at                DATETIME(6)  NULL,
    phase_changed_at          DATETIME(6)  NULL,
    phase_changed_by          VARCHAR(128) NULL,
    version                   BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (scheme_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Rule sets are immutable once published. There is deliberately no UPDATE path in the
-- application; amending the rules means publishing a new version, and every roll records
-- the version and hash it was frozen under.
CREATE TABLE rule_set (
    version                   VARCHAR(64)  NOT NULL,
    scheme_code               VARCHAR(32)  NOT NULL,
    encoded                   LONGTEXT     NOT NULL,
    rule_set_hash             VARCHAR(64)  NOT NULL,
    published_at              DATETIME(6)  NOT NULL,
    published_by              VARCHAR(128) NOT NULL,
    published_rules_uri       VARCHAR(500) NULL,
    PRIMARY KEY (version),
    KEY ix_rule_set_scheme (scheme_code, published_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE application (
    application_id                VARCHAR(32)  NOT NULL,
    scheme_code                   VARCHAR(32)  NOT NULL,
    channel                       VARCHAR(16)  NOT NULL,
    submitted_at                  DATETIME(6)  NOT NULL,
    batch_id                      VARCHAR(64)  NULL,
    idempotency_key               VARCHAR(128) NULL,
    full_name                     VARCHAR(200) NOT NULL,
    relative_name                 VARCHAR(200) NULL,
    -- Keyed SHA-256 of the government identifier, never the identifier itself.
    government_id_hash            VARCHAR(64)  NULL,
    government_id_last4           VARCHAR(8)   NULL,
    phone                         VARCHAR(24)  NULL,
    date_of_birth                 DATE         NULL,
    address_line                  VARCHAR(500) NULL,
    ward_code                     VARCHAR(32)  NULL,
    residency_years               INT          NOT NULL DEFAULT 0,
    vertical_code                 VARCHAR(32)  NOT NULL,
    horizontal_codes              VARCHAR(500) NULL,
    unit_type_preferences         VARCHAR(500) NULL,
    status                        VARCHAR(32)  NOT NULL,
    cluster_id                    VARCHAR(64)  NULL,
    superseded_by_application_id  VARCHAR(32)  NULL,
    status_reason                 VARCHAR(500) NULL,
    received_by                   VARCHAR(128) NULL,
    created_at                    DATETIME(6)  NULL,
    updated_at                    DATETIME(6)  NULL,
    version                       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (application_id),
    -- Makes intake idempotent at the storage layer, not merely in application code: the
    -- applicant who submits twice because the page hung cannot create two applications
    -- even under a race.
    UNIQUE KEY ux_application_idempotency (idempotency_key),
    KEY ix_application_scheme_status (scheme_code, status),
    KEY ix_application_cluster (cluster_id),
    KEY ix_application_gid (government_id_hash),
    KEY ix_application_phone (phone),
    KEY ix_application_dob (date_of_birth)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE duplicate_link (
    link_id               VARCHAR(72)   NOT NULL,
    scheme_code           VARCHAR(32)   NOT NULL,
    left_application_id   VARCHAR(32)   NOT NULL,
    right_application_id  VARCHAR(32)   NOT NULL,
    method                VARCHAR(40)   NOT NULL,
    score                 DOUBLE        NOT NULL,
    decision              VARCHAR(24)   NOT NULL,
    features              VARCHAR(4000) NULL,
    rationale             VARCHAR(2000) NULL,
    shared_blocking_keys  VARCHAR(500)  NULL,
    detected_at           DATETIME(6)   NOT NULL,
    review_decision       VARCHAR(24)   NOT NULL,
    reviewed_by           VARCHAR(128)  NULL,
    reviewed_at           DATETIME(6)   NULL,
    review_note           VARCHAR(1000) NULL,
    PRIMARY KEY (link_id),
    KEY ix_duplicate_left (left_application_id),
    KEY ix_duplicate_right (right_application_id),
    KEY ix_duplicate_review (scheme_code, review_decision, score)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE eligibility_check (
    check_id        VARCHAR(72)   NOT NULL,
    application_id  VARCHAR(32)   NOT NULL,
    check_code      VARCHAR(64)   NOT NULL,
    passed          BOOLEAN       NOT NULL,
    reason          VARCHAR(1000) NULL,
    evidence_ref    VARCHAR(300)  NULL,
    decided_by      VARCHAR(128)  NOT NULL,
    decided_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (check_id),
    KEY ix_eligibility_application (application_id, decided_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE flat_unit (
    unit_id           VARCHAR(32)  NOT NULL,
    scheme_code       VARCHAR(32)  NOT NULL,
    block             VARCHAR(32)  NULL,
    unit_type         VARCHAR(32)  NOT NULL,
    floor_number      INT          NOT NULL DEFAULT 0,
    withdrawn         BOOLEAN      NOT NULL DEFAULT FALSE,
    withdrawn_reason  VARCHAR(500) NULL,
    PRIMARY KEY (unit_id),
    KEY ix_unit_scheme_type (scheme_code, unit_type),
    KEY ix_unit_available (scheme_code, withdrawn)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- A frozen roll. Immutable: the application never updates these rows, and the roll hash
-- is recomputed and checked on every read.
CREATE TABLE draw_roll (
    roll_id                 VARCHAR(64)   NOT NULL,
    scheme_code             VARCHAR(32)   NOT NULL,
    rule_set_version        VARCHAR(64)   NOT NULL,
    rule_set_hash           VARCHAR(64)   NOT NULL,
    frozen_at               DATETIME(6)   NOT NULL,
    frozen_by               VARCHAR(128)  NOT NULL,
    roll_hash               VARCHAR(64)   NOT NULL,
    entry_count             INT           NOT NULL,
    inventory_count         INT           NOT NULL,
    exclusion_summary       VARCHAR(4000) NULL,
    superseded_by_roll_id   VARCHAR(64)   NULL,
    PRIMARY KEY (roll_id),
    KEY ix_roll_scheme (scheme_code, frozen_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Snapshot of the attributes each application was drawn under. Duplicates columns on
-- `application` on purpose: the application row is a living record, this is the frozen one
-- that the hash covers.
CREATE TABLE roll_entry (
    entry_id               VARCHAR(100) NOT NULL,
    roll_id                VARCHAR(64)  NOT NULL,
    application_id         VARCHAR(32)  NOT NULL,
    cluster_id             VARCHAR(64)  NOT NULL,
    vertical_code          VARCHAR(32)  NOT NULL,
    horizontal_codes       VARCHAR(500) NULL,
    residency_years        INT          NOT NULL DEFAULT 0,
    unit_type_preferences  VARCHAR(500) NULL,
    PRIMARY KEY (entry_id),
    -- One ticket per application per roll, enforced by the database.
    UNIQUE KEY ux_roll_entry (roll_id, application_id),
    KEY ix_roll_entry_roll (roll_id),
    KEY ix_roll_entry_application (application_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE draw (
    draw_id                     VARCHAR(64)   NOT NULL,
    scheme_code                 VARCHAR(32)   NOT NULL,
    roll_id                     VARCHAR(64)   NOT NULL,
    roll_hash                   VARCHAR(64)   NOT NULL,
    rule_set_version            VARCHAR(64)   NOT NULL,
    status                      VARCHAR(24)   NOT NULL,
    commitment_hex              VARCHAR(64)   NOT NULL,
    committed_at                DATETIME(6)   NOT NULL,
    committed_by                VARCHAR(128)  NOT NULL,
    entropy_source_description  VARCHAR(300)  NOT NULL,
    -- Secret until the reveal. A database column is not a real secret; see the README on
    -- moving this to a key vault or a sealed envelope with a separate custodian.
    nonce                       VARCHAR(128)  NULL,
    public_entropy              VARCHAR(300)  NULL,
    seed_hex                    VARCHAR(64)   NULL,
    executed_at                 DATETIME(6)   NULL,
    executed_by                 VARCHAR(128)  NULL,
    result_hash                 VARCHAR(64)   NULL,
    seat_plan                   LONGTEXT      NULL,
    pool_summary                LONGTEXT      NULL,
    published_at                DATETIME(6)   NULL,
    published_by                VARCHAR(128)  NULL,
    annulment_reason            VARCHAR(1000) NULL,
    version                     BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (draw_id),
    KEY ix_draw_scheme (scheme_code, committed_at),
    KEY ix_draw_roll (roll_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- One row per applicant per draw, not one per winner. This is what makes "why not me?"
-- a single indexed lookup years later.
CREATE TABLE draw_selection (
    selection_id       VARCHAR(100)  NOT NULL,
    draw_id            VARCHAR(64)   NOT NULL,
    application_id     VARCHAR(32)   NOT NULL,
    outcome            VARCHAR(24)   NOT NULL,
    pool_code          VARCHAR(32)   NOT NULL,
    ticket_hex         VARCHAR(64)   NOT NULL,
    residency_tier     INT           NOT NULL,
    rank_in_pool       INT           NOT NULL,
    waitlist_position  INT           NULL,
    reason_code        VARCHAR(48)   NOT NULL,
    reason_text        VARCHAR(2000) NOT NULL,
    PRIMARY KEY (selection_id),
    UNIQUE KEY ux_selection (draw_id, application_id),
    KEY ix_selection_draw_outcome (draw_id, outcome, ticket_hex),
    KEY ix_selection_application (application_id),
    KEY ix_selection_waitlist (draw_id, pool_code, waitlist_position)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE unit_allotment (
    allotment_id              VARCHAR(140)  NOT NULL,
    draw_id                   VARCHAR(64)   NOT NULL,
    application_id            VARCHAR(32)   NOT NULL,
    unit_id                   VARCHAR(32)   NOT NULL,
    unit_type                 VARCHAR(32)   NOT NULL,
    block                     VARCHAR(32)   NULL,
    pick_order                INT           NOT NULL,
    preference_rank_honoured  INT           NOT NULL,
    basis                     VARCHAR(500)  NOT NULL,
    status                    VARCHAR(32)   NOT NULL,
    offered_at                DATETIME(6)   NOT NULL,
    decided_at                DATETIME(6)   NULL,
    decided_by                VARCHAR(128)  NULL,
    decision_reason           VARCHAR(1000) NULL,
    fills_vacancy_of          VARCHAR(140)  NULL,
    -- '<draw_id>:<unit_id>' while the offer is live, NULL once forfeited. A unique index
    -- over a nullable column allows any number of NULLs, so this enforces "at most one
    -- live offer per flat per draw" in the database while keeping the full forfeiture
    -- history. A unique index on (draw_id, unit_id) could not: a forfeited row and the
    -- promotion that replaces it legitimately share both.
    live_unit_key             VARCHAR(100)  NULL,
    version                   BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (allotment_id),
    UNIQUE KEY ux_allotment_live_unit (live_unit_key),
    KEY ix_allotment_draw (draw_id, pick_order),
    KEY ix_allotment_application (application_id, offered_at),
    KEY ix_allotment_unit (draw_id, unit_id, status),
    KEY ix_allotment_vacancy (fills_vacancy_of)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Append-only. In production, grant the application's database user INSERT and SELECT on
-- this table and nothing else; a role that can UPDATE an audit log makes the chain a
-- formality. See the README.
CREATE TABLE audit_event (
    event_seq      BIGINT       NOT NULL,
    event_id       VARCHAR(64)  NOT NULL,
    occurred_at    DATETIME(6)  NOT NULL,
    actor          VARCHAR(128) NOT NULL,
    actor_role     VARCHAR(32)  NOT NULL,
    action         VARCHAR(64)  NOT NULL,
    entity_type    VARCHAR(32)  NOT NULL,
    entity_id      VARCHAR(100) NOT NULL,
    payload        LONGTEXT     NULL,
    previous_hash  VARCHAR(64)  NOT NULL,
    hash           VARCHAR(64)  NOT NULL,
    PRIMARY KEY (event_seq),
    UNIQUE KEY ux_audit_event_id (event_id),
    -- A unique index on previous_hash makes two events claiming the same predecessor a
    -- constraint violation rather than a silently forked chain, which is the failure mode
    -- of concurrent appends.
    UNIQUE KEY ux_audit_previous (previous_hash),
    KEY ix_audit_entity (entity_type, entity_id, event_seq),
    KEY ix_audit_actor (actor, event_seq),
    KEY ix_audit_action (action, event_seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
