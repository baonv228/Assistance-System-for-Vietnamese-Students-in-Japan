-- New pins that skip the Moderator (user decision 20/11/2026, replaces "top rank only"): the Moderators set, on their
-- overview, whether it is on, the points a member needs and how many pins of one member a day go on the map at once.
-- Read by feature/review/PinService; changed by feature/place/PinSettingsService (and the Admin's settings page).
INSERT INTO system_settings (setting_key, setting_value) VALUES
    ('pin_auto_approve', '1'),
    ('pin_auto_min_points', '100'),
    ('pin_auto_per_day', '3')
ON DUPLICATE KEY UPDATE setting_key = setting_key;
