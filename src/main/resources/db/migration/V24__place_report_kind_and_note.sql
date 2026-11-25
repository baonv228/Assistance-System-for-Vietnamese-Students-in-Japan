-- "Báo cáo địa điểm" (user decision 20/11/2026): a member reports a place as closed ("Đã đóng cửa", CLOSED, still 5
-- reports before the Moderators look) or with wrong details ("Sai thông tin", WRONG_INFO, in the Moderators' queue from
-- the first report), always with a reason. One report of each kind per member and place. The rows already there were
-- "đã đóng cửa" reports, without a reason.
ALTER TABLE place_closed_reports
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'CLOSED' AFTER user_id,
    ADD COLUMN note VARCHAR(300) NULL AFTER kind,
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (place_id, user_id, kind),
    ADD CONSTRAINT ck_place_report_kind CHECK (kind IN ('CLOSED', 'WRONG_INFO'));

-- A Moderator handling the "Sai thông tin" reports is written to the place's history ("Xử lý 2 báo cáo sai thông tin")
ALTER TABLE place_history DROP CHECK ck_phist_action;
ALTER TABLE place_history ADD CONSTRAINT ck_phist_action CHECK (action IN
    ('CREATED','APPROVED','REJECTED','EDITED','CLOSED','REOPENED','REMOVED','RESTORED','MERGED','REPORTS_HANDLED'));
