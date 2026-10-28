-- Photos and videos are also read by Gemini (MediaScanService, MediaReader): the text in them and the words spoken in a
-- video go through the banned keywords and the link rule. Each reading is a moderation_scans row of engine GEMINI next
-- to the OpenAI one (PASS, FLAG with the reason shown to the author in "reason" of detail, or ERROR).
--
-- A CHECK constraint cannot be changed in place, so ck_scan_engine is dropped and added again (MySQL 8.0.16+).

ALTER TABLE moderation_scans DROP CHECK ck_scan_engine;

ALTER TABLE moderation_scans
  ADD CONSTRAINT ck_scan_engine CHECK (engine IN ('KEYWORD','OPENAI','GEMINI'));
