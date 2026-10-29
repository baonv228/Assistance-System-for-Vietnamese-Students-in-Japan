ALTER TABLE guide_items ADD COLUMN preparation_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE guide_preparation_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    guide_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    notes VARCHAR(2000),
    sort_order INT NOT NULL DEFAULT 0,
    INDEX idx_preparation_guide_order(guide_id, sort_order),
    CONSTRAINT fk_preparation_guide FOREIGN KEY(guide_id) REFERENCES guide_items(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE guide_preparation_progress (
    user_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    completed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(user_id, task_id),
    CONSTRAINT fk_preparation_progress_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_preparation_progress_task FOREIGN KEY(task_id) REFERENCES guide_preparation_tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB;
