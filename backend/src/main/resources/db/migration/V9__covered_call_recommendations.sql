-- Phase 6 (AC1): covered-call overlays sell a call against already-held
-- shares, so a recommendation can have a sold option leg without a bought
-- option leg.
ALTER TABLE recommendations ALTER COLUMN long_option_symbol DROP NOT NULL;
ALTER TABLE recommendations ALTER COLUMN long_strike DROP NOT NULL;
