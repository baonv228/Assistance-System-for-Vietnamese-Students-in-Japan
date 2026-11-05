CREATE TABLE faq_topics(code VARCHAR(30) PRIMARY KEY,title VARCHAR(100) NOT NULL,sort_order INT NOT NULL DEFAULT 0) ENGINE=InnoDB;
INSERT INTO faq_topics VALUES ('PREPARATION','Chuẩn bị trước khi sang Nhật',1),('LIVING','Sinh hoạt và đời sống',2),('STUDY_WORK','Học tập và công việc',3),('PROCEDURES','Thủ tục và giấy tờ',4),('LANGUAGE','Ngôn ngữ và giao tiếp',5),('OTHER','Khác',99);
CREATE TABLE faq_entries(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,question VARCHAR(300) NOT NULL,answer MEDIUMTEXT NOT NULL,topic_code VARCHAR(30) NOT NULL,
 published BOOLEAN NOT NULL DEFAULT TRUE,created_by BIGINT NOT NULL,updated_by BIGINT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 INDEX idx_faq_public_topic(published,topic_code,updated_at),
 CONSTRAINT fk_faq_topic FOREIGN KEY(topic_code) REFERENCES faq_topics(code),
 CONSTRAINT fk_faq_creator FOREIGN KEY(created_by) REFERENCES users(id),CONSTRAINT fk_faq_updater FOREIGN KEY(updated_by) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE faq_resources(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,faq_id BIGINT NOT NULL,kind VARCHAR(10) NOT NULL,target_id BIGINT,url VARCHAR(2000),label VARCHAR(200) NOT NULL,sort_order INT NOT NULL,
 CONSTRAINT fk_faq_resource FOREIGN KEY(faq_id) REFERENCES faq_entries(id) ON DELETE CASCADE,
 CONSTRAINT ck_faq_resource CHECK(kind IN ('GUIDE','BLOG','QUESTION','EXTERNAL'))
) ENGINE=InnoDB;
CREATE TABLE faq_sources(
 question_id BIGINT NOT NULL,answer_id BIGINT NOT NULL,faq_id BIGINT NOT NULL,processed_by BIGINT NOT NULL,processed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(question_id,answer_id),
 CONSTRAINT fk_faq_source_question FOREIGN KEY(question_id) REFERENCES blogs(id) ON DELETE CASCADE,
 CONSTRAINT fk_faq_source_answer FOREIGN KEY(answer_id) REFERENCES comments(id) ON DELETE CASCADE,
 CONSTRAINT fk_faq_source_entry FOREIGN KEY(faq_id) REFERENCES faq_entries(id),
 CONSTRAINT fk_faq_source_moderator FOREIGN KEY(processed_by) REFERENCES users(id)
) ENGINE=InnoDB;
