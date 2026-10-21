-- The general words for prostitution and gambling (MSG36). V5 banned only the phrases that solicit or advertise; the
-- team now wants every post, question, comment, answer and display name that names prostitution or gambling refused,
-- warnings and questions about the topic included ("mại dâm", "cờ bạc", "cá cược", 賭博, 風俗…).
--
-- The filter matches a word typed without accents and ignores spaces and punctuation, so some of these words are also
-- everyday phrases: "co bac nao biet" (có bác nào biết), "bạn gái gọi điện", "con gái bao nhiêu tuổi", "lo để dành".
-- ContentFilter.EVERYDAY_PHRASES lists those phrases; a keyword found only inside one of them is not counted (an
-- unaccented one only when the whole text is typed without accents, so "chơi co bac online" is still refused).
-- Left out on purpose: "gạ tình" (harassment must stay reportable in a post), "mãi dâm" (the column is UNIQUE with
-- accents ignored, so it cannot sit next to "mại dâm"), lottery (xổ số, 宝くじ) and horse racing.
--
-- INSERT IGNORE: a keyword an Admin already added (accents ignored) is kept as it is.

INSERT IGNORE INTO banned_keywords (keyword) VALUES
 -- gambling
 ('cờ bạc'), ('đánh bạc'), ('bài bạc'), ('sòng bạc'), ('cá cược'), ('đặt cược'), ('nhà cái'), ('lô đề'), ('số đề'),
 ('đánh đề'), ('ghi đề'), ('nổ hũ'), ('đá gà'), ('bầu cua'), ('xì dách'), ('xì tố'),
 ('casino'), ('gambling'), ('betting'), ('poker'), ('blackjack'), ('roulette'), ('bookmaker'), ('pachinko'),
 ('賭博'), ('ギャンブル'), ('カジノ'), ('パチンコ'), ('パチスロ'), ('賭け麻雀'),
 -- prostitution
 ('mại dâm'), ('bán dâm'), ('mua dâm'), ('gái gọi'), ('gái bao'), ('trai bao'), ('gái điếm'), ('đi khách'),
 ('khách làng chơi'), ('nhà thổ'), ('lầu xanh'), ('bao nuôi'), ('dịch vụ tình dục'),
 ('prostitution'), ('prostitute'), ('brothel'), ('hooker'),
 ('売春'), ('買春'), ('風俗'), ('援交');
