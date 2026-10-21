CREATE TABLE whatsapp_onboarding (
 token_hash VARCHAR(64) PRIMARY KEY,
 actor UUID NOT NULL REFERENCES app_user(id),
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 state VARCHAR(30) NOT NULL DEFAULT 'CREATED',
 encrypted_token TEXT,
 waba_id VARCHAR(80),
 phone_id VARCHAR(80),
 display_phone VARCHAR(80),
 verified_name VARCHAR(200),
 error_code VARCHAR(100)
);
CREATE INDEX onboarding_expiry ON whatsapp_onboarding(expires_at);
CREATE TABLE whatsapp_credentials (
 id INTEGER PRIMARY KEY CHECK(id=1),
 encrypted_token TEXT NOT NULL,
 waba_id VARCHAR(80) NOT NULL,
 phone_id VARCHAR(80) NOT NULL,
 display_phone VARCHAR(80) NOT NULL,
 verified_name VARCHAR(200) NOT NULL,
 connected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
