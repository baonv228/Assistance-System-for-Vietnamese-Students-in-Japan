-- Banned keywords for prostitution and gambling (MSG36). Posts, questions, comments and answers are checked against
-- the whole banned_keywords list when they are sent and edited, and a post or question waiting for its media scan is
-- checked again right before it is published (BlogPublishService).
--
-- Agreed with the team: only phrases used to solicit or advertise are banned, so warnings and questions about the
-- topic stay possible ("Pachinko có phải cờ bạc không?", "bị gạ tình thì báo ai?"). General words ("cờ bạc",
-- "mại dâm", "cá cược", 賭博, 風俗…) are left out on purpose.
--
-- The filter (ContentFilter) also matches a phrase typed without accents and its words run together, so phrases
-- whose unaccented form is an everyday phrase are left out too: "lô đề" ("lo để"), "số đề" ("số để"), "nổ hũ"
-- ("nó hư"), "đá gà" ("da gà"), "gái gọi" ("bạn gái gọi điện"), "gái bao" ("con gái bao nhiêu tuổi"),
-- "trai bao" ("bạn trai bao nhiêu"), "bán thân" ("bạn thân"). Keywords in Japanese are found inside any text, so
-- 援交 is left out (支援交流). ContentFilterTest checks this list against ordinary sentences.
--
-- INSERT IGNORE: a keyword an Admin already added (the column is UNIQUE, accents ignored) is kept as it is.

INSERT IGNORE INTO banned_keywords (keyword) VALUES
 -- gambling: bookmakers, betting tips, lottery numbers, casino and card games for money
 ('kèo nhà cái'), ('nhà cái uy tín'), ('nhà cái online'), ('nhà cái hàng đầu'), ('nhà cái bóng đá'),
 ('nhà cái cá cược'), ('soi kèo'), ('tỷ lệ kèo'), ('tỉ lệ kèo'),
 ('cá cược bóng đá'), ('cá cược online'), ('cá cược thể thao'),
 ('lô đề online'), ('chơi lô đề'), ('đánh lô đề'), ('soi cầu lô'),
 ('tài xỉu'), ('xóc đĩa'), ('game bài đổi thưởng'), ('bắn cá đổi thưởng'), ('nổ hũ đổi thưởng'), ('nổ hũ online'),
 ('quay hũ'), ('casino online'), ('sòng bạc online'), ('đá gà online'), ('đá gà trực tiếp'), ('đá gà thomo'),
 ('baccarat'), ('online casino'), ('sports betting'),
 -- gambling sites advertised to Vietnamese users
 ('fb88'), ('w88'), ('m88'), ('188bet'), ('12bet'), ('fun88'), ('8xbet'), ('kubet'), ('jun88'), ('new88'), ('hi88'),
 ('f8bet'), ('shbet'), ('789bet'), ('vn88'), ('ae888'), ('five88'), ('sv388'), ('bong88'), ('lucky88'), ('go88'),
 ('sunwin'), ('hitclub'), ('rikvip'), ('thabet'),
 -- gambling in Japanese: online and underground casinos (illegal in Japan)
 ('オンラインカジノ'), ('ネットカジノ'), ('闇カジノ'), ('裏カジノ'), ('闇スロ'),
 -- prostitution: paid sex, kept partners, sexual massage
 ('gái gọi cao cấp'), ('gái gọi giá rẻ'), ('dịch vụ gái gọi'), ('gái làng chơi'), ('nhận đi khách'),
 ('đi khách qua đêm'), ('nhận bao nuôi'), ('tìm người bao nuôi'), ('cần người bao nuôi'), ('kích dục'),
 ('dịch vụ tươi mát'), ('massage happy ending'), ('happy ending massage'), ('erotic massage'), ('chat sex'),
 ('sugar baby'), ('sugar daddy'), ('sugar mommy'), ('call girl'), ('escort service'),
 -- prostitution in Japanese, also written in romaji
 ('援助交際'), ('パパ活'), ('ママ活'), ('デリヘル'), ('ソープランド'), ('ピンサロ'), ('立ちんぼ'), ('jkビジネス'),
 ('裏風俗'), ('風俗求人'), ('papa katsu'), ('enjo kosai');
