-- Phase 5 (AC1): the directional matrix adds long single-leg structures
-- (LONG_CALL / LONG_PUT), which carry a bought leg but no sold leg.
ALTER TABLE recommendations ALTER COLUMN short_option_symbol DROP NOT NULL;
ALTER TABLE recommendations ALTER COLUMN short_strike DROP NOT NULL;
