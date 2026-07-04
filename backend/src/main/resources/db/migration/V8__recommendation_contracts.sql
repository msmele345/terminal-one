-- Phase 5 (AC5): persist the §7 contract count the engine sized each
-- recommendation to. Defaults to 1 so existing rows stay valid.
ALTER TABLE recommendations
    ADD COLUMN contracts INTEGER NOT NULL DEFAULT 1;