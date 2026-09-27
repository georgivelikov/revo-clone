-- plans/phase-05-cards.md's Card screen note: "requires a step-up (re-enter passcode; in this
-- project, a modal confirming against a stored PIN)". No PIN concept exists anywhere else in
-- this project (no per-user app passcode), so it is scoped here as the card's own PIN, chosen
-- by the cardholder at issuance - exactly what happens with a real physical card. Stored as a
-- salted hash (see CardPinHasher), never in clear, never the same secret as pan_hash/pan_encrypted.
alter table card add column pin_hash text not null;
