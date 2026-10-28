-- Sample content for local development only (profile "local"). Loaded once by DevDataSeeder when the
-- places table is empty. Content comes from the clickable prototype (Nippori area).
-- Dates are relative to today so the homepage always has upcoming events and recent posts.

SET @admin   = (SELECT id FROM users WHERE email = 'admin@gmail.com');
SET @mod     = (SELECT id FROM users WHERE email = 'moderator@gmail.com');
SET @acad    = (SELECT id FROM users WHERE email = 'academic@gmail.com');
SET @minh    = (SELECT id FROM users WHERE email = 'user@gmail.com');
SET @lananh  = (SELECT id FROM users WHERE email = 'mentor@gmail.com');
SET @hoang   = (SELECT id FROM users WHERE email = 'kouhai@gmail.com');
SET @nippori = (SELECT id FROM areas WHERE code = '131181');  -- Arakawa-ku, Tokyo (V10)

-- ---------------------------------------------------------------- places
INSERT INTO places (osm_id, name_vi, name_ja, name_en, category_id, area_id, address, lat, lng, source, status, created_by, reviewed_by, reviewed_at) VALUES
 ('N772389775', 'Ga Nippori', '日暮里駅', 'Nippori Station', 'station', @nippori, 'Higashi-Nippori 6, Arakawa, Tokyo', 35.728920, 139.771364, 'OSM', 'ACTIVE', @admin, @mod, NOW()),
 (NULL, 'Trường Nhật ngữ FPT', 'FPT日本語学校', 'FPT Japanese Language School', 'school', @nippori, 'Higashi-Nippori, Arakawa, Tokyo', 35.729600, 139.772800, 'TEAM', 'ACTIVE', @admin, @mod, NOW()),
 (NULL, 'Konbini Midori', 'コンビニみどり', 'Midori Mart', 'konbini', @nippori, 'Higashi-Nippori 5', 35.727500, 139.772000, 'TEAM', 'ACTIVE', @admin, @mod, NOW()),
 (NULL, 'Quán cơm Hana', 'はな食堂', 'Hana Shokudo', 'food', @nippori, 'Ghim do cộng đồng thêm', 35.726700, 139.773500, 'USER', 'ACTIVE', @lananh, @mod, NOW()),
 (NULL, 'Siêu thị Sakura', 'さくらスーパー', 'Sakura Super', 'super', @nippori, 'Higashi-Nippori 6', 35.730900, 139.773700, 'TEAM', 'ACTIVE', @admin, @mod, NOW()),
 (NULL, 'Văn phòng quận Arakawa', '荒川区役所', 'Arakawa City Office', 'ward', @nippori, 'Arakawa, Tokyo', 35.736000, 139.783400, 'TEAM', 'ACTIVE', @admin, @mod, NOW()),
 (NULL, 'Phòng khám Aoi', 'あおいクリニック', 'Aoi Clinic', 'hospital', @nippori, 'Yanaka 7', 35.727900, 139.767400, 'TEAM', 'ACTIVE', @admin, @mod, NOW()),
 (NULL, 'Bưu điện Nippori', '日暮里郵便局', 'Nippori Post Office', 'bank', @nippori, 'Higashi-Nippori 4', 35.725400, 139.771800, 'TEAM', 'ACTIVE', @admin, @mod, NOW()),
 (NULL, 'Hiệu thuốc Mori', 'もり薬局', 'Mori Pharmacy', 'pharmacy', @nippori, 'Higashi-Nippori 5', 35.728300, 139.772400, 'TEAM', 'ACTIVE', @admin, @mod, NOW()),
 (NULL, 'Ramen Aoba', 'ラーメン青葉', 'Ramen Aoba', 'food', @nippori, 'Yanaka 3', 35.726200, 139.767000, 'TEAM', 'ACTIVE', @admin, @mod, NOW()),
 (NULL, 'Tiệm giặt Coin Laundry Sora', 'コインランドリーそら', 'Coin Laundry Sora', 'laundry', @nippori, 'Nishi-Nippori 2', 35.725000, 139.768500, 'TEAM', 'ACTIVE', @admin, @mod, NOW()),
 (NULL, 'Phở Hà Nội Nippori', 'フォーハノイ', 'Pho Hanoi', 'food', @nippori, 'Ghim do cộng đồng thêm', 35.724000, 139.773000, 'USER', 'PENDING', @hoang, NULL, NULL);

SET @p_station = (SELECT id FROM places WHERE name_vi = 'Ga Nippori');
SET @p_school  = (SELECT id FROM places WHERE name_vi = 'Trường Nhật ngữ FPT');
SET @p_konbini = (SELECT id FROM places WHERE name_vi = 'Konbini Midori');
SET @p_hana    = (SELECT id FROM places WHERE name_vi = 'Quán cơm Hana');
SET @p_ward    = (SELECT id FROM places WHERE name_vi = 'Văn phòng quận Arakawa');
SET @p_post    = (SELECT id FROM places WHERE name_vi = 'Bưu điện Nippori');
SET @p_ramen   = (SELECT id FROM places WHERE name_vi = 'Ramen Aoba');

-- ---------------------------------------------------------------- reviews
INSERT INTO reviews (author_id, place_id, rating, content, status, like_count, created_at) VALUES
 (@lananh, @p_station, 5, 'Cổng Bắc gần trường hơn cổng Nam. Máy bán vé có nút đổi sang tiếng Anh ở góc trên bên phải.', 'PUBLISHED', 14, NOW() - INTERVAL 20 DAY),
 (@hoang,  @p_station, 4, 'Khoảng 8 giờ sáng rất đông, nên đi sớm 15 phút.', 'PUBLISHED', 6, NOW() - INTERVAL 400 DAY),
 (@lananh, @p_school,  5, 'Tuần đầu nên đến sớm để làm thủ tục và nhận lịch học. Quanh trường có nhiều quán ăn giá sinh viên.', 'PUBLISHED', 11, NOW() - INTERVAL 35 DAY),
 (@hoang,  @p_school,  4, 'Mình mới nhập học tháng này. Các anh chị khóa trước chỉ đường rất kỹ nên đỡ bỡ ngỡ.', 'PUBLISHED', 3, NOW() - INTERVAL 6 DAY),
 (@hoang,  @p_konbini, 5, 'Nhân viên quen khách Việt và nói chậm. Có máy ATM rút được thẻ quốc tế.', 'PUBLISHED', 9, NOW() - INTERVAL 15 DAY),
 (@lananh, @p_hana,    5, 'Cơm phần 700 yên, chủ quán hiểu tiếng Việt cơ bản. Quán không có trên Google Maps nên mình cắm ghim.', 'PUBLISHED', 8, NOW() - INTERVAL 40 DAY),
 (@lananh, @p_ward,    4, 'Đăng ký cư trú ở tầng 1, lấy số ở máy cạnh cửa. Nhớ mang thẻ cư trú và hộ chiếu.', 'PUBLISHED', 18, NOW() - INTERVAL 60 DAY),
 (@hoang,  @p_post,    4, 'Gửi đồ về Việt Nam cần điền tờ khai bằng chữ Latin. Quầy có mẫu sẵn.', 'PUBLISHED', 4, NOW() - INTERVAL 90 DAY),
 (@lananh, @p_ramen,   4, 'Ramen shoyu 950 yên, tăng 100 yên so với năm ngoái. Mua vé ở máy trước khi ngồi.', 'PUBLISHED', 12, NOW() - INTERVAL 12 DAY);

-- ---------------------------------------------------------------- events (Moderator)
INSERT INTO events (title, start_date, end_date, location_name, lat, lng, cost_note, tip, created_by) VALUES
 ('Lễ hội Obon công viên Yanaka', CURDATE() - INTERVAL 60 DAY, CURDATE() - INTERVAL 58 DAY, 'Công viên Yanaka', 35.725800, 139.766000, 'Miễn phí', 'Buổi tối có múa Bon Odori quanh sân khấu giữa công viên.', @mod),
 ('Chợ phiên cuối tuần phố Yanaka Ginza', CURDATE() - INTERVAL 1 DAY, CURDATE() + INTERVAL 1 DAY, 'Phố Yanaka Ginza', 35.727400, 139.765800, 'Miễn phí vào cửa', 'Nhiều món ăn vặt 100 đến 300 yên, nên mang tiền lẻ.', @mod),
 ('Lễ hội phố vải Nippori', CURDATE() + INTERVAL 3 DAY, CURDATE() + INTERVAL 4 DAY, 'Phố vải Nippori, Higashi-Nippori', 35.726500, 139.774200, 'Miễn phí', 'Nhiều cửa hàng vải giảm giá trong hai ngày lễ.', @mod),
 ('Lễ hội mùa thu đền Suwa', CURDATE() + INTERVAL 14 DAY, CURDATE() + INTERVAL 15 DAY, 'Đền Suwa, Nishi-Nippori', 35.730300, 139.768200, 'Miễn phí', 'Có quầy đồ ăn và biểu diễn trống taiko buổi tối.', @mod),
 ('Ngắm hoa anh đào ở công viên Ueno', CURDATE() + INTERVAL 160 DAY, CURDATE() + INTERVAL 171 DAY, 'Công viên Ueno, cách Nippori hai ga', NULL, NULL, 'Miễn phí', 'Đi trước 9 giờ sáng để có chỗ ngồi.', @mod);

-- Event reviews: one per user per year the event is held (event_year = year of its start date)
SET @e_obon   = (SELECT id FROM events WHERE title = 'Lễ hội Obon công viên Yanaka');
SET @e_market = (SELECT id FROM events WHERE title = 'Chợ phiên cuối tuần phố Yanaka Ginza');
INSERT INTO reviews (author_id, event_id, event_year, rating, content, status, like_count, created_at) VALUES
 (@hoang,  @e_market, YEAR(CURDATE() - INTERVAL 1 DAY), 5, 'Chiều thứ bảy đông nhưng vui. Croquette 100 yên ở đầu phố rất ngon.', 'PUBLISHED', 4, NOW() - INTERVAL 1 DAY),
 (@lananh, @e_obon,   YEAR(CURDATE() - INTERVAL 60 DAY), 4, 'Múa Bon Odori bắt đầu khoảng 7 giờ tối, ai cũng được vào nhảy cùng. Nhớ mang quạt và khăn.', 'PUBLISHED', 7, NOW() - INTERVAL 58 DAY);

-- ---------------------------------------------------------------- blog
INSERT INTO blogs (author_id, blog_type, title, content, status, is_pinned, like_count, comment_count, created_at) VALUES
 (@lananh, 'POST', 'Năm điều mình ước biết trước tuần đầu ở Tokyo',
  'Mua thẻ IC đi tàu ngay ở ga đầu tiên, vì gần như mọi tuyến đều dùng được.\nĐăng ký cư trú tại văn phòng quận trong 14 ngày kể từ khi có chỗ ở.\nMang sẵn tiền mặt: nhiều quán nhỏ quanh Nippori chưa nhận thẻ.\nHỏi lịch đổ rác của tòa nhà ngay hôm nhận phòng.\nLưu lại vài mẫu câu ở cửa hàng tiện lợi để dùng ngay.',
  'PUBLISHED', TRUE, 24, 0, NOW() - INTERVAL 18 DAY),
 (@lananh, 'POST', 'Tin nhắn tuyển việc làm thêm lương cao quanh ga',
  'Gần đây có tài khoản nhắn tin mời làm thêm lương cao, không cần tiếng Nhật, yêu cầu gửi ảnh thẻ cư trú trước.\nĐừng gửi ảnh giấy tờ cho người lạ. Việc làm thêm hợp pháp luôn có hợp đồng và nơi làm việc rõ ràng.\nNếu gặp tin như vậy trên Senpai ơi, hãy bấm Báo cáo.',
  'PUBLISHED', FALSE, 31, 0, NOW() - INTERVAL 9 DAY),
 (@hoang, 'POST', 'Hai quán ăn dưới 1.000 yên quanh Nippori',
  'Quán cơm Hana: cơm phần 700 yên, chủ quán hiểu tiếng Việt cơ bản.\nRamen Aoba: ramen shoyu khoảng 950 yên, mua vé ở máy trước khi ngồi.',
  'PUBLISHED', FALSE, 15, 0, NOW() - INTERVAL 4 DAY),
 (@hoang, 'POST', 'Phân loại rác ở khu Nippori',
  'Rác cháy được, rác không cháy được và rác tái chế bỏ vào các ngày khác nhau.\nLịch thường dán ở bảng tin của tòa nhà. Nếu không thấy, hãy hỏi quản lý tòa nhà.',
  'PUBLISHED', FALSE, 9, 0, NOW() - INTERVAL 25 DAY),
 (@lananh, 'POST', 'Checklist một tháng trước khi về nước',
  'Nộp báo chuyển đi (転出届) ở văn phòng quận trước ngày bay.\nĐóng tài khoản ngân hàng sau khi nhận khoản lương cuối.\nSau khi về, nộp hồ sơ xin hoàn tiền lương hưu (脱退一時金) trong vòng 2 năm.',
  'PUBLISHED', FALSE, 19, 0, NOW() - INTERVAL 60 DAY),
 (@minh, 'QA', 'Mua SIM data giá rẻ ở đâu gần Nippori ạ?',
  'Em mới sang được 3 ngày, cần SIM data rẻ để dùng bản đồ. Mọi người đang dùng nhà mạng nào và mua ở đâu ạ?',
  'PUBLISHED', FALSE, 3, 0, NOW() - INTERVAL 1 DAY);

SET @b_week    = (SELECT id FROM blogs WHERE title = 'Năm điều mình ước biết trước tuần đầu ở Tokyo');
SET @b_rac     = (SELECT id FROM blogs WHERE title = 'Phân loại rác ở khu Nippori');
SET @b_ve      = (SELECT id FROM blogs WHERE title = 'Checklist một tháng trước khi về nước');

INSERT INTO blog_topic_links (blog_id, topic_id)
SELECT b.id, t.id FROM blogs b JOIN blog_topics t ON
   (b.title LIKE 'Năm điều%' AND t.name IN ('Kinh nghiệm', 'Thủ tục giấy tờ', 'Đi lại'))
OR (b.title LIKE 'Tin nhắn tuyển%' AND t.name IN ('Cảnh báo lừa đảo', 'Việc làm thêm'))
OR (b.title LIKE 'Hai quán ăn%' AND t.name = 'Ăn uống')
OR (b.title LIKE 'Phân loại rác%' AND t.name IN ('Kinh nghiệm', 'Nhà ở'))
OR (b.title LIKE 'Checklist%' AND t.name IN ('Sau khi về nước', 'Thủ tục giấy tờ'))
OR (b.title LIKE 'Mua SIM%' AND t.name IN ('Mua sắm', 'Ngân hàng và tiền'));

INSERT INTO blog_place_links (blog_id, place_id)
SELECT b.id, p.id FROM blogs b JOIN places p ON
   (b.title LIKE 'Năm điều%' AND p.name_vi IN ('Ga Nippori', 'Văn phòng quận Arakawa'))
OR (b.title LIKE 'Tin nhắn tuyển%' AND p.name_vi = 'Ga Nippori')
OR (b.title LIKE 'Hai quán ăn%' AND p.name_vi IN ('Quán cơm Hana', 'Ramen Aoba'))
OR (b.title LIKE 'Checklist%' AND p.name_vi IN ('Văn phòng quận Arakawa', 'Bưu điện Nippori'));

-- ---------------------------------------------------------------- guide (Moderator)
INSERT INTO guide_items (stage_code, title, due_note, place_id, blog_id, sort_order, created_by) VALUES
 ('PRE', 'Hộ chiếu, visa và giấy chứng nhận tư cách lưu trú', NULL, NULL, NULL, 1, @mod),
 ('PRE', 'Tiền mặt bằng yên cho hai tuần đầu', NULL, NULL, NULL, 2, @mod),
 ('PRE', 'Thuốc cá nhân kèm đơn tiếng Anh', NULL, NULL, NULL, 3, @mod),
 ('PRE', 'Ổ cắm chuyển đổi loại A', NULL, NULL, NULL, 4, @mod),
 ('ARR', 'Đăng ký cư trú tại văn phòng quận', 'Trong 14 ngày', @p_ward, NULL, 1, @mod),
 ('ARR', 'Tham gia bảo hiểm y tế quốc dân', NULL, @p_ward, NULL, 2, @mod),
 ('ARR', 'Mua thẻ IC đi tàu', NULL, @p_station, @b_week, 3, @mod),
 ('ARR', 'Mở tài khoản ngân hàng', NULL, @p_post, NULL, 4, @mod),
 ('ARR', 'Hỏi lịch đổ rác của tòa nhà', NULL, NULL, @b_rac, 5, @mod),
 ('ARR', 'Xin giấy phép làm thêm (資格外活動許可) nếu định đi làm', 'Trước khi đi làm', NULL, NULL, 6, @mod),
 ('LEAVE', 'Nộp báo chuyển đi (転出届) ở văn phòng quận', 'Trước ngày bay', @p_ward, @b_ve, 1, @mod),
 ('LEAVE', 'Đóng tài khoản ngân hàng, hủy hợp đồng điện thoại', NULL, @p_post, NULL, 2, @mod),
 ('LEAVE', 'Thanh toán hóa đơn điện, nước, gas', NULL, NULL, NULL, 3, @mod),
 ('LEAVE', 'Xin giấy chứng nhận tốt nghiệp (卒業証明書) và bảng điểm', NULL, @p_school, NULL, 4, @mod),
 ('LEAVE', 'Gửi đồ về Việt Nam', NULL, @p_post, NULL, 5, @mod),
 ('HOME', 'Nộp lại thẻ cư trú ở sân bay khi xuất cảnh', 'Ngày bay', NULL, NULL, 1, @mod),
 ('HOME', 'Nộp hồ sơ hoàn tiền lương hưu (脱退一時金)', 'Trong 2 năm', NULL, @b_ve, 2, @mod),
 ('HOME', 'Hợp pháp hóa lãnh sự bằng cấp nếu cần đi làm', NULL, NULL, NULL, 3, @mod),
 ('HOME', 'Xin tag Mentor để giúp các bạn đi sau', NULL, NULL, NULL, 4, @mod);

-- Minh already ticked the first two "before leaving" items
INSERT INTO user_guide_progress (user_id, item_id)
SELECT @minh, id FROM guide_items WHERE stage_code = 'PRE' AND sort_order IN (1, 2);

-- ---------------------------------------------------------------- Japanese phrases (Academic Manager)
INSERT INTO learning_topics (id, name, icon) VALUES
 ('pay', 'Thanh toán', 'payments'), ('ask', 'Hỏi đường, hỏi đồ', 'help'), ('order', 'Gọi món', 'restaurant_menu'),
 ('train', 'Đi tàu', 'train'), ('health', 'Khám bệnh, mua thuốc', 'medical_services'),
 ('paper', 'Giấy tờ, thủ tục', 'description'), ('polite', 'Lịch sự cơ bản', 'waving_hand');

INSERT INTO phrases (category_id, kind, japanese, romaji, meaning_vi, created_by) VALUES
 ('konbini', 'PHRASE', '袋はいりません。', 'Fukuro wa irimasen.', 'Tôi không cần túi.', @acad),
 ('konbini', 'PHRASE', '温めてください。', 'Atatamete kudasai.', 'Làm ơn hâm nóng giúp tôi.', @acad),
 ('konbini', 'PHRASE', 'カードで払えますか。', 'Kādo de haraemasu ka.', 'Tôi trả bằng thẻ được không?', @acad),
 ('konbini', 'VOCAB', '〜てください', '~te kudasai', 'Nhờ ai làm gì một cách lịch sự.', @acad),
 ('food', 'PHRASE', '注文お願いします。', 'Chūmon onegai shimasu.', 'Cho tôi gọi món.', @acad),
 ('food', 'PHRASE', 'おすすめは何ですか。', 'Osusume wa nan desu ka.', 'Quán có món gì nên thử?', @acad),
 ('food', 'PHRASE', 'お会計お願いします。', 'Okaikei onegai shimasu.', 'Cho tôi thanh toán.', @acad),
 ('food', 'VOCAB', '〜にします', '~ni shimasu', 'Chọn một món hoặc một phương án.', @acad),
 ('station', 'PHRASE', 'この電車は上野に止まりますか。', 'Kono densha wa Ueno ni tomarimasu ka.', 'Tàu này có dừng ở Ueno không?', @acad),
 ('station', 'PHRASE', 'チャージしたいです。', 'Chāji shitai desu.', 'Tôi muốn nạp tiền vào thẻ IC.', @acad),
 ('station', 'VOCAB', '乗り換え', 'norikae', 'Đổi tàu.', @acad),
 ('super', 'PHRASE', 'これはどこにありますか。', 'Kore wa doko ni arimasu ka.', 'Món này ở đâu?', @acad),
 ('super', 'VOCAB', '割引', 'waribiki', 'Giảm giá.', @acad),
 ('ward', 'PHRASE', '転入届を出したいです。', 'Tennyū-todoke o dashitai desu.', 'Tôi muốn nộp giấy đăng ký chuyển đến.', @acad),
 ('ward', 'VOCAB', '在留カード', 'zairyū kādo', 'Thẻ cư trú.', @acad),
 ('ward', 'VOCAB', '〜たいです', '~tai desu', 'Diễn đạt điều mình muốn làm.', @acad),
 ('hospital', 'PHRASE', '熱があります。', 'Netsu ga arimasu.', 'Tôi bị sốt.', @acad),
 ('hospital', 'PHRASE', 'ここが痛いです。', 'Koko ga itai desu.', 'Tôi đau ở đây.', @acad),
 ('bank', 'PHRASE', '口座を開きたいです。', 'Kōza o hirakitai desu.', 'Tôi muốn mở tài khoản.', @acad),
 ('bank', 'PHRASE', '荷物を送りたいです。', 'Nimotsu o okuritai desu.', 'Tôi muốn gửi bưu kiện.', @acad),
 ('pharmacy', 'PHRASE', '風邪薬はありますか。', 'Kazegusuri wa arimasu ka.', 'Có thuốc cảm không?', @acad),
 ('school', 'PHRASE', 'もう一度お願いします。', 'Mō ichido onegai shimasu.', 'Xin nhắc lại một lần nữa.', @acad),
 ('school', 'PHRASE', '遅れてすみません。', 'Okurete sumimasen.', 'Xin lỗi vì tôi đến muộn.', @acad),
 ('laundry', 'VOCAB', '両替機', 'ryōgaeki', 'Máy đổi tiền xu.', @acad);

INSERT INTO phrase_topic_links (phrase_id, topic_id)
SELECT ph.id, t.topic FROM phrases ph JOIN (
           SELECT '袋はいりません。' AS ja, 'pay' AS topic UNION ALL SELECT '袋はいりません。', 'polite'
 UNION ALL SELECT '温めてください。', 'polite'      UNION ALL SELECT 'カードで払えますか。', 'pay'
 UNION ALL SELECT '〜てください', 'polite'          UNION ALL SELECT '〜てください', 'ask'
 UNION ALL SELECT '注文お願いします。', 'order'     UNION ALL SELECT 'おすすめは何ですか。', 'order'
 UNION ALL SELECT 'おすすめは何ですか。', 'ask'     UNION ALL SELECT 'お会計お願いします。', 'pay'
 UNION ALL SELECT 'お会計お願いします。', 'order'   UNION ALL SELECT '〜にします', 'order'
 UNION ALL SELECT 'この電車は上野に止まりますか。', 'train' UNION ALL SELECT 'この電車は上野に止まりますか。', 'ask'
 UNION ALL SELECT 'チャージしたいです。', 'train'   UNION ALL SELECT 'チャージしたいです。', 'pay'
 UNION ALL SELECT '乗り換え', 'train'               UNION ALL SELECT 'これはどこにありますか。', 'ask'
 UNION ALL SELECT '割引', 'pay'                     UNION ALL SELECT '転入届を出したいです。', 'paper'
 UNION ALL SELECT '在留カード', 'paper'             UNION ALL SELECT '〜たいです', 'paper'
 UNION ALL SELECT '〜たいです', 'polite'            UNION ALL SELECT '熱があります。', 'health'
 UNION ALL SELECT 'ここが痛いです。', 'health'      UNION ALL SELECT '口座を開きたいです。', 'paper'
 UNION ALL SELECT '口座を開きたいです。', 'pay'     UNION ALL SELECT '荷物を送りたいです。', 'paper'
 UNION ALL SELECT '風邪薬はありますか。', 'health'  UNION ALL SELECT '風邪薬はありますか。', 'ask'
 UNION ALL SELECT 'もう一度お願いします。', 'polite' UNION ALL SELECT 'もう一度お願いします。', 'ask'
 UNION ALL SELECT '遅れてすみません。', 'polite'    UNION ALL SELECT '両替機', 'pay'
) t ON t.ja = ph.japanese;
