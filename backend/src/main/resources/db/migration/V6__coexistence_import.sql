CREATE TABLE whatsapp_import (
 kind VARCHAR(30) NOT NULL,
 external_id VARCHAR(300) NOT NULL,
 local_id UUID NOT NULL,
 removed BOOLEAN NOT NULL DEFAULT FALSE,
 imported_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(kind,external_id)
);
CREATE TABLE whatsapp_sync_progress (
 event_key VARCHAR(64) NOT NULL REFERENCES webhook_event(event_key),
 phase INTEGER NOT NULL CHECK(phase BETWEEN 0 AND 2),
 chunk_order INTEGER NOT NULL CHECK(chunk_order >= 0),
 progress INTEGER NOT NULL CHECK(progress BETWEEN 0 AND 100),
 received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(event_key,phase,chunk_order)
);
