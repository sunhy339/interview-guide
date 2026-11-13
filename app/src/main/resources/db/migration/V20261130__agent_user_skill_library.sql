CREATE TABLE IF NOT EXISTS agent_user_skills (
    id BIGSERIAL PRIMARY KEY,
    skill_id VARCHAR(36) NOT NULL UNIQUE,
    owner_id VARCHAR(64) NOT NULL,
    skill_key VARCHAR(64) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    content TEXT NOT NULL,
    version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    security_flagged BOOLEAN NOT NULL DEFAULT FALSE,
    security_notice VARCHAR(500),
    reviewed_at TIMESTAMP,
    enabled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_user_skills_owner_updated
    ON agent_user_skills (owner_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_user_skills_owner_key_version
    ON agent_user_skills (owner_id, skill_key, version DESC);

CREATE INDEX IF NOT EXISTS idx_agent_user_skills_owner_status
    ON agent_user_skills (owner_id, status, updated_at DESC);
