ALTER TABLE guide_items
    ADD COLUMN deleted_at DATETIME NULL,
    ADD INDEX idx_guide_deleted_published(deleted_at, published, created_at);
