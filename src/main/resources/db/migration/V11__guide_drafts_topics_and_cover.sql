CREATE TABLE guide_topics (
 code VARCHAR(30) PRIMARY KEY,
 title VARCHAR(100) NOT NULL,
 sort_order INT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
INSERT INTO guide_topics(code,title,sort_order) VALUES
 ('LIVING','Sinh hoạt',1),('PROCEDURES','Thủ tục',2),('STUDY','Học tập',3),
 ('HOUSING','Nhà ở',4),('WORK','Việc làm',5),('HEALTH','Y tế',6),('TRANSPORT','Đi lại',7),('OTHER','Khác',99);
ALTER TABLE guide_items
 MODIFY COLUMN stage_code VARCHAR(10) NULL,
 ADD COLUMN topic_code VARCHAR(30) NULL,
 ADD COLUMN source_url VARCHAR(2000) NULL,
 ADD COLUMN cover_media_id BIGINT NULL,
 ADD CONSTRAINT fk_guide_topic FOREIGN KEY(topic_code) REFERENCES guide_topics(code),
 ADD CONSTRAINT fk_guide_cover FOREIGN KEY(cover_media_id) REFERENCES media(id),
 ADD INDEX idx_guide_topic_status(topic_code,published,created_at);
