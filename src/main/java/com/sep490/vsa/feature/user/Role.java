package com.sep490.vsa.feature.user;

/** Values of users.role, with the page each role lands on after login. */
public enum Role {
    USER("/", "Người dùng"),
    MODERATOR("/moderator", "Moderator"),
    ACADEMIC_MANAGER("/academic", "Người quản lý học thuật"),
    ADMIN("/admin", "Quản trị viên");

    private final String homePath;
    private final String label;

    Role(String homePath, String label) {
        this.homePath = homePath;
        this.label = label;
    }

    public String homePath() {
        return homePath;
    }

    public String label() {
        return label;
    }
}
