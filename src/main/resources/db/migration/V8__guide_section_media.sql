ALTER TABLE guide_sections
    ADD COLUMN media_id BIGINT NULL,
    ADD CONSTRAINT fk_guide_section_media FOREIGN KEY (media_id) REFERENCES media(id);
