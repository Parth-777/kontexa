-- Insights V2 inbox: additive read_at for persisted read/unread without hiding cards.
-- Run once in Postgres before relying on inbox read styling.

ALTER TABLE insight_cards
    ADD COLUMN IF NOT EXISTS read_at TIMESTAMP NULL;

COMMENT ON COLUMN insight_cards.read_at IS
    'When the user opened/marked the insight as read. Null = unread. Status DECLINED/EXPIRED still hide from inbox.';
