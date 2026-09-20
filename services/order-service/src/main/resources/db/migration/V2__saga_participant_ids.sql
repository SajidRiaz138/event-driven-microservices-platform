-- V2: persist the ids the saga issues to participants, so later steps reuse the SAME id
-- rather than minting a fresh random one. Fixes the correlation gap where ReleaseStock /
-- CapturePayment carried ids that matched nothing the participant had seen.
-- (Additive migration — V1 is already applied and immutable.)

ALTER TABLE saga_instance
    ADD COLUMN reservation_id      UUID,
    ADD COLUMN payment_intent_id   UUID,
    ADD COLUMN payment_attempt_id  UUID;
