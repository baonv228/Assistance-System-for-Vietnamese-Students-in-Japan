-- Existing items and creation timestamps are preserved.
ALTER TABLE guide_items
 ADD COLUMN summary TEXT NULL,
 ADD COLUMN published BOOLEAN NOT NULL DEFAULT TRUE,
 ADD INDEX idx_guide_topic_created(stage_code,published,created_at);
CREATE TABLE guide_sections (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 guide_id BIGINT NOT NULL,
 sort_order INT NOT NULL,
 heading VARCHAR(200) NOT NULL,
 content MEDIUMTEXT NOT NULL,
 image_url VARCHAR(2000),
 reference_url VARCHAR(2000),
 CONSTRAINT fk_guide_section FOREIGN KEY(guide_id) REFERENCES guide_items(id) ON DELETE CASCADE,
 UNIQUE KEY uk_guide_section_order(guide_id,sort_order)
) ENGINE=InnoDB;
