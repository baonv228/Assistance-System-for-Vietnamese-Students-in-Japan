-- MySQL dump 10.13  Distrib 8.0.46, for Win64 (x86_64)
--
-- Host: localhost    Database: asvsj
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `activity_logs`
--

DROP TABLE IF EXISTS `activity_logs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `activity_logs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `actor_id` bigint DEFAULT NULL,
  `action` varchar(50) NOT NULL,
  `target_type` varchar(20) DEFAULT NULL,
  `target_id` bigint DEFAULT NULL,
  `old_value` json DEFAULT NULL,
  `new_value` json DEFAULT NULL,
  `description` varchar(1000) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_logs_created` (`created_at`),
  KEY `idx_logs_actor` (`actor_id`),
  KEY `idx_logs_target` (`target_type`,`target_id`),
  CONSTRAINT `fk_log_actor` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `activity_logs`
--

LOCK TABLES `activity_logs` WRITE;
/*!40000 ALTER TABLE `activity_logs` DISABLE KEYS */;
/*!40000 ALTER TABLE `activity_logs` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `api_usage`
--

DROP TABLE IF EXISTS `api_usage`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_usage` (
  `service` varchar(40) NOT NULL,
  `usage_date` date NOT NULL,
  `amount` decimal(12,2) NOT NULL DEFAULT '0.00',
  PRIMARY KEY (`service`,`usage_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `api_usage`
--

LOCK TABLES `api_usage` WRITE;
/*!40000 ALTER TABLE `api_usage` DISABLE KEYS */;
INSERT INTO `api_usage` VALUES ('GEMINI_FILE_UPLOAD','2026-10-27',1.00),('GEMINI_GENERATE','2026-10-24',14.00),('GEMINI_GENERATE','2026-10-27',2.00),('GEMINI_GENERATE','2026-10-28',3.00),('GEMINI_GENERATE','2026-10-30',5.00),('GEMINI_GENERATE','2026-10-31',3.00),('GEMINI_GENERATE','2026-11-07',1.00),('OSM_NOMINATIM_LOOKUP','2026-10-19',2.00),('OSM_NOMINATIM_LOOKUP','2026-10-22',2.00),('OSM_NOMINATIM_LOOKUP','2026-10-24',1.00),('OSM_NOMINATIM_LOOKUP','2026-10-28',1.00),('OSM_NOMINATIM_LOOKUP','2026-10-31',1.00),('OSM_NOMINATIM_LOOKUP','2026-11-07',1.00),('OSM_PHOTON_SEARCH','2026-10-19',31.00),('OSM_PHOTON_SEARCH','2026-10-21',5.00),('OSM_PHOTON_SEARCH','2026-10-22',9.00),('OSM_PHOTON_SEARCH','2026-10-23',4.00),('OSM_PHOTON_SEARCH','2026-10-24',10.00),('OSM_PHOTON_SEARCH','2026-10-27',2.00),('OSM_PHOTON_SEARCH','2026-10-28',4.00),('OSM_PHOTON_SEARCH','2026-10-29',2.00),('OSM_PHOTON_SEARCH','2026-10-31',7.00),('OSM_PHOTON_SEARCH','2026-11-07',5.00);
/*!40000 ALTER TABLE `api_usage` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `appeals`
--

DROP TABLE IF EXISTS `appeals`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `appeals` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `target_type` varchar(20) NOT NULL,
  `target_id` bigint NOT NULL,
  `content` varchar(1000) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `decided_by` bigint DEFAULT NULL,
  `decided_at` datetime DEFAULT NULL,
  `decision_note` varchar(500) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_appeal_once` (`target_type`,`target_id`),
  KEY `fk_appeal_user` (`user_id`),
  KEY `fk_appeal_decider` (`decided_by`),
  CONSTRAINT `fk_appeal_decider` FOREIGN KEY (`decided_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_appeal_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_appeal_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'ACCEPTED',_utf8mb4'REJECTED'))),
  CONSTRAINT `ck_appeal_target` CHECK ((`target_type` in (_utf8mb4'REVIEW',_utf8mb4'BLOG',_utf8mb4'COMMENT')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `appeals`
--

LOCK TABLES `appeals` WRITE;
/*!40000 ALTER TABLE `appeals` DISABLE KEYS */;
/*!40000 ALTER TABLE `appeals` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `areas`
--

DROP TABLE IF EXISTS `areas`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `areas` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` char(6) DEFAULT NULL,
  `prefecture_code` char(2) DEFAULT NULL,
  `ward` varchar(80) NOT NULL,
  `station` varchar(80) DEFAULT NULL,
  `display_name` varchar(160) NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `name_ja` varchar(40) DEFAULT NULL,
  `name_kana` varchar(80) DEFAULT NULL,
  `name_en` varchar(80) DEFAULT NULL,
  `lat` decimal(9,6) DEFAULT NULL,
  `lng` decimal(9,6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `display_name` (`display_name`),
  UNIQUE KEY `uq_areas_code` (`code`),
  KEY `idx_areas_prefecture` (`prefecture_code`,`sort_order`),
  CONSTRAINT `fk_areas_prefecture` FOREIGN KEY (`prefecture_code`) REFERENCES `prefectures` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=1900 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `areas`
--

LOCK TABLES `areas` WRITE;
/*!40000 ALTER TABLE `areas` DISABLE KEYS */;
INSERT INTO `areas` VALUES (8,'011011','01','Chuo-ku, Sapporo-shi',NULL,'Chuo-ku, Sapporo-shi, Hokkaido · 北海道札幌市中央区',1,1,'札幌市中央区','サッポロシチュウオウク','Chuo-ku, Sapporo-shi',43.061414,141.353890),(9,'011029','01','Kita-ku, Sapporo-shi',NULL,'Kita-ku, Sapporo-shi, Hokkaido · 北海道札幌市北区',2,1,'札幌市北区','サッポロシキタク','Kita-ku, Sapporo-shi',43.090693,141.340882),(10,'011037','01','Higashi-ku, Sapporo-shi',NULL,'Higashi-ku, Sapporo-shi, Hokkaido · 北海道札幌市東区',3,1,'札幌市東区','サッポロシヒガシク','Higashi-ku, Sapporo-shi',43.076242,141.363662),(11,'011045','01','Shiroishi-ku, Sapporo-shi',NULL,'Shiroishi-ku, Sapporo-shi, Hokkaido · 北海道札幌市白石区',4,1,'札幌市白石区','サッポロシシロイシク','Shiroishi-ku, Sapporo-shi',43.045637,141.396521),(12,'011053','01','Toyohira-ku, Sapporo-shi',NULL,'Toyohira-ku, Sapporo-shi, Hokkaido · 北海道札幌市豊平区',5,1,'札幌市豊平区','サッポロシトヨヒラク','Toyohira-ku, Sapporo-shi',43.031370,141.379974),(13,'011061','01','Minami-ku, Sapporo-shi',NULL,'Minami-ku, Sapporo-shi, Hokkaido · 北海道札幌市南区',6,1,'札幌市南区','サッポロシミナミク','Minami-ku, Sapporo-shi',42.989995,141.353496),(14,'011070','01','Nishi-ku, Sapporo-shi',NULL,'Nishi-ku, Sapporo-shi, Hokkaido · 北海道札幌市西区',7,1,'札幌市西区','サッポロシニシク','Nishi-ku, Sapporo-shi',43.074347,141.300898),(15,'011088','01','Atsubetsu-ku, Sapporo-shi',NULL,'Atsubetsu-ku, Sapporo-shi, Hokkaido · 北海道札幌市厚別区',8,1,'札幌市厚別区','サッポロシアツベツク','Atsubetsu-ku, Sapporo-shi',43.036213,141.474733),(16,'011096','01','Teine-ku, Sapporo-shi',NULL,'Teine-ku, Sapporo-shi, Hokkaido · 北海道札幌市手稲区',9,1,'札幌市手稲区','サッポロシテイネク','Teine-ku, Sapporo-shi',43.121972,141.245782),(17,'011100','01','Kiyota-ku, Sapporo-shi',NULL,'Kiyota-ku, Sapporo-shi, Hokkaido · 北海道札幌市清田区',10,1,'札幌市清田区','サッポロシキヨタク','Kiyota-ku, Sapporo-shi',42.999432,141.443842),(18,'012025','01','Hakodate-shi',NULL,'Hakodate-shi, Hokkaido · 北海道函館市',11,1,'函館市','ハコダテシ','Hakodate-shi',41.768712,140.729108),(19,'012033','01','Otaru-shi',NULL,'Otaru-shi, Hokkaido · 北海道小樽市',12,1,'小樽市','オタルシ','Otaru-shi',43.190753,140.994605),(20,'012041','01','Asahikawa-shi',NULL,'Asahikawa-shi, Hokkaido · 北海道旭川市',13,1,'旭川市','アサヒカワシ','Asahikawa-shi',43.771404,142.365314),(21,'012050','01','Muroran-shi',NULL,'Muroran-shi, Hokkaido · 北海道室蘭市',14,1,'室蘭市','ムロランシ','Muroran-shi',42.315204,140.973784),(22,'012068','01','Kushiro-shi',NULL,'Kushiro-shi, Hokkaido · 北海道釧路市',15,1,'釧路市','クシロシ','Kushiro-shi',42.984856,144.381670),(23,'012076','01','Obihiro-shi',NULL,'Obihiro-shi, Hokkaido · 北海道帯広市',16,1,'帯広市','オビヒロシ','Obihiro-shi',42.924014,143.196195),(24,'012084','01','Kitami-shi',NULL,'Kitami-shi, Hokkaido · 北海道北見市',17,1,'北見市','キタミシ','Kitami-shi',43.803066,143.894806),(25,'012092','01','Yubari-shi',NULL,'Yubari-shi, Hokkaido · 北海道夕張市',18,1,'夕張市','ユウバリシ','Yubari-shi',43.056814,141.974069),(26,'012106','01','Iwamizawa-shi',NULL,'Iwamizawa-shi, Hokkaido · 北海道岩見沢市',19,1,'岩見沢市','イワミザワシ','Iwamizawa-shi',43.196169,141.775857),(27,'012114','01','Abashiri-shi',NULL,'Abashiri-shi, Hokkaido · 北海道網走市',20,1,'網走市','アバシリシ','Abashiri-shi',44.020631,144.273422),(28,'012122','01','Rumoi-shi',NULL,'Rumoi-shi, Hokkaido · 北海道留萌市',21,1,'留萌市','ルモイシ','Rumoi-shi',43.940987,141.637009),(29,'012131','01','Tomakomai-shi',NULL,'Tomakomai-shi, Hokkaido · 北海道苫小牧市',22,1,'苫小牧市','トマコマイシ','Tomakomai-shi',42.634094,141.605503),(30,'012149','01','Wakkanai-shi',NULL,'Wakkanai-shi, Hokkaido · 北海道稚内市',23,1,'稚内市','ワッカナイシ','Wakkanai-shi',45.415675,141.673082),(31,'012157','01','Bibai-shi',NULL,'Bibai-shi, Hokkaido · 北海道美唄市',24,1,'美唄市','ビバイシ','Bibai-shi',43.332951,141.853986),(32,'012165','01','Ashibetsu-shi',NULL,'Ashibetsu-shi, Hokkaido · 北海道芦別市',25,1,'芦別市','アシベツシ','Ashibetsu-shi',43.518210,142.189552),(33,'012173','01','Ebetsu-shi',NULL,'Ebetsu-shi, Hokkaido · 北海道江別市',26,1,'江別市','エベツシ','Ebetsu-shi',43.103666,141.536103),(34,'012181','01','Akabira-shi',NULL,'Akabira-shi, Hokkaido · 北海道赤平市',27,1,'赤平市','アカビラシ','Akabira-shi',43.558039,142.044223),(35,'012190','01','Mombetsu-shi',NULL,'Mombetsu-shi, Hokkaido · 北海道紋別市',28,1,'紋別市','モンベツシ','Mombetsu-shi',44.356332,143.354279),(36,'012203','01','Shibetsu-shi',NULL,'Shibetsu-shi, Hokkaido · 北海道士別市',29,1,'士別市','シベツシ','Shibetsu-shi',44.178626,142.400182),(37,'012211','01','Nayoro-shi',NULL,'Nayoro-shi, Hokkaido · 北海道名寄市',30,1,'名寄市','ナヨロシ','Nayoro-shi',44.355924,142.463134),(38,'012220','01','Mikasa-shi',NULL,'Mikasa-shi, Hokkaido · 北海道三笠市',31,1,'三笠市','ミカサシ','Mikasa-shi',43.245622,141.875342),(39,'012238','01','Nemuro-shi',NULL,'Nemuro-shi, Hokkaido · 北海道根室市',32,1,'根室市','ネムロシ','Nemuro-shi',43.330036,145.582903),(40,'012246','01','Chitose-shi',NULL,'Chitose-shi, Hokkaido · 北海道千歳市',33,1,'千歳市','チトセシ','Chitose-shi',42.821124,141.651139),(41,'012254','01','Takikawa-shi',NULL,'Takikawa-shi, Hokkaido · 北海道滝川市',34,1,'滝川市','タキカワシ','Takikawa-shi',43.557753,141.910371),(42,'012262','01','Sunagawa-shi',NULL,'Sunagawa-shi, Hokkaido · 北海道砂川市',35,1,'砂川市','スナガワシ','Sunagawa-shi',43.494254,141.902504),(43,'012271','01','Utashinai-shi',NULL,'Utashinai-shi, Hokkaido · 北海道歌志内市',36,1,'歌志内市','ウタシナイシ','Utashinai-shi',43.521682,142.035353),(44,'012289','01','Fukagawa-shi',NULL,'Fukagawa-shi, Hokkaido · 北海道深川市',37,1,'深川市','フカガワシ','Fukagawa-shi',43.723184,142.053528),(45,'012297','01','Furano-shi',NULL,'Furano-shi, Hokkaido · 北海道富良野市',38,1,'富良野市','フラノシ','Furano-shi',43.342024,142.383104),(46,'012301','01','Noboribetsu-shi',NULL,'Noboribetsu-shi, Hokkaido · 北海道登別市',39,1,'登別市','ノボリベツシ','Noboribetsu-shi',42.412735,141.106556),(47,'012319','01','Eniwa-shi',NULL,'Eniwa-shi, Hokkaido · 北海道恵庭市',40,1,'恵庭市','エニワシ','Eniwa-shi',42.882592,141.577799),(48,'012335','01','Date-shi',NULL,'Date-shi, Hokkaido · 北海道伊達市',41,1,'伊達市','ダテシ','Date-shi',42.471898,140.864702),(49,'012343','01','Kitahiroshima-shi',NULL,'Kitahiroshima-shi, Hokkaido · 北海道北広島市',42,1,'北広島市','キタヒロシマシ','Kitahiroshima-shi',42.985663,141.563625),(50,'012351','01','Ishikari-shi',NULL,'Ishikari-shi, Hokkaido · 北海道石狩市',43,1,'石狩市','イシカリシ','Ishikari-shi',43.171365,141.315514),(51,'012360','01','Hokuto-shi',NULL,'Hokuto-shi, Hokkaido · 北海道北斗市',44,1,'北斗市','ホクトシ','Hokuto-shi',41.824174,140.653067),(52,'013030','01','Tobetsu-cho',NULL,'Tobetsu-cho, Hokkaido · 北海道当別町',45,1,'当別町','トウベツチョウ','Tobetsu-cho',43.223637,141.517003),(53,'013048','01','Shinshinotsu-mura',NULL,'Shinshinotsu-mura, Hokkaido · 北海道新篠津村',46,1,'新篠津村','シンシノツムラ','Shinshinotsu-mura',43.225368,141.649247),(54,'013315','01','Matsumae-cho',NULL,'Matsumae-cho, Hokkaido · 北海道松前町',47,1,'松前町','マツマエチョウ','Matsumae-cho',41.429981,140.110403),(55,'013323','01','Fukushima-cho',NULL,'Fukushima-cho, Hokkaido · 北海道福島町',48,1,'福島町','フクシマチョウ','Fukushima-cho',41.483783,140.251315),(56,'013331','01','Shiriuchi-cho',NULL,'Shiriuchi-cho, Hokkaido · 北海道知内町',49,1,'知内町','シリウチチョウ','Shiriuchi-cho',41.598377,140.418861),(57,'013340','01','Kikonai-cho',NULL,'Kikonai-cho, Hokkaido · 北海道木古内町',50,1,'木古内町','キコナイチョウ','Kikonai-cho',41.678332,140.437635),(58,'013374','01','Nanae-cho',NULL,'Nanae-cho, Hokkaido · 北海道七飯町',51,1,'七飯町','ナナエチョウ','Nanae-cho',41.895711,140.694405),(59,'013439','01','Shikabe-cho',NULL,'Shikabe-cho, Hokkaido · 北海道鹿部町',52,1,'鹿部町','シカベチョウ','Shikabe-cho',42.026623,140.831691),(60,'013455','01','Mori-machi',NULL,'Mori-machi, Hokkaido · 北海道森町',53,1,'森町','モリマチ','Mori-machi',42.104992,140.576446),(61,'013463','01','Yakumo-cho',NULL,'Yakumo-cho, Hokkaido · 北海道八雲町',54,1,'八雲町','ヤクモチョウ','Yakumo-cho',42.255918,140.265224),(62,'013471','01','Oshamambe-cho',NULL,'Oshamambe-cho, Hokkaido · 北海道長万部町',55,1,'長万部町','オシャマンベチョウ','Oshamambe-cho',42.513488,140.380344),(63,'013617','01','Esashi-cho',NULL,'Esashi-cho, Hokkaido · 北海道江差町',56,1,'江差町','エサシチョウ','Esashi-cho',41.869244,140.127560),(64,'013625','01','Kaminokuni-cho',NULL,'Kaminokuni-cho, Hokkaido · 北海道上ノ国町',57,1,'上ノ国町','カミノクニチョウ','Kaminokuni-cho',41.801042,140.121398),(65,'013633','01','Assabu-cho',NULL,'Assabu-cho, Hokkaido · 北海道厚沢部町',58,1,'厚沢部町','アッサブチョウ','Assabu-cho',41.920895,140.225475),(66,'013641','01','Otobe-cho',NULL,'Otobe-cho, Hokkaido · 北海道乙部町',59,1,'乙部町','オトベチョウ','Otobe-cho',41.968504,140.135460),(67,'013676','01','Okushiri-cho',NULL,'Okushiri-cho, Hokkaido · 北海道奥尻町',60,1,'奥尻町','オクシリチョウ','Okushiri-cho',42.172260,139.514121),(68,'013706','01','Imakane-cho',NULL,'Imakane-cho, Hokkaido · 北海道今金町',61,1,'今金町','イマカネチョウ','Imakane-cho',42.429373,140.008766),(69,'013714','01','Setana-cho',NULL,'Setana-cho, Hokkaido · 北海道せたな町',62,1,'せたな町','セタナチョウ','Setana-cho',42.417145,139.882999),(70,'013919','01','Shimamaki-mura',NULL,'Shimamaki-mura, Hokkaido · 北海道島牧村',63,1,'島牧村','シママキムラ','Shimamaki-mura',42.700471,140.061454),(71,'013927','01','Suttsu-cho',NULL,'Suttsu-cho, Hokkaido · 北海道寿都町',64,1,'寿都町','スッツチョウ','Suttsu-cho',42.790984,140.228891),(72,'013935','01','Kuromatsunai-cho',NULL,'Kuromatsunai-cho, Hokkaido · 北海道黒松内町',65,1,'黒松内町','クロマツナイチョウ','Kuromatsunai-cho',42.667788,140.307739),(73,'013943','01','Rankoshi-cho',NULL,'Rankoshi-cho, Hokkaido · 北海道蘭越町',66,1,'蘭越町','ランコシチョウ','Rankoshi-cho',42.809205,140.528346),(74,'013951','01','Niseko-cho',NULL,'Niseko-cho, Hokkaido · 北海道ニセコ町',67,1,'ニセコ町','ニセコチョウ','Niseko-cho',42.805036,140.688010),(75,'013960','01','Makkari-mura',NULL,'Makkari-mura, Hokkaido · 北海道真狩村',68,1,'真狩村','マッカリムラ','Makkari-mura',42.762955,140.803669),(76,'013978','01','Rusutsu-mura',NULL,'Rusutsu-mura, Hokkaido · 北海道留寿都村',69,1,'留寿都村','ルスツムラ','Rusutsu-mura',42.737263,140.875574),(77,'013986','01','Kimobetsu-cho',NULL,'Kimobetsu-cho, Hokkaido · 北海道喜茂別町',70,1,'喜茂別町','キモベツチョウ','Kimobetsu-cho',42.795439,140.934532),(78,'013994','01','Kyogoku-cho',NULL,'Kyogoku-cho, Hokkaido · 北海道京極町',71,1,'京極町','キョウゴクチョウ','Kyogoku-cho',42.858216,140.883999),(79,'014001','01','Kutchan-cho',NULL,'Kutchan-cho, Hokkaido · 北海道倶知安町',72,1,'倶知安町','クッチャンチョウ','Kutchan-cho',42.901752,140.758878),(80,'014010','01','Kyowa-cho',NULL,'Kyowa-cho, Hokkaido · 北海道共和町',73,1,'共和町','キョウワチョウ','Kyowa-cho',42.980407,140.611175),(81,'014028','01','Iwanai-cho',NULL,'Iwanai-cho, Hokkaido · 北海道岩内町',74,1,'岩内町','イワナイチョウ','Iwanai-cho',42.979778,140.514729),(82,'014036','01','Tomari-mura',NULL,'Tomari-mura, Hokkaido · 北海道泊村',75,1,'泊村','トマリムラ','Tomari-mura',43.063212,140.498857),(83,'014044','01','Kamoenai-mura',NULL,'Kamoenai-mura, Hokkaido · 北海道神恵内村',76,1,'神恵内村','カモエナイムラ','Kamoenai-mura',43.143348,140.430776),(84,'014052','01','Shakotan-cho',NULL,'Shakotan-cho, Hokkaido · 北海道積丹町',77,1,'積丹町','シャコタンチョウ','Shakotan-cho',43.298710,140.598001),(85,'014061','01','Furubira-cho',NULL,'Furubira-cho, Hokkaido · 北海道古平町',78,1,'古平町','フルビラチョウ','Furubira-cho',43.264027,140.638711),(86,'014079','01','Niki-cho',NULL,'Niki-cho, Hokkaido · 北海道仁木町',79,1,'仁木町','ニキチョウ','Niki-cho',43.151675,140.766168),(87,'014087','01','Yoichi-cho',NULL,'Yoichi-cho, Hokkaido · 北海道余市町',80,1,'余市町','ヨイチチョウ','Yoichi-cho',43.195343,140.783542),(88,'014095','01','Akaigawa-mura',NULL,'Akaigawa-mura, Hokkaido · 北海道赤井川村',81,1,'赤井川村','アカイガワムラ','Akaigawa-mura',43.083479,140.813640),(89,'014231','01','Namporo-cho',NULL,'Namporo-cho, Hokkaido · 北海道南幌町',82,1,'南幌町','ナンポロチョウ','Namporo-cho',43.063739,141.650330),(90,'014249','01','Naie-cho',NULL,'Naie-cho, Hokkaido · 北海道奈井江町',83,1,'奈井江町','ナイエチョウ','Naie-cho',43.425353,141.882816),(91,'014257','01','Kamisunagawa-cho',NULL,'Kamisunagawa-cho, Hokkaido · 北海道上砂川町',84,1,'上砂川町','カミスナガワチョウ','Kamisunagawa-cho',43.482538,141.984064),(92,'014273','01','Yuni-cho',NULL,'Yuni-cho, Hokkaido · 北海道由仁町',85,1,'由仁町','ユニチョウ','Yuni-cho',42.999598,141.790309),(93,'014281','01','Naganuma-cho',NULL,'Naganuma-cho, Hokkaido · 北海道長沼町',86,1,'長沼町','ナガヌマチョウ','Naganuma-cho',43.010340,141.695367),(94,'014290','01','Kuriyama-cho',NULL,'Kuriyama-cho, Hokkaido · 北海道栗山町',87,1,'栗山町','クリヤマチョウ','Kuriyama-cho',43.056290,141.784099),(95,'014303','01','Tsukigata-cho',NULL,'Tsukigata-cho, Hokkaido · 北海道月形町',88,1,'月形町','ツキガタチョウ','Tsukigata-cho',43.338397,141.669604),(96,'014311','01','Urausu-cho',NULL,'Urausu-cho, Hokkaido · 北海道浦臼町',89,1,'浦臼町','ウラウスチョウ','Urausu-cho',43.430363,141.818727),(97,'014320','01','Shintotsukawa-cho',NULL,'Shintotsukawa-cho, Hokkaido · 北海道新十津川町',90,1,'新十津川町','シントツカワチョウ','Shintotsukawa-cho',43.548500,141.877009),(98,'014338','01','Moseushi-cho',NULL,'Moseushi-cho, Hokkaido · 北海道妹背牛町',91,1,'妹背牛町','モセウシチョウ','Moseushi-cho',43.700093,141.961606),(99,'014346','01','Chippubetsu-cho',NULL,'Chippubetsu-cho, Hokkaido · 北海道秩父別町',92,1,'秩父別町','チップベツチョウ','Chippubetsu-cho',43.767000,141.957896),(100,'014362','01','Uryu-cho',NULL,'Uryu-cho, Hokkaido · 北海道雨竜町',93,1,'雨竜町','ウリュウチョウ','Uryu-cho',43.643943,141.889036),(101,'014371','01','Hokuryu-cho',NULL,'Hokuryu-cho, Hokkaido · 北海道北竜町',94,1,'北竜町','ホクリュウチョウ','Hokuryu-cho',43.731426,141.879409),(102,'014389','01','Numata-cho',NULL,'Numata-cho, Hokkaido · 北海道沼田町',95,1,'沼田町','ヌマタチョウ','Numata-cho',43.806740,141.933817),(103,'014524','01','Takasu-cho',NULL,'Takasu-cho, Hokkaido · 北海道鷹栖町',96,1,'鷹栖町','タカスチョウ','Takasu-cho',43.843321,142.354382),(104,'014532','01','Higashikagura-cho',NULL,'Higashikagura-cho, Hokkaido · 北海道東神楽町',97,1,'東神楽町','ヒガシカグラチョウ','Higashikagura-cho',43.696303,142.451571),(105,'014541','01','Toma-cho',NULL,'Toma-cho, Hokkaido · 北海道当麻町',98,1,'当麻町','トウマチョウ','Toma-cho',43.828201,142.508388),(106,'014559','01','Pippu-cho',NULL,'Pippu-cho, Hokkaido · 北海道比布町',99,1,'比布町','ピップチョウ','Pippu-cho',43.875021,142.477699),(107,'014567','01','Aibetsu-cho',NULL,'Aibetsu-cho, Hokkaido · 北海道愛別町',100,1,'愛別町','アイベツチョウ','Aibetsu-cho',43.906679,142.577822),(108,'014575','01','Kamikawa-cho',NULL,'Kamikawa-cho, Hokkaido · 北海道上川町',101,1,'上川町','カミカワチョウ','Kamikawa-cho',43.847128,142.770459),(109,'014583','01','Higashikawa-cho',NULL,'Higashikawa-cho, Hokkaido · 北海道東川町',102,1,'東川町','ヒガシカワチョウ','Higashikawa-cho',43.698877,142.510128),(110,'014591','01','Biei-cho',NULL,'Biei-cho, Hokkaido · 北海道美瑛町',103,1,'美瑛町','ビエイチョウ','Biei-cho',43.588279,142.467055),(111,'014605','01','Kamifurano-cho',NULL,'Kamifurano-cho, Hokkaido · 北海道上富良野町',104,1,'上富良野町','カミフラノチョウ','Kamifurano-cho',43.455614,142.467112),(112,'014613','01','Nakafurano-cho',NULL,'Nakafurano-cho, Hokkaido · 北海道中富良野町',105,1,'中富良野町','ナカフラノチョウ','Nakafurano-cho',43.405576,142.425034),(113,'014621','01','Minamifurano-cho',NULL,'Minamifurano-cho, Hokkaido · 北海道南富良野町',106,1,'南富良野町','ミナミフラノチョウ','Minamifurano-cho',43.164088,142.568522),(114,'014630','01','Shimukappu-mura',NULL,'Shimukappu-mura, Hokkaido · 北海道占冠村',107,1,'占冠村','シムカップムラ','Shimukappu-mura',42.979836,142.398526),(115,'014648','01','Wassamu-cho',NULL,'Wassamu-cho, Hokkaido · 北海道和寒町',108,1,'和寒町','ワッサムチョウ','Wassamu-cho',44.023119,142.413383),(116,'014656','01','Kembuchi-cho',NULL,'Kembuchi-cho, Hokkaido · 北海道剣淵町',109,1,'剣淵町','ケンブチチョウ','Kembuchi-cho',44.095746,142.361007),(117,'014681','01','Shimokawa-cho',NULL,'Shimokawa-cho, Hokkaido · 北海道下川町',110,1,'下川町','シモカワチョウ','Shimokawa-cho',44.302576,142.635219),(118,'014699','01','Bifuka-cho',NULL,'Bifuka-cho, Hokkaido · 北海道美深町',111,1,'美深町','ビフカチョウ','Bifuka-cho',44.480994,142.342963),(119,'014702','01','Otoineppu-mura',NULL,'Otoineppu-mura, Hokkaido · 北海道音威子府村',112,1,'音威子府村','オトイネップムラ','Otoineppu-mura',44.725096,142.262458),(120,'014711','01','Nakagawa-cho',NULL,'Nakagawa-cho, Hokkaido · 北海道中川町',113,1,'中川町','ナカガワチョウ','Nakagawa-cho',44.811488,142.071409),(121,'014729','01','Horokanai-cho',NULL,'Horokanai-cho, Hokkaido · 北海道幌加内町',114,1,'幌加内町','ホロカナイチョウ','Horokanai-cho',44.009812,142.153828),(122,'014818','01','Mashike-cho',NULL,'Mashike-cho, Hokkaido · 北海道増毛町',115,1,'増毛町','マシケチョウ','Mashike-cho',43.856063,141.524978),(123,'014826','01','Obira-cho',NULL,'Obira-cho, Hokkaido · 北海道小平町',116,1,'小平町','オビラチョウ','Obira-cho',44.015141,141.662828),(124,'014834','01','Tomamae-cho',NULL,'Tomamae-cho, Hokkaido · 北海道苫前町',117,1,'苫前町','トママエチョウ','Tomamae-cho',44.306086,141.652932),(125,'014842','01','Haboro-cho',NULL,'Haboro-cho, Hokkaido · 北海道羽幌町',118,1,'羽幌町','ハボロチョウ','Haboro-cho',44.360610,141.697249),(126,'014851','01','Shosambetsu-mura',NULL,'Shosambetsu-mura, Hokkaido · 北海道初山別村',119,1,'初山別村','ショサンベツムラ','Shosambetsu-mura',44.532149,141.766342),(127,'014869','01','Embetsu-cho',NULL,'Embetsu-cho, Hokkaido · 北海道遠別町',120,1,'遠別町','エンベツチョウ','Embetsu-cho',44.722462,141.792322),(128,'014877','01','Teshio-cho',NULL,'Teshio-cho, Hokkaido · 北海道天塩町',121,1,'天塩町','テシオチョウ','Teshio-cho',44.888121,141.745324),(129,'015113','01','Sarufutsu-mura',NULL,'Sarufutsu-mura, Hokkaido · 北海道猿払村',122,1,'猿払村','サルフツムラ','Sarufutsu-mura',45.330610,142.108945),(130,'015121','01','Hamatombetsu-cho',NULL,'Hamatombetsu-cho, Hokkaido · 北海道浜頓別町',123,1,'浜頓別町','ハマトンベツチョウ','Hamatombetsu-cho',45.123716,142.359640),(131,'015130','01','Nakatombetsu-cho',NULL,'Nakatombetsu-cho, Hokkaido · 北海道中頓別町',124,1,'中頓別町','ナカトンベツチョウ','Nakatombetsu-cho',44.969710,142.286705),(132,'015148','01','Esashi-cho',NULL,'Esashi-cho, Hokkaido · 北海道枝幸町',125,1,'枝幸町','エサシチョウ','Esashi-cho',44.938700,142.581405),(133,'015164','01','Toyotomi-cho',NULL,'Toyotomi-cho, Hokkaido · 北海道豊富町',126,1,'豊富町','トヨトミチョウ','Toyotomi-cho',45.102847,141.777508),(134,'015172','01','Rebun-cho',NULL,'Rebun-cho, Hokkaido · 北海道礼文町',127,1,'礼文町','レブンチョウ','Rebun-cho',45.303062,141.047717),(135,'015181','01','Rishiri-cho',NULL,'Rishiri-cho, Hokkaido · 北海道利尻町',128,1,'利尻町','リシリチョウ','Rishiri-cho',45.187041,141.139597),(136,'015199','01','Rishirifuji-cho',NULL,'Rishirifuji-cho, Hokkaido · 北海道利尻富士町',129,1,'利尻富士町','リシリフジチョウ','Rishirifuji-cho',45.247459,141.214719),(137,'015202','01','Horonobe-cho',NULL,'Horonobe-cho, Hokkaido · 北海道幌延町',130,1,'幌延町','ホロノベチョウ','Horonobe-cho',45.017635,141.849386),(138,'015431','01','Bihoro-cho',NULL,'Bihoro-cho, Hokkaido · 北海道美幌町',131,1,'美幌町','ビホロチョウ','Bihoro-cho',43.823721,144.107151),(139,'015440','01','Tsubetsu-cho',NULL,'Tsubetsu-cho, Hokkaido · 北海道津別町',132,1,'津別町','ツベツチョウ','Tsubetsu-cho',43.706316,144.024803),(140,'015458','01','Shari-cho',NULL,'Shari-cho, Hokkaido · 北海道斜里町',133,1,'斜里町','シャリチョウ','Shari-cho',43.911441,144.670713),(141,'015466','01','Kiyosato-cho',NULL,'Kiyosato-cho, Hokkaido · 北海道清里町',134,1,'清里町','キヨサトチョウ','Kiyosato-cho',43.835224,144.594582),(142,'015474','01','Koshimizu-cho',NULL,'Koshimizu-cho, Hokkaido · 北海道小清水町',135,1,'小清水町','コシミズチョウ','Koshimizu-cho',43.857067,144.462554),(143,'015491','01','Kunneppu-cho',NULL,'Kunneppu-cho, Hokkaido · 北海道訓子府町',136,1,'訓子府町','クンネップチョウ','Kunneppu-cho',43.725328,143.741665),(144,'015504','01','Oketo-cho',NULL,'Oketo-cho, Hokkaido · 北海道置戸町',137,1,'置戸町','オケトチョウ','Oketo-cho',43.676328,143.586402),(145,'015521','01','Saroma-cho',NULL,'Saroma-cho, Hokkaido · 北海道佐呂間町',138,1,'佐呂間町','サロマチョウ','Saroma-cho',44.017859,143.774714),(146,'015555','01','Engaru-cho',NULL,'Engaru-cho, Hokkaido · 北海道遠軽町',139,1,'遠軽町','エンガルチョウ','Engaru-cho',44.061947,143.527726),(147,'015598','01','Yubetsu-cho',NULL,'Yubetsu-cho, Hokkaido · 北海道湧別町',140,1,'湧別町','ユウベツチョウ','Yubetsu-cho',44.151465,143.572919),(148,'015601','01','Takinoe-cho',NULL,'Takinoe-cho, Hokkaido · 北海道滝上町',141,1,'滝上町','タキノウエチョウ','Takinoe-cho',44.192270,143.077650),(149,'015610','01','Okoppe-cho',NULL,'Okoppe-cho, Hokkaido · 北海道興部町',142,1,'興部町','オコッペチョウ','Okoppe-cho',44.469874,143.123699),(150,'015628','01','Nishiokoppe-mura',NULL,'Nishiokoppe-mura, Hokkaido · 北海道西興部村',143,1,'西興部村','ニシオコッペムラ','Nishiokoppe-mura',44.328833,142.944445),(151,'015636','01','Omu-cho',NULL,'Omu-cho, Hokkaido · 北海道雄武町',144,1,'雄武町','オウムチョウ','Omu-cho',44.582482,142.961863),(152,'015644','01','Ozora-cho',NULL,'Ozora-cho, Hokkaido · 北海道大空町',145,1,'大空町','オオゾラチョウ','Ozora-cho',43.911858,144.172473),(153,'015717','01','Toyora-cho',NULL,'Toyora-cho, Hokkaido · 北海道豊浦町',146,1,'豊浦町','トヨウラチョウ','Toyora-cho',42.583464,140.711815),(154,'015750','01','Sobetsu-cho',NULL,'Sobetsu-cho, Hokkaido · 北海道壮瞥町',147,1,'壮瞥町','ソウベツチョウ','Sobetsu-cho',42.552556,140.885472),(155,'015784','01','Shiraoi-cho',NULL,'Shiraoi-cho, Hokkaido · 北海道白老町',148,1,'白老町','シラオイチョウ','Shiraoi-cho',42.551259,141.355861),(156,'015814','01','Atsuma-cho',NULL,'Atsuma-cho, Hokkaido · 北海道厚真町',149,1,'厚真町','アツマチョウ','Atsuma-cho',42.723566,141.877841),(157,'015849','01','Toyako-cho',NULL,'Toyako-cho, Hokkaido · 北海道洞爺湖町',150,1,'洞爺湖町','トウヤコチョウ','Toyako-cho',42.551223,140.764128),(158,'015857','01','Abira-cho',NULL,'Abira-cho, Hokkaido · 北海道安平町',151,1,'安平町','アビラチョウ','Abira-cho',42.762798,141.818047),(159,'015865','01','Mukawa-cho',NULL,'Mukawa-cho, Hokkaido · 北海道むかわ町',152,1,'むかわ町','ムカワチョウ','Mukawa-cho',42.574741,141.926761),(160,'016012','01','Hidaka-cho',NULL,'Hidaka-cho, Hokkaido · 北海道日高町',153,1,'日高町','ヒダカチョウ','Hidaka-cho',42.480318,142.074359),(161,'016021','01','Biratori-cho',NULL,'Biratori-cho, Hokkaido · 北海道平取町',154,1,'平取町','ビラトリチョウ','Biratori-cho',42.585098,142.128525),(162,'016047','01','Niikappu-cho',NULL,'Niikappu-cho, Hokkaido · 北海道新冠町',155,1,'新冠町','ニイカップチョウ','Niikappu-cho',42.362417,142.318315),(163,'016071','01','Urakawa-cho',NULL,'Urakawa-cho, Hokkaido · 北海道浦河町',156,1,'浦河町','ウラカワチョウ','Urakawa-cho',42.168355,142.768213),(164,'016080','01','Samani-cho',NULL,'Samani-cho, Hokkaido · 北海道様似町',157,1,'様似町','サマニチョウ','Samani-cho',42.127737,142.933797),(165,'016098','01','Erimo-cho',NULL,'Erimo-cho, Hokkaido · 北海道えりも町',158,1,'えりも町','エリモチョウ','Erimo-cho',42.016298,143.148348),(166,'016101','01','Shinhidaka-cho',NULL,'Shinhidaka-cho, Hokkaido · 北海道新ひだか町',159,1,'新ひだか町','シンヒダカチョウ','Shinhidaka-cho',42.341276,142.368588),(167,'016314','01','Otofuke-cho',NULL,'Otofuke-cho, Hokkaido · 北海道音更町',160,1,'音更町','オトフケチョウ','Otofuke-cho',42.994115,143.197869),(168,'016322','01','Shihoro-cho',NULL,'Shihoro-cho, Hokkaido · 北海道士幌町',161,1,'士幌町','シホロチョウ','Shihoro-cho',43.168047,143.241344),(169,'016331','01','Kamishihoro-cho',NULL,'Kamishihoro-cho, Hokkaido · 北海道上士幌町',162,1,'上士幌町','カミシホロチョウ','Kamishihoro-cho',43.232615,143.296243),(170,'016349','01','Shikaoi-cho',NULL,'Shikaoi-cho, Hokkaido · 北海道鹿追町',163,1,'鹿追町','シカオイチョウ','Shikaoi-cho',43.098876,142.989028),(171,'016357','01','Shintoku-cho',NULL,'Shintoku-cho, Hokkaido · 北海道新得町',164,1,'新得町','シントクチョウ','Shintoku-cho',43.079741,142.838822),(172,'016365','01','Shimizu-cho',NULL,'Shimizu-cho, Hokkaido · 北海道清水町',165,1,'清水町','シミズチョウ','Shimizu-cho',43.011423,142.884568),(173,'016373','01','Memuro-cho',NULL,'Memuro-cho, Hokkaido · 北海道芽室町',166,1,'芽室町','メムロチョウ','Memuro-cho',42.911890,143.050807),(174,'016381','01','Nakasatsunai-mura',NULL,'Nakasatsunai-mura, Hokkaido · 北海道中札内村',167,1,'中札内村','ナカサツナイムラ','Nakasatsunai-mura',42.697734,143.134453),(175,'016390','01','Sarabetsu-mura',NULL,'Sarabetsu-mura, Hokkaido · 北海道更別村',168,1,'更別村','サラベツムラ','Sarabetsu-mura',42.650415,143.187794),(176,'016411','01','Taiki-cho',NULL,'Taiki-cho, Hokkaido · 北海道大樹町',169,1,'大樹町','タイキチョウ','Taiki-cho',42.497525,143.278910),(177,'016420','01','Hiro-cho',NULL,'Hiro-cho, Hokkaido · 北海道広尾町',170,1,'広尾町','ヒロオチョウ','Hiro-cho',42.285841,143.311498),(178,'016438','01','Makubetsu-cho',NULL,'Makubetsu-cho, Hokkaido · 北海道幕別町',171,1,'幕別町','マクベツチョウ','Makubetsu-cho',42.908198,143.356085),(179,'016446','01','Ikeda-cho',NULL,'Ikeda-cho, Hokkaido · 北海道池田町',172,1,'池田町','イケダチョウ','Ikeda-cho',42.928980,143.448472),(180,'016454','01','Toyokoro-cho',NULL,'Toyokoro-cho, Hokkaido · 北海道豊頃町',173,1,'豊頃町','トヨコロチョウ','Toyokoro-cho',42.801023,143.505900),(181,'016462','01','Hombetsu-cho',NULL,'Hombetsu-cho, Hokkaido · 北海道本別町',174,1,'本別町','ホンベツチョウ','Hombetsu-cho',43.124615,143.610962),(182,'016471','01','Ashoro-cho',NULL,'Ashoro-cho, Hokkaido · 北海道足寄町',175,1,'足寄町','アショロチョウ','Ashoro-cho',43.244786,143.553930),(183,'016489','01','Rikubetsu-cho',NULL,'Rikubetsu-cho, Hokkaido · 北海道陸別町',176,1,'陸別町','リクベツチョウ','Rikubetsu-cho',43.468914,143.747305),(184,'016497','01','Urahoro-cho',NULL,'Urahoro-cho, Hokkaido · 北海道浦幌町',177,1,'浦幌町','ウラホロチョウ','Urahoro-cho',42.808947,143.658581),(185,'016616','01','Kushiro-cho',NULL,'Kushiro-cho, Hokkaido · 北海道釧路町',178,1,'釧路町','クシロチョウ','Kushiro-cho',42.996124,144.466081),(186,'016624','01','Akkeshi-cho',NULL,'Akkeshi-cho, Hokkaido · 北海道厚岸町',179,1,'厚岸町','アッケシチョウ','Akkeshi-cho',43.051951,144.847443),(187,'016632','01','Hamanaka-cho',NULL,'Hamanaka-cho, Hokkaido · 北海道浜中町',180,1,'浜中町','ハマナカチョウ','Hamanaka-cho',43.075949,145.131133),(188,'016641','01','Shibecha-cho',NULL,'Shibecha-cho, Hokkaido · 北海道標茶町',181,1,'標茶町','シベチャチョウ','Shibecha-cho',43.303337,144.600665),(189,'016659','01','Teshikaga-cho',NULL,'Teshikaga-cho, Hokkaido · 北海道弟子屈町',182,1,'弟子屈町','テシカガチョウ','Teshikaga-cho',43.485352,144.459223),(190,'016675','01','Tsurui-mura',NULL,'Tsurui-mura, Hokkaido · 北海道鶴居村',183,1,'鶴居村','ツルイムラ','Tsurui-mura',43.230160,144.321165),(191,'016683','01','Shiranuka-cho',NULL,'Shiranuka-cho, Hokkaido · 北海道白糠町',184,1,'白糠町','シラヌカチョウ','Shiranuka-cho',42.956161,144.071805),(192,'016918','01','Betsukai-cho',NULL,'Betsukai-cho, Hokkaido · 北海道別海町',185,1,'別海町','ベツカイチョウ','Betsukai-cho',43.393990,145.117341),(193,'016926','01','Nakashibetsu-cho',NULL,'Nakashibetsu-cho, Hokkaido · 北海道中標津町',186,1,'中標津町','ナカシベツチョウ','Nakashibetsu-cho',43.555191,144.971504),(194,'016934','01','Shibetsu-cho',NULL,'Shibetsu-cho, Hokkaido · 北海道標津町',187,1,'標津町','シベツチョウ','Shibetsu-cho',43.661309,145.131325),(195,'016942','01','Rausu-cho',NULL,'Rausu-cho, Hokkaido · 北海道羅臼町',188,1,'羅臼町','ラウスチョウ','Rausu-cho',44.021953,145.189532),(196,'022012','02','Aomori-shi',NULL,'Aomori-shi, Aomori · 青森県青森市',189,1,'青森市','アオモリシ','Aomori-shi',40.822358,140.747320),(197,'022021','02','Hirosaki-shi',NULL,'Hirosaki-shi, Aomori · 青森県弘前市',190,1,'弘前市','ヒロサキシ','Hirosaki-shi',40.602965,140.464008),(198,'022039','02','Hachinohe-shi',NULL,'Hachinohe-shi, Aomori · 青森県八戸市',191,1,'八戸市','ハチノヘシ','Hachinohe-shi',40.512278,141.488404),(199,'022047','02','Kuroishi-shi',NULL,'Kuroishi-shi, Aomori · 青森県黒石市',192,1,'黒石市','クロイシシ','Kuroishi-shi',40.642608,140.594547),(200,'022055','02','Goshogawara-shi',NULL,'Goshogawara-shi, Aomori · 青森県五所川原市',193,1,'五所川原市','ゴショガワラシ','Goshogawara-shi',40.807695,140.446075),(201,'022063','02','Towada-shi',NULL,'Towada-shi, Aomori · 青森県十和田市',194,1,'十和田市','トワダシ','Towada-shi',40.612703,141.205910),(202,'022071','02','Misawa-shi',NULL,'Misawa-shi, Aomori · 青森県三沢市',195,1,'三沢市','ミサワシ','Misawa-shi',40.683078,141.369093),(203,'022080','02','Mutsu-shi',NULL,'Mutsu-shi, Aomori · 青森県むつ市',196,1,'むつ市','ムツシ','Mutsu-shi',41.292835,141.183174),(204,'022098','02','Tsugaru-shi',NULL,'Tsugaru-shi, Aomori · 青森県つがる市',197,1,'つがる市','ツガルシ','Tsugaru-shi',40.808836,140.380195),(205,'022101','02','Hirakawa-shi',NULL,'Hirakawa-shi, Aomori · 青森県平川市',198,1,'平川市','ヒラカワシ','Hirakawa-shi',40.584114,140.566506),(206,'023019','02','Hiranai-machi',NULL,'Hiranai-machi, Aomori · 青森県平内町',199,1,'平内町','ヒラナイマチ','Hiranai-machi',40.926023,140.955884),(207,'023035','02','Imabetsu-machi',NULL,'Imabetsu-machi, Aomori · 青森県今別町',200,1,'今別町','イマベツマチ','Imabetsu-machi',41.181875,140.481610),(208,'023043','02','Yomogita-mura',NULL,'Yomogita-mura, Aomori · 青森県蓬田村',201,1,'蓬田村','ヨモギタムラ','Yomogita-mura',40.971779,140.656053),(209,'023078','02','Sotogahama-machi',NULL,'Sotogahama-machi, Aomori · 青森県外ヶ浜町',202,1,'外ヶ浜町','ソトガハママチ','Sotogahama-machi',41.043159,140.632243),(210,'023213','02','Ajigasawa-machi',NULL,'Ajigasawa-machi, Aomori · 青森県鰺ヶ沢町',203,1,'鰺ヶ沢町','アジガサワマチ','Ajigasawa-machi',40.783011,140.233902),(211,'023230','02','Fukaura-machi',NULL,'Fukaura-machi, Aomori · 青森県深浦町',204,1,'深浦町','フカウラマチ','Fukaura-machi',40.647876,139.927431),(212,'023434','02','Nishimeya-mura',NULL,'Nishimeya-mura, Aomori · 青森県西目屋村',205,1,'西目屋村','ニシメヤムラ','Nishimeya-mura',40.577553,140.297277),(213,'023612','02','Fujisaki-machi',NULL,'Fujisaki-machi, Aomori · 青森県藤崎町',206,1,'藤崎町','フジサキマチ','Fujisaki-machi',40.656064,140.502824),(214,'023621','02','Owani-machi',NULL,'Owani-machi, Aomori · 青森県大鰐町',207,1,'大鰐町','オオワニマチ','Owani-machi',40.518356,140.567878),(215,'023671','02','Inakadate-mura',NULL,'Inakadate-mura, Aomori · 青森県田舎館村',208,1,'田舎館村','イナカダテムラ','Inakadate-mura',40.631259,140.550198),(216,'023817','02','Itayanagi-machi',NULL,'Itayanagi-machi, Aomori · 青森県板柳町',209,1,'板柳町','イタヤナギマチ','Itayanagi-machi',40.695896,140.457414),(217,'023841','02','Tsuruta-machi',NULL,'Tsuruta-machi, Aomori · 青森県鶴田町',210,1,'鶴田町','ツルタマチ','Tsuruta-machi',40.758819,140.428702),(218,'023876','02','Nakadomari-machi',NULL,'Nakadomari-machi, Aomori · 青森県中泊町',211,1,'中泊町','ナカドマリマチ','Nakadomari-machi',40.960440,140.434113),(219,'024015','02','Noheji-machi',NULL,'Noheji-machi, Aomori · 青森県野辺地町',212,1,'野辺地町','ノヘジマチ','Noheji-machi',40.864447,141.128685),(220,'024023','02','Shichinohe-machi',NULL,'Shichinohe-machi, Aomori · 青森県七戸町',213,1,'七戸町','シチノヘマチ','Shichinohe-machi',40.744714,141.157991),(221,'024058','02','Rokunohe-machi',NULL,'Rokunohe-machi, Aomori · 青森県六戸町',214,1,'六戸町','ロクノヘマチ','Rokunohe-machi',40.609526,141.324958),(222,'024066','02','Yokohama-machi',NULL,'Yokohama-machi, Aomori · 青森県横浜町',215,1,'横浜町','ヨコハママチ','Yokohama-machi',41.083155,141.247782),(223,'024082','02','Tohoku-machi',NULL,'Tohoku-machi, Aomori · 青森県東北町',216,1,'東北町','トウホクマチ','Tohoku-machi',40.727871,141.257888),(224,'024112','02','Rokkasho-mura',NULL,'Rokkasho-mura, Aomori · 青森県六ヶ所村',217,1,'六ヶ所村','ロッカショムラ','Rokkasho-mura',40.967375,141.374434),(225,'024121','02','Oirase-cho',NULL,'Oirase-cho, Aomori · 青森県おいらせ町',218,1,'おいらせ町','オイラセチョウ','Oirase-cho',40.599104,141.397822),(226,'024236','02','Oma-machi',NULL,'Oma-machi, Aomori · 青森県大間町',219,1,'大間町','オオママチ','Oma-machi',41.522371,140.904830),(227,'024244','02','Higashidori-mura',NULL,'Higashidori-mura, Aomori · 青森県東通村',220,1,'東通村','ヒガシドオリムラ','Higashidori-mura',41.277726,141.328900),(228,'024252','02','Kazamaura-mura',NULL,'Kazamaura-mura, Aomori · 青森県風間浦村',221,1,'風間浦村','カザマウラムラ','Kazamaura-mura',41.487487,140.995534),(229,'024261','02','Sai-mura',NULL,'Sai-mura, Aomori · 青森県佐井村',222,1,'佐井村','サイムラ','Sai-mura',41.429718,140.859175),(230,'024414','02','Sannohe-machi',NULL,'Sannohe-machi, Aomori · 青森県三戸町',223,1,'三戸町','サンノヘマチ','Sannohe-machi',40.378423,141.258740),(231,'024422','02','Gonohe-machi',NULL,'Gonohe-machi, Aomori · 青森県五戸町',224,1,'五戸町','ゴノヘマチ','Gonohe-machi',40.531208,141.307813),(232,'024431','02','Takko-machi',NULL,'Takko-machi, Aomori · 青森県田子町',225,1,'田子町','タッコマチ','Takko-machi',40.340030,141.152150),(233,'024457','02','Nambu-cho',NULL,'Nambu-cho, Aomori · 青森県南部町',226,1,'南部町','ナンブチョウ','Nambu-cho',40.420431,141.330464),(234,'024465','02','Hashikami-cho',NULL,'Hashikami-cho, Aomori · 青森県階上町',227,1,'階上町','ハシカミチョウ','Hashikami-cho',40.452454,141.621001),(235,'024503','02','Shingo-mura',NULL,'Shingo-mura, Aomori · 青森県新郷村',228,1,'新郷村','シンゴウムラ','Shingo-mura',40.465784,141.173400),(236,'032018','03','Morioka-shi',NULL,'Morioka-shi, Iwate · 岩手県盛岡市',229,1,'盛岡市','モリオカシ','Morioka-shi',39.701795,141.154183),(237,'032026','03','Miyako-shi',NULL,'Miyako-shi, Iwate · 岩手県宮古市',230,1,'宮古市','ミヤコシ','Miyako-shi',39.639517,141.946133),(238,'032034','03','Ofunato-shi',NULL,'Ofunato-shi, Iwate · 岩手県大船渡市',231,1,'大船渡市','オオフナトシ','Ofunato-shi',39.081901,141.708547),(239,'032051','03','Hanamaki-shi',NULL,'Hanamaki-shi, Iwate · 岩手県花巻市',232,1,'花巻市','ハナマキシ','Hanamaki-shi',39.388609,141.116854),(240,'032069','03','Kitakami-shi',NULL,'Kitakami-shi, Iwate · 岩手県北上市',233,1,'北上市','キタカミシ','Kitakami-shi',39.286817,141.113157),(241,'032077','03','Kuji-shi',NULL,'Kuji-shi, Iwate · 岩手県久慈市',234,1,'久慈市','クジシ','Kuji-shi',40.190466,141.775644),(242,'032085','03','Tono-shi',NULL,'Tono-shi, Iwate · 岩手県遠野市',235,1,'遠野市','トオノシ','Tono-shi',39.330650,141.531463),(243,'032093','03','Ichinoseki-shi',NULL,'Ichinoseki-shi, Iwate · 岩手県一関市',236,1,'一関市','イチノセキシ','Ichinoseki-shi',38.934754,141.126759),(244,'032107','03','Rikuzentakata-shi',NULL,'Rikuzentakata-shi, Iwate · 岩手県陸前高田市',237,1,'陸前高田市','リクゼンタカタシ','Rikuzentakata-shi',39.020372,141.633237),(245,'032115','03','Kamaishi-shi',NULL,'Kamaishi-shi, Iwate · 岩手県釜石市',238,1,'釜石市','カマイシシ','Kamaishi-shi',39.275810,141.885716),(246,'032131','03','Ninohe-shi',NULL,'Ninohe-shi, Iwate · 岩手県二戸市',239,1,'二戸市','ニノヘシ','Ninohe-shi',40.271193,141.304805),(247,'032140','03','Hachimantai-shi',NULL,'Hachimantai-shi, Iwate · 岩手県八幡平市',240,1,'八幡平市','ハチマンタイシ','Hachimantai-shi',39.956511,141.071120),(248,'032158','03','Oshu-shi',NULL,'Oshu-shi, Iwate · 岩手県奥州市',241,1,'奥州市','オウシュウシ','Oshu-shi',39.144506,141.139116),(249,'032166','03','Takizawa-shi',NULL,'Takizawa-shi, Iwate · 岩手県滝沢市',242,1,'滝沢市','タキザワシ','Takizawa-shi',39.734739,141.077065),(250,'033014','03','Shizukuishi-cho',NULL,'Shizukuishi-cho, Iwate · 岩手県雫石町',243,1,'雫石町','シズクイシチョウ','Shizukuishi-cho',39.696319,140.975779),(251,'033022','03','Kuzumaki-machi',NULL,'Kuzumaki-machi, Iwate · 岩手県葛巻町',244,1,'葛巻町','クズマキマチ','Kuzumaki-machi',40.039840,141.436719),(252,'033031','03','Iwate-machi',NULL,'Iwate-machi, Iwate · 岩手県岩手町',245,1,'岩手町','イワテマチ','Iwate-machi',39.972768,141.212166),(253,'033219','03','Shiwa-cho',NULL,'Shiwa-cho, Iwate · 岩手県紫波町',246,1,'紫波町','シワチョウ','Shiwa-cho',39.554531,141.155782),(254,'033227','03','Yahaba-cho',NULL,'Yahaba-cho, Iwate · 岩手県矢巾町',247,1,'矢巾町','ヤハバチョウ','Yahaba-cho',39.606006,141.143001),(255,'033669','03','Nishiwaga-machi',NULL,'Nishiwaga-machi, Iwate · 岩手県西和賀町',248,1,'西和賀町','ニシワガマチ','Nishiwaga-machi',39.317906,140.778754),(256,'033812','03','Kanegasaki-cho',NULL,'Kanegasaki-cho, Iwate · 岩手県金ケ崎町',249,1,'金ケ崎町','カネガサキチョウ','Kanegasaki-cho',39.195687,141.116408),(257,'034029','03','Hiraizumi-cho',NULL,'Hiraizumi-cho, Iwate · 岩手県平泉町',250,1,'平泉町','ヒライズミチョウ','Hiraizumi-cho',38.986781,141.113980),(258,'034410','03','Sumita-cho',NULL,'Sumita-cho, Iwate · 岩手県住田町',251,1,'住田町','スミタチョウ','Sumita-cho',39.142223,141.575065),(259,'034614','03','Otsuchi-cho',NULL,'Otsuchi-cho, Iwate · 岩手県大槌町',252,1,'大槌町','オオツチチョウ','Otsuchi-cho',39.358196,141.899731),(260,'034827','03','Yamada-machi',NULL,'Yamada-machi, Iwate · 岩手県山田町',253,1,'山田町','ヤマダマチ','Yamada-machi',39.467619,141.948923),(261,'034835','03','Iwaizumi-cho',NULL,'Iwaizumi-cho, Iwate · 岩手県岩泉町',254,1,'岩泉町','イワイズミチョウ','Iwaizumi-cho',39.843204,141.796675),(262,'034843','03','Tanohata-mura',NULL,'Tanohata-mura, Iwate · 岩手県田野畑村',255,1,'田野畑村','タノハタムラ','Tanohata-mura',39.930454,141.888864),(263,'034851','03','Fudai-mura',NULL,'Fudai-mura, Iwate · 岩手県普代村',256,1,'普代村','フダイムラ','Fudai-mura',40.005164,141.886014),(264,'035017','03','Karumai-machi',NULL,'Karumai-machi, Iwate · 岩手県軽米町',257,1,'軽米町','カルマイマチ','Karumai-machi',40.326678,141.460349),(265,'035033','03','Noda-mura',NULL,'Noda-mura, Iwate · 岩手県野田村',258,1,'野田村','ノダムラ','Noda-mura',40.110272,141.817674),(266,'035068','03','Kunohe-mura',NULL,'Kunohe-mura, Iwate · 岩手県九戸村',259,1,'九戸村','クノヘムラ','Kunohe-mura',40.211356,141.418982),(267,'035076','03','Hirono-cho',NULL,'Hirono-cho, Iwate · 岩手県洋野町',260,1,'洋野町','ヒロノチョウ','Hirono-cho',40.408298,141.718650),(268,'035246','03','Ichinohe-machi',NULL,'Ichinohe-machi, Iwate · 岩手県一戸町',261,1,'一戸町','イチノヘマチ','Ichinohe-machi',40.212733,141.295460),(269,'041017','04','Aoba-ku, Sendai-shi',NULL,'Aoba-ku, Sendai-shi, Miyagi · 宮城県仙台市青葉区',262,1,'仙台市青葉区','センダイシアオバク','Aoba-ku, Sendai-shi',38.269087,140.870378),(270,'041025','04','Miyagino-ku, Sendai-shi',NULL,'Miyagino-ku, Sendai-shi, Miyagi · 宮城県仙台市宮城野区',263,1,'仙台市宮城野区','センダイシミヤギノク','Miyagino-ku, Sendai-shi',38.266189,140.909841),(271,'041033','04','Wakabayashi-ku, Sendai-shi',NULL,'Wakabayashi-ku, Sendai-shi, Miyagi · 宮城県仙台市若林区',264,1,'仙台市若林区','センダイシワカバヤシク','Wakabayashi-ku, Sendai-shi',38.244180,140.900748),(272,'041041','04','Taihaku-ku, Sendai-shi',NULL,'Taihaku-ku, Sendai-shi, Miyagi · 宮城県仙台市太白区',265,1,'仙台市太白区','センダイシタイハクク','Taihaku-ku, Sendai-shi',38.224395,140.877212),(273,'041050','04','Izumi-ku, Sendai-shi',NULL,'Izumi-ku, Sendai-shi, Miyagi · 宮城県仙台市泉区',266,1,'仙台市泉区','センダイシイズミク','Izumi-ku, Sendai-shi',38.326361,140.881615),(274,'042021','04','Ishinomaki-shi',NULL,'Ishinomaki-shi, Miyagi · 宮城県石巻市',267,1,'石巻市','イシノマキシ','Ishinomaki-shi',38.434457,141.302906),(275,'042030','04','Shiogama-shi',NULL,'Shiogama-shi, Miyagi · 宮城県塩竈市',268,1,'塩竈市','シオガマシ','Shiogama-shi',38.314360,141.022030),(276,'042056','04','Kesennuma-shi',NULL,'Kesennuma-shi, Miyagi · 宮城県気仙沼市',269,1,'気仙沼市','ケセンヌマシ','Kesennuma-shi',38.908127,141.570044),(277,'042064','04','Shiroishi-shi',NULL,'Shiroishi-shi, Miyagi · 宮城県白石市',270,1,'白石市','シロイシシ','Shiroishi-shi',38.002467,140.619888),(278,'042072','04','Natori-shi',NULL,'Natori-shi, Miyagi · 宮城県名取市',271,1,'名取市','ナトリシ','Natori-shi',38.171501,140.891849),(279,'042081','04','Kakuda-shi',NULL,'Kakuda-shi, Miyagi · 宮城県角田市',272,1,'角田市','カクダシ','Kakuda-shi',37.977016,140.781543),(280,'042099','04','Tagajo-shi',NULL,'Tagajo-shi, Miyagi · 宮城県多賀城市',273,1,'多賀城市','タガジョウシ','Tagajo-shi',38.293803,141.004369),(281,'042111','04','Iwanuma-shi',NULL,'Iwanuma-shi, Miyagi · 宮城県岩沼市',274,1,'岩沼市','イワヌマシ','Iwanuma-shi',38.104303,140.869949),(282,'042129','04','Tome-shi',NULL,'Tome-shi, Miyagi · 宮城県登米市',275,1,'登米市','トメシ','Tome-shi',38.691856,141.187720),(283,'042137','04','Kurihara-shi',NULL,'Kurihara-shi, Miyagi · 宮城県栗原市',276,1,'栗原市','クリハラシ','Kurihara-shi',38.730062,141.021508),(284,'042145','04','Higashimatsushima-shi',NULL,'Higashimatsushima-shi, Miyagi · 宮城県東松島市',277,1,'東松島市','ヒガシマツシマシ','Higashimatsushima-shi',38.426134,141.210278),(285,'042153','04','Osaki-shi',NULL,'Osaki-shi, Miyagi · 宮城県大崎市',278,1,'大崎市','オオサキシ','Osaki-shi',38.577132,140.955565),(286,'042161','04','Tomiya-shi',NULL,'Tomiya-shi, Miyagi · 宮城県富谷市',279,1,'富谷市','トミヤシ','Tomiya-shi',38.399918,140.895485),(287,'043010','04','Zao-machi',NULL,'Zao-machi, Miyagi · 宮城県蔵王町',280,1,'蔵王町','ザオウマチ','Zao-machi',38.098128,140.658680),(288,'043028','04','Shichikashuku-machi',NULL,'Shichikashuku-machi, Miyagi · 宮城県七ヶ宿町',281,1,'七ヶ宿町','シチカシュクマチ','Shichikashuku-machi',37.993063,140.441615),(289,'043214','04','Ogawara-machi',NULL,'Ogawara-machi, Miyagi · 宮城県大河原町',282,1,'大河原町','オオガワラマチ','Ogawara-machi',38.049412,140.730774),(290,'043222','04','Murata-machi',NULL,'Murata-machi, Miyagi · 宮城県村田町',283,1,'村田町','ムラタマチ','Murata-machi',38.118589,140.722404),(291,'043231','04','Shibata-machi',NULL,'Shibata-machi, Miyagi · 宮城県柴田町',284,1,'柴田町','シバタマチ','Shibata-machi',38.056599,140.765798),(292,'043249','04','Kawasaki-machi',NULL,'Kawasaki-machi, Miyagi · 宮城県川崎町',285,1,'川崎町','カワサキマチ','Kawasaki-machi',38.177751,140.643188),(293,'043419','04','Marumori-machi',NULL,'Marumori-machi, Miyagi · 宮城県丸森町',286,1,'丸森町','マルモリマチ','Marumori-machi',37.911536,140.765365),(294,'043613','04','Watari-cho',NULL,'Watari-cho, Miyagi · 宮城県亘理町',287,1,'亘理町','ワタリチョウ','Watari-cho',38.044251,140.867917),(295,'043621','04','Yamamoto-cho',NULL,'Yamamoto-cho, Miyagi · 宮城県山元町',288,1,'山元町','ヤマモトチョウ','Yamamoto-cho',37.962405,140.877529),(296,'044016','04','Matsushima-machi',NULL,'Matsushima-machi, Miyagi · 宮城県松島町',289,1,'松島町','マツシママチ','Matsushima-machi',38.380149,141.067291),(297,'044041','04','Shichigahama-machi',NULL,'Shichigahama-machi, Miyagi · 宮城県七ヶ浜町',290,1,'七ヶ浜町','シチガハママチ','Shichigahama-machi',38.304554,141.059143),(298,'044067','04','Rifu-cho',NULL,'Rifu-cho, Miyagi · 宮城県利府町',291,1,'利府町','リフチョウ','Rifu-cho',38.330355,140.975793),(299,'044211','04','Taiwa-cho',NULL,'Taiwa-cho, Miyagi · 宮城県大和町',292,1,'大和町','タイワチョウ','Taiwa-cho',38.437330,140.886309),(300,'044229','04','Osato-cho',NULL,'Osato-cho, Miyagi · 宮城県大郷町',293,1,'大郷町','オオサトチョウ','Osato-cho',38.424244,141.004460),(301,'044245','04','Ohira-mura',NULL,'Ohira-mura, Miyagi · 宮城県大衡村',294,1,'大衡村','オオヒラムラ','Ohira-mura',38.467278,140.879982),(302,'044440','04','Shikama-cho',NULL,'Shikama-cho, Miyagi · 宮城県色麻町',295,1,'色麻町','シカマチョウ','Shikama-cho',38.548921,140.849876),(303,'044458','04','Kami-machi',NULL,'Kami-machi, Miyagi · 宮城県加美町',296,1,'加美町','カミマチ','Kami-machi',38.571770,140.854759),(304,'045012','04','Wakuya-cho',NULL,'Wakuya-cho, Miyagi · 宮城県涌谷町',297,1,'涌谷町','ワクヤチョウ','Wakuya-cho',38.539704,141.128105),(305,'045055','04','Misato-machi',NULL,'Misato-machi, Miyagi · 宮城県美里町',298,1,'美里町','ミサトマチ','Misato-machi',38.544400,141.056717),(306,'045811','04','Onagawa-cho',NULL,'Onagawa-cho, Miyagi · 宮城県女川町',299,1,'女川町','オナガワチョウ','Onagawa-cho',38.445417,141.442972),(307,'046060','04','Minamisanriku-cho',NULL,'Minamisanriku-cho, Miyagi · 宮城県南三陸町',300,1,'南三陸町','ミナミサンリクチョウ','Minamisanriku-cho',38.680707,141.462552),(308,'052019','05','Akita-shi',NULL,'Akita-shi, Akita · 秋田県秋田市',301,1,'秋田市','アキタシ','Akita-shi',39.719929,140.102512),(309,'052027','05','Noshiro-shi',NULL,'Noshiro-shi, Akita · 秋田県能代市',302,1,'能代市','ノシロシ','Noshiro-shi',40.212131,140.026641),(310,'052035','05','Yokote-shi',NULL,'Yokote-shi, Akita · 秋田県横手市',303,1,'横手市','ヨコテシ','Yokote-shi',39.313777,140.566636),(311,'052043','05','Odate-shi',NULL,'Odate-shi, Akita · 秋田県大館市',304,1,'大館市','オオダテシ','Odate-shi',40.271372,140.564363),(312,'052060','05','Oga-shi',NULL,'Oga-shi, Akita · 秋田県男鹿市',305,1,'男鹿市','オガシ','Oga-shi',39.886721,139.847500),(313,'052078','05','Yuzawa-shi',NULL,'Yuzawa-shi, Akita · 秋田県湯沢市',306,1,'湯沢市','ユザワシ','Yuzawa-shi',39.164074,140.494683),(314,'052094','05','Kazuno-shi',NULL,'Kazuno-shi, Akita · 秋田県鹿角市',307,1,'鹿角市','カヅノシ','Kazuno-shi',40.215789,140.788519),(315,'052108','05','Yurihonjo-shi',NULL,'Yurihonjo-shi, Akita · 秋田県由利本荘市',308,1,'由利本荘市','ユリホンジョウシ','Yurihonjo-shi',39.385814,140.048819),(316,'052116','05','Katagami-shi',NULL,'Katagami-shi, Akita · 秋田県潟上市',309,1,'潟上市','カタガミシ','Katagami-shi',39.857248,140.013054),(317,'052124','05','Daisen-shi',NULL,'Daisen-shi, Akita · 秋田県大仙市',310,1,'大仙市','ダイセンシ','Daisen-shi',39.453090,140.475448),(318,'052132','05','Kitaakita-shi',NULL,'Kitaakita-shi, Akita · 秋田県北秋田市',311,1,'北秋田市','キタアキタシ','Kitaakita-shi',40.226046,140.370728),(319,'052141','05','Nikaho-shi',NULL,'Nikaho-shi, Akita · 秋田県にかほ市',312,1,'にかほ市','ニカホシ','Nikaho-shi',39.203067,139.907649),(320,'052159','05','Semboku-shi',NULL,'Semboku-shi, Akita · 秋田県仙北市',313,1,'仙北市','センボクシ','Semboku-shi',39.700044,140.730728),(321,'053031','05','Kosaka-machi',NULL,'Kosaka-machi, Akita · 秋田県小坂町',314,1,'小坂町','コサカマチ','Kosaka-machi',40.332926,140.736191),(322,'053279','05','Kamikoani-mura',NULL,'Kamikoani-mura, Akita · 秋田県上小阿仁村',315,1,'上小阿仁村','カミコアニムラ','Kamikoani-mura',40.063263,140.295736),(323,'053465','05','Fujisato-machi',NULL,'Fujisato-machi, Akita · 秋田県藤里町',316,1,'藤里町','フジサトマチ','Fujisato-machi',40.278399,140.261879),(324,'053481','05','Mitane-cho',NULL,'Mitane-cho, Akita · 秋田県三種町',317,1,'三種町','ミタネチョウ','Mitane-cho',40.101615,140.004981),(325,'053490','05','Happo-cho',NULL,'Happo-cho, Akita · 秋田県八峰町',318,1,'八峰町','ハッポウチョウ','Happo-cho',40.318715,140.038618),(326,'053619','05','Gojome-machi',NULL,'Gojome-machi, Akita · 秋田県五城目町',319,1,'五城目町','ゴジョウメマチ','Gojome-machi',39.943895,140.111309),(327,'053635','05','Hachirogata-machi',NULL,'Hachirogata-machi, Akita · 秋田県八郎潟町',320,1,'八郎潟町','ハチロウガタマチ','Hachirogata-machi',39.949355,140.073335),(328,'053660','05','Ikawa-machi',NULL,'Ikawa-machi, Akita · 秋田県井川町',321,1,'井川町','イカワマチ','Ikawa-machi',39.914188,140.081265),(329,'053686','05','Ogata-mura',NULL,'Ogata-mura, Akita · 秋田県大潟村',322,1,'大潟村','オオガタムラ','Ogata-mura',40.017788,139.960064),(330,'054348','05','Misato-cho',NULL,'Misato-cho, Akita · 秋田県美郷町',323,1,'美郷町','ミサトチョウ','Misato-cho',39.461633,140.582127),(331,'054631','05','Ugo-machi',NULL,'Ugo-machi, Akita · 秋田県羽後町',324,1,'羽後町','ウゴマチ','Ugo-machi',39.199323,140.412881),(332,'054640','05','Higashinaruse-mura',NULL,'Higashinaruse-mura, Akita · 秋田県東成瀬村',325,1,'東成瀬村','ヒガシナルセムラ','Higashinaruse-mura',39.179185,140.648892),(333,'062014','06','Yamagata-shi',NULL,'Yamagata-shi, Yamagata · 山形県山形市',326,1,'山形市','ヤマガタシ','Yamagata-shi',38.255436,140.339605),(334,'062022','06','Yonezawa-shi',NULL,'Yonezawa-shi, Yamagata · 山形県米沢市',327,1,'米沢市','ヨネザワシ','Yonezawa-shi',37.922242,140.116683),(335,'062031','06','Tsuruoka-shi',NULL,'Tsuruoka-shi, Yamagata · 山形県鶴岡市',328,1,'鶴岡市','ツルオカシ','Tsuruoka-shi',38.727183,139.826725),(336,'062049','06','Sakata-shi',NULL,'Sakata-shi, Yamagata · 山形県酒田市',329,1,'酒田市','サカタシ','Sakata-shi',38.914386,139.836513),(337,'062057','06','Shinjo-shi',NULL,'Shinjo-shi, Yamagata · 山形県新庄市',330,1,'新庄市','シンジョウシ','Shinjo-shi',38.764960,140.301664),(338,'062065','06','Sagae-shi',NULL,'Sagae-shi, Yamagata · 山形県寒河江市',331,1,'寒河江市','サガエシ','Sagae-shi',38.380954,140.276068),(339,'062073','06','Kaminoyama-shi',NULL,'Kaminoyama-shi, Yamagata · 山形県上山市',332,1,'上山市','カミノヤマシ','Kaminoyama-shi',38.149562,140.267830),(340,'062081','06','Murayama-shi',NULL,'Murayama-shi, Yamagata · 山形県村山市',333,1,'村山市','ムラヤマシ','Murayama-shi',38.483352,140.380380),(341,'062090','06','Nagai-shi',NULL,'Nagai-shi, Yamagata · 山形県長井市',334,1,'長井市','ナガイシ','Nagai-shi',38.105998,140.034040),(342,'062103','06','Tendo-shi',NULL,'Tendo-shi, Yamagata · 山形県天童市',335,1,'天童市','テンドウシ','Tendo-shi',38.362177,140.377802),(343,'062111','06','Higashine-shi',NULL,'Higashine-shi, Yamagata · 山形県東根市',336,1,'東根市','ヒガシネシ','Higashine-shi',38.431292,140.391021),(344,'062120','06','Obanazawa-shi',NULL,'Obanazawa-shi, Yamagata · 山形県尾花沢市',337,1,'尾花沢市','オバナザワシ','Obanazawa-shi',38.601071,140.406824),(345,'062138','06','Nanyo-shi',NULL,'Nanyo-shi, Yamagata · 山形県南陽市',338,1,'南陽市','ナンヨウシ','Nanyo-shi',38.055116,140.148309),(346,'063011','06','Yamanobe-machi',NULL,'Yamanobe-machi, Yamagata · 山形県山辺町',339,1,'山辺町','ヤマノベマチ','Yamanobe-machi',38.289217,140.262180),(347,'063029','06','Nakayama-machi',NULL,'Nakayama-machi, Yamagata · 山形県中山町',340,1,'中山町','ナカヤママチ','Nakayama-machi',38.333122,140.283051),(348,'063215','06','Kahoku-cho',NULL,'Kahoku-cho, Yamagata · 山形県河北町',341,1,'河北町','カホクチョウ','Kahoku-cho',38.426276,140.314310),(349,'063223','06','Nishikawa-machi',NULL,'Nishikawa-machi, Yamagata · 山形県西川町',342,1,'西川町','ニシカワマチ','Nishikawa-machi',38.426487,140.147661),(350,'063231','06','Asahi-machi',NULL,'Asahi-machi, Yamagata · 山形県朝日町',343,1,'朝日町','アサヒマチ','Asahi-machi',38.299293,140.145924),(351,'063240','06','Oe-machi',NULL,'Oe-machi, Yamagata · 山形県大江町',344,1,'大江町','オオエマチ','Oe-machi',38.380713,140.206793),(352,'063410','06','Oishida-machi',NULL,'Oishida-machi, Yamagata · 山形県大石田町',345,1,'大石田町','オオイシダマチ','Oishida-machi',38.593877,140.372647),(353,'063614','06','Kaneyama-machi',NULL,'Kaneyama-machi, Yamagata · 山形県金山町',346,1,'金山町','カネヤママチ','Kaneyama-machi',38.883440,140.339367),(354,'063622','06','Mogami-machi',NULL,'Mogami-machi, Yamagata · 山形県最上町',347,1,'最上町','モガミマチ','Mogami-machi',38.758467,140.519359),(355,'063631','06','Funagata-machi',NULL,'Funagata-machi, Yamagata · 山形県舟形町',348,1,'舟形町','フナガタマチ','Funagata-machi',38.691417,140.319988),(356,'063649','06','Mamurogawa-machi',NULL,'Mamurogawa-machi, Yamagata · 山形県真室川町',349,1,'真室川町','マムロガワマチ','Mamurogawa-machi',38.856933,140.251567),(357,'063657','06','Okura-mura',NULL,'Okura-mura, Yamagata · 山形県大蔵村',350,1,'大蔵村','オオクラムラ','Okura-mura',38.704121,140.230368),(358,'063665','06','Sakegawa-mura',NULL,'Sakegawa-mura, Yamagata · 山形県鮭川村',351,1,'鮭川村','サケガワムラ','Sakegawa-mura',38.796455,140.222007),(359,'063673','06','Tozawa-mura',NULL,'Tozawa-mura, Yamagata · 山形県戸沢村',352,1,'戸沢村','トザワムラ','Tozawa-mura',38.737601,140.143569),(360,'063819','06','Takahata-machi',NULL,'Takahata-machi, Yamagata · 山形県高畠町',353,1,'高畠町','タカハタマチ','Takahata-machi',38.002745,140.189113),(361,'063827','06','Kawanishi-machi',NULL,'Kawanishi-machi, Yamagata · 山形県川西町',354,1,'川西町','カワニシマチ','Kawanishi-machi',38.005188,140.053094),(362,'064017','06','Oguni-machi',NULL,'Oguni-machi, Yamagata · 山形県小国町',355,1,'小国町','オグニマチ','Oguni-machi',38.061391,139.743333),(363,'064025','06','Shirataka-machi',NULL,'Shirataka-machi, Yamagata · 山形県白鷹町',356,1,'白鷹町','シラタカマチ','Shirataka-machi',38.183069,140.098571),(364,'064033','06','Iide-machi',NULL,'Iide-machi, Yamagata · 山形県飯豊町',357,1,'飯豊町','イイデマチ','Iide-machi',38.045714,139.987617),(365,'064262','06','Mikawa-machi',NULL,'Mikawa-machi, Yamagata · 山形県三川町',358,1,'三川町','ミカワマチ','Mikawa-machi',38.794529,139.849612),(366,'064289','06','Shonai-machi',NULL,'Shonai-machi, Yamagata · 山形県庄内町',359,1,'庄内町','ショウナイマチ','Shonai-machi',38.849845,139.904723),(367,'064611','06','Yuza-machi',NULL,'Yuza-machi, Yamagata · 山形県遊佐町',360,1,'遊佐町','ユザマチ','Yuza-machi',39.014837,139.908673),(368,'072010','07','Fukushima-shi',NULL,'Fukushima-shi, Fukushima · 福島県福島市',361,1,'福島市','フクシマシ','Fukushima-shi',37.760759,140.473269),(369,'072028','07','Aizuwakamatsu-shi',NULL,'Aizuwakamatsu-shi, Fukushima · 福島県会津若松市',362,1,'会津若松市','アイヅワカマツシ','Aizuwakamatsu-shi',37.494842,139.929707),(370,'072036','07','Koriyama-shi',NULL,'Koriyama-shi, Fukushima · 福島県郡山市',363,1,'郡山市','コオリヤマシ','Koriyama-shi',37.400455,140.359650),(371,'072044','07','Iwaki-shi',NULL,'Iwaki-shi, Fukushima · 福島県いわき市',364,1,'いわき市','イワキシ','Iwaki-shi',37.050456,140.887687),(372,'072052','07','Shirakawa-shi',NULL,'Shirakawa-shi, Fukushima · 福島県白河市',365,1,'白河市','シラカワシ','Shirakawa-shi',37.126379,140.210923),(373,'072079','07','Sukagawa-shi',NULL,'Sukagawa-shi, Fukushima · 福島県須賀川市',366,1,'須賀川市','スカガワシ','Sukagawa-shi',37.286913,140.372534),(374,'072087','07','Kitakata-shi',NULL,'Kitakata-shi, Fukushima · 福島県喜多方市',367,1,'喜多方市','キタカタシ','Kitakata-shi',37.651134,139.874484),(375,'072095','07','Soma-shi',NULL,'Soma-shi, Fukushima · 福島県相馬市',368,1,'相馬市','ソウマシ','Soma-shi',37.797242,140.918900),(376,'072109','07','Nihommatsu-shi',NULL,'Nihommatsu-shi, Fukushima · 福島県二本松市',369,1,'二本松市','ニホンマツシ','Nihommatsu-shi',37.584783,140.431199),(377,'072117','07','Tamura-shi',NULL,'Tamura-shi, Fukushima · 福島県田村市',370,1,'田村市','タムラシ','Tamura-shi',37.440592,140.576339),(378,'072125','07','Minamisoma-shi',NULL,'Minamisoma-shi, Fukushima · 福島県南相馬市',371,1,'南相馬市','ミナミソウマシ','Minamisoma-shi',37.642197,140.957237),(379,'072133','07','Date-shi',NULL,'Date-shi, Fukushima · 福島県伊達市',372,1,'伊達市','ダテシ','Date-shi',37.819095,140.562894),(380,'072141','07','Motomiya-shi',NULL,'Motomiya-shi, Fukushima · 福島県本宮市',373,1,'本宮市','モトミヤシ','Motomiya-shi',37.513162,140.393877),(381,'073016','07','Kori-machi',NULL,'Kori-machi, Fukushima · 福島県桑折町',374,1,'桑折町','コオリマチ','Kori-machi',37.854640,140.520775),(382,'073032','07','Kunimi-machi',NULL,'Kunimi-machi, Fukushima · 福島県国見町',375,1,'国見町','クニミマチ','Kunimi-machi',37.876822,140.549228),(383,'073083','07','Kawamata-machi',NULL,'Kawamata-machi, Fukushima · 福島県川俣町',376,1,'川俣町','カワマタマチ','Kawamata-machi',37.664989,140.598244),(384,'073229','07','Otama-mura',NULL,'Otama-mura, Fukushima · 福島県大玉村',377,1,'大玉村','オオタマムラ','Otama-mura',37.534362,140.371058),(385,'073423','07','Kagamiishi-machi',NULL,'Kagamiishi-machi, Fukushima · 福島県鏡石町',378,1,'鏡石町','カガミイシマチ','Kagamiishi-machi',37.252858,140.343405),(386,'073440','07','Tenei-mura',NULL,'Tenei-mura, Fukushima · 福島県天栄村',379,1,'天栄村','テンエイムラ','Tenei-mura',37.255576,140.247165),(387,'073628','07','Shimogo-machi',NULL,'Shimogo-machi, Fukushima · 福島県下郷町',380,1,'下郷町','シモゴウマチ','Shimogo-machi',37.255571,139.872084),(388,'073644','07','Hinoemata-mura',NULL,'Hinoemata-mura, Fukushima · 福島県檜枝岐村',381,1,'檜枝岐村','ヒノエマタムラ','Hinoemata-mura',37.024141,139.388941),(389,'073679','07','Tadami-machi',NULL,'Tadami-machi, Fukushima · 福島県只見町',382,1,'只見町','タダミマチ','Tadami-machi',37.348657,139.315789),(390,'073687','07','Minamiaizu-machi',NULL,'Minamiaizu-machi, Fukushima · 福島県南会津町',383,1,'南会津町','ミナミアイヅマチ','Minamiaizu-machi',37.200389,139.773215),(391,'074021','07','Kitashiobara-mura',NULL,'Kitashiobara-mura, Fukushima · 福島県北塩原村',384,1,'北塩原村','キタシオバラムラ','Kitashiobara-mura',37.655673,139.937383),(392,'074055','07','Nishiaizu-machi',NULL,'Nishiaizu-machi, Fukushima · 福島県西会津町',385,1,'西会津町','ニシアイヅマチ','Nishiaizu-machi',37.587400,139.649314),(393,'074071','07','Bandai-machi',NULL,'Bandai-machi, Fukushima · 福島県磐梯町',386,1,'磐梯町','バンダイマチ','Bandai-machi',37.562099,139.988766),(394,'074080','07','Inawashiro-machi',NULL,'Inawashiro-machi, Fukushima · 福島県猪苗代町',387,1,'猪苗代町','イナワシロマチ','Inawashiro-machi',37.557799,140.104794),(395,'074217','07','Aizubange-machi',NULL,'Aizubange-machi, Fukushima · 福島県会津坂下町',388,1,'会津坂下町','アイヅバンゲマチ','Aizubange-machi',37.561457,139.821732),(396,'074225','07','Yugawa-mura',NULL,'Yugawa-mura, Fukushima · 福島県湯川村',389,1,'湯川村','ユガワムラ','Yugawa-mura',37.565657,139.886738),(397,'074233','07','Yanaizu-machi',NULL,'Yanaizu-machi, Fukushima · 福島県柳津町',390,1,'柳津町','ヤナイヅマチ','Yanaizu-machi',37.526035,139.719552),(398,'074446','07','Mishima-machi',NULL,'Mishima-machi, Fukushima · 福島県三島町',391,1,'三島町','ミシママチ','Mishima-machi',37.470292,139.644441),(399,'074454','07','Kaneyama-machi',NULL,'Kaneyama-machi, Fukushima · 福島県金山町',392,1,'金山町','カネヤママチ','Kaneyama-machi',37.453735,139.524524),(400,'074462','07','Showa-mura',NULL,'Showa-mura, Fukushima · 福島県昭和村',393,1,'昭和村','ショウワムラ','Showa-mura',37.335446,139.610666),(401,'074471','07','Aizumisato-machi',NULL,'Aizumisato-machi, Fukushima · 福島県会津美里町',394,1,'会津美里町','アイヅミサトマチ','Aizumisato-machi',37.465240,139.834011),(402,'074616','07','Nishigo-mura',NULL,'Nishigo-mura, Fukushima · 福島県西郷村',395,1,'西郷村','ニシゴウムラ','Nishigo-mura',37.141767,140.155299),(403,'074641','07','Izumizaki-mura',NULL,'Izumizaki-mura, Fukushima · 福島県泉崎村',396,1,'泉崎村','イズミザキムラ','Izumizaki-mura',37.153797,140.303441),(404,'074659','07','Nakajima-mura',NULL,'Nakajima-mura, Fukushima · 福島県中島村',397,1,'中島村','ナカジマムラ','Nakajima-mura',37.148756,140.350230),(405,'074667','07','Yabuki-machi',NULL,'Yabuki-machi, Fukushima · 福島県矢吹町',398,1,'矢吹町','ヤブキマチ','Yabuki-machi',37.201221,140.338493),(406,'074811','07','Tanagura-machi',NULL,'Tanagura-machi, Fukushima · 福島県棚倉町',399,1,'棚倉町','タナグラマチ','Tanagura-machi',37.029867,140.379591),(407,'074829','07','Yamatsuri-machi',NULL,'Yamatsuri-machi, Fukushima · 福島県矢祭町',400,1,'矢祭町','ヤマツリマチ','Yamatsuri-machi',36.871334,140.424755),(408,'074837','07','Hanawa-machi',NULL,'Hanawa-machi, Fukushima · 福島県塙町',401,1,'塙町','ハナワマチ','Hanawa-machi',36.957238,140.409647),(409,'074845','07','Samegawa-mura',NULL,'Samegawa-mura, Fukushima · 福島県鮫川村',402,1,'鮫川村','サメガワムラ','Samegawa-mura',37.042229,140.509699),(410,'075019','07','Ishikawa-machi',NULL,'Ishikawa-machi, Fukushima · 福島県石川町',403,1,'石川町','イシカワマチ','Ishikawa-machi',37.157055,140.446767),(411,'075027','07','Tamakawa-mura',NULL,'Tamakawa-mura, Fukushima · 福島県玉川村',404,1,'玉川村','タマカワムラ','Tamakawa-mura',37.210718,140.408939),(412,'075035','07','Hirata-mura',NULL,'Hirata-mura, Fukushima · 福島県平田村',405,1,'平田村','ヒラタムラ','Hirata-mura',37.221944,140.575685),(413,'075043','07','Asakawa-machi',NULL,'Asakawa-machi, Fukushima · 福島県浅川町',406,1,'浅川町','アサカワマチ','Asakawa-machi',37.080947,140.412852),(414,'075051','07','Furudono-machi',NULL,'Furudono-machi, Fukushima · 福島県古殿町',407,1,'古殿町','フルドノマチ','Furudono-machi',37.089182,140.555692),(415,'075213','07','Miharu-machi',NULL,'Miharu-machi, Fukushima · 福島県三春町',408,1,'三春町','ミハルマチ','Miharu-machi',37.441047,140.492575),(416,'075221','07','Ono-machi',NULL,'Ono-machi, Fukushima · 福島県小野町',409,1,'小野町','オノマチ','Ono-machi',37.286823,140.626280),(417,'075418','07','Hirono-machi',NULL,'Hirono-machi, Fukushima · 福島県広野町',410,1,'広野町','ヒロノマチ','Hirono-machi',37.214403,140.994561),(418,'075426','07','Naraha-machi',NULL,'Naraha-machi, Fukushima · 福島県楢葉町',411,1,'楢葉町','ナラハマチ','Naraha-machi',37.282605,140.993465),(419,'075434','07','Tomioka-machi',NULL,'Tomioka-machi, Fukushima · 福島県富岡町',412,1,'富岡町','トミオカマチ','Tomioka-machi',37.345496,141.008652),(420,'075442','07','Kawauchi-mura',NULL,'Kawauchi-mura, Fukushima · 福島県川内村',413,1,'川内村','カワウチムラ','Kawauchi-mura',37.337680,140.809266),(421,'075451','07','Okuma-machi',NULL,'Okuma-machi, Fukushima · 福島県大熊町',414,1,'大熊町','オオクママチ','Okuma-machi',37.381757,140.958329),(422,'075469','07','Futaba-machi',NULL,'Futaba-machi, Fukushima · 福島県双葉町',415,1,'双葉町','フタバマチ','Futaba-machi',37.452917,141.006340),(423,'075477','07','Namie-machi',NULL,'Namie-machi, Fukushima · 福島県浪江町',416,1,'浪江町','ナミエマチ','Namie-machi',37.494594,141.000746),(424,'075485','07','Katsurao-mura',NULL,'Katsurao-mura, Fukushima · 福島県葛尾村',417,1,'葛尾村','カツラオムラ','Katsurao-mura',37.503547,140.764518),(425,'075612','07','Shinchi-machi',NULL,'Shinchi-machi, Fukushima · 福島県新地町',418,1,'新地町','シンチマチ','Shinchi-machi',37.876379,140.919591),(426,'075647','07','Iitate-mura',NULL,'Iitate-mura, Fukushima · 福島県飯舘村',419,1,'飯舘村','イイタテムラ','Iitate-mura',37.678947,140.735091),(427,'082015','08','Mito-shi',NULL,'Mito-shi, Ibaraki · 茨城県水戸市',420,1,'水戸市','ミトシ','Mito-shi',36.365861,140.471222),(428,'082023','08','Hitachi-shi',NULL,'Hitachi-shi, Ibaraki · 茨城県日立市',421,1,'日立市','ヒタチシ','Hitachi-shi',36.599016,140.651546),(429,'082031','08','Tsuchiura-shi',NULL,'Tsuchiura-shi, Ibaraki · 茨城県土浦市',422,1,'土浦市','ツチウラシ','Tsuchiura-shi',36.078313,140.204306),(430,'082040','08','Koga-shi',NULL,'Koga-shi, Ibaraki · 茨城県古河市',423,1,'古河市','コガシ','Koga-shi',36.178228,139.755371),(431,'082058','08','Ishioka-shi',NULL,'Ishioka-shi, Ibaraki · 茨城県石岡市',424,1,'石岡市','イシオカシ','Ishioka-shi',36.190840,140.287242),(432,'082074','08','Yuki-shi',NULL,'Yuki-shi, Ibaraki · 茨城県結城市',425,1,'結城市','ユウキシ','Yuki-shi',36.289588,139.871455),(433,'082082','08','Ryugasaki-shi',NULL,'Ryugasaki-shi, Ibaraki · 茨城県龍ケ崎市',426,1,'龍ケ崎市','リュウガサキシ','Ryugasaki-shi',35.911594,140.182265),(434,'082104','08','Shimotsuma-shi',NULL,'Shimotsuma-shi, Ibaraki · 茨城県下妻市',427,1,'下妻市','シモツマシ','Shimotsuma-shi',36.184368,139.968240),(435,'082112','08','Joso-shi',NULL,'Joso-shi, Ibaraki · 茨城県常総市',428,1,'常総市','ジョウソウシ','Joso-shi',36.023563,139.993934),(436,'082121','08','Hitachiota-shi',NULL,'Hitachiota-shi, Ibaraki · 茨城県常陸太田市',429,1,'常陸太田市','ヒタチオオタシ','Hitachiota-shi',36.538227,140.530929),(437,'082147','08','Takahagi-shi',NULL,'Takahagi-shi, Ibaraki · 茨城県高萩市',430,1,'高萩市','タカハギシ','Takahagi-shi',36.713796,140.709302),(438,'082155','08','Kitaibaraki-shi',NULL,'Kitaibaraki-shi, Ibaraki · 茨城県北茨城市',431,1,'北茨城市','キタイバラキシ','Kitaibaraki-shi',36.801892,140.751032),(439,'082163','08','Kasama-shi',NULL,'Kasama-shi, Ibaraki · 茨城県笠間市',432,1,'笠間市','カサマシ','Kasama-shi',36.345128,140.304232),(440,'082171','08','Toride-shi',NULL,'Toride-shi, Ibaraki · 茨城県取手市',433,1,'取手市','トリデシ','Toride-shi',35.911474,140.050305),(441,'082198','08','Ushiku-shi',NULL,'Ushiku-shi, Ibaraki · 茨城県牛久市',434,1,'牛久市','ウシクシ','Ushiku-shi',35.979397,140.149532),(442,'082201','08','Tsukuba-shi',NULL,'Tsukuba-shi, Ibaraki · 茨城県つくば市',435,1,'つくば市','ツクバシ','Tsukuba-shi',36.083560,140.076473),(443,'082210','08','Hitachinaka-shi',NULL,'Hitachinaka-shi, Ibaraki · 茨城県ひたちなか市',436,1,'ひたちなか市','ヒタチナカシ','Hitachinaka-shi',36.396615,140.534676),(444,'082228','08','Kashima-shi',NULL,'Kashima-shi, Ibaraki · 茨城県鹿嶋市',437,1,'鹿嶋市','カシマシ','Kashima-shi',35.965721,140.644852),(445,'082236','08','Itako-shi',NULL,'Itako-shi, Ibaraki · 茨城県潮来市',438,1,'潮来市','イタコシ','Itako-shi',35.947134,140.555364),(446,'082244','08','Moriya-shi',NULL,'Moriya-shi, Ibaraki · 茨城県守谷市',439,1,'守谷市','モリヤシ','Moriya-shi',35.951327,139.975465),(447,'082252','08','Hitachiomiya-shi',NULL,'Hitachiomiya-shi, Ibaraki · 茨城県常陸大宮市',440,1,'常陸大宮市','ヒタチオオミヤシ','Hitachiomiya-shi',36.542592,140.411012),(448,'082261','08','Naka-shi',NULL,'Naka-shi, Ibaraki · 茨城県那珂市',441,1,'那珂市','ナカシ','Naka-shi',36.457424,140.486726),(449,'082279','08','Chikusei-shi',NULL,'Chikusei-shi, Ibaraki · 茨城県筑西市',442,1,'筑西市','チクセイシ','Chikusei-shi',36.305241,139.979416),(450,'082287','08','Bando-shi',NULL,'Bando-shi, Ibaraki · 茨城県坂東市',443,1,'坂東市','バンドウシ','Bando-shi',36.048363,139.888680),(451,'082295','08','Inashiki-shi',NULL,'Inashiki-shi, Ibaraki · 茨城県稲敷市',444,1,'稲敷市','イナシキシ','Inashiki-shi',35.972914,140.303612),(452,'082309','08','Kasumigaura-shi',NULL,'Kasumigaura-shi, Ibaraki · 茨城県かすみがうら市',445,1,'かすみがうら市','カスミガウラシ','Kasumigaura-shi',36.151812,140.237006),(453,'082317','08','Sakuragawa-shi',NULL,'Sakuragawa-shi, Ibaraki · 茨城県桜川市',446,1,'桜川市','サクラガワシ','Sakuragawa-shi',36.327273,140.090478),(454,'082325','08','Kamisu-shi',NULL,'Kamisu-shi, Ibaraki · 茨城県神栖市',447,1,'神栖市','カミスシ','Kamisu-shi',35.889972,140.664527),(455,'082333','08','Namegata-shi',NULL,'Namegata-shi, Ibaraki · 茨城県行方市',448,1,'行方市','ナメガタシ','Namegata-shi',35.990166,140.489100),(456,'082341','08','Hokota-shi',NULL,'Hokota-shi, Ibaraki · 茨城県鉾田市',449,1,'鉾田市','ホコタシ','Hokota-shi',36.158687,140.516437),(457,'082350','08','Tsukubamirai-shi',NULL,'Tsukubamirai-shi, Ibaraki · 茨城県つくばみらい市',450,1,'つくばみらい市','ツクバミライシ','Tsukubamirai-shi',35.962911,140.037069),(458,'082368','08','Omitama-shi',NULL,'Omitama-shi, Ibaraki · 茨城県小美玉市',451,1,'小美玉市','オミタマシ','Omitama-shi',36.239283,140.352589),(459,'083020','08','Ibaraki-machi',NULL,'Ibaraki-machi, Ibaraki · 茨城県茨城町',452,1,'茨城町','イバラキマチ','Ibaraki-machi',36.286921,140.424517),(460,'083097','08','Oarai-machi',NULL,'Oarai-machi, Ibaraki · 茨城県大洗町',453,1,'大洗町','オオアライマチ','Oarai-machi',36.313356,140.574888),(461,'083101','08','Shirosato-machi',NULL,'Shirosato-machi, Ibaraki · 茨城県城里町',454,1,'城里町','シロサトマチ','Shirosato-machi',36.479297,140.376241),(462,'083411','08','Tokai-mura',NULL,'Tokai-mura, Ibaraki · 茨城県東海村',455,1,'東海村','トウカイムラ','Tokai-mura',36.472882,140.566301),(463,'083640','08','Daigo-machi',NULL,'Daigo-machi, Ibaraki · 茨城県大子町',456,1,'大子町','ダイゴマチ','Daigo-machi',36.764385,140.363469),(464,'084425','08','Miho-mura',NULL,'Miho-mura, Ibaraki · 茨城県美浦村',457,1,'美浦村','ミホムラ','Miho-mura',36.004554,140.301906),(465,'084433','08','Ami-machi',NULL,'Ami-machi, Ibaraki · 茨城県阿見町',458,1,'阿見町','アミマチ','Ami-machi',36.030870,140.214769),(466,'084476','08','Kawachi-machi',NULL,'Kawachi-machi, Ibaraki · 茨城県河内町',459,1,'河内町','カワチマチ','Kawachi-machi',35.884743,140.244516),(467,'085219','08','Yachiyo-machi',NULL,'Yachiyo-machi, Ibaraki · 茨城県八千代町',460,1,'八千代町','ヤチヨマチ','Yachiyo-machi',36.181632,139.891145),(468,'085421','08','Goka-machi',NULL,'Goka-machi, Ibaraki · 茨城県五霞町',461,1,'五霞町','ゴカマチ','Goka-machi',36.114116,139.745832),(469,'085464','08','Sakai-machi',NULL,'Sakai-machi, Ibaraki · 茨城県境町',462,1,'境町','サカイマチ','Sakai-machi',36.108529,139.795054),(470,'085642','08','Tone-machi',NULL,'Tone-machi, Ibaraki · 茨城県利根町',463,1,'利根町','トネマチ','Tone-machi',35.857825,140.139106),(471,'092011','09','Utsunomiya-shi',NULL,'Utsunomiya-shi, Tochigi · 栃木県宇都宮市',464,1,'宇都宮市','ウツノミヤシ','Utsunomiya-shi',36.555115,139.882807),(472,'092029','09','Ashikaga-shi',NULL,'Ashikaga-shi, Tochigi · 栃木県足利市',465,1,'足利市','アシカガシ','Ashikaga-shi',36.340146,139.449696),(473,'092037','09','Tochigi-shi',NULL,'Tochigi-shi, Tochigi · 栃木県栃木市',466,1,'栃木市','トチギシ','Tochigi-shi',36.382414,139.734111),(474,'092045','09','Sano-shi',NULL,'Sano-shi, Tochigi · 栃木県佐野市',467,1,'佐野市','サノシ','Sano-shi',36.314354,139.578369),(475,'092053','09','Kanuma-shi',NULL,'Kanuma-shi, Tochigi · 栃木県鹿沼市',468,1,'鹿沼市','カヌマシ','Kanuma-shi',36.567120,139.745098),(476,'092061','09','Nikko-shi',NULL,'Nikko-shi, Tochigi · 栃木県日光市',469,1,'日光市','ニッコウシ','Nikko-shi',36.719861,139.698204),(477,'092088','09','Oyama-shi',NULL,'Oyama-shi, Tochigi · 栃木県小山市',470,1,'小山市','オヤマシ','Oyama-shi',36.314477,139.800132),(478,'092096','09','Moka-shi',NULL,'Moka-shi, Tochigi · 栃木県真岡市',471,1,'真岡市','モオカシ','Moka-shi',36.440427,140.013428),(479,'092100','09','Otawara-shi',NULL,'Otawara-shi, Tochigi · 栃木県大田原市',472,1,'大田原市','オオタワラシ','Otawara-shi',36.871076,140.015458),(480,'092118','09','Yaita-shi',NULL,'Yaita-shi, Tochigi · 栃木県矢板市',473,1,'矢板市','ヤイタシ','Yaita-shi',36.806732,139.924226),(481,'092134','09','Nasushiobara-shi',NULL,'Nasushiobara-shi, Tochigi · 栃木県那須塩原市',474,1,'那須塩原市','ナスシオバラシ','Nasushiobara-shi',36.961690,140.046044),(482,'092142','09','Sakura-shi',NULL,'Sakura-shi, Tochigi · 栃木県さくら市',475,1,'さくら市','サクラシ','Sakura-shi',36.685295,139.966474),(483,'092151','09','Nasukarasuyama-shi',NULL,'Nasukarasuyama-shi, Tochigi · 栃木県那須烏山市',476,1,'那須烏山市','ナスカラスヤマシ','Nasukarasuyama-shi',36.656891,140.151376),(484,'092169','09','Shimotsuke-shi',NULL,'Shimotsuke-shi, Tochigi · 栃木県下野市',477,1,'下野市','シモツケシ','Shimotsuke-shi',36.395210,139.851928),(485,'093017','09','Kaminokawa-machi',NULL,'Kaminokawa-machi, Tochigi · 栃木県上三川町',478,1,'上三川町','カミノカワマチ','Kaminokawa-machi',36.439268,139.910214),(486,'093424','09','Mashiko-machi',NULL,'Mashiko-machi, Tochigi · 栃木県益子町',479,1,'益子町','マシコマチ','Mashiko-machi',36.467353,140.093381),(487,'093432','09','Motegi-machi',NULL,'Motegi-machi, Tochigi · 栃木県茂木町',480,1,'茂木町','モテギマチ','Motegi-machi',36.532184,140.187535),(488,'093441','09','Ichikai-machi',NULL,'Ichikai-machi, Tochigi · 栃木県市貝町',481,1,'市貝町','イチカイマチ','Ichikai-machi',36.543242,140.102113),(489,'093459','09','Haga-machi',NULL,'Haga-machi, Tochigi · 栃木県芳賀町',482,1,'芳賀町','ハガマチ','Haga-machi',36.548212,140.058199),(490,'093611','09','Mibu-machi',NULL,'Mibu-machi, Tochigi · 栃木県壬生町',483,1,'壬生町','ミブマチ','Mibu-machi',36.442168,139.814656),(491,'093645','09','Nogi-machi',NULL,'Nogi-machi, Tochigi · 栃木県野木町',484,1,'野木町','ノギマチ','Nogi-machi',36.233263,139.740818),(492,'093840','09','Shioya-machi',NULL,'Shioya-machi, Tochigi · 栃木県塩谷町',485,1,'塩谷町','シオヤマチ','Shioya-machi',36.779292,139.857520),(493,'093866','09','Takanezawa-machi',NULL,'Takanezawa-machi, Tochigi · 栃木県高根沢町',486,1,'高根沢町','タカネザワマチ','Takanezawa-machi',36.630976,139.986672),(494,'094072','09','Nasu-machi',NULL,'Nasu-machi, Tochigi · 栃木県那須町',487,1,'那須町','ナスマチ','Nasu-machi',37.019770,140.121008),(495,'094111','09','Nakagawa-machi',NULL,'Nakagawa-machi, Tochigi · 栃木県那珂川町',488,1,'那珂川町','ナカガワマチ','Nakagawa-machi',36.736223,140.172227),(496,'102016','10','Maebashi-shi',NULL,'Maebashi-shi, Gumma · 群馬県前橋市',489,1,'前橋市','マエバシシ','Maebashi-shi',36.389413,139.063493),(497,'102024','10','Takasaki-shi',NULL,'Takasaki-shi, Gumma · 群馬県高崎市',490,1,'高崎市','タカサキシ','Takasaki-shi',36.321950,139.003355),(498,'102032','10','Kiryu-shi',NULL,'Kiryu-shi, Gumma · 群馬県桐生市',491,1,'桐生市','キリュウシ','Kiryu-shi',36.405210,139.330646),(499,'102041','10','Isesaki-shi',NULL,'Isesaki-shi, Gumma · 群馬県伊勢崎市',492,1,'伊勢崎市','イセサキシ','Isesaki-shi',36.311341,139.196960),(500,'102059','10','Ota-shi',NULL,'Ota-shi, Gumma · 群馬県太田市',493,1,'太田市','オオタシ','Ota-shi',36.291127,139.375360),(501,'102067','10','Numata-shi',NULL,'Numata-shi, Gumma · 群馬県沼田市',494,1,'沼田市','ヌマタシ','Numata-shi',36.643962,139.042792),(502,'102075','10','Tatebayashi-shi',NULL,'Tatebayashi-shi, Gumma · 群馬県館林市',495,1,'館林市','タテバヤシシ','Tatebayashi-shi',36.244841,139.542057),(503,'102083','10','Shibukawa-shi',NULL,'Shibukawa-shi, Gumma · 群馬県渋川市',496,1,'渋川市','シブカワシ','Shibukawa-shi',36.489480,139.000394),(504,'102091','10','Fujioka-shi',NULL,'Fujioka-shi, Gumma · 群馬県藤岡市',497,1,'藤岡市','フジオカシ','Fujioka-shi',36.258509,139.074564),(505,'102105','10','Tomioka-shi',NULL,'Tomioka-shi, Gumma · 群馬県富岡市',498,1,'富岡市','トミオカシ','Tomioka-shi',36.259852,138.889954),(506,'102113','10','Annaka-shi',NULL,'Annaka-shi, Gumma · 群馬県安中市',499,1,'安中市','アンナカシ','Annaka-shi',36.326298,138.887244),(507,'102121','10','Midori-shi',NULL,'Midori-shi, Gumma · 群馬県みどり市',500,1,'みどり市','ミドリシ','Midori-shi',36.394819,139.281076),(508,'103446','10','Shinto-mura',NULL,'Shinto-mura, Gumma · 群馬県榛東村',501,1,'榛東村','シントウムラ','Shinto-mura',36.438647,138.967118),(509,'103454','10','Yoshioka-machi',NULL,'Yoshioka-machi, Gumma · 群馬県吉岡町',502,1,'吉岡町','ヨシオカマチ','Yoshioka-machi',36.447443,139.009700),(510,'103667','10','Ueno-mura',NULL,'Ueno-mura, Gumma · 群馬県上野村',503,1,'上野村','ウエノムラ','Ueno-mura',36.083135,138.777334),(511,'103675','10','Kanna-machi',NULL,'Kanna-machi, Gumma · 群馬県神流町',504,1,'神流町','カンナマチ','Kanna-machi',36.116013,138.916991),(512,'103829','10','Shimonita-machi',NULL,'Shimonita-machi, Gumma · 群馬県下仁田町',505,1,'下仁田町','シモニタマチ','Shimonita-machi',36.212470,138.789083),(513,'103837','10','Nammoku-mura',NULL,'Nammoku-mura, Gumma · 群馬県南牧村',506,1,'南牧村','ナンモクムラ','Nammoku-mura',36.158553,138.711406),(514,'103845','10','Kanra-machi',NULL,'Kanra-machi, Gumma · 群馬県甘楽町',507,1,'甘楽町','カンラマチ','Kanra-machi',36.243009,138.921752),(515,'104213','10','Nakanojo-machi',NULL,'Nakanojo-machi, Gumma · 群馬県中之条町',508,1,'中之条町','ナカノジョウマチ','Nakanojo-machi',36.589843,138.841075),(516,'104248','10','Naganohara-machi',NULL,'Naganohara-machi, Gumma · 群馬県長野原町',509,1,'長野原町','ナガノハラマチ','Naganohara-machi',36.544316,138.649837),(517,'104256','10','Tsumagoi-mura',NULL,'Tsumagoi-mura, Gumma · 群馬県嬬恋村',510,1,'嬬恋村','ツマゴイムラ','Tsumagoi-mura',36.516735,138.530298),(518,'104264','10','Kusatsu-machi',NULL,'Kusatsu-machi, Gumma · 群馬県草津町',511,1,'草津町','クサツマチ','Kusatsu-machi',36.620711,138.596127),(519,'104281','10','Takayama-mura',NULL,'Takayama-mura, Gumma · 群馬県高山村',512,1,'高山村','タカヤマムラ','Takayama-mura',36.620809,138.943477),(520,'104299','10','Higashiagatsuma-machi',NULL,'Higashiagatsuma-machi, Gumma · 群馬県東吾妻町',513,1,'東吾妻町','ヒガシアガツママチ','Higashiagatsuma-machi',36.571557,138.820512),(521,'104434','10','Katashina-mura',NULL,'Katashina-mura, Gumma · 群馬県片品村',514,1,'片品村','カタシナムラ','Katashina-mura',36.772549,139.225241),(522,'104442','10','Kawaba-mura',NULL,'Kawaba-mura, Gumma · 群馬県川場村',515,1,'川場村','カワバムラ','Kawaba-mura',36.694683,139.106512),(523,'104485','10','Showa-mura',NULL,'Showa-mura, Gumma · 群馬県昭和村',516,1,'昭和村','ショウワムラ','Showa-mura',36.639761,139.065881),(524,'104493','10','Minakami-machi',NULL,'Minakami-machi, Gumma · 群馬県みなかみ町',517,1,'みなかみ町','ミナカミマチ','Minakami-machi',36.678650,138.999055),(525,'104647','10','Tamamura-machi',NULL,'Tamamura-machi, Gumma · 群馬県玉村町',518,1,'玉村町','タマムラマチ','Tamamura-machi',36.304435,139.114916),(526,'105210','10','Itakura-machi',NULL,'Itakura-machi, Gumma · 群馬県板倉町',519,1,'板倉町','イタクラマチ','Itakura-machi',36.225960,139.602097),(527,'105228','10','Meiwa-machi',NULL,'Meiwa-machi, Gumma · 群馬県明和町',520,1,'明和町','メイワマチ','Meiwa-machi',36.211283,139.534228),(528,'105236','10','Chiyoda-machi',NULL,'Chiyoda-machi, Gumma · 群馬県千代田町',521,1,'千代田町','チヨダマチ','Chiyoda-machi',36.217754,139.442437),(529,'105244','10','Oizumi-machi',NULL,'Oizumi-machi, Gumma · 群馬県大泉町',522,1,'大泉町','オオイズミマチ','Oizumi-machi',36.247862,139.404843),(530,'105252','10','Ora-machi',NULL,'Ora-machi, Gumma · 群馬県邑楽町',523,1,'邑楽町','オウラマチ','Ora-machi',36.252392,139.462300),(531,'111015','11','Nishi-ku, Saitama-shi',NULL,'Nishi-ku, Saitama-shi, Saitama · 埼玉県さいたま市西区',524,1,'さいたま市西区','サイタマシニシク','Nishi-ku, Saitama-shi',35.925166,139.579633),(532,'111023','11','Kita-ku, Saitama-shi',NULL,'Kita-ku, Saitama-shi, Saitama · 埼玉県さいたま市北区',525,1,'さいたま市北区','サイタマシキタク','Kita-ku, Saitama-shi',35.931390,139.620287),(533,'111031','11','Omiya-ku, Saitama-shi',NULL,'Omiya-ku, Saitama-shi, Saitama · 埼玉県さいたま市大宮区',526,1,'さいたま市大宮区','サイタマシオオミヤク','Omiya-ku, Saitama-shi',35.901874,139.630550),(534,'111040','11','Minuma-ku, Saitama-shi',NULL,'Minuma-ku, Saitama-shi, Saitama · 埼玉県さいたま市見沼区',527,1,'さいたま市見沼区','サイタマシミヌマク','Minuma-ku, Saitama-shi',35.935216,139.654427),(535,'111058','11','Chuo-ku, Saitama-shi',NULL,'Chuo-ku, Saitama-shi, Saitama · 埼玉県さいたま市中央区',528,1,'さいたま市中央区','サイタマシチュウオウク','Chuo-ku, Saitama-shi',35.883965,139.626177),(536,'111066','11','Sakura-ku, Saitama-shi',NULL,'Sakura-ku, Saitama-shi, Saitama · 埼玉県さいたま市桜区',529,1,'さいたま市桜区','サイタマシサクラク','Sakura-ku, Saitama-shi',35.855876,139.609766),(537,'111074','11','Urawa-ku, Saitama-shi',NULL,'Urawa-ku, Saitama-shi, Saitama · 埼玉県さいたま市浦和区',530,1,'さいたま市浦和区','サイタマシウラワク','Urawa-ku, Saitama-shi',35.861970,139.645408),(538,'111082','11','Minami-ku, Saitama-shi',NULL,'Minami-ku, Saitama-shi, Saitama · 埼玉県さいたま市南区',531,1,'さいたま市南区','サイタマシミナミク','Minami-ku, Saitama-shi',35.844841,139.646521),(539,'111091','11','Midori-ku, Saitama-shi',NULL,'Midori-ku, Saitama-shi, Saitama · 埼玉県さいたま市緑区',532,1,'さいたま市緑区','サイタマシミドリク','Midori-ku, Saitama-shi',35.871095,139.684017),(540,'111104','11','Iwatsuki-ku, Saitama-shi',NULL,'Iwatsuki-ku, Saitama-shi, Saitama · 埼玉県さいたま市岩槻区',533,1,'さいたま市岩槻区','サイタマシイワツキク','Iwatsuki-ku, Saitama-shi',35.949882,139.694182),(541,'112011','11','Kawagoe-shi',NULL,'Kawagoe-shi, Saitama · 埼玉県川越市',534,1,'川越市','カワゴエシ','Kawagoe-shi',35.925112,139.485840),(542,'112020','11','Kumagaya-shi',NULL,'Kumagaya-shi, Saitama · 埼玉県熊谷市',535,1,'熊谷市','クマガヤシ','Kumagaya-shi',36.147362,139.388664),(543,'112038','11','Kawaguchi-shi',NULL,'Kawaguchi-shi, Saitama · 埼玉県川口市',536,1,'川口市','カワグチシ','Kawaguchi-shi',35.807741,139.724171),(544,'112062','11','Gyoda-shi',NULL,'Gyoda-shi, Saitama · 埼玉県行田市',537,1,'行田市','ギョウダシ','Gyoda-shi',36.138951,139.455646),(545,'112071','11','Chichibu-shi',NULL,'Chichibu-shi, Saitama · 埼玉県秩父市',538,1,'秩父市','チチブシ','Chichibu-shi',35.991681,139.085475),(546,'112089','11','Tokorozawa-shi',NULL,'Tokorozawa-shi, Saitama · 埼玉県所沢市',539,1,'所沢市','トコロザワシ','Tokorozawa-shi',35.799672,139.468613),(547,'112097','11','Hanno-shi',NULL,'Hanno-shi, Saitama · 埼玉県飯能市',540,1,'飯能市','ハンノウシ','Hanno-shi',35.855760,139.327791),(548,'112101','11','Kazo-shi',NULL,'Kazo-shi, Saitama · 埼玉県加須市',541,1,'加須市','カゾシ','Kazo-shi',36.131438,139.601775),(549,'112119','11','Honjo-shi',NULL,'Honjo-shi, Saitama · 埼玉県本庄市',542,1,'本庄市','ホンジョウシ','Honjo-shi',36.243329,139.190629),(550,'112127','11','Higashimatsuyama-shi',NULL,'Higashimatsuyama-shi, Saitama · 埼玉県東松山市',543,1,'東松山市','ヒガシマツヤマシ','Higashimatsuyama-shi',36.042162,139.399950),(551,'112143','11','Kasukabe-shi',NULL,'Kasukabe-shi, Saitama · 埼玉県春日部市',544,1,'春日部市','カスカベシ','Kasukabe-shi',35.975305,139.752409),(552,'112151','11','Sayama-shi',NULL,'Sayama-shi, Saitama · 埼玉県狭山市',545,1,'狭山市','サヤマシ','Sayama-shi',35.852907,139.412227),(553,'112160','11','Hanyu-shi',NULL,'Hanyu-shi, Saitama · 埼玉県羽生市',546,1,'羽生市','ハニュウシ','Hanyu-shi',36.172667,139.548592),(554,'112178','11','Konosu-shi',NULL,'Konosu-shi, Saitama · 埼玉県鴻巣市',547,1,'鴻巣市','コウノスシ','Konosu-shi',36.065758,139.522172),(555,'112186','11','Fukaya-shi',NULL,'Fukaya-shi, Saitama · 埼玉県深谷市',548,1,'深谷市','フカヤシ','Fukaya-shi',36.197466,139.281470),(556,'112194','11','Ageo-shi',NULL,'Ageo-shi, Saitama · 埼玉県上尾市',549,1,'上尾市','アゲオシ','Ageo-shi',35.977408,139.593203),(557,'112216','11','Soka-shi',NULL,'Soka-shi, Saitama · 埼玉県草加市',550,1,'草加市','ソウカシ','Soka-shi',35.825371,139.805327),(558,'112224','11','Koshigaya-shi',NULL,'Koshigaya-shi, Saitama · 埼玉県越谷市',551,1,'越谷市','コシガヤシ','Koshigaya-shi',35.891087,139.790943),(559,'112232','11','Warabi-shi',NULL,'Warabi-shi, Saitama · 埼玉県蕨市',552,1,'蕨市','ワラビシ','Warabi-shi',35.825576,139.679758),(560,'112241','11','Toda-shi',NULL,'Toda-shi, Saitama · 埼玉県戸田市',553,1,'戸田市','トダシ','Toda-shi',35.817635,139.677931),(561,'112259','11','Iruma-shi',NULL,'Iruma-shi, Saitama · 埼玉県入間市',554,1,'入間市','イルマシ','Iruma-shi',35.835769,139.391058),(562,'112275','11','Asaka-shi',NULL,'Asaka-shi, Saitama · 埼玉県朝霞市',555,1,'朝霞市','アサカシ','Asaka-shi',35.797255,139.593919),(563,'112283','11','Shiki-shi',NULL,'Shiki-shi, Saitama · 埼玉県志木市',556,1,'志木市','シキシ','Shiki-shi',35.836614,139.580230),(564,'112291','11','Wako-shi',NULL,'Wako-shi, Saitama · 埼玉県和光市',557,1,'和光市','ワコウシ','Wako-shi',35.781104,139.605693),(565,'112305','11','Niiza-shi',NULL,'Niiza-shi, Saitama · 埼玉県新座市',558,1,'新座市','ニイザシ','Niiza-shi',35.793497,139.565330),(566,'112313','11','Okegawa-shi',NULL,'Okegawa-shi, Saitama · 埼玉県桶川市',559,1,'桶川市','オケガワシ','Okegawa-shi',36.002966,139.558177),(567,'112321','11','Kuki-shi',NULL,'Kuki-shi, Saitama · 埼玉県久喜市',560,1,'久喜市','クキシ','Kuki-shi',36.062059,139.666838),(568,'112330','11','Kitamoto-shi',NULL,'Kitamoto-shi, Saitama · 埼玉県北本市',561,1,'北本市','キタモトシ','Kitamoto-shi',36.027026,139.530240),(569,'112348','11','Yashio-shi',NULL,'Yashio-shi, Saitama · 埼玉県八潮市',562,1,'八潮市','ヤシオシ','Yashio-shi',35.822539,139.839175),(570,'112356','11','Fujimi-shi',NULL,'Fujimi-shi, Saitama · 埼玉県富士見市',563,1,'富士見市','フジミシ','Fujimi-shi',35.856759,139.549074),(571,'112372','11','Misato-shi',NULL,'Misato-shi, Saitama · 埼玉県三郷市',564,1,'三郷市','ミサトシ','Misato-shi',35.830132,139.872247),(572,'112381','11','Hasuda-shi',NULL,'Hasuda-shi, Saitama · 埼玉県蓮田市',565,1,'蓮田市','ハスダシ','Hasuda-shi',35.994504,139.662200),(573,'112399','11','Sakado-shi',NULL,'Sakado-shi, Saitama · 埼玉県坂戸市',566,1,'坂戸市','サカドシ','Sakado-shi',35.957262,139.402983),(574,'112402','11','Satte-shi',NULL,'Satte-shi, Saitama · 埼玉県幸手市',567,1,'幸手市','サッテシ','Satte-shi',36.078089,139.725805),(575,'112411','11','Tsurugashima-shi',NULL,'Tsurugashima-shi, Saitama · 埼玉県鶴ヶ島市',568,1,'鶴ヶ島市','ツルガシマシ','Tsurugashima-shi',35.934523,139.393126),(576,'112429','11','Hidaka-shi',NULL,'Hidaka-shi, Saitama · 埼玉県日高市',569,1,'日高市','ヒダカシ','Hidaka-shi',35.907748,139.339140),(577,'112437','11','Yoshikawa-shi',NULL,'Yoshikawa-shi, Saitama · 埼玉県吉川市',570,1,'吉川市','ヨシカワシ','Yoshikawa-shi',35.895921,139.855673),(578,'112453','11','Fujimino-shi',NULL,'Fujimino-shi, Saitama · 埼玉県ふじみ野市',571,1,'ふじみ野市','フジミノシ','Fujimino-shi',35.879396,139.519820),(579,'112461','11','Shiraoka-shi',NULL,'Shiraoka-shi, Saitama · 埼玉県白岡市',572,1,'白岡市','シラオカシ','Shiraoka-shi',36.019070,139.676861),(580,'113018','11','Ina-machi',NULL,'Ina-machi, Saitama · 埼玉県伊奈町',573,1,'伊奈町','イナマチ','Ina-machi',35.999865,139.623877),(581,'113247','11','Miyoshi-machi',NULL,'Miyoshi-machi, Saitama · 埼玉県三芳町',574,1,'三芳町','ミヨシマチ','Miyoshi-machi',35.828347,139.526514),(582,'113263','11','Moroyama-machi',NULL,'Moroyama-machi, Saitama · 埼玉県毛呂山町',575,1,'毛呂山町','モロヤママチ','Moroyama-machi',35.941596,139.315954),(583,'113271','11','Ogose-machi',NULL,'Ogose-machi, Saitama · 埼玉県越生町',576,1,'越生町','オゴセマチ','Ogose-machi',35.964478,139.294202),(584,'113417','11','Namegawa-machi',NULL,'Namegawa-machi, Saitama · 埼玉県滑川町',577,1,'滑川町','ナメガワマチ','Namegawa-machi',36.065990,139.360919),(585,'113425','11','Ranzan-machi',NULL,'Ranzan-machi, Saitama · 埼玉県嵐山町',578,1,'嵐山町','ランザンマチ','Ranzan-machi',36.056568,139.320514),(586,'113433','11','Ogawa-machi',NULL,'Ogawa-machi, Saitama · 埼玉県小川町',579,1,'小川町','オガワマチ','Ogawa-machi',36.056657,139.261789),(587,'113468','11','Kawajima-machi',NULL,'Kawajima-machi, Saitama · 埼玉県川島町',580,1,'川島町','カワジママチ','Kawajima-machi',35.992474,139.484326),(588,'113476','11','Yoshimi-machi',NULL,'Yoshimi-machi, Saitama · 埼玉県吉見町',581,1,'吉見町','ヨシミマチ','Yoshimi-machi',36.039853,139.453730),(589,'113484','11','Hatoyama-machi',NULL,'Hatoyama-machi, Saitama · 埼玉県鳩山町',582,1,'鳩山町','ハトヤママチ','Hatoyama-machi',35.981468,139.334106),(590,'113492','11','Tokigawa-machi',NULL,'Tokigawa-machi, Saitama · 埼玉県ときがわ町',583,1,'ときがわ町','トキガワマチ','Tokigawa-machi',36.008570,139.296763),(591,'113611','11','Yokoze-machi',NULL,'Yokoze-machi, Saitama · 埼玉県横瀬町',584,1,'横瀬町','ヨコゼマチ','Yokoze-machi',35.987286,139.100049),(592,'113620','11','Minano-machi',NULL,'Minano-machi, Saitama · 埼玉県皆野町',585,1,'皆野町','ミナノマチ','Minano-machi',36.070845,139.098754),(593,'113638','11','Nagatoro-machi',NULL,'Nagatoro-machi, Saitama · 埼玉県長瀞町',586,1,'長瀞町','ナガトロマチ','Nagatoro-machi',36.114787,139.109720),(594,'113654','11','Ogano-machi',NULL,'Ogano-machi, Saitama · 埼玉県小鹿野町',587,1,'小鹿野町','オガノマチ','Ogano-machi',36.017138,139.008604),(595,'113697','11','Higashichichibu-mura',NULL,'Higashichichibu-mura, Saitama · 埼玉県東秩父村',588,1,'東秩父村','ヒガシチチブムラ','Higashichichibu-mura',36.058148,139.194573),(596,'113816','11','Misato-machi',NULL,'Misato-machi, Saitama · 埼玉県美里町',589,1,'美里町','ミサトマチ','Misato-machi',36.177104,139.181410),(597,'113832','11','Kamikawa-machi',NULL,'Kamikawa-machi, Saitama · 埼玉県神川町',590,1,'神川町','カミカワマチ','Kamikawa-machi',36.213901,139.101578),(598,'113859','11','Kamisato-machi',NULL,'Kamisato-machi, Saitama · 埼玉県上里町',591,1,'上里町','カミサトマチ','Kamisato-machi',36.251615,139.144870),(599,'114081','11','Yorii-machi',NULL,'Yorii-machi, Saitama · 埼玉県寄居町',592,1,'寄居町','ヨリイマチ','Yorii-machi',36.118348,139.193014),(600,'114421','11','Miyashiro-machi',NULL,'Miyashiro-machi, Saitama · 埼玉県宮代町',593,1,'宮代町','ミヤシロマチ','Miyashiro-machi',36.022593,139.723069),(601,'114642','11','Sugito-machi',NULL,'Sugito-machi, Saitama · 埼玉県杉戸町',594,1,'杉戸町','スギトマチ','Sugito-machi',36.025618,139.736829),(602,'114651','11','Matsubushi-machi',NULL,'Matsubushi-machi, Saitama · 埼玉県松伏町',595,1,'松伏町','マツブシマチ','Matsubushi-machi',35.925751,139.815139),(603,'121011','12','Chuo-ku, Chiba-shi',NULL,'Chuo-ku, Chiba-shi, Chiba · 千葉県千葉市中央区',596,1,'千葉市中央区','チバシチュウオウク','Chuo-ku, Chiba-shi',35.607413,140.122853),(604,'121029','12','Hanamigawa-ku, Chiba-shi',NULL,'Hanamigawa-ku, Chiba-shi, Chiba · 千葉県千葉市花見川区',597,1,'千葉市花見川区','チバシハナミガワク','Hanamigawa-ku, Chiba-shi',35.662876,140.069061),(605,'121037','12','Inage-ku, Chiba-shi',NULL,'Inage-ku, Chiba-shi, Chiba · 千葉県千葉市稲毛区',598,1,'千葉市稲毛区','チバシイナゲク','Inage-ku, Chiba-shi',35.636202,140.107106),(606,'121045','12','Wakaba-ku, Chiba-shi',NULL,'Wakaba-ku, Chiba-shi, Chiba · 千葉県千葉市若葉区',599,1,'千葉市若葉区','チバシワカバク','Wakaba-ku, Chiba-shi',35.634016,140.155653),(607,'121053','12','Midori-ku, Chiba-shi',NULL,'Midori-ku, Chiba-shi, Chiba · 千葉県千葉市緑区',600,1,'千葉市緑区','チバシミドリク','Midori-ku, Chiba-shi',35.560501,140.176153),(608,'121061','12','Mihama-ku, Chiba-shi',NULL,'Mihama-ku, Chiba-shi, Chiba · 千葉県千葉市美浜区',601,1,'千葉市美浜区','チバシミハマク','Mihama-ku, Chiba-shi',35.640552,140.063040),(609,'122025','12','Choshi-shi',NULL,'Choshi-shi, Chiba · 千葉県銚子市',602,1,'銚子市','チョウシシ','Choshi-shi',35.734667,140.826791),(610,'122033','12','Ichikawa-shi',NULL,'Ichikawa-shi, Chiba · 千葉県市川市',603,1,'市川市','イチカワシ','Ichikawa-shi',35.721914,139.931013),(611,'122041','12','Funabashi-shi',NULL,'Funabashi-shi, Chiba · 千葉県船橋市',604,1,'船橋市','フナバシシ','Funabashi-shi',35.694711,139.982621),(612,'122050','12','Tateyama-shi',NULL,'Tateyama-shi, Chiba · 千葉県館山市',605,1,'館山市','タテヤマシ','Tateyama-shi',34.996608,139.870085),(613,'122068','12','Kisarazu-shi',NULL,'Kisarazu-shi, Chiba · 千葉県木更津市',606,1,'木更津市','キサラヅシ','Kisarazu-shi',35.381168,139.924973),(614,'122076','12','Matsudo-shi',NULL,'Matsudo-shi, Chiba · 千葉県松戸市',607,1,'松戸市','マツドシ','Matsudo-shi',35.787652,139.903226),(615,'122084','12','Noda-shi',NULL,'Noda-shi, Chiba · 千葉県野田市',608,1,'野田市','ノダシ','Noda-shi',35.955106,139.874828),(616,'122106','12','Mobara-shi',NULL,'Mobara-shi, Chiba · 千葉県茂原市',609,1,'茂原市','モバラシ','Mobara-shi',35.428540,140.288050),(617,'122114','12','Narita-shi',NULL,'Narita-shi, Chiba · 千葉県成田市',610,1,'成田市','ナリタシ','Narita-shi',35.776605,140.318781),(618,'122122','12','Sakura-shi',NULL,'Sakura-shi, Chiba · 千葉県佐倉市',611,1,'佐倉市','サクラシ','Sakura-shi',35.723441,140.223972),(619,'122131','12','Togane-shi',NULL,'Togane-shi, Chiba · 千葉県東金市',612,1,'東金市','トウガネシ','Togane-shi',35.559932,140.366115),(620,'122157','12','Asahi-shi',NULL,'Asahi-shi, Chiba · 千葉県旭市',613,1,'旭市','アサヒシ','Asahi-shi',35.716025,140.648158),(621,'122165','12','Narashino-shi',NULL,'Narashino-shi, Chiba · 千葉県習志野市',614,1,'習志野市','ナラシノシ','Narashino-shi',35.681610,140.027227),(622,'122173','12','Kashiwa-shi',NULL,'Kashiwa-shi, Chiba · 千葉県柏市',615,1,'柏市','カシワシ','Kashiwa-shi',35.868295,139.976199),(623,'122181','12','Katsura-shi',NULL,'Katsura-shi, Chiba · 千葉県勝浦市',616,1,'勝浦市','カツウラシ','Katsura-shi',35.152211,140.320868),(624,'122190','12','Ichihara-shi',NULL,'Ichihara-shi, Chiba · 千葉県市原市',617,1,'市原市','イチハラシ','Ichihara-shi',35.497897,140.115594),(625,'122203','12','Nagareyama-shi',NULL,'Nagareyama-shi, Chiba · 千葉県流山市',618,1,'流山市','ナガレヤマシ','Nagareyama-shi',35.856287,139.902586),(626,'122211','12','Yachiyo-shi',NULL,'Yachiyo-shi, Chiba · 千葉県八千代市',619,1,'八千代市','ヤチヨシ','Yachiyo-shi',35.722381,140.099915),(627,'122220','12','Abiko-shi',NULL,'Abiko-shi, Chiba · 千葉県我孫子市',620,1,'我孫子市','アビコシ','Abiko-shi',35.864162,140.028225),(628,'122238','12','Kamogawa-shi',NULL,'Kamogawa-shi, Chiba · 千葉県鴨川市',621,1,'鴨川市','カモガワシ','Kamogawa-shi',35.114017,140.098805),(629,'122246','12','Kamagaya-shi',NULL,'Kamagaya-shi, Chiba · 千葉県鎌ケ谷市',622,1,'鎌ケ谷市','カマガヤシ','Kamagaya-shi',35.776765,140.000736),(630,'122254','12','Kimitsu-shi',NULL,'Kimitsu-shi, Chiba · 千葉県君津市',623,1,'君津市','キミツシ','Kimitsu-shi',35.330424,139.902561),(631,'122262','12','Futtsu-shi',NULL,'Futtsu-shi, Chiba · 千葉県富津市',624,1,'富津市','フッツシ','Futtsu-shi',35.304140,139.857113),(632,'122271','12','Urayasu-shi',NULL,'Urayasu-shi, Chiba · 千葉県浦安市',625,1,'浦安市','ウラヤスシ','Urayasu-shi',35.653988,139.902187),(633,'122289','12','Yotsukaido-shi',NULL,'Yotsukaido-shi, Chiba · 千葉県四街道市',626,1,'四街道市','ヨツカイドウシ','Yotsukaido-shi',35.669713,140.167957),(634,'122297','12','Sodegaura-shi',NULL,'Sodegaura-shi, Chiba · 千葉県袖ケ浦市',627,1,'袖ケ浦市','ソデガウラシ','Sodegaura-shi',35.429945,139.954353),(635,'122301','12','Yachimata-shi',NULL,'Yachimata-shi, Chiba · 千葉県八街市',628,1,'八街市','ヤチマタシ','Yachimata-shi',35.665859,140.317913),(636,'122319','12','Inzai-shi',NULL,'Inzai-shi, Chiba · 千葉県印西市',629,1,'印西市','インザイシ','Inzai-shi',35.832332,140.145765),(637,'122327','12','Shiroi-shi',NULL,'Shiroi-shi, Chiba · 千葉県白井市',630,1,'白井市','シロイシ','Shiroi-shi',35.791503,140.056372),(638,'122335','12','Tomisato-shi',NULL,'Tomisato-shi, Chiba · 千葉県富里市',631,1,'富里市','トミサトシ','Tomisato-shi',35.726810,140.343071),(639,'122343','12','Minamiboso-shi',NULL,'Minamiboso-shi, Chiba · 千葉県南房総市',632,1,'南房総市','ミナミボウソウシ','Minamiboso-shi',35.043147,139.839997),(640,'122351','12','Sosa-shi',NULL,'Sosa-shi, Chiba · 千葉県匝瑳市',633,1,'匝瑳市','ソウサシ','Sosa-shi',35.707914,140.564356),(641,'122360','12','Katori-shi',NULL,'Katori-shi, Chiba · 千葉県香取市',634,1,'香取市','カトリシ','Katori-shi',35.897730,140.499248),(642,'122378','12','Sammu-shi',NULL,'Sammu-shi, Chiba · 千葉県山武市',635,1,'山武市','サンムシ','Sammu-shi',35.602992,140.413531),(643,'122386','12','Isumi-shi',NULL,'Isumi-shi, Chiba · 千葉県いすみ市',636,1,'いすみ市','イスミシ','Isumi-shi',35.253887,140.385117),(644,'122394','12','Oamishirasato-shi',NULL,'Oamishirasato-shi, Chiba · 千葉県大網白里市',637,1,'大網白里市','オオアミシラサトシ','Oamishirasato-shi',35.521708,140.320963),(645,'123226','12','Shisui-machi',NULL,'Shisui-machi, Chiba · 千葉県酒々井町',638,1,'酒々井町','シスイマチ','Shisui-machi',35.724751,140.269483),(646,'123293','12','Sakae-machi',NULL,'Sakae-machi, Chiba · 千葉県栄町',639,1,'栄町','サカエマチ','Sakae-machi',35.840866,140.243945),(647,'123421','12','Kozaki-machi',NULL,'Kozaki-machi, Chiba · 千葉県神崎町',640,1,'神崎町','コウザキマチ','Kozaki-machi',35.901611,140.405326),(648,'123471','12','Tako-machi',NULL,'Tako-machi, Chiba · 千葉県多古町',641,1,'多古町','タコマチ','Tako-machi',35.735592,140.467727),(649,'123498','12','Tonosho-machi',NULL,'Tonosho-machi, Chiba · 千葉県東庄町',642,1,'東庄町','トウノショウマチ','Tonosho-machi',35.837150,140.668786),(650,'124036','12','Kujukuri-machi',NULL,'Kujukuri-machi, Chiba · 千葉県九十九里町',643,1,'九十九里町','クジュウクリマチ','Kujukuri-machi',35.535065,140.440250),(651,'124095','12','Shibayama-machi',NULL,'Shibayama-machi, Chiba · 千葉県芝山町',644,1,'芝山町','シバヤママチ','Shibayama-machi',35.693064,140.414255),(652,'124109','12','Yokoshibahikari-machi',NULL,'Yokoshibahikari-machi, Chiba · 千葉県横芝光町',645,1,'横芝光町','ヨコシバヒカリマチ','Yokoshibahikari-machi',35.665609,140.504396),(653,'124214','12','Ichinomiya-machi',NULL,'Ichinomiya-machi, Chiba · 千葉県一宮町',646,1,'一宮町','イチノミヤマチ','Ichinomiya-machi',35.372812,140.368734),(654,'124222','12','Mutsuzawa-machi',NULL,'Mutsuzawa-machi, Chiba · 千葉県睦沢町',647,1,'睦沢町','ムツザワマチ','Mutsuzawa-machi',35.361091,140.319275),(655,'124231','12','Chosei-mura',NULL,'Chosei-mura, Chiba · 千葉県長生村',648,1,'長生村','チョウセイムラ','Chosei-mura',35.412179,140.354058),(656,'124249','12','Shirako-machi',NULL,'Shirako-machi, Chiba · 千葉県白子町',649,1,'白子町','シラコマチ','Shirako-machi',35.454340,140.374253),(657,'124265','12','Nagara-machi',NULL,'Nagara-machi, Chiba · 千葉県長柄町',650,1,'長柄町','ナガラマチ','Nagara-machi',35.431152,140.227073),(658,'124273','12','Chonan-machi',NULL,'Chonan-machi, Chiba · 千葉県長南町',651,1,'長南町','チョウナンマチ','Chonan-machi',35.386423,140.237039),(659,'124419','12','Otaki-machi',NULL,'Otaki-machi, Chiba · 千葉県大多喜町',652,1,'大多喜町','オオタキマチ','Otaki-machi',35.284822,140.245419),(660,'124435','12','Onjuku-machi',NULL,'Onjuku-machi, Chiba · 千葉県御宿町',653,1,'御宿町','オンジュクマチ','Onjuku-machi',35.191484,140.348785),(661,'124630','12','Kyonan-machi',NULL,'Kyonan-machi, Chiba · 千葉県鋸南町',654,1,'鋸南町','キョナンマチ','Kyonan-machi',35.111131,139.835645),(662,'131016','13','Chiyoda-ku',NULL,'Chiyoda-ku, Tokyo · 東京都千代田区',655,1,'千代田区','チヨダク','Chiyoda-ku',35.694003,139.753634),(663,'131024','13','Chuo-ku',NULL,'Chuo-ku, Tokyo · 東京都中央区',656,1,'中央区','チュウオウク','Chuo-ku',35.670587,139.772003),(664,'131032','13','Minato-ku',NULL,'Minato-ku, Tokyo · 東京都港区',657,1,'港区','ミナトク','Minato-ku',35.658071,139.751599),(665,'131041','13','Shinjuku-ku',NULL,'Shinjuku-ku, Tokyo · 東京都新宿区',658,1,'新宿区','シンジュクク','Shinjuku-ku',35.693890,139.703463),(666,'131059','13','Bunkyo-ku',NULL,'Bunkyo-ku, Tokyo · 東京都文京区',659,1,'文京区','ブンキョウク','Bunkyo-ku',35.707976,139.752473),(667,'131067','13','Taito-ku',NULL,'Taito-ku, Tokyo · 東京都台東区',660,1,'台東区','タイトウク','Taito-ku',35.712607,139.779984),(668,'131075','13','Sumida-ku',NULL,'Sumida-ku, Tokyo · 東京都墨田区',661,1,'墨田区','スミダク','Sumida-ku',35.710724,139.801497),(669,'131083','13','Koto-ku',NULL,'Koto-ku, Tokyo · 東京都江東区',662,1,'江東区','コウトウク','Koto-ku',35.672859,139.817365),(670,'131091','13','Shinagawa-ku',NULL,'Shinagawa-ku, Tokyo · 東京都品川区',663,1,'品川区','シナガワク','Shinagawa-ku',35.609066,139.730250),(671,'131105','13','Meguro-ku',NULL,'Meguro-ku, Tokyo · 東京都目黒区',664,1,'目黒区','メグロク','Meguro-ku',35.641410,139.698118),(672,'131113','13','Ota-ku',NULL,'Ota-ku, Tokyo · 東京都大田区',665,1,'大田区','オオタク','Ota-ku',35.561260,139.715987),(673,'131121','13','Setagaya-ku',NULL,'Setagaya-ku, Tokyo · 東京都世田谷区',666,1,'世田谷区','セタガヤク','Setagaya-ku',35.646481,139.653208),(674,'131130','13','Shibuya-ku',NULL,'Shibuya-ku, Tokyo · 東京都渋谷区',667,1,'渋谷区','シブヤク','Shibuya-ku',35.663982,139.697948),(675,'131148','13','Nakano-ku',NULL,'Nakano-ku, Tokyo · 東京都中野区',668,1,'中野区','ナカノク','Nakano-ku',35.707268,139.663738),(676,'131156','13','Suginami-ku',NULL,'Suginami-ku, Tokyo · 東京都杉並区',669,1,'杉並区','スギナミク','Suginami-ku',35.699546,139.636414),(677,'131164','13','Toshima-ku',NULL,'Toshima-ku, Tokyo · 東京都豊島区',670,1,'豊島区','トシマク','Toshima-ku',35.726321,139.716702),(678,'131172','13','Kita-ku',NULL,'Kita-ku, Tokyo · 東京都北区',671,1,'北区','キタク','Kita-ku',35.752805,139.733657),(679,'131181','13','Arakawa-ku',NULL,'Arakawa-ku, Tokyo · 東京都荒川区',672,1,'荒川区','アラカワク','Arakawa-ku',35.736083,139.783372),(680,'131199','13','Itabashi-ku',NULL,'Itabashi-ku, Tokyo · 東京都板橋区',673,1,'板橋区','イタバシク','Itabashi-ku',35.751245,139.709246),(681,'131202','13','Nerima-ku',NULL,'Nerima-ku, Tokyo · 東京都練馬区',674,1,'練馬区','ネリマク','Nerima-ku',35.735600,139.651725),(682,'131211','13','Adachi-ku',NULL,'Adachi-ku, Tokyo · 東京都足立区',675,1,'足立区','アダチク','Adachi-ku',35.774945,139.804584),(683,'131229','13','Katsushika-ku',NULL,'Katsushika-ku, Tokyo · 東京都葛飾区',676,1,'葛飾区','カツシカク','Katsushika-ku',35.743430,139.847213),(684,'131237','13','Edogawa-ku',NULL,'Edogawa-ku, Tokyo · 東京都江戸川区',677,1,'江戸川区','エドガワク','Edogawa-ku',35.706648,139.868312),(685,'132012','13','Hachioji-shi',NULL,'Hachioji-shi, Tokyo · 東京都八王子市',678,1,'八王子市','ハチオウジシ','Hachioji-shi',35.666570,139.316075),(686,'132021','13','Tachikawa-shi',NULL,'Tachikawa-shi, Tokyo · 東京都立川市',679,1,'立川市','タチカワシ','Tachikawa-shi',35.713981,139.407846),(687,'132039','13','Musashino-shi',NULL,'Musashino-shi, Tokyo · 東京都武蔵野市',680,1,'武蔵野市','ムサシノシ','Musashino-shi',35.717837,139.565938),(688,'132047','13','Mitaka-shi',NULL,'Mitaka-shi, Tokyo · 東京都三鷹市',681,1,'三鷹市','ミタカシ','Mitaka-shi',35.683308,139.559883),(689,'132055','13','Ome-shi',NULL,'Ome-shi, Tokyo · 東京都青梅市',682,1,'青梅市','オウメシ','Ome-shi',35.788171,139.274994),(690,'132063','13','Fuchu-shi',NULL,'Fuchu-shi, Tokyo · 東京都府中市',683,1,'府中市','フチュウシ','Fuchu-shi',35.668921,139.477663),(691,'132071','13','Akishima-shi',NULL,'Akishima-shi, Tokyo · 東京都昭島市',684,1,'昭島市','アキシマシ','Akishima-shi',35.705705,139.353773),(692,'132080','13','Chofu-shi',NULL,'Chofu-shi, Tokyo · 東京都調布市',685,1,'調布市','チョウフシ','Chofu-shi',35.650628,139.540683),(693,'132098','13','Machida-shi',NULL,'Machida-shi, Tokyo · 東京都町田市',686,1,'町田市','マチダシ','Machida-shi',35.546559,139.438527),(694,'132101','13','Koganei-shi',NULL,'Koganei-shi, Tokyo · 東京都小金井市',687,1,'小金井市','コガネイシ','Koganei-shi',35.699479,139.502989),(695,'132110','13','Kodaira-shi',NULL,'Kodaira-shi, Tokyo · 東京都小平市',688,1,'小平市','コダイラシ','Kodaira-shi',35.728496,139.477474),(696,'132128','13','Hino-shi',NULL,'Hino-shi, Tokyo · 東京都日野市',689,1,'日野市','ヒノシ','Hino-shi',35.671347,139.394981),(697,'132136','13','Higashimurayama-shi',NULL,'Higashimurayama-shi, Tokyo · 東京都東村山市',690,1,'東村山市','ヒガシムラヤマシ','Higashimurayama-shi',35.754624,139.468484),(698,'132144','13','Kokubunji-shi',NULL,'Kokubunji-shi, Tokyo · 東京都国分寺市',691,1,'国分寺市','コクブンジシ','Kokubunji-shi',35.710943,139.462252),(699,'132152','13','Kunitachi-shi',NULL,'Kunitachi-shi, Tokyo · 東京都国立市',692,1,'国立市','クニタチシ','Kunitachi-shi',35.683912,139.441366),(700,'132187','13','Fussa-shi',NULL,'Fussa-shi, Tokyo · 東京都福生市',693,1,'福生市','フッサシ','Fussa-shi',35.738601,139.326744),(701,'132195','13','Komae-shi',NULL,'Komae-shi, Tokyo · 東京都狛江市',694,1,'狛江市','コマエシ','Komae-shi',35.634814,139.578696),(702,'132209','13','Higashiyamato-shi',NULL,'Higashiyamato-shi, Tokyo · 東京都東大和市',695,1,'東大和市','ヒガシヤマトシ','Higashiyamato-shi',35.745328,139.426591),(703,'132217','13','Kiyose-shi',NULL,'Kiyose-shi, Tokyo · 東京都清瀬市',696,1,'清瀬市','キヨセシ','Kiyose-shi',35.785761,139.526422),(704,'132225','13','Higashikurume-shi',NULL,'Higashikurume-shi, Tokyo · 東京都東久留米市',697,1,'東久留米市','ヒガシクルメシ','Higashikurume-shi',35.757992,139.529673),(705,'132233','13','Musashimurayama-shi',NULL,'Musashimurayama-shi, Tokyo · 東京都武蔵村山市',698,1,'武蔵村山市','ムサシムラヤマシ','Musashimurayama-shi',35.754906,139.387421),(706,'132241','13','Tama-shi',NULL,'Tama-shi, Tokyo · 東京都多摩市',699,1,'多摩市','タマシ','Tama-shi',35.636959,139.446366),(707,'132250','13','Inagi-shi',NULL,'Inagi-shi, Tokyo · 東京都稲城市',700,1,'稲城市','イナギシ','Inagi-shi',35.637938,139.504603),(708,'132276','13','Hamura-shi',NULL,'Hamura-shi, Tokyo · 東京都羽村市',701,1,'羽村市','ハムラシ','Hamura-shi',35.767168,139.311042),(709,'132284','13','Akiruno-shi',NULL,'Akiruno-shi, Tokyo · 東京都あきる野市',702,1,'あきる野市','アキルノシ','Akiruno-shi',35.728959,139.294046),(710,'132292','13','Nishitokyo-shi',NULL,'Nishitokyo-shi, Tokyo · 東京都西東京市',703,1,'西東京市','ニシトウキョウシ','Nishitokyo-shi',35.725499,139.538159),(711,'133035','13','Mizuho-machi',NULL,'Mizuho-machi, Tokyo · 東京都瑞穂町',704,1,'瑞穂町','ミズホマチ','Mizuho-machi',35.771989,139.354054),(712,'133051','13','Hinode-machi',NULL,'Hinode-machi, Tokyo · 東京都日の出町',705,1,'日の出町','ヒノデマチ','Hinode-machi',35.742115,139.257405),(713,'133078','13','Hinohara-mura',NULL,'Hinohara-mura, Tokyo · 東京都檜原村',706,1,'檜原村','ヒノハラムラ','Hinohara-mura',35.726840,139.148852),(714,'133086','13','Okutama-machi',NULL,'Okutama-machi, Tokyo · 東京都奥多摩町',707,1,'奥多摩町','オクタママチ','Okutama-machi',35.809520,139.096214),(715,'133612','13','Oshima-machi',NULL,'Oshima-machi, Tokyo · 東京都大島町',708,1,'大島町','オオシママチ','Oshima-machi',34.750058,139.355619),(716,'133621','13','Toshima-mura',NULL,'Toshima-mura, Tokyo · 東京都利島村',709,1,'利島村','トシマムラ','Toshima-mura',34.529354,139.282584),(717,'133639','13','Niijima-mura',NULL,'Niijima-mura, Tokyo · 東京都新島村',710,1,'新島村','ニイジマムラ','Niijima-mura',34.377138,139.256556),(718,'133647','13','Kozushima-mura',NULL,'Kozushima-mura, Tokyo · 東京都神津島村',711,1,'神津島村','コウヅシマムラ','Kozushima-mura',34.205371,139.134426),(719,'133817','13','Miyakejima Miyake-mura',NULL,'Miyakejima Miyake-mura, Tokyo · 東京都三宅村',712,1,'三宅村','ミヤケムラ','Miyakejima Miyake-mura',34.075779,139.479747),(720,'133825','13','Mikurajima-mura',NULL,'Mikurajima-mura, Tokyo · 東京都御蔵島村',713,1,'御蔵島村','ミクラジマムラ','Mikurajima-mura',33.897156,139.596009),(721,'134015','13','Hachijojima Hachijo-machi',NULL,'Hachijojima Hachijo-machi, Tokyo · 東京都八丈町',714,1,'八丈町','ハチジョウマチ','Hachijojima Hachijo-machi',33.112797,139.789079),(722,'134023','13','Aogashima-mura',NULL,'Aogashima-mura, Tokyo · 東京都青ヶ島村',715,1,'青ヶ島村','アオガシマムラ','Aogashima-mura',32.466537,139.763337),(723,'134210','13','Ogasawara-mura',NULL,'Ogasawara-mura, Tokyo · 東京都小笠原村',716,1,'小笠原村','オガサワラムラ','Ogasawara-mura',27.094380,142.191904),(724,'141011','14','Tsurumi-ku, Yokohama-shi',NULL,'Tsurumi-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市鶴見区',717,1,'横浜市鶴見区','ヨコハマシツルミク','Tsurumi-ku, Yokohama-shi',35.508398,139.682384),(725,'141020','14','Kanagawa-ku, Yokohama-shi',NULL,'Kanagawa-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市神奈川区',718,1,'横浜市神奈川区','ヨコハマシカナガワク','Kanagawa-ku, Yokohama-shi',35.477051,139.629293),(726,'141038','14','Nishi-ku, Yokohama-shi',NULL,'Nishi-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市西区',719,1,'横浜市西区','ヨコハマシニシク','Nishi-ku, Yokohama-shi',35.453609,139.616877),(727,'141046','14','Naka-ku, Yokohama-shi',NULL,'Naka-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市中区',720,1,'横浜市中区','ヨコハマシナカク','Naka-ku, Yokohama-shi',35.444688,139.642203),(728,'141054','14','Minami-ku, Yokohama-shi',NULL,'Minami-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市南区',721,1,'横浜市南区','ヨコハマシミナミク','Minami-ku, Yokohama-shi',35.434506,139.627342),(729,'141062','14','Hodogaya-ku, Yokohama-shi',NULL,'Hodogaya-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市保土ケ谷区',722,1,'横浜市保土ケ谷区','ヨコハマシホドガヤク','Hodogaya-ku, Yokohama-shi',35.459854,139.596029),(730,'141071','14','Isogo-ku, Yokohama-shi',NULL,'Isogo-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市磯子区',723,1,'横浜市磯子区','ヨコハマシイソゴク','Isogo-ku, Yokohama-shi',35.402489,139.618458),(731,'141089','14','Kanazawa-ku, Yokohama-shi',NULL,'Kanazawa-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市金沢区',724,1,'横浜市金沢区','ヨコハマシカナザワク','Kanazawa-ku, Yokohama-shi',35.337199,139.624497),(732,'141097','14','Kohoku-ku, Yokohama-shi',NULL,'Kohoku-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市港北区',725,1,'横浜市港北区','ヨコハマシコウホクク','Kohoku-ku, Yokohama-shi',35.519016,139.633197),(733,'141101','14','Totsuka-ku, Yokohama-shi',NULL,'Totsuka-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市戸塚区',726,1,'横浜市戸塚区','ヨコハマシトツカク','Totsuka-ku, Yokohama-shi',35.400223,139.533467),(734,'141119','14','Konan-ku, Yokohama-shi',NULL,'Konan-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市港南区',727,1,'横浜市港南区','ヨコハマシコウナンク','Konan-ku, Yokohama-shi',35.400765,139.592237),(735,'141127','14','Asahi-ku, Yokohama-shi',NULL,'Asahi-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市旭区',728,1,'横浜市旭区','ヨコハマシアサヒク','Asahi-ku, Yokohama-shi',35.474761,139.544800),(736,'141135','14','Midori-ku, Yokohama-shi',NULL,'Midori-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市緑区',729,1,'横浜市緑区','ヨコハマシミドリク','Midori-ku, Yokohama-shi',35.512407,139.538011),(737,'141143','14','Seya-ku, Yokohama-shi',NULL,'Seya-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市瀬谷区',730,1,'横浜市瀬谷区','ヨコハマシセヤク','Seya-ku, Yokohama-shi',35.466397,139.499188),(738,'141151','14','Sakae-ku, Yokohama-shi',NULL,'Sakae-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市栄区',731,1,'横浜市栄区','ヨコハマシサカエク','Sakae-ku, Yokohama-shi',35.364390,139.554106),(739,'141160','14','Izumi-ku, Yokohama-shi',NULL,'Izumi-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市泉区',732,1,'横浜市泉区','ヨコハマシイズミク','Izumi-ku, Yokohama-shi',35.417899,139.488693),(740,'141178','14','Aoba-ku, Yokohama-shi',NULL,'Aoba-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市青葉区',733,1,'横浜市青葉区','ヨコハマシアオバク','Aoba-ku, Yokohama-shi',35.552831,139.537092),(741,'141186','14','Tsuzuki-ku, Yokohama-shi',NULL,'Tsuzuki-ku, Yokohama-shi, Kanagawa · 神奈川県横浜市都筑区',734,1,'横浜市都筑区','ヨコハマシツヅキク','Tsuzuki-ku, Yokohama-shi',35.544937,139.571030),(742,'141313','14','Kawasaki-ku, Kawasaki-shi',NULL,'Kawasaki-ku, Kawasaki-shi, Kanagawa · 神奈川県川崎市川崎区',735,1,'川崎市川崎区','カワサキシカワサキク','Kawasaki-ku, Kawasaki-shi',35.529653,139.703707),(743,'141321','14','Saiwai-ku, Kawasaki-shi',NULL,'Saiwai-ku, Kawasaki-shi, Kanagawa · 神奈川県川崎市幸区',736,1,'川崎市幸区','カワサキシサイワイク','Saiwai-ku, Kawasaki-shi',35.543882,139.687346),(744,'141330','14','Nakahara-ku, Kawasaki-shi',NULL,'Nakahara-ku, Kawasaki-shi, Kanagawa · 神奈川県川崎市中原区',737,1,'川崎市中原区','カワサキシナカハラク','Nakahara-ku, Kawasaki-shi',35.576258,139.655756),(745,'141348','14','Takatsu-ku, Kawasaki-shi',NULL,'Takatsu-ku, Kawasaki-shi, Kanagawa · 神奈川県川崎市高津区',738,1,'川崎市高津区','カワサキシタカツク','Takatsu-ku, Kawasaki-shi',35.599443,139.608032),(746,'141356','14','Tama-ku, Kawasaki-shi',NULL,'Tama-ku, Kawasaki-shi, Kanagawa · 神奈川県川崎市多摩区',739,1,'川崎市多摩区','カワサキシタマク','Tama-ku, Kawasaki-shi',35.619609,139.562113),(747,'141364','14','Miyamae-ku, Kawasaki-shi',NULL,'Miyamae-ku, Kawasaki-shi, Kanagawa · 神奈川県川崎市宮前区',740,1,'川崎市宮前区','カワサキシミヤマエク','Miyamae-ku, Kawasaki-shi',35.589216,139.578580),(748,'141372','14','Asao-ku, Kawasaki-shi',NULL,'Asao-ku, Kawasaki-shi, Kanagawa · 神奈川県川崎市麻生区',741,1,'川崎市麻生区','カワサキシアサオク','Asao-ku, Kawasaki-shi',35.603769,139.505686),(749,'141518','14','Midori-ku, Sagamihara-shi',NULL,'Midori-ku, Sagamihara-shi, Kanagawa · 神奈川県相模原市緑区',742,1,'相模原市緑区','サガミハラシミドリク','Midori-ku, Sagamihara-shi',35.595639,139.337590),(750,'141526','14','Chuo-ku, Sagamihara-shi',NULL,'Chuo-ku, Sagamihara-shi, Kanagawa · 神奈川県相模原市中央区',743,1,'相模原市中央区','サガミハラシチュウオウク','Chuo-ku, Sagamihara-shi',35.571376,139.373268),(751,'141534','14','Minami-ku, Sagamihara-shi',NULL,'Minami-ku, Sagamihara-shi, Kanagawa · 神奈川県相模原市南区',744,1,'相模原市南区','サガミハラシミナミク','Minami-ku, Sagamihara-shi',35.530342,139.430092),(752,'142018','14','Yokosuka-shi',NULL,'Yokosuka-shi, Kanagawa · 神奈川県横須賀市',745,1,'横須賀市','ヨコスカシ','Yokosuka-shi',35.281276,139.672284),(753,'142034','14','Hiratsuka-shi',NULL,'Hiratsuka-shi, Kanagawa · 神奈川県平塚市',746,1,'平塚市','ヒラツカシ','Hiratsuka-shi',35.335502,139.349412),(754,'142042','14','Kamakura-shi',NULL,'Kamakura-shi, Kanagawa · 神奈川県鎌倉市',747,1,'鎌倉市','カマクラシ','Kamakura-shi',35.319228,139.546690),(755,'142051','14','Fujisawa-shi',NULL,'Fujisawa-shi, Kanagawa · 神奈川県藤沢市',748,1,'藤沢市','フジサワシ','Fujisawa-shi',35.338940,139.491116),(756,'142069','14','Odawara-shi',NULL,'Odawara-shi, Kanagawa · 神奈川県小田原市',749,1,'小田原市','オダワラシ','Odawara-shi',35.264694,139.152355),(757,'142077','14','Chigasaki-shi',NULL,'Chigasaki-shi, Kanagawa · 神奈川県茅ヶ崎市',750,1,'茅ヶ崎市','チガサキシ','Chigasaki-shi',35.333879,139.404702),(758,'142085','14','Zushi-shi',NULL,'Zushi-shi, Kanagawa · 神奈川県逗子市',751,1,'逗子市','ズシシ','Zushi-shi',35.295592,139.580414),(759,'142107','14','Miura-shi',NULL,'Miura-shi, Kanagawa · 神奈川県三浦市',752,1,'三浦市','ミウラシ','Miura-shi',35.144176,139.620765),(760,'142115','14','Hadano-shi',NULL,'Hadano-shi, Kanagawa · 神奈川県秦野市',753,1,'秦野市','ハダノシ','Hadano-shi',35.374800,139.219946),(761,'142123','14','Atsugi-shi',NULL,'Atsugi-shi, Kanagawa · 神奈川県厚木市',754,1,'厚木市','アツギシ','Atsugi-shi',35.443049,139.362442),(762,'142131','14','Yamato-shi',NULL,'Yamato-shi, Kanagawa · 神奈川県大和市',755,1,'大和市','ヤマトシ','Yamato-shi',35.487515,139.457950),(763,'142140','14','Isehara-shi',NULL,'Isehara-shi, Kanagawa · 神奈川県伊勢原市',756,1,'伊勢原市','イセハラシ','Isehara-shi',35.402985,139.314906),(764,'142158','14','Ebina-shi',NULL,'Ebina-shi, Kanagawa · 神奈川県海老名市',757,1,'海老名市','エビナシ','Ebina-shi',35.446449,139.390803),(765,'142166','14','Zama-shi',NULL,'Zama-shi, Kanagawa · 神奈川県座間市',758,1,'座間市','ザマシ','Zama-shi',35.488640,139.407637),(766,'142174','14','Minamiashigara-shi',NULL,'Minamiashigara-shi, Kanagawa · 神奈川県南足柄市',759,1,'南足柄市','ミナミアシガラシ','Minamiashigara-shi',35.320633,139.099729),(767,'142182','14','Ayase-shi',NULL,'Ayase-shi, Kanagawa · 神奈川県綾瀬市',760,1,'綾瀬市','アヤセシ','Ayase-shi',35.437161,139.426348),(768,'143014','14','Hayama-machi',NULL,'Hayama-machi, Kanagawa · 神奈川県葉山町',761,1,'葉山町','ハヤママチ','Hayama-machi',35.272025,139.586275),(769,'143219','14','Samukawa-machi',NULL,'Samukawa-machi, Kanagawa · 神奈川県寒川町',762,1,'寒川町','サムカワマチ','Samukawa-machi',35.372963,139.384188),(770,'143413','14','Oiso-machi',NULL,'Oiso-machi, Kanagawa · 神奈川県大磯町',763,1,'大磯町','オオイソマチ','Oiso-machi',35.306940,139.311348),(771,'143421','14','Ninomiya-machi',NULL,'Ninomiya-machi, Kanagawa · 神奈川県二宮町',764,1,'二宮町','ニノミヤマチ','Ninomiya-machi',35.299494,139.255525),(772,'143618','14','Nakai-machi',NULL,'Nakai-machi, Kanagawa · 神奈川県中井町',765,1,'中井町','ナカイマチ','Nakai-machi',35.330751,139.218766),(773,'143626','14','Oi-machi',NULL,'Oi-machi, Kanagawa · 神奈川県大井町',766,1,'大井町','オオイマチ','Oi-machi',35.326619,139.156649),(774,'143634','14','Matsuda-machi',NULL,'Matsuda-machi, Kanagawa · 神奈川県松田町',767,1,'松田町','マツダマチ','Matsuda-machi',35.348199,139.139337),(775,'143642','14','Yamakita-machi',NULL,'Yamakita-machi, Kanagawa · 神奈川県山北町',768,1,'山北町','ヤマキタマチ','Yamakita-machi',35.360631,139.083806),(776,'143669','14','Kaisei-machi',NULL,'Kaisei-machi, Kanagawa · 神奈川県開成町',769,1,'開成町','カイセイマチ','Kaisei-machi',35.336430,139.123183),(777,'143821','14','Hakone-machi',NULL,'Hakone-machi, Kanagawa · 神奈川県箱根町',770,1,'箱根町','ハコネマチ','Hakone-machi',35.232301,139.106886),(778,'143839','14','Manazuru-machi',NULL,'Manazuru-machi, Kanagawa · 神奈川県真鶴町',771,1,'真鶴町','マナツルマチ','Manazuru-machi',35.158399,139.137233),(779,'143847','14','Yugawara-machi',NULL,'Yugawara-machi, Kanagawa · 神奈川県湯河原町',772,1,'湯河原町','ユガワラマチ','Yugawara-machi',35.147931,139.108336),(780,'144011','14','Aikawa-machi',NULL,'Aikawa-machi, Kanagawa · 神奈川県愛川町',773,1,'愛川町','アイカワマチ','Aikawa-machi',35.528746,139.321720),(781,'144029','14','Kiyokawa-mura',NULL,'Kiyokawa-mura, Kanagawa · 神奈川県清川村',774,1,'清川村','キヨカワムラ','Kiyokawa-mura',35.482330,139.276374),(782,'151017','15','Kita-ku, Niigata-shi',NULL,'Kita-ku, Niigata-shi, Niigata · 新潟県新潟市北区',775,1,'新潟市北区','ニイガタシキタク','Kita-ku, Niigata-shi',37.913559,139.221631),(783,'151025','15','Higashi-ku, Niigata-shi',NULL,'Higashi-ku, Niigata-shi, Niigata · 新潟県新潟市東区',776,1,'新潟市東区','ニイガタシヒガシク','Higashi-ku, Niigata-shi',37.924894,139.092606),(784,'151033','15','Chuo-ku, Niigata-shi',NULL,'Chuo-ku, Niigata-shi, Niigata · 新潟県新潟市中央区',777,1,'新潟市中央区','ニイガタシチュウオウク','Chuo-ku, Niigata-shi',37.922488,139.043232),(785,'151041','15','Konan-ku, Niigata-shi',NULL,'Konan-ku, Niigata-shi, Niigata · 新潟県新潟市江南区',778,1,'新潟市江南区','ニイガタシコウナンク','Konan-ku, Niigata-shi',37.867660,139.094013),(786,'151050','15','Akiha-ku, Niigata-shi',NULL,'Akiha-ku, Niigata-shi, Niigata · 新潟県新潟市秋葉区',779,1,'新潟市秋葉区','ニイガタシアキハク','Akiha-ku, Niigata-shi',37.788362,139.114618),(787,'151068','15','Minami-ku, Niigata-shi',NULL,'Minami-ku, Niigata-shi, Niigata · 新潟県新潟市南区',780,1,'新潟市南区','ニイガタシミナミク','Minami-ku, Niigata-shi',37.765817,139.019184),(788,'151076','15','Nishi-ku, Niigata-shi',NULL,'Nishi-ku, Niigata-shi, Niigata · 新潟県新潟市西区',781,1,'新潟市西区','ニイガタシニシク','Nishi-ku, Niigata-shi',37.874081,138.971648),(789,'151084','15','Nishikan-ku, Niigata-shi',NULL,'Nishikan-ku, Niigata-shi, Niigata · 新潟県新潟市西蒲区',782,1,'新潟市西蒲区','ニイガタシニシカンク','Nishikan-ku, Niigata-shi',37.760333,138.889253),(790,'152021','15','Nagaoka-shi',NULL,'Nagaoka-shi, Niigata · 新潟県長岡市',783,1,'長岡市','ナガオカシ','Nagaoka-shi',37.446587,138.851224),(791,'152048','15','Sanjo-shi',NULL,'Sanjo-shi, Niigata · 新潟県三条市',784,1,'三条市','サンジョウシ','Sanjo-shi',37.636768,138.961700),(792,'152056','15','Kashiwazaki-shi',NULL,'Kashiwazaki-shi, Niigata · 新潟県柏崎市',785,1,'柏崎市','カシワザキシ','Kashiwazaki-shi',37.364952,138.557988),(793,'152064','15','Shibata-shi',NULL,'Shibata-shi, Niigata · 新潟県新発田市',786,1,'新発田市','シバタシ','Shibata-shi',37.947958,139.327138),(794,'152081','15','Ojiya-shi',NULL,'Ojiya-shi, Niigata · 新潟県小千谷市',787,1,'小千谷市','オヂヤシ','Ojiya-shi',37.314350,138.795097),(795,'152099','15','Kamo-shi',NULL,'Kamo-shi, Niigata · 新潟県加茂市',788,1,'加茂市','カモシ','Kamo-shi',37.666335,139.040220),(796,'152102','15','Tokamachi-shi',NULL,'Tokamachi-shi, Niigata · 新潟県十日町市',789,1,'十日町市','トオカマチシ','Tokamachi-shi',37.127588,138.755710),(797,'152111','15','Mitsuke-shi',NULL,'Mitsuke-shi, Niigata · 新潟県見附市',790,1,'見附市','ミツケシ','Mitsuke-shi',37.531496,138.912724),(798,'152129','15','Murakami-shi',NULL,'Murakami-shi, Niigata · 新潟県村上市',791,1,'村上市','ムラカミシ','Murakami-shi',38.223990,139.480040),(799,'152137','15','Tsubame-shi',NULL,'Tsubame-shi, Niigata · 新潟県燕市',792,1,'燕市','ツバメシ','Tsubame-shi',37.673149,138.882249),(800,'152161','15','Itoigawa-shi',NULL,'Itoigawa-shi, Niigata · 新潟県糸魚川市',793,1,'糸魚川市','イトイガワシ','Itoigawa-shi',37.039025,137.862658),(801,'152170','15','Myoko-shi',NULL,'Myoko-shi, Niigata · 新潟県妙高市',794,1,'妙高市','ミョウコウシ','Myoko-shi',37.025265,138.253485),(802,'152188','15','Gosen-shi',NULL,'Gosen-shi, Niigata · 新潟県五泉市',795,1,'五泉市','ゴセンシ','Gosen-shi',37.744483,139.182568),(803,'152226','15','Joetsu-shi',NULL,'Joetsu-shi, Niigata · 新潟県上越市',796,1,'上越市','ジョウエツシ','Joetsu-shi',37.147873,138.236039),(804,'152234','15','Agano-shi',NULL,'Agano-shi, Niigata · 新潟県阿賀野市',797,1,'阿賀野市','アガノシ','Agano-shi',37.834509,139.226002),(805,'152242','15','Sado-shi',NULL,'Sado-shi, Niigata · 新潟県佐渡市',798,1,'佐渡市','サドシ','Sado-shi',38.018302,138.368138),(806,'152251','15','Uonuma-shi',NULL,'Uonuma-shi, Niigata · 新潟県魚沼市',799,1,'魚沼市','ウオヌマシ','Uonuma-shi',37.236415,138.963763),(807,'152269','15','Minamiuonuma-shi',NULL,'Minamiuonuma-shi, Niigata · 新潟県南魚沼市',800,1,'南魚沼市','ミナミウオヌマシ','Minamiuonuma-shi',37.065522,138.876081),(808,'152277','15','Tainai-shi',NULL,'Tainai-shi, Niigata · 新潟県胎内市',801,1,'胎内市','タイナイシ','Tainai-shi',38.059708,139.410350),(809,'153079','15','Seiro-machi',NULL,'Seiro-machi, Niigata · 新潟県聖籠町',802,1,'聖籠町','セイロウマチ','Seiro-machi',37.974526,139.274374),(810,'153427','15','Yahiko-mura',NULL,'Yahiko-mura, Niigata · 新潟県弥彦村',803,1,'弥彦村','ヤヒコムラ','Yahiko-mura',37.691007,138.855252),(811,'153613','15','Tagami-machi',NULL,'Tagami-machi, Niigata · 新潟県田上町',804,1,'田上町','タガミマチ','Tagami-machi',37.698857,139.057993),(812,'153851','15','Aga-machi',NULL,'Aga-machi, Niigata · 新潟県阿賀町',805,1,'阿賀町','アガマチ','Aga-machi',37.675487,139.458781),(813,'154059','15','Izumozaki-machi',NULL,'Izumozaki-machi, Niigata · 新潟県出雲崎町',806,1,'出雲崎町','イズモザキマチ','Izumozaki-machi',37.530715,138.709361),(814,'154610','15','Yuzawa-machi',NULL,'Yuzawa-machi, Niigata · 新潟県湯沢町',807,1,'湯沢町','ユザワマチ','Yuzawa-machi',36.934007,138.817424),(815,'154822','15','Tsunan-machi',NULL,'Tsunan-machi, Niigata · 新潟県津南町',808,1,'津南町','ツナンマチ','Tsunan-machi',37.014313,138.652547),(816,'155047','15','Kariwa-mura',NULL,'Kariwa-mura, Niigata · 新潟県刈羽村',809,1,'刈羽村','カリワムラ','Kariwa-mura',37.422361,138.622442),(817,'155811','15','Sekikawa-mura',NULL,'Sekikawa-mura, Niigata · 新潟県関川村',810,1,'関川村','セキカワムラ','Sekikawa-mura',38.089412,139.564957),(818,'155861','15','Awashimaura-mura',NULL,'Awashimaura-mura, Niigata · 新潟県粟島浦村',811,1,'粟島浦村','アワシマウラムラ','Awashimaura-mura',38.468213,139.254360),(819,'162019','16','Toyama-shi',NULL,'Toyama-shi, Toyama · 富山県富山市',812,1,'富山市','トヤマシ','Toyama-shi',36.695982,137.213449),(820,'162027','16','Takaoka-shi',NULL,'Takaoka-shi, Toyama · 富山県高岡市',813,1,'高岡市','タカオカシ','Takaoka-shi',36.754099,137.025717),(821,'162043','16','Uozu-shi',NULL,'Uozu-shi, Toyama · 富山県魚津市',814,1,'魚津市','ウオヅシ','Uozu-shi',36.827367,137.409190),(822,'162051','16','Himi-shi',NULL,'Himi-shi, Toyama · 富山県氷見市',815,1,'氷見市','ヒミシ','Himi-shi',36.855978,136.972868),(823,'162060','16','Namerikawa-shi',NULL,'Namerikawa-shi, Toyama · 富山県滑川市',816,1,'滑川市','ナメリカワシ','Namerikawa-shi',36.764394,137.341180),(824,'162078','16','Kurobe-shi',NULL,'Kurobe-shi, Toyama · 富山県黒部市',817,1,'黒部市','クロベシ','Kurobe-shi',36.871437,137.447897),(825,'162086','16','Tonami-shi',NULL,'Tonami-shi, Toyama · 富山県砺波市',818,1,'砺波市','トナミシ','Tonami-shi',36.647467,136.962167),(826,'162094','16','Oyabe-shi',NULL,'Oyabe-shi, Toyama · 富山県小矢部市',819,1,'小矢部市','オヤベシ','Oyabe-shi',36.675531,136.868653),(827,'162108','16','Nanto-shi',NULL,'Nanto-shi, Toyama · 富山県南砺市',820,1,'南砺市','ナントシ','Nanto-shi',36.557451,136.875395),(828,'162116','16','Imizu-shi',NULL,'Imizu-shi, Toyama · 富山県射水市',821,1,'射水市','イミズシ','Imizu-shi',36.730462,137.075391),(829,'163210','16','Funahashi-mura',NULL,'Funahashi-mura, Toyama · 富山県舟橋村',822,1,'舟橋村','フナハシムラ','Funahashi-mura',36.703525,137.307372),(830,'163228','16','Kamiichi-machi',NULL,'Kamiichi-machi, Toyama · 富山県上市町',823,1,'上市町','カミイチマチ','Kamiichi-machi',36.698429,137.362586),(831,'163236','16','Tateyama-machi',NULL,'Tateyama-machi, Toyama · 富山県立山町',824,1,'立山町','タテヤママチ','Tateyama-machi',36.663346,137.313658),(832,'163422','16','Nyuzen-machi',NULL,'Nyuzen-machi, Toyama · 富山県入善町',825,1,'入善町','ニュウゼンマチ','Nyuzen-machi',36.933578,137.502128),(833,'163431','16','Asahi-machi',NULL,'Asahi-machi, Toyama · 富山県朝日町',826,1,'朝日町','アサヒマチ','Asahi-machi',36.946179,137.559868),(834,'172014','17','Kanazawa-shi',NULL,'Kanazawa-shi, Ishikawa · 石川県金沢市',827,1,'金沢市','カナザワシ','Kanazawa-shi',36.561051,136.656633),(835,'172022','17','Nanao-shi',NULL,'Nanao-shi, Ishikawa · 石川県七尾市',828,1,'七尾市','ナナオシ','Nanao-shi',37.043108,136.967296),(836,'172031','17','Komatsu-shi',NULL,'Komatsu-shi, Ishikawa · 石川県小松市',829,1,'小松市','コマツシ','Komatsu-shi',36.408357,136.445588),(837,'172049','17','Wajima-shi',NULL,'Wajima-shi, Ishikawa · 石川県輪島市',830,1,'輪島市','ワジマシ','Wajima-shi',37.390557,136.899185),(838,'172057','17','Suzu-shi',NULL,'Suzu-shi, Ishikawa · 石川県珠洲市',831,1,'珠洲市','スズシ','Suzu-shi',37.436369,137.260406),(839,'172065','17','Kaga-shi',NULL,'Kaga-shi, Ishikawa · 石川県加賀市',832,1,'加賀市','カガシ','Kaga-shi',36.302669,136.314685),(840,'172073','17','Hakui-shi',NULL,'Hakui-shi, Ishikawa · 石川県羽咋市',833,1,'羽咋市','ハクイシ','Hakui-shi',36.893599,136.778999),(841,'172090','17','Kahoku-shi',NULL,'Kahoku-shi, Ishikawa · 石川県かほく市',834,1,'かほく市','カホクシ','Kahoku-shi',36.719973,136.706668),(842,'172103','17','Hakusan-shi',NULL,'Hakusan-shi, Ishikawa · 石川県白山市',835,1,'白山市','ハクサンシ','Hakusan-shi',36.514427,136.565833),(843,'172111','17','Nomi-shi',NULL,'Nomi-shi, Ishikawa · 石川県能美市',836,1,'能美市','ノミシ','Nomi-shi',36.446888,136.554028),(844,'172120','17','Nonoichi-shi',NULL,'Nonoichi-shi, Ishikawa · 石川県野々市市',837,1,'野々市市','ノノイチシ','Nonoichi-shi',36.519466,136.609685),(845,'173240','17','Kawakita-machi',NULL,'Kawakita-machi, Ishikawa · 石川県川北町',838,1,'川北町','カワキタマチ','Kawakita-machi',36.468632,136.542276),(846,'173614','17','Tsubata-machi',NULL,'Tsubata-machi, Ishikawa · 石川県津幡町',839,1,'津幡町','ツバタマチ','Tsubata-machi',36.669093,136.728683),(847,'173657','17','Uchinada-machi',NULL,'Uchinada-machi, Ishikawa · 石川県内灘町',840,1,'内灘町','ウチナダマチ','Uchinada-machi',36.653530,136.645125),(848,'173843','17','Shika-machi',NULL,'Shika-machi, Ishikawa · 石川県志賀町',841,1,'志賀町','シカマチ','Shika-machi',37.006187,136.778010),(849,'173860','17','Hodatsushimizu-cho',NULL,'Hodatsushimizu-cho, Ishikawa · 石川県宝達志水町',842,1,'宝達志水町','ホウダツシミズチョウ','Hodatsushimizu-cho',36.862759,136.797557),(850,'174076','17','Nakanoto-machi',NULL,'Nakanoto-machi, Ishikawa · 石川県中能登町',843,1,'中能登町','ナカノトマチ','Nakanoto-machi',36.988890,136.901476),(851,'174611','17','Anamizu-machi',NULL,'Anamizu-machi, Ishikawa · 石川県穴水町',844,1,'穴水町','アナミズマチ','Anamizu-machi',37.230874,136.912479),(852,'174637','17','Noto-cho',NULL,'Noto-cho, Ishikawa · 石川県能登町',845,1,'能登町','ノトチョウ','Noto-cho',37.310401,137.147790),(853,'182010','18','Fukui-shi',NULL,'Fukui-shi, Fukui · 福井県福井市',846,1,'福井市','フクイシ','Fukui-shi',36.064120,136.219452),(854,'182028','18','Tsuruga-shi',NULL,'Tsuruga-shi, Fukui · 福井県敦賀市',847,1,'敦賀市','ツルガシ','Tsuruga-shi',35.645222,136.055544),(855,'182044','18','Obama-shi',NULL,'Obama-shi, Fukui · 福井県小浜市',848,1,'小浜市','オバマシ','Obama-shi',35.495593,135.746647),(856,'182052','18','Ono-shi',NULL,'Ono-shi, Fukui · 福井県大野市',849,1,'大野市','オオノシ','Ono-shi',35.980564,136.487692),(857,'182061','18','Katsuyama-shi',NULL,'Katsuyama-shi, Fukui · 福井県勝山市',850,1,'勝山市','カツヤマシ','Katsuyama-shi',36.060917,136.500542),(858,'182079','18','Sabae-shi',NULL,'Sabae-shi, Fukui · 福井県鯖江市',851,1,'鯖江市','サバエシ','Sabae-shi',35.956565,136.184238),(859,'182087','18','Awara-shi',NULL,'Awara-shi, Fukui · 福井県あわら市',852,1,'あわら市','アワラシ','Awara-shi',36.211361,136.229012),(860,'182095','18','Echizen-shi',NULL,'Echizen-shi, Fukui · 福井県越前市',853,1,'越前市','エチゼンシ','Echizen-shi',35.903471,136.168672),(861,'182109','18','Sakai-shi',NULL,'Sakai-shi, Fukui · 福井県坂井市',854,1,'坂井市','サカイシ','Sakai-shi',36.166902,136.231287),(862,'183229','18','Eiheiji-cho',NULL,'Eiheiji-cho, Fukui · 福井県永平寺町',855,1,'永平寺町','エイヘイジチョウ','Eiheiji-cho',36.092248,136.298724),(863,'183822','18','Ikeda-cho',NULL,'Ikeda-cho, Fukui · 福井県池田町',856,1,'池田町','イケダチョウ','Ikeda-cho',35.890383,136.344079),(864,'184047','18','Minamiechizen-cho',NULL,'Minamiechizen-cho, Fukui · 福井県南越前町',857,1,'南越前町','ミナミエチゼンチョウ','Minamiechizen-cho',35.835150,136.194435),(865,'184233','18','Echizen-cho',NULL,'Echizen-cho, Fukui · 福井県越前町',858,1,'越前町','エチゼンチョウ','Echizen-cho',35.974264,136.129765),(866,'184420','18','Mihama-cho',NULL,'Mihama-cho, Fukui · 福井県美浜町',859,1,'美浜町','ミハマチョウ','Mihama-cho',35.600600,135.940619),(867,'184811','18','Takahama-cho',NULL,'Takahama-cho, Fukui · 福井県高浜町',860,1,'高浜町','タカハマチョウ','Takahama-cho',35.487821,135.546157),(868,'184837','18','Oi-cho',NULL,'Oi-cho, Fukui · 福井県おおい町',861,1,'おおい町','オオイチョウ','Oi-cho',35.481128,135.617885),(869,'185019','18','Wakasa-cho',NULL,'Wakasa-cho, Fukui · 福井県若狭町',862,1,'若狭町','ワカサチョウ','Wakasa-cho',35.548882,135.908384),(870,'192015','19','Kofu-shi',NULL,'Kofu-shi, Yamanashi · 山梨県甲府市',863,1,'甲府市','コウフシ','Kofu-shi',35.662033,138.568339),(871,'192023','19','Fujiyoshida-shi',NULL,'Fujiyoshida-shi, Yamanashi · 山梨県富士吉田市',864,1,'富士吉田市','フジヨシダシ','Fujiyoshida-shi',35.487499,138.807857),(872,'192040','19','Tsuru-shi',NULL,'Tsuru-shi, Yamanashi · 山梨県都留市',865,1,'都留市','ツルシ','Tsuru-shi',35.551565,138.905470),(873,'192058','19','Yamanashi-shi',NULL,'Yamanashi-shi, Yamanashi · 山梨県山梨市',866,1,'山梨市','ヤマナシシ','Yamanashi-shi',35.693429,138.686955),(874,'192066','19','Otsuki-shi',NULL,'Otsuki-shi, Yamanashi · 山梨県大月市',867,1,'大月市','オオツキシ','Otsuki-shi',35.610474,138.940041),(875,'192074','19','Nirasaki-shi',NULL,'Nirasaki-shi, Yamanashi · 山梨県韮崎市',868,1,'韮崎市','ニラサキシ','Nirasaki-shi',35.708804,138.446192),(876,'192082','19','Minamiarupusu-shi',NULL,'Minamiarupusu-shi, Yamanashi · 山梨県南アルプス市',869,1,'南アルプス市','ミナミアルプスシ','Minamiarupusu-shi',35.608361,138.465005),(877,'192091','19','Hokuto-shi',NULL,'Hokuto-shi, Yamanashi · 山梨県北杜市',870,1,'北杜市','ホクトシ','Hokuto-shi',35.776501,138.423537),(878,'192104','19','Kai-shi',NULL,'Kai-shi, Yamanashi · 山梨県甲斐市',871,1,'甲斐市','カイシ','Kai-shi',35.660829,138.515796),(879,'192112','19','Fuefuki-shi',NULL,'Fuefuki-shi, Yamanashi · 山梨県笛吹市',872,1,'笛吹市','フエフキシ','Fuefuki-shi',35.647296,138.639730),(880,'192121','19','Uenohara-shi',NULL,'Uenohara-shi, Yamanashi · 山梨県上野原市',873,1,'上野原市','ウエノハラシ','Uenohara-shi',35.630301,139.108751),(881,'192139','19','Koshu-shi',NULL,'Koshu-shi, Yamanashi · 山梨県甲州市',874,1,'甲州市','コウシュウシ','Koshu-shi',35.704308,138.729456),(882,'192147','19','Chuo-shi',NULL,'Chuo-shi, Yamanashi · 山梨県中央市',875,1,'中央市','チュウオウシ','Chuo-shi',35.599643,138.517269),(883,'193461','19','Ichikawamisato-',NULL,'Ichikawamisato-, Yamanashi · 山梨県市川三郷町',876,1,'市川三郷町','イチカワミサトチョウ','Ichikawamisato-',35.565120,138.502406),(884,'193640','19','Hayakawa-cho',NULL,'Hayakawa-cho, Yamanashi · 山梨県早川町',877,1,'早川町','ハヤカワチョウ','Hayakawa-cho',35.412658,138.363192),(885,'193658','19','Minobu-cho',NULL,'Minobu-cho, Yamanashi · 山梨県身延町',878,1,'身延町','ミノブチョウ','Minobu-cho',35.467569,138.442488),(886,'193666','19','Nambu-cho',NULL,'Nambu-cho, Yamanashi · 山梨県南部町',879,1,'南部町','ナンブチョウ','Nambu-cho',35.242300,138.486054),(887,'193682','19','Fujikawa-cho',NULL,'Fujikawa-cho, Yamanashi · 山梨県富士川町',880,1,'富士川町','フジカワチョウ','Fujikawa-cho',35.561163,138.461305),(888,'193844','19','Showa-cho',NULL,'Showa-cho, Yamanashi · 山梨県昭和町',881,1,'昭和町','ショウワチョウ','Showa-cho',35.627928,138.535148),(889,'194221','19','Doshi-mura',NULL,'Doshi-mura, Yamanashi · 山梨県道志村',882,1,'道志村','ドウシムラ','Doshi-mura',35.528025,139.033429),(890,'194239','19','Nishikatsura-cho',NULL,'Nishikatsura-cho, Yamanashi · 山梨県西桂町',883,1,'西桂町','ニシカツラチョウ','Nishikatsura-cho',35.524082,138.846880),(891,'194247','19','Oshino-mura',NULL,'Oshino-mura, Yamanashi · 山梨県忍野村',884,1,'忍野村','オシノムラ','Oshino-mura',35.460062,138.847853),(892,'194255','19','Yamanakako-mura',NULL,'Yamanakako-mura, Yamanashi · 山梨県山中湖村',885,1,'山中湖村','ヤマナカコムラ','Yamanakako-mura',35.410644,138.861078),(893,'194298','19','Narusawa-mura',NULL,'Narusawa-mura, Yamanashi · 山梨県鳴沢村',886,1,'鳴沢村','ナルサワムラ','Narusawa-mura',35.481318,138.706609),(894,'194301','19','Fujikawaguchiko-machi',NULL,'Fujikawaguchiko-machi, Yamanashi · 山梨県富士河口湖町',887,1,'富士河口湖町','フジカワグチコマチ','Fujikawaguchiko-machi',35.497297,138.754926),(895,'194425','19','Kosuge-mura',NULL,'Kosuge-mura, Yamanashi · 山梨県小菅村',888,1,'小菅村','コスゲムラ','Kosuge-mura',35.760274,138.940283),(896,'194433','19','Tabayama-mura',NULL,'Tabayama-mura, Yamanashi · 山梨県丹波山村',889,1,'丹波山村','タバヤマムラ','Tabayama-mura',35.791740,138.917769),(897,'202011','20','Nagano-shi',NULL,'Nagano-shi, Nagano · 長野県長野市',890,1,'長野市','ナガノシ','Nagano-shi',36.648631,138.194287),(898,'202029','20','Matsumoto-shi',NULL,'Matsumoto-shi, Nagano · 長野県松本市',891,1,'松本市','マツモトシ','Matsumoto-shi',36.238096,137.971992),(899,'202037','20','Ueda-shi',NULL,'Ueda-shi, Nagano · 長野県上田市',892,1,'上田市','ウエダシ','Ueda-shi',36.401942,138.249069),(900,'202045','20','Okaya-shi',NULL,'Okaya-shi, Nagano · 長野県岡谷市',893,1,'岡谷市','オカヤシ','Okaya-shi',36.067003,138.049557),(901,'202053','20','Iida-shi',NULL,'Iida-shi, Nagano · 長野県飯田市',894,1,'飯田市','イイダシ','Iida-shi',35.514703,137.821824),(902,'202061','20','Suwa-shi',NULL,'Suwa-shi, Nagano · 長野県諏訪市',895,1,'諏訪市','スワシ','Suwa-shi',36.039175,138.113984),(903,'202070','20','Suzaka-shi',NULL,'Suzaka-shi, Nagano · 長野県須坂市',896,1,'須坂市','スザカシ','Suzaka-shi',36.651117,138.307275),(904,'202088','20','Komoro-shi',NULL,'Komoro-shi, Nagano · 長野県小諸市',897,1,'小諸市','コモロシ','Komoro-shi',36.326874,138.426021),(905,'202096','20','Ina-shi',NULL,'Ina-shi, Nagano · 長野県伊那市',898,1,'伊那市','イナシ','Ina-shi',35.827478,137.954083),(906,'202100','20','Komagane-shi',NULL,'Komagane-shi, Nagano · 長野県駒ヶ根市',899,1,'駒ヶ根市','コマガネシ','Komagane-shi',35.728861,137.933993),(907,'202118','20','Nakano-shi',NULL,'Nakano-shi, Nagano · 長野県中野市',900,1,'中野市','ナカノシ','Nakano-shi',36.742027,138.369470),(908,'202126','20','Omachi-shi',NULL,'Omachi-shi, Nagano · 長野県大町市',901,1,'大町市','オオマチシ','Omachi-shi',36.502976,137.851169),(909,'202134','20','Iiyama-shi',NULL,'Iiyama-shi, Nagano · 長野県飯山市',902,1,'飯山市','イイヤマシ','Iiyama-shi',36.851745,138.365498),(910,'202142','20','Chino-shi',NULL,'Chino-shi, Nagano · 長野県茅野市',903,1,'茅野市','チノシ','Chino-shi',35.995571,138.158896),(911,'202151','20','Shiojiri-shi',NULL,'Shiojiri-shi, Nagano · 長野県塩尻市',904,1,'塩尻市','シオジリシ','Shiojiri-shi',36.115020,137.953380),(912,'202177','20','Saku-shi',NULL,'Saku-shi, Nagano · 長野県佐久市',905,1,'佐久市','サクシ','Saku-shi',36.248788,138.476831),(913,'202185','20','Chikuma-shi',NULL,'Chikuma-shi, Nagano · 長野県千曲市',906,1,'千曲市','チクマシ','Chikuma-shi',36.530877,138.114683),(914,'202193','20','Tomi-shi',NULL,'Tomi-shi, Nagano · 長野県東御市',907,1,'東御市','トウミシ','Tomi-shi',36.359550,138.330295),(915,'202207','20','Azumino-shi',NULL,'Azumino-shi, Nagano · 長野県安曇野市',908,1,'安曇野市','アヅミノシ','Azumino-shi',36.303942,137.905760),(916,'203033','20','Komi-machi',NULL,'Komi-machi, Nagano · 長野県小海町',909,1,'小海町','コウミマチ','Komi-machi',36.095241,138.483515),(917,'203041','20','Kawakami-mura',NULL,'Kawakami-mura, Nagano · 長野県川上村',910,1,'川上村','カワカミムラ','Kawakami-mura',35.975487,138.578605),(918,'203050','20','Minamimaki-mura',NULL,'Minamimaki-mura, Nagano · 長野県南牧村',911,1,'南牧村','ミナミマキムラ','Minamimaki-mura',36.020907,138.492185),(919,'203068','20','Minamiaiki-mura',NULL,'Minamiaiki-mura, Nagano · 長野県南相木村',912,1,'南相木村','ミナミアイキムラ','Minamiaiki-mura',36.036072,138.546890),(920,'203076','20','Kitaaiki-mura',NULL,'Kitaaiki-mura, Nagano · 長野県北相木村',913,1,'北相木村','キタアイキムラ','Kitaaiki-mura',36.059195,138.551203),(921,'203092','20','Sakuho-machi',NULL,'Sakuho-machi, Nagano · 長野県佐久穂町',914,1,'佐久穂町','サクホマチ','Sakuho-machi',36.160980,138.483425),(922,'203211','20','Karuizawa-machi',NULL,'Karuizawa-machi, Nagano · 長野県軽井沢町',915,1,'軽井沢町','カルイザワマチ','Karuizawa-machi',36.348310,138.596966),(923,'203238','20','Miyota-machi',NULL,'Miyota-machi, Nagano · 長野県御代田町',916,1,'御代田町','ミヨタマチ','Miyota-machi',36.322754,138.506479),(924,'203246','20','Tateshina-machi',NULL,'Tateshina-machi, Nagano · 長野県立科町',917,1,'立科町','タテシナマチ','Tateshina-machi',36.272089,138.315991),(925,'203491','20','Aoki-mura',NULL,'Aoki-mura, Nagano · 長野県青木村',918,1,'青木村','アオキムラ','Aoki-mura',36.369998,138.128698),(926,'203505','20','Nagawa-machi',NULL,'Nagawa-machi, Nagano · 長野県長和町',919,1,'長和町','ナガワマチ','Nagawa-machi',36.269920,138.257967),(927,'203611','20','Shimosuwa-machi',NULL,'Shimosuwa-machi, Nagano · 長野県下諏訪町',920,1,'下諏訪町','シモスワマチ','Shimosuwa-machi',36.069577,138.080068),(928,'203629','20','Fujimi-machi',NULL,'Fujimi-machi, Nagano · 長野県富士見町',921,1,'富士見町','フジミマチ','Fujimi-machi',35.914652,138.240740),(929,'203637','20','Hara-mura',NULL,'Hara-mura, Nagano · 長野県原村',922,1,'原村','ハラムラ','Hara-mura',35.964354,138.217471),(930,'203823','20','Tatsuno-machi',NULL,'Tatsuno-machi, Nagano · 長野県辰野町',923,1,'辰野町','タツノマチ','Tatsuno-machi',35.982413,137.987428),(931,'203831','20','Minowa-machi',NULL,'Minowa-machi, Nagano · 長野県箕輪町',924,1,'箕輪町','ミノワマチ','Minowa-machi',35.915020,137.981992),(932,'203840','20','Iijima-machi',NULL,'Iijima-machi, Nagano · 長野県飯島町',925,1,'飯島町','イイジママチ','Iijima-machi',35.676622,137.919523),(933,'203858','20','Minamiminowa-mura',NULL,'Minamiminowa-mura, Nagano · 長野県南箕輪村',926,1,'南箕輪村','ミナミミノワムラ','Minamiminowa-mura',35.872853,137.975084),(934,'203866','20','Nakagawa-mura',NULL,'Nakagawa-mura, Nagano · 長野県中川村',927,1,'中川村','ナカガワムラ','Nakagawa-mura',35.634617,137.945961),(935,'203882','20','Miyada-mura',NULL,'Miyada-mura, Nagano · 長野県宮田村',928,1,'宮田村','ミヤダムラ','Miyada-mura',35.768882,137.944209),(936,'204021','20','Matsukawa-machi',NULL,'Matsukawa-machi, Nagano · 長野県松川町',929,1,'松川町','マツカワマチ','Matsukawa-machi',35.597231,137.909667),(937,'204030','20','Takamori-machi',NULL,'Takamori-machi, Nagano · 長野県高森町',930,1,'高森町','タカモリマチ','Takamori-machi',35.551542,137.878519),(938,'204048','20','Anan-cho',NULL,'Anan-cho, Nagano · 長野県阿南町',931,1,'阿南町','アナンチョウ','Anan-cho',35.323620,137.816090),(939,'204072','20','Achi-mura',NULL,'Achi-mura, Nagano · 長野県阿智村',932,1,'阿智村','アチムラ','Achi-mura',35.443791,137.747444),(940,'204099','20','Hiraya-mura',NULL,'Hiraya-mura, Nagano · 長野県平谷村',933,1,'平谷村','ヒラヤムラ','Hiraya-mura',35.323279,137.630083),(941,'204102','20','Neba-mura',NULL,'Neba-mura, Nagano · 長野県根羽村',934,1,'根羽村','ネバムラ','Neba-mura',35.255419,137.581760),(942,'204111','20','Shimojo-mura',NULL,'Shimojo-mura, Nagano · 長野県下條村',935,1,'下條村','シモジョウムラ','Shimojo-mura',35.397330,137.785926),(943,'204129','20','Urugi-mura',NULL,'Urugi-mura, Nagano · 長野県売木村',936,1,'売木村','ウルギムラ','Urugi-mura',35.271068,137.711191),(944,'204137','20','Tenryu-mura',NULL,'Tenryu-mura, Nagano · 長野県天龍村',937,1,'天龍村','テンリュウムラ','Tenryu-mura',35.276307,137.854244),(945,'204145','20','Yasuoka-mura',NULL,'Yasuoka-mura, Nagano · 長野県泰阜村',938,1,'泰阜村','ヤスオカムラ','Yasuoka-mura',35.377434,137.846157),(946,'204153','20','Takagi-mura',NULL,'Takagi-mura, Nagano · 長野県喬木村',939,1,'喬木村','タカギムラ','Takagi-mura',35.513823,137.873751),(947,'204161','20','Toyoka-mura',NULL,'Toyoka-mura, Nagano · 長野県豊丘村',940,1,'豊丘村','トヨオカムラ','Toyoka-mura',35.551497,137.895791),(948,'204170','20','Oshika-mura',NULL,'Oshika-mura, Nagano · 長野県大鹿村',941,1,'大鹿村','オオシカムラ','Oshika-mura',35.578234,138.034044),(949,'204226','20','Agematsu-machi',NULL,'Agematsu-machi, Nagano · 長野県上松町',942,1,'上松町','アゲマツマチ','Agematsu-machi',35.782192,137.693244),(950,'204234','20','Nagiso-machi',NULL,'Nagiso-machi, Nagano · 長野県南木曽町',943,1,'南木曽町','ナギソマチ','Nagiso-machi',35.603787,137.608936),(951,'204251','20','Kiso-mura',NULL,'Kiso-mura, Nagano · 長野県木祖村',944,1,'木祖村','キソムラ','Kiso-mura',35.936307,137.783163),(952,'204293','20','Otaki-mura',NULL,'Otaki-mura, Nagano · 長野県王滝村',945,1,'王滝村','オウタキムラ','Otaki-mura',35.809343,137.551076),(953,'204307','20','Okuwa-mura',NULL,'Okuwa-mura, Nagano · 長野県大桑村',946,1,'大桑村','オオクワムラ','Okuwa-mura',35.689200,137.671400),(954,'204323','20','Kiso-machi',NULL,'Kiso-machi, Nagano · 長野県木曽町',947,1,'木曽町','キソマチ','Kiso-machi',35.842455,137.691534),(955,'204463','20','Omi-mura',NULL,'Omi-mura, Nagano · 長野県麻績村',948,1,'麻績村','オミムラ','Omi-mura',36.456086,138.045267),(956,'204480','20','Ikusaka-mura',NULL,'Ikusaka-mura, Nagano · 長野県生坂村',949,1,'生坂村','イクサカムラ','Ikusaka-mura',36.425234,137.927484),(957,'204501','20','Yamagata-mura',NULL,'Yamagata-mura, Nagano · 長野県山形村',950,1,'山形村','ヤマガタムラ','Yamagata-mura',36.168104,137.878970),(958,'204510','20','Asahi-mura',NULL,'Asahi-mura, Nagano · 長野県朝日村',951,1,'朝日村','アサヒムラ','Asahi-mura',36.129275,137.867147),(959,'204528','20','Chikuhoku-mura',NULL,'Chikuhoku-mura, Nagano · 長野県筑北村',952,1,'筑北村','チクホクムラ','Chikuhoku-mura',36.402706,138.011643),(960,'204811','20','Ikeda-machi',NULL,'Ikeda-machi, Nagano · 長野県池田町',953,1,'池田町','イケダマチ','Ikeda-machi',36.421368,137.874499),(961,'204820','20','Matsukawa-mura',NULL,'Matsukawa-mura, Nagano · 長野県松川村',954,1,'松川村','マツカワムラ','Matsukawa-mura',36.424045,137.854556),(962,'204854','20','Hakuba-mura',NULL,'Hakuba-mura, Nagano · 長野県白馬村',955,1,'白馬村','ハクバムラ','Hakuba-mura',36.698070,137.861969),(963,'204862','20','Otari-mura',NULL,'Otari-mura, Nagano · 長野県小谷村',956,1,'小谷村','オタリムラ','Otari-mura',36.779206,137.908305),(964,'205214','20','Sakaki-machi',NULL,'Sakaki-machi, Nagano · 長野県坂城町',957,1,'坂城町','サカキマチ','Sakaki-machi',36.461839,138.180106),(965,'205419','20','Obuse-machi',NULL,'Obuse-machi, Nagano · 長野県小布施町',958,1,'小布施町','オブセマチ','Obuse-machi',36.697478,138.312146),(966,'205435','20','Takayama-mura',NULL,'Takayama-mura, Nagano · 長野県高山村',959,1,'高山村','タカヤマムラ','Takayama-mura',36.679770,138.363261),(967,'205613','20','Yamanochi-machi',NULL,'Yamanochi-machi, Nagano · 長野県山ノ内町',960,1,'山ノ内町','ヤマノウチマチ','Yamanochi-machi',36.744606,138.412621),(968,'205621','20','Kijimadaira-mura',NULL,'Kijimadaira-mura, Nagano · 長野県木島平村',961,1,'木島平村','キジマダイラムラ','Kijimadaira-mura',36.857841,138.407012),(969,'205630','20','Nozawaonsen-mura',NULL,'Nozawaonsen-mura, Nagano · 長野県野沢温泉村',962,1,'野沢温泉村','ノザワオンセンムラ','Nozawaonsen-mura',36.922728,138.440456),(970,'205834','20','Shinano-machi',NULL,'Shinano-machi, Nagano · 長野県信濃町',963,1,'信濃町','シナノマチ','Shinano-machi',36.806379,138.206991),(971,'205885','20','Ogawa-mura',NULL,'Ogawa-mura, Nagano · 長野県小川村',964,1,'小川村','オガワムラ','Ogawa-mura',36.617135,137.974801),(972,'205907','20','Iizuna-machi',NULL,'Iizuna-machi, Nagano · 長野県飯綱町',965,1,'飯綱町','イイヅナマチ','Iizuna-machi',36.754661,138.235530),(973,'206024','20','Sakae-mura',NULL,'Sakae-mura, Nagano · 長野県栄村',966,1,'栄村','サカエムラ','Sakae-mura',36.987481,138.577359),(974,'212016','21','Gifu-shi',NULL,'Gifu-shi, Gifu · 岐阜県岐阜市',967,1,'岐阜市','ギフシ','Gifu-shi',35.426201,136.759974),(975,'212024','21','Ogaki-shi',NULL,'Ogaki-shi, Gifu · 岐阜県大垣市',968,1,'大垣市','オオガキシ','Ogaki-shi',35.359365,136.612753),(976,'212032','21','Takayama-shi',NULL,'Takayama-shi, Gifu · 岐阜県高山市',969,1,'高山市','タカヤマシ','Takayama-shi',36.146093,137.252206),(977,'212041','21','Tajimi-shi',NULL,'Tajimi-shi, Gifu · 岐阜県多治見市',970,1,'多治見市','タジミシ','Tajimi-shi',35.332773,137.132078),(978,'212059','21','Seki-shi',NULL,'Seki-shi, Gifu · 岐阜県関市',971,1,'関市','セキシ','Seki-shi',35.495777,136.917895),(979,'212067','21','Nakatsugawa-shi',NULL,'Nakatsugawa-shi, Gifu · 岐阜県中津川市',972,1,'中津川市','ナカツガワシ','Nakatsugawa-shi',35.487493,137.500616),(980,'212075','21','Mino-shi',NULL,'Mino-shi, Gifu · 岐阜県美濃市',973,1,'美濃市','ミノシ','Mino-shi',35.544713,136.907570),(981,'212083','21','Mizunami-shi',NULL,'Mizunami-shi, Gifu · 岐阜県瑞浪市',974,1,'瑞浪市','ミズナミシ','Mizunami-shi',35.361740,137.254639),(982,'212091','21','Hashima-shi',NULL,'Hashima-shi, Gifu · 岐阜県羽島市',975,1,'羽島市','ハシマシ','Hashima-shi',35.319910,136.703349),(983,'212105','21','Ena-shi',NULL,'Ena-shi, Gifu · 岐阜県恵那市',976,1,'恵那市','エナシ','Ena-shi',35.449320,137.412817),(984,'212113','21','Minokamo-shi',NULL,'Minokamo-shi, Gifu · 岐阜県美濃加茂市',977,1,'美濃加茂市','ミノカモシ','Minokamo-shi',35.440242,137.015664),(985,'212121','21','Toki-shi',NULL,'Toki-shi, Gifu · 岐阜県土岐市',978,1,'土岐市','トキシ','Toki-shi',35.352497,137.183205),(986,'212130','21','Kakamigahara-shi',NULL,'Kakamigahara-shi, Gifu · 岐阜県各務原市',979,1,'各務原市','カカミガハラシ','Kakamigahara-shi',35.398912,136.848393),(987,'212148','21','Kani-shi',NULL,'Kani-shi, Gifu · 岐阜県可児市',980,1,'可児市','カニシ','Kani-shi',35.426132,137.061075),(988,'212156','21','Yamagata-shi',NULL,'Yamagata-shi, Gifu · 岐阜県山県市',981,1,'山県市','ヤマガタシ','Yamagata-shi',35.506111,136.781401),(989,'212164','21','Mizuho-shi',NULL,'Mizuho-shi, Gifu · 岐阜県瑞穂市',982,1,'瑞穂市','ミズホシ','Mizuho-shi',35.391826,136.690784),(990,'212172','21','Hida-shi',NULL,'Hida-shi, Gifu · 岐阜県飛騨市',983,1,'飛騨市','ヒダシ','Hida-shi',36.238144,137.186246),(991,'212181','21','Motosu-shi',NULL,'Motosu-shi, Gifu · 岐阜県本巣市',984,1,'本巣市','モトスシ','Motosu-shi',35.483052,136.678771),(992,'212199','21','Gujo-shi',NULL,'Gujo-shi, Gifu · 岐阜県郡上市',985,1,'郡上市','グジョウシ','Gujo-shi',35.748569,136.964368),(993,'212202','21','Gero-shi',NULL,'Gero-shi, Gifu · 岐阜県下呂市',986,1,'下呂市','ゲロシ','Gero-shi',35.805879,137.244061),(994,'212211','21','Kaizu-shi',NULL,'Kaizu-shi, Gifu · 岐阜県海津市',987,1,'海津市','カイヅシ','Kaizu-shi',35.220470,136.636612),(995,'213021','21','Ginan-cho',NULL,'Ginan-cho, Gifu · 岐阜県岐南町',988,1,'岐南町','ギナンチョウ','Ginan-cho',35.389594,136.782620),(996,'213039','21','Kasamatsu-cho',NULL,'Kasamatsu-cho, Gifu · 岐阜県笠松町',989,1,'笠松町','カサマツチョウ','Kasamatsu-cho',35.367222,136.763192),(997,'213411','21','Yoro-cho',NULL,'Yoro-cho, Gifu · 岐阜県養老町',990,1,'養老町','ヨウロウチョウ','Yoro-cho',35.308411,136.561359),(998,'213616','21','Tarui-cho',NULL,'Tarui-cho, Gifu · 岐阜県垂井町',991,1,'垂井町','タルイチョウ','Tarui-cho',35.366223,136.537963),(999,'213624','21','Sekigahara-cho',NULL,'Sekigahara-cho, Gifu · 岐阜県関ケ原町',992,1,'関ケ原町','セキガハラチョウ','Sekigahara-cho',35.365523,136.466976),(1000,'213811','21','Godo-cho',NULL,'Godo-cho, Gifu · 岐阜県神戸町',993,1,'神戸町','ゴウドチョウ','Godo-cho',35.417349,136.608517),(1001,'213829','21','Wanochi-cho',NULL,'Wanochi-cho, Gifu · 岐阜県輪之内町',994,1,'輪之内町','ワノウチチョウ','Wanochi-cho',35.285113,136.637444),(1002,'213837','21','Ampachi-cho',NULL,'Ampachi-cho, Gifu · 岐阜県安八町',995,1,'安八町','アンパチチョウ','Ampachi-cho',35.335418,136.665415),(1003,'214019','21','Ibigawa-cho',NULL,'Ibigawa-cho, Gifu · 岐阜県揖斐川町',996,1,'揖斐川町','イビガワチョウ','Ibigawa-cho',35.486948,136.568200),(1004,'214035','21','Ono-cho',NULL,'Ono-cho, Gifu · 岐阜県大野町',997,1,'大野町','オオノチョウ','Ono-cho',35.470675,136.627636),(1005,'214043','21','Ikeda-cho',NULL,'Ikeda-cho, Gifu · 岐阜県池田町',998,1,'池田町','イケダチョウ','Ikeda-cho',35.442300,136.572851),(1006,'214213','21','Kitagata-cho',NULL,'Kitagata-cho, Gifu · 岐阜県北方町',999,1,'北方町','キタガタチョウ','Kitagata-cho',35.435686,136.684315),(1007,'215015','21','Sakahogi-cho',NULL,'Sakahogi-cho, Gifu · 岐阜県坂祝町',1000,1,'坂祝町','サカホギチョウ','Sakahogi-cho',35.426734,136.985370),(1008,'215023','21','Tomika-cho',NULL,'Tomika-cho, Gifu · 岐阜県富加町',1001,1,'富加町','トミカチョウ','Tomika-cho',35.484761,136.979707),(1009,'215031','21','Kawabe-cho',NULL,'Kawabe-cho, Gifu · 岐阜県川辺町',1002,1,'川辺町','カワベチョウ','Kawabe-cho',35.486548,137.070605),(1010,'215040','21','Hichiso-cho',NULL,'Hichiso-cho, Gifu · 岐阜県七宗町',1003,1,'七宗町','ヒチソウチョウ','Hichiso-cho',35.543840,137.119946),(1011,'215058','21','Yaotsu-cho',NULL,'Yaotsu-cho, Gifu · 岐阜県八百津町',1004,1,'八百津町','ヤオツチョウ','Yaotsu-cho',35.475988,137.141499),(1012,'215066','21','Shirakawa-cho',NULL,'Shirakawa-cho, Gifu · 岐阜県白川町',1005,1,'白川町','シラカワチョウ','Shirakawa-cho',35.581862,137.187821),(1013,'215074','21','Higashishirakawa-mura',NULL,'Higashishirakawa-mura, Gifu · 岐阜県東白川村',1006,1,'東白川村','ヒガシシラカワムラ','Higashishirakawa-mura',35.642560,137.323745),(1014,'215210','21','Mitake-cho',NULL,'Mitake-cho, Gifu · 岐阜県御嵩町',1007,1,'御嵩町','ミタケチョウ','Mitake-cho',35.434493,137.130885),(1015,'216046','21','Shirakawa-mura',NULL,'Shirakawa-mura, Gifu · 岐阜県白川村',1008,1,'白川村','シラカワムラ','Shirakawa-mura',36.270904,136.898542),(1016,'221015','22','Aoi-ku, Shizuoka-shi',NULL,'Aoi-ku, Shizuoka-shi, Shizuoka · 静岡県静岡市葵区',1009,1,'静岡市葵区','シズオカシアオイク','Aoi-ku, Shizuoka-shi',34.975135,138.383259),(1017,'221023','22','Suruga-ku, Shizuoka-shi',NULL,'Suruga-ku, Shizuoka-shi, Shizuoka · 静岡県静岡市駿河区',1010,1,'静岡市駿河区','シズオカシスルガク','Suruga-ku, Shizuoka-shi',34.960745,138.404047),(1018,'221031','22','Shimizu-ku, Shizuoka-shi',NULL,'Shimizu-ku, Shizuoka-shi, Shizuoka · 静岡県静岡市清水区',1011,1,'静岡市清水区','シズオカシシミズク','Shimizu-ku, Shizuoka-shi',35.015730,138.489603),(1019,'221384','22','Chuo-ku, Hamamatsu-shi',NULL,'Chuo-ku, Hamamatsu-shi, Shizuoka · 静岡県浜松市中央区',1012,1,'浜松市中央区','ハママツシチュウオウク','Chuo-ku, Hamamatsu-shi',34.710865,137.726117),(1020,'221392','22','Hamana-ku, Hamamatsu-shi',NULL,'Hamana-ku, Hamamatsu-shi, Shizuoka · 静岡県浜松市浜名区',1013,1,'浜松市浜名区','ハママツシハマナク','Hamana-ku, Hamamatsu-shi',34.791607,137.783447),(1021,'221406','22','Tenryu-ku, Hamamatsu-shi',NULL,'Tenryu-ku, Hamamatsu-shi, Shizuoka · 静岡県浜松市天竜区',1014,1,'浜松市天竜区','ハママツシテンリュウク','Tenryu-ku, Hamamatsu-shi',34.872686,137.815924),(1022,'222038','22','Numazu-shi',NULL,'Numazu-shi, Shizuoka · 静岡県沼津市',1015,1,'沼津市','ヌマヅシ','Numazu-shi',35.095723,138.863337),(1023,'222054','22','Atami-shi',NULL,'Atami-shi, Shizuoka · 静岡県熱海市',1016,1,'熱海市','アタミシ','Atami-shi',35.095987,139.071546),(1024,'222062','22','Mishima-shi',NULL,'Mishima-shi, Shizuoka · 静岡県三島市',1017,1,'三島市','ミシマシ','Mishima-shi',35.118483,138.918501),(1025,'222071','22','Fujinomiya-shi',NULL,'Fujinomiya-shi, Shizuoka · 静岡県富士宮市',1018,1,'富士宮市','フジノミヤシ','Fujinomiya-shi',35.221983,138.621656),(1026,'222089','22','Ito-shi',NULL,'Ito-shi, Shizuoka · 静岡県伊東市',1019,1,'伊東市','イトウシ','Ito-shi',34.965680,139.102048),(1027,'222097','22','Shimada-shi',NULL,'Shimada-shi, Shizuoka · 静岡県島田市',1020,1,'島田市','シマダシ','Shimada-shi',34.836417,138.176055),(1028,'222101','22','Fuji-shi',NULL,'Fuji-shi, Shizuoka · 静岡県富士市',1021,1,'富士市','フジシ','Fuji-shi',35.161386,138.676311),(1029,'222119','22','Iwata-shi',NULL,'Iwata-shi, Shizuoka · 静岡県磐田市',1022,1,'磐田市','イワタシ','Iwata-shi',34.717881,137.851511),(1030,'222127','22','Yaizu-shi',NULL,'Yaizu-shi, Shizuoka · 静岡県焼津市',1023,1,'焼津市','ヤイヅシ','Yaizu-shi',34.866906,138.323260),(1031,'222135','22','Kakegawa-shi',NULL,'Kakegawa-shi, Shizuoka · 静岡県掛川市',1024,1,'掛川市','カケガワシ','Kakegawa-shi',34.768717,137.998403),(1032,'222143','22','Fujieda-shi',NULL,'Fujieda-shi, Shizuoka · 静岡県藤枝市',1025,1,'藤枝市','フジエダシ','Fujieda-shi',34.867349,138.257548),(1033,'222151','22','Gotemba-shi',NULL,'Gotemba-shi, Shizuoka · 静岡県御殿場市',1026,1,'御殿場市','ゴテンバシ','Gotemba-shi',35.308615,138.934506),(1034,'222160','22','Fukuroi-shi',NULL,'Fukuroi-shi, Shizuoka · 静岡県袋井市',1027,1,'袋井市','フクロイシ','Fukuroi-shi',34.750160,137.924624),(1035,'222194','22','Shimoda-shi',NULL,'Shimoda-shi, Shizuoka · 静岡県下田市',1028,1,'下田市','シモダシ','Shimoda-shi',34.679536,138.945316),(1036,'222208','22','Susono-shi',NULL,'Susono-shi, Shizuoka · 静岡県裾野市',1029,1,'裾野市','スソノシ','Susono-shi',35.173891,138.906724),(1037,'222216','22','Kosai-shi',NULL,'Kosai-shi, Shizuoka · 静岡県湖西市',1030,1,'湖西市','コサイシ','Kosai-shi',34.718474,137.531617),(1038,'222224','22','Izu-shi',NULL,'Izu-shi, Shizuoka · 静岡県伊豆市',1031,1,'伊豆市','イズシ','Izu-shi',34.976591,138.946715),(1039,'222232','22','Omaezaki-shi',NULL,'Omaezaki-shi, Shizuoka · 静岡県御前崎市',1032,1,'御前崎市','オマエザキシ','Omaezaki-shi',34.637977,138.128117),(1040,'222241','22','Kikugawa-shi',NULL,'Kikugawa-shi, Shizuoka · 静岡県菊川市',1033,1,'菊川市','キクガワシ','Kikugawa-shi',34.757721,138.084539),(1041,'222259','22','Izunokuni-shi',NULL,'Izunokuni-shi, Shizuoka · 静岡県伊豆の国市',1034,1,'伊豆の国市','イズノクニシ','Izunokuni-shi',35.027713,138.928885),(1042,'222267','22','Makinohara-shi',NULL,'Makinohara-shi, Shizuoka · 静岡県牧之原市',1035,1,'牧之原市','マキノハラシ','Makinohara-shi',34.740059,138.224641),(1043,'223018','22','Higashiizu-cho',NULL,'Higashiizu-cho, Shizuoka · 静岡県東伊豆町',1036,1,'東伊豆町','ヒガシイズチョウ','Higashiizu-cho',34.772816,139.041265),(1044,'223026','22','Kawazu-cho',NULL,'Kawazu-cho, Shizuoka · 静岡県河津町',1037,1,'河津町','カワヅチョウ','Kawazu-cho',34.757018,138.987622),(1045,'223042','22','Minamiizu-cho',NULL,'Minamiizu-cho, Shizuoka · 静岡県南伊豆町',1038,1,'南伊豆町','ミナミイズチョウ','Minamiizu-cho',34.651089,138.858533),(1046,'223051','22','Matsuzaki-cho',NULL,'Matsuzaki-cho, Shizuoka · 静岡県松崎町',1039,1,'松崎町','マツザキチョウ','Matsuzaki-cho',34.752763,138.778757),(1047,'223069','22','Nishiizu-cho',NULL,'Nishiizu-cho, Shizuoka · 静岡県西伊豆町',1040,1,'西伊豆町','ニシイズチョウ','Nishiizu-cho',34.771693,138.775334),(1048,'223255','22','Kannami-cho',NULL,'Kannami-cho, Shizuoka · 静岡県函南町',1041,1,'函南町','カンナミチョウ','Kannami-cho',35.088937,138.953348),(1049,'223417','22','Shimizu-cho',NULL,'Shimizu-cho, Shizuoka · 静岡県清水町',1042,1,'清水町','シミズチョウ','Shimizu-cho',35.099015,138.902720),(1050,'223425','22','Nagaizumi-cho',NULL,'Nagaizumi-cho, Shizuoka · 静岡県長泉町',1043,1,'長泉町','ナガイズミチョウ','Nagaizumi-cho',35.137712,138.897258),(1051,'223441','22','Oyama-cho',NULL,'Oyama-cho, Shizuoka · 静岡県小山町',1044,1,'小山町','オヤマチョウ','Oyama-cho',35.360110,138.987296),(1052,'224243','22','Yoshida-cho',NULL,'Yoshida-cho, Shizuoka · 静岡県吉田町',1045,1,'吉田町','ヨシダチョウ','Yoshida-cho',34.770870,138.251943),(1053,'224294','22','Kawanehon-cho',NULL,'Kawanehon-cho, Shizuoka · 静岡県川根本町',1046,1,'川根本町','カワネホンチョウ','Kawanehon-cho',35.046822,138.081695),(1054,'224618','22','Mori-machi',NULL,'Mori-machi, Shizuoka · 静岡県森町',1047,1,'森町','モリマチ','Mori-machi',34.835601,137.927088),(1055,'231011','23','Chikusa-ku, Nagoya-shi',NULL,'Chikusa-ku, Nagoya-shi, Aichi · 愛知県名古屋市千種区',1048,1,'名古屋市千種区','ナゴヤシチクサク','Chikusa-ku, Nagoya-shi',35.162635,136.977620),(1056,'231029','23','Higashi-ku, Nagoya-shi',NULL,'Higashi-ku, Nagoya-shi, Aichi · 愛知県名古屋市東区',1049,1,'名古屋市東区','ナゴヤシヒガシク','Higashi-ku, Nagoya-shi',35.179314,136.926043),(1057,'231037','23','Kita-ku, Nagoya-shi',NULL,'Kita-ku, Nagoya-shi, Aichi · 愛知県名古屋市北区',1050,1,'名古屋市北区','ナゴヤシキタク','Kita-ku, Nagoya-shi',35.194169,136.911558),(1058,'231045','23','Nishi-ku, Nagoya-shi',NULL,'Nishi-ku, Nagoya-shi, Aichi · 愛知県名古屋市西区',1051,1,'名古屋市西区','ナゴヤシニシク','Nishi-ku, Nagoya-shi',35.189109,136.890058),(1059,'231053','23','Nakamura-ku, Nagoya-shi',NULL,'Nakamura-ku, Nagoya-shi, Aichi · 愛知県名古屋市中村区',1052,1,'名古屋市中村区','ナゴヤシナカムラク','Nakamura-ku, Nagoya-shi',35.176830,136.868423),(1060,'231061','23','Naka-ku, Nagoya-shi',NULL,'Naka-ku, Nagoya-shi, Aichi · 愛知県名古屋市中区',1053,1,'名古屋市中区','ナゴヤシナカク','Naka-ku, Nagoya-shi',35.168607,136.910156),(1061,'231070','23','Showa-ku, Nagoya-shi',NULL,'Showa-ku, Nagoya-shi, Aichi · 愛知県名古屋市昭和区',1054,1,'名古屋市昭和区','ナゴヤシショウワク','Showa-ku, Nagoya-shi',35.150216,136.934166),(1062,'231088','23','Mizuho-ku, Nagoya-shi',NULL,'Mizuho-ku, Nagoya-shi, Aichi · 愛知県名古屋市瑞穂区',1055,1,'名古屋市瑞穂区','ナゴヤシミズホク','Mizuho-ku, Nagoya-shi',35.131439,136.934953),(1063,'231096','23','Atsuta-ku, Nagoya-shi',NULL,'Atsuta-ku, Nagoya-shi, Aichi · 愛知県名古屋市熱田区',1056,1,'名古屋市熱田区','ナゴヤシアツタク','Atsuta-ku, Nagoya-shi',35.128372,136.910317),(1064,'231100','23','Nakagawa-ku, Nagoya-shi',NULL,'Nakagawa-ku, Nagoya-shi, Aichi · 愛知県名古屋市中川区',1057,1,'名古屋市中川区','ナゴヤシナカガワク','Nakagawa-ku, Nagoya-shi',35.141539,136.854816),(1065,'231118','23','Minato-ku, Nagoya-shi',NULL,'Minato-ku, Nagoya-shi, Aichi · 愛知県名古屋市港区',1058,1,'名古屋市港区','ナゴヤシミナトク','Minato-ku, Nagoya-shi',35.107792,136.885553),(1066,'231126','23','Minami-ku, Nagoya-shi',NULL,'Minami-ku, Nagoya-shi, Aichi · 愛知県名古屋市南区',1059,1,'名古屋市南区','ナゴヤシミナミク','Minami-ku, Nagoya-shi',35.095127,136.931212),(1067,'231134','23','Moriyama-ku, Nagoya-shi',NULL,'Moriyama-ku, Nagoya-shi, Aichi · 愛知県名古屋市守山区',1060,1,'名古屋市守山区','ナゴヤシモリヤマク','Moriyama-ku, Nagoya-shi',35.203343,136.976330),(1068,'231142','23','Midori-ku, Nagoya-shi',NULL,'Midori-ku, Nagoya-shi, Aichi · 愛知県名古屋市緑区',1061,1,'名古屋市緑区','ナゴヤシミドリク','Midori-ku, Nagoya-shi',35.070721,136.952602),(1069,'231151','23','Meito-ku, Nagoya-shi',NULL,'Meito-ku, Nagoya-shi, Aichi · 愛知県名古屋市名東区',1062,1,'名古屋市名東区','ナゴヤシメイトウク','Meito-ku, Nagoya-shi',35.175862,137.010239),(1070,'231169','23','Tempaku-ku, Nagoya-shi',NULL,'Tempaku-ku, Nagoya-shi, Aichi · 愛知県名古屋市天白区',1063,1,'名古屋市天白区','ナゴヤシテンパクク','Tempaku-ku, Nagoya-shi',35.122740,136.975049),(1071,'232017','23','Toyohashi-shi',NULL,'Toyohashi-shi, Aichi · 愛知県豊橋市',1064,1,'豊橋市','トヨハシシ','Toyohashi-shi',34.769199,137.391469),(1072,'232025','23','Okazaki-shi',NULL,'Okazaki-shi, Aichi · 愛知県岡崎市',1065,1,'岡崎市','オカザキシ','Okazaki-shi',34.954809,137.172999),(1073,'232033','23','Ichinomiya-shi',NULL,'Ichinomiya-shi, Aichi · 愛知県一宮市',1066,1,'一宮市','イチノミヤシ','Ichinomiya-shi',35.304013,136.802344),(1074,'232041','23','Seto-shi',NULL,'Seto-shi, Aichi · 愛知県瀬戸市',1067,1,'瀬戸市','セトシ','Seto-shi',35.223620,137.084117),(1075,'232050','23','Handa-shi',NULL,'Handa-shi, Aichi · 愛知県半田市',1068,1,'半田市','ハンダシ','Handa-shi',34.892626,136.937850),(1076,'232068','23','Kasugai-shi',NULL,'Kasugai-shi, Aichi · 愛知県春日井市',1069,1,'春日井市','カスガイシ','Kasugai-shi',35.247664,136.972232),(1077,'232076','23','Toyokawa-shi',NULL,'Toyokawa-shi, Aichi · 愛知県豊川市',1070,1,'豊川市','トヨカワシ','Toyokawa-shi',34.826784,137.375578),(1078,'232084','23','Tsushima-shi',NULL,'Tsushima-shi, Aichi · 愛知県津島市',1071,1,'津島市','ツシマシ','Tsushima-shi',35.177040,136.741236),(1079,'232092','23','Hekinan-shi',NULL,'Hekinan-shi, Aichi · 愛知県碧南市',1072,1,'碧南市','ヘキナンシ','Hekinan-shi',34.884782,136.993362),(1080,'232106','23','Kariya-shi',NULL,'Kariya-shi, Aichi · 愛知県刈谷市',1073,1,'刈谷市','カリヤシ','Kariya-shi',34.988864,137.002060),(1081,'232114','23','Toyota-shi',NULL,'Toyota-shi, Aichi · 愛知県豊田市',1074,1,'豊田市','トヨタシ','Toyota-shi',35.082345,137.156224),(1082,'232122','23','Anjo-shi',NULL,'Anjo-shi, Aichi · 愛知県安城市',1075,1,'安城市','アンジョウシ','Anjo-shi',34.958644,137.080297),(1083,'232131','23','Nishio-shi',NULL,'Nishio-shi, Aichi · 愛知県西尾市',1076,1,'西尾市','ニシオシ','Nishio-shi',34.862009,137.061741),(1084,'232149','23','Gamagori-shi',NULL,'Gamagori-shi, Aichi · 愛知県蒲郡市',1077,1,'蒲郡市','ガマゴオリシ','Gamagori-shi',34.826439,137.219653),(1085,'232157','23','Inuyama-shi',NULL,'Inuyama-shi, Aichi · 愛知県犬山市',1078,1,'犬山市','イヌヤマシ','Inuyama-shi',35.378641,136.944625),(1086,'232165','23','Tokoname-shi',NULL,'Tokoname-shi, Aichi · 愛知県常滑市',1079,1,'常滑市','トコナメシ','Tokoname-shi',34.896051,136.854495),(1087,'232173','23','Konan-shi',NULL,'Konan-shi, Aichi · 愛知県江南市',1080,1,'江南市','コウナンシ','Konan-shi',35.331953,136.870453),(1088,'232190','23','Komaki-shi',NULL,'Komaki-shi, Aichi · 愛知県小牧市',1081,1,'小牧市','コマキシ','Komaki-shi',35.291141,136.911971),(1089,'232203','23','Inazawa-shi',NULL,'Inazawa-shi, Aichi · 愛知県稲沢市',1082,1,'稲沢市','イナザワシ','Inazawa-shi',35.248135,136.780216),(1090,'232211','23','Shinshiro-shi',NULL,'Shinshiro-shi, Aichi · 愛知県新城市',1083,1,'新城市','シンシロシ','Shinshiro-shi',34.899025,137.497850),(1091,'232220','23','Tokai-shi',NULL,'Tokai-shi, Aichi · 愛知県東海市',1084,1,'東海市','トウカイシ','Tokai-shi',35.022976,136.902229),(1092,'232238','23','Obu-shi',NULL,'Obu-shi, Aichi · 愛知県大府市',1085,1,'大府市','オオブシ','Obu-shi',35.011611,136.963868),(1093,'232246','23','Chita-shi',NULL,'Chita-shi, Aichi · 愛知県知多市',1086,1,'知多市','チタシ','Chita-shi',34.996533,136.864755),(1094,'232254','23','Chiryu-shi',NULL,'Chiryu-shi, Aichi · 愛知県知立市',1087,1,'知立市','チリュウシ','Chiryu-shi',35.001285,137.050843),(1095,'232262','23','Owariasahi-shi',NULL,'Owariasahi-shi, Aichi · 愛知県尾張旭市',1088,1,'尾張旭市','オワリアサヒシ','Owariasahi-shi',35.216568,137.035412),(1096,'232271','23','Takahama-shi',NULL,'Takahama-shi, Aichi · 愛知県高浜市',1089,1,'高浜市','タカハマシ','Takahama-shi',34.927630,136.987660),(1097,'232289','23','Iwakura-shi',NULL,'Iwakura-shi, Aichi · 愛知県岩倉市',1090,1,'岩倉市','イワクラシ','Iwakura-shi',35.279433,136.871428),(1098,'232297','23','Toyoake-shi',NULL,'Toyoake-shi, Aichi · 愛知県豊明市',1091,1,'豊明市','トヨアケシ','Toyoake-shi',35.053756,137.013124),(1099,'232301','23','Nisshin-shi',NULL,'Nisshin-shi, Aichi · 愛知県日進市',1092,1,'日進市','ニッシンシ','Nisshin-shi',35.132018,137.039414),(1100,'232319','23','Tahara-shi',NULL,'Tahara-shi, Aichi · 愛知県田原市',1093,1,'田原市','タハラシ','Tahara-shi',34.668972,137.263830),(1101,'232327','23','Aisai-shi',NULL,'Aisai-shi, Aichi · 愛知県愛西市',1094,1,'愛西市','アイサイシ','Aisai-shi',35.152802,136.728217),(1102,'232335','23','Kiyosu-shi',NULL,'Kiyosu-shi, Aichi · 愛知県清須市',1095,1,'清須市','キヨスシ','Kiyosu-shi',35.199848,136.852934),(1103,'232343','23','Kitanagoya-shi',NULL,'Kitanagoya-shi, Aichi · 愛知県北名古屋市',1096,1,'北名古屋市','キタナゴヤシ','Kitanagoya-shi',35.245657,136.865961),(1104,'232351','23','Yatomi-shi',NULL,'Yatomi-shi, Aichi · 愛知県弥富市',1097,1,'弥富市','ヤトミシ','Yatomi-shi',35.110118,136.724720),(1105,'232360','23','Miyoshi-shi',NULL,'Miyoshi-shi, Aichi · 愛知県みよし市',1098,1,'みよし市','ミヨシシ','Miyoshi-shi',35.089699,137.074728),(1106,'232378','23','Ama-shi',NULL,'Ama-shi, Aichi · 愛知県あま市',1099,1,'あま市','アマシ','Ama-shi',35.188266,136.803703),(1107,'232386','23','Nagakute-shi',NULL,'Nagakute-shi, Aichi · 愛知県長久手市',1100,1,'長久手市','ナガクテシ','Nagakute-shi',35.184120,137.048631),(1108,'233021','23','Togo-cho',NULL,'Togo-cho, Aichi · 愛知県東郷町',1101,1,'東郷町','トウゴウチョウ','Togo-cho',35.096790,137.052623),(1109,'233421','23','Toyoyama-cho',NULL,'Toyoyama-cho, Aichi · 愛知県豊山町',1102,1,'豊山町','トヨヤマチョウ','Toyoyama-cho',35.250462,136.911977),(1110,'233617','23','Oguchi-cho',NULL,'Oguchi-cho, Aichi · 愛知県大口町',1103,1,'大口町','オオグチチョウ','Oguchi-cho',35.332444,136.907694),(1111,'233625','23','Fuso-cho',NULL,'Fuso-cho, Aichi · 愛知県扶桑町',1104,1,'扶桑町','フソウチョウ','Fuso-cho',35.359140,136.913079),(1112,'234249','23','Oharu-cho',NULL,'Oharu-cho, Aichi · 愛知県大治町',1105,1,'大治町','オオハルチョウ','Oharu-cho',35.175089,136.820100),(1113,'234257','23','Kanie-cho',NULL,'Kanie-cho, Aichi · 愛知県蟹江町',1106,1,'蟹江町','カニエチョウ','Kanie-cho',35.132339,136.786679),(1114,'234273','23','Tobishima-mura',NULL,'Tobishima-mura, Aichi · 愛知県飛島村',1107,1,'飛島村','トビシマムラ','Tobishima-mura',35.078804,136.791157),(1115,'234419','23','Agui-cho',NULL,'Agui-cho, Aichi · 愛知県阿久比町',1108,1,'阿久比町','アグイチョウ','Agui-cho',34.932569,136.915512),(1116,'234427','23','Higashiura-cho',NULL,'Higashiura-cho, Aichi · 愛知県東浦町',1109,1,'東浦町','ヒガシウラチョウ','Higashiura-cho',34.977139,136.965569),(1117,'234451','23','Minamichita-cho',NULL,'Minamichita-cho, Aichi · 愛知県南知多町',1110,1,'南知多町','ミナミチタチョウ','Minamichita-cho',34.715169,136.929934),(1118,'234460','23','Mihama-cho',NULL,'Mihama-cho, Aichi · 愛知県美浜町',1111,1,'美浜町','ミハマチョウ','Mihama-cho',34.778843,136.908251),(1119,'234478','23','Taketoyo-cho',NULL,'Taketoyo-cho, Aichi · 愛知県武豊町',1112,1,'武豊町','タケトヨチョウ','Taketoyo-cho',34.850970,136.914937),(1120,'235016','23','Kota-cho',NULL,'Kota-cho, Aichi · 愛知県幸田町',1113,1,'幸田町','コウタチョウ','Kota-cho',34.864425,137.165667),(1121,'235610','23','Shitara-cho',NULL,'Shitara-cho, Aichi · 愛知県設楽町',1114,1,'設楽町','シタラチョウ','Shitara-cho',35.097298,137.571225),(1122,'235628','23','Toei-cho',NULL,'Toei-cho, Aichi · 愛知県東栄町',1115,1,'東栄町','トウエイチョウ','Toei-cho',35.076930,137.697833),(1123,'235636','23','Toyone-mura',NULL,'Toyone-mura, Aichi · 愛知県豊根村',1116,1,'豊根村','トヨネムラ','Toyone-mura',35.146394,137.719871),(1124,'242012','24','Tsu-shi',NULL,'Tsu-shi, Mie · 三重県津市',1117,1,'津市','ツシ','Tsu-shi',34.718563,136.505443),(1125,'242021','24','Yokkaichi-shi',NULL,'Yokkaichi-shi, Mie · 三重県四日市市',1118,1,'四日市市','ヨッカイチシ','Yokkaichi-shi',34.965092,136.624427),(1126,'242039','24','Ise-shi',NULL,'Ise-shi, Mie · 三重県伊勢市',1119,1,'伊勢市','イセシ','Ise-shi',34.487506,136.709286),(1127,'242047','24','Matsusaka-shi',NULL,'Matsusaka-shi, Mie · 三重県松阪市',1120,1,'松阪市','マツサカシ','Matsusaka-shi',34.577974,136.527595),(1128,'242055','24','Kuwana-shi',NULL,'Kuwana-shi, Mie · 三重県桑名市',1121,1,'桑名市','クワナシ','Kuwana-shi',35.062288,136.683539),(1129,'242071','24','Suzuka-shi',NULL,'Suzuka-shi, Mie · 三重県鈴鹿市',1122,1,'鈴鹿市','スズカシ','Suzuka-shi',34.881866,136.584185),(1130,'242080','24','Nabari-shi',NULL,'Nabari-shi, Mie · 三重県名張市',1123,1,'名張市','ナバリシ','Nabari-shi',34.627662,136.108299),(1131,'242098','24','Owase-shi',NULL,'Owase-shi, Mie · 三重県尾鷲市',1124,1,'尾鷲市','オワセシ','Owase-shi',34.070799,136.190998),(1132,'242101','24','Kameyama-shi',NULL,'Kameyama-shi, Mie · 三重県亀山市',1125,1,'亀山市','カメヤマシ','Kameyama-shi',34.855755,136.451645),(1133,'242110','24','Toba-shi',NULL,'Toba-shi, Mie · 三重県鳥羽市',1126,1,'鳥羽市','トバシ','Toba-shi',34.481432,136.843378),(1134,'242128','24','Kumano-shi',NULL,'Kumano-shi, Mie · 三重県熊野市',1127,1,'熊野市','クマノシ','Kumano-shi',33.888620,136.100288),(1135,'242144','24','Inabe-shi',NULL,'Inabe-shi, Mie · 三重県いなべ市',1128,1,'いなべ市','イナベシ','Inabe-shi',35.158372,136.516784),(1136,'242152','24','Shima-shi',NULL,'Shima-shi, Mie · 三重県志摩市',1129,1,'志摩市','シマシ','Shima-shi',34.328218,136.829655),(1137,'242161','24','Iga-shi',NULL,'Iga-shi, Mie · 三重県伊賀市',1130,1,'伊賀市','イガシ','Iga-shi',34.749838,136.142305),(1138,'243035','24','Kisosaki-cho',NULL,'Kisosaki-cho, Mie · 三重県木曽岬町',1131,1,'木曽岬町','キソサキチョウ','Kisosaki-cho',35.075958,136.731353),(1139,'243248','24','Toin-cho',NULL,'Toin-cho, Mie · 三重県東員町',1132,1,'東員町','トウインチョウ','Toin-cho',35.074125,136.583756),(1140,'243418','24','Komono-cho',NULL,'Komono-cho, Mie · 三重県菰野町',1133,1,'菰野町','コモノチョウ','Komono-cho',35.020001,136.507346),(1141,'243434','24','Asahi-cho',NULL,'Asahi-cho, Mie · 三重県朝日町',1134,1,'朝日町','アサヒチョウ','Asahi-cho',35.034221,136.664366),(1142,'243442','24','Kawagoe-cho',NULL,'Kawagoe-cho, Mie · 三重県川越町',1135,1,'川越町','カワゴエチョウ','Kawagoe-cho',35.022950,136.673971),(1143,'244414','24','Taki-cho',NULL,'Taki-cho, Mie · 三重県多気町',1136,1,'多気町','タキチョウ','Taki-cho',34.496156,136.546149),(1144,'244422','24','Meiwa-cho',NULL,'Meiwa-cho, Mie · 三重県明和町',1137,1,'明和町','メイワチョウ','Meiwa-cho',34.547528,136.623251),(1145,'244431','24','Odai-cho',NULL,'Odai-cho, Mie · 三重県大台町',1138,1,'大台町','オオダイチョウ','Odai-cho',34.393370,136.407876),(1146,'244619','24','Tamaki-cho',NULL,'Tamaki-cho, Mie · 三重県玉城町',1139,1,'玉城町','タマキチョウ','Tamaki-cho',34.490276,136.630932),(1147,'244708','24','Watarai-cho',NULL,'Watarai-cho, Mie · 三重県度会町',1140,1,'度会町','ワタライチョウ','Watarai-cho',34.438900,136.622510),(1148,'244716','24','Taiki-cho',NULL,'Taiki-cho, Mie · 三重県大紀町',1141,1,'大紀町','タイキチョウ','Taiki-cho',34.358023,136.415798),(1149,'244724','24','Minamiise-cho',NULL,'Minamiise-cho, Mie · 三重県南伊勢町',1142,1,'南伊勢町','ミナミイセチョウ','Minamiise-cho',34.352059,136.703650),(1150,'245437','24','Kihoku-cho',NULL,'Kihoku-cho, Mie · 三重県紀北町',1143,1,'紀北町','キホクチョウ','Kihoku-cho',34.211199,136.336852),(1151,'245615','24','Mihama-cho',NULL,'Mihama-cho, Mie · 三重県御浜町',1144,1,'御浜町','ミハマチョウ','Mihama-cho',33.814505,136.048677),(1152,'245623','24','Kiho-cho',NULL,'Kiho-cho, Mie · 三重県紀宝町',1145,1,'紀宝町','キホウチョウ','Kiho-cho',33.733870,136.009715),(1153,'252018','25','Otsu-shi',NULL,'Otsu-shi, Shiga · 滋賀県大津市',1146,1,'大津市','オオツシ','Otsu-shi',35.018357,135.854666),(1154,'252026','25','Hikone-shi',NULL,'Hikone-shi, Shiga · 滋賀県彦根市',1147,1,'彦根市','ヒコネシ','Hikone-shi',35.274464,136.259623),(1155,'252034','25','Nagahama-shi',NULL,'Nagahama-shi, Shiga · 滋賀県長浜市',1148,1,'長浜市','ナガハマシ','Nagahama-shi',35.380758,136.278588),(1156,'252042','25','Omihachiman-shi',NULL,'Omihachiman-shi, Shiga · 滋賀県近江八幡市',1149,1,'近江八幡市','オウミハチマンシ','Omihachiman-shi',35.128214,136.097846),(1157,'252069','25','Kusatsu-shi',NULL,'Kusatsu-shi, Shiga · 滋賀県草津市',1150,1,'草津市','クサツシ','Kusatsu-shi',35.013123,135.959994),(1158,'252077','25','Moriyama-shi',NULL,'Moriyama-shi, Shiga · 滋賀県守山市',1151,1,'守山市','モリヤマシ','Moriyama-shi',35.058663,135.994020),(1159,'252085','25','Ritto-shi',NULL,'Ritto-shi, Shiga · 滋賀県栗東市',1152,1,'栗東市','リットウシ','Ritto-shi',35.021614,135.997993),(1160,'252093','25','Koka-shi',NULL,'Koka-shi, Shiga · 滋賀県甲賀市',1153,1,'甲賀市','コウカシ','Koka-shi',34.966120,136.167084),(1161,'252107','25','Yasu-shi',NULL,'Yasu-shi, Shiga · 滋賀県野洲市',1154,1,'野洲市','ヤスシ','Yasu-shi',35.067470,136.025873),(1162,'252115','25','Konan-shi',NULL,'Konan-shi, Shiga · 滋賀県湖南市',1155,1,'湖南市','コナンシ','Konan-shi',35.004106,136.084693),(1163,'252123','25','Takashima-shi',NULL,'Takashima-shi, Shiga · 滋賀県高島市',1156,1,'高島市','タカシマシ','Takashima-shi',35.353052,136.035785),(1164,'252131','25','Higashiomi-shi',NULL,'Higashiomi-shi, Shiga · 滋賀県東近江市',1157,1,'東近江市','ヒガシオウミシ','Higashiomi-shi',35.112603,136.207620),(1165,'252140','25','Maibara-shi',NULL,'Maibara-shi, Shiga · 滋賀県米原市',1158,1,'米原市','マイバラシ','Maibara-shi',35.315073,136.291414),(1166,'253839','25','Hino-cho',NULL,'Hino-cho, Shiga · 滋賀県日野町',1159,1,'日野町','ヒノチョウ','Hino-cho',35.018018,136.246023),(1167,'253847','25','Ryuo-cho',NULL,'Ryuo-cho, Shiga · 滋賀県竜王町',1160,1,'竜王町','リュウオウチョウ','Ryuo-cho',35.060750,136.124438),(1168,'254258','25','Aisho-cho',NULL,'Aisho-cho, Shiga · 滋賀県愛荘町',1161,1,'愛荘町','アイショウチョウ','Aisho-cho',35.168834,136.212306),(1169,'254410','25','Toyosato-cho',NULL,'Toyosato-cho, Shiga · 滋賀県豊郷町',1162,1,'豊郷町','トヨサトチョウ','Toyosato-cho',35.200382,136.229974),(1170,'254428','25','Kora-cho',NULL,'Kora-cho, Shiga · 滋賀県甲良町',1163,1,'甲良町','コウラチョウ','Kora-cho',35.204224,136.261350),(1171,'254436','25','Taga-cho',NULL,'Taga-cho, Shiga · 滋賀県多賀町',1164,1,'多賀町','タガチョウ','Taga-cho',35.222035,136.292199),(1172,'261017','26','Kita-ku, Kyoto-shi',NULL,'Kita-ku, Kyoto-shi, Kyoto · 京都府京都市北区',1165,1,'京都市北区','キョウトシキタク','Kita-ku, Kyoto-shi',35.040970,135.753991),(1173,'261025','26','Kamigyo-ku, Kyoto-shi',NULL,'Kamigyo-ku, Kyoto-shi, Kyoto · 京都府京都市上京区',1166,1,'京都市上京区','キョウトシカミギョウク','Kamigyo-ku, Kyoto-shi',35.029530,135.756697),(1174,'261033','26','Sakyo-ku, Kyoto-shi',NULL,'Sakyo-ku, Kyoto-shi, Kyoto · 京都府京都市左京区',1167,1,'京都市左京区','キョウトシサキョウク','Sakyo-ku, Kyoto-shi',35.048580,135.778575),(1175,'261041','26','Nakagyo-ku, Kyoto-shi',NULL,'Nakagyo-ku, Kyoto-shi, Kyoto · 京都府京都市中京区',1168,1,'京都市中京区','キョウトシナカギョウク','Nakagyo-ku, Kyoto-shi',35.010404,135.751433),(1176,'261050','26','Higashiyama-ku, Kyoto-shi',NULL,'Higashiyama-ku, Kyoto-shi, Kyoto · 京都府京都市東山区',1169,1,'京都市東山区','キョウトシヒガシヤマク','Higashiyama-ku, Kyoto-shi',34.997082,135.776282),(1177,'261068','26','Shimogyo-ku, Kyoto-shi',NULL,'Shimogyo-ku, Kyoto-shi, Kyoto · 京都府京都市下京区',1170,1,'京都市下京区','キョウトシシモギョウク','Shimogyo-ku, Kyoto-shi',34.987560,135.755469),(1178,'261076','26','Minami-ku, Kyoto-shi',NULL,'Minami-ku, Kyoto-shi, Kyoto · 京都府京都市南区',1171,1,'京都市南区','キョウトシミナミク','Minami-ku, Kyoto-shi',34.976756,135.746418),(1179,'261084','26','Ukyo-ku, Kyoto-shi',NULL,'Ukyo-ku, Kyoto-shi, Kyoto · 京都府京都市右京区',1172,1,'京都市右京区','キョウトシウキョウク','Ukyo-ku, Kyoto-shi',35.010116,135.715996),(1180,'261092','26','Fushimi-ku, Kyoto-shi',NULL,'Fushimi-ku, Kyoto-shi, Kyoto · 京都府京都市伏見区',1173,1,'京都市伏見区','キョウトシフシミク','Fushimi-ku, Kyoto-shi',34.935546,135.761321),(1181,'261106','26','Yamashina-ku, Kyoto-shi',NULL,'Yamashina-ku, Kyoto-shi, Kyoto · 京都府京都市山科区',1174,1,'京都市山科区','キョウトシヤマシナク','Yamashina-ku, Kyoto-shi',34.972297,135.813625),(1182,'261114','26','Nishikyo-ku, Kyoto-shi',NULL,'Nishikyo-ku, Kyoto-shi, Kyoto · 京都府京都市西京区',1175,1,'京都市西京区','キョウトシニシキョウク','Nishikyo-ku, Kyoto-shi',34.985027,135.693108),(1183,'262013','26','Fukuchiyama-shi',NULL,'Fukuchiyama-shi, Kyoto · 京都府福知山市',1176,1,'福知山市','フクチヤマシ','Fukuchiyama-shi',35.296725,135.126532),(1184,'262021','26','Maizuru-shi',NULL,'Maizuru-shi, Kyoto · 京都府舞鶴市',1177,1,'舞鶴市','マイヅルシ','Maizuru-shi',35.474797,135.385992),(1185,'262030','26','Ayabe-shi',NULL,'Ayabe-shi, Kyoto · 京都府綾部市',1178,1,'綾部市','アヤベシ','Ayabe-shi',35.298916,135.258812),(1186,'262048','26','Uji-shi',NULL,'Uji-shi, Kyoto · 京都府宇治市',1179,1,'宇治市','ウジシ','Uji-shi',34.884400,135.799780),(1187,'262056','26','Miyazu-shi',NULL,'Miyazu-shi, Kyoto · 京都府宮津市',1180,1,'宮津市','ミヤヅシ','Miyazu-shi',35.535577,135.195567),(1188,'262064','26','Kameoka-shi',NULL,'Kameoka-shi, Kyoto · 京都府亀岡市',1181,1,'亀岡市','カメオカシ','Kameoka-shi',35.013475,135.573512),(1189,'262072','26','Joyo-shi',NULL,'Joyo-shi, Kyoto · 京都府城陽市',1182,1,'城陽市','ジョウヨウシ','Joyo-shi',34.852958,135.780074),(1190,'262081','26','Muko-shi',NULL,'Muko-shi, Kyoto · 京都府向日市',1183,1,'向日市','ムコウシ','Muko-shi',34.948699,135.698319),(1191,'262099','26','Nagaokakyo-shi',NULL,'Nagaokakyo-shi, Kyoto · 京都府長岡京市',1184,1,'長岡京市','ナガオカキョウシ','Nagaokakyo-shi',34.926782,135.695685),(1192,'262102','26','Yawata-shi',NULL,'Yawata-shi, Kyoto · 京都府八幡市',1185,1,'八幡市','ヤワタシ','Yawata-shi',34.875533,135.707571),(1193,'262111','26','Kyotanabe-shi',NULL,'Kyotanabe-shi, Kyoto · 京都府京田辺市',1186,1,'京田辺市','キョウタナベシ','Kyotanabe-shi',34.814442,135.767842),(1194,'262129','26','Kyotango-shi',NULL,'Kyotango-shi, Kyoto · 京都府京丹後市',1187,1,'京丹後市','キョウタンゴシ','Kyotango-shi',35.624169,135.060997),(1195,'262137','26','Nantan-shi',NULL,'Nantan-shi, Kyoto · 京都府南丹市',1188,1,'南丹市','ナンタンシ','Nantan-shi',35.107390,135.470239),(1196,'262145','26','Kizugawa-shi',NULL,'Kizugawa-shi, Kyoto · 京都府木津川市',1189,1,'木津川市','キヅガワシ','Kizugawa-shi',34.737179,135.820058),(1197,'263036','26','Oyamazaki-cho',NULL,'Oyamazaki-cho, Kyoto · 京都府大山崎町',1190,1,'大山崎町','オオヤマザキチョウ','Oyamazaki-cho',34.902795,135.688476),(1198,'263222','26','Kumiyama-cho',NULL,'Kumiyama-cho, Kyoto · 京都府久御山町',1191,1,'久御山町','クミヤマチョウ','Kumiyama-cho',34.881502,135.732564),(1199,'263435','26','Ide-cho',NULL,'Ide-cho, Kyoto · 京都府井手町',1192,1,'井手町','イデチョウ','Ide-cho',34.800695,135.814571),(1200,'263443','26','Ujitawara-cho',NULL,'Ujitawara-cho, Kyoto · 京都府宇治田原町',1193,1,'宇治田原町','ウジタワラチョウ','Ujitawara-cho',34.844856,135.868000),(1201,'263648','26','Kasagi-cho',NULL,'Kasagi-cho, Kyoto · 京都府笠置町',1194,1,'笠置町','カサギチョウ','Kasagi-cho',34.760497,135.939380),(1202,'263656','26','Wazuka-cho',NULL,'Wazuka-cho, Kyoto · 京都府和束町',1195,1,'和束町','ワヅカチョウ','Wazuka-cho',34.795726,135.904866),(1203,'263664','26','Seika-cho',NULL,'Seika-cho, Kyoto · 京都府精華町',1196,1,'精華町','セイカチョウ','Seika-cho',34.760819,135.785686),(1204,'263672','26','Minamiyamashiro-mura',NULL,'Minamiyamashiro-mura, Kyoto · 京都府南山城村',1197,1,'南山城村','ミナミヤマシロムラ','Minamiyamashiro-mura',34.772787,135.993665),(1205,'264075','26','Kyotamba-cho',NULL,'Kyotamba-cho, Kyoto · 京都府京丹波町',1198,1,'京丹波町','キョウタンバチョウ','Kyotamba-cho',35.170002,135.419288),(1206,'264636','26','Ine-cho',NULL,'Ine-cho, Kyoto · 京都府伊根町',1199,1,'伊根町','イネチョウ','Ine-cho',35.675172,135.272853),(1207,'264652','26','Yosano-cho',NULL,'Yosano-cho, Kyoto · 京都府与謝野町',1200,1,'与謝野町','ヨサノチョウ','Yosano-cho',35.565375,135.152916),(1208,'271021','27','Miyakojima-ku, Osaka-shi',NULL,'Miyakojima-ku, Osaka-shi, Osaka · 大阪府大阪市都島区',1201,1,'大阪市都島区','オオサカシミヤコジマク','Miyakojima-ku, Osaka-shi',34.701279,135.528090),(1209,'271039','27','Fukushima-ku, Osaka-shi',NULL,'Fukushima-ku, Osaka-shi, Osaka · 大阪府大阪市福島区',1202,1,'大阪市福島区','オオサカシフクシマク','Fukushima-ku, Osaka-shi',34.692357,135.472232),(1210,'271047','27','Konohana-ku, Osaka-shi',NULL,'Konohana-ku, Osaka-shi, Osaka · 大阪府大阪市此花区',1203,1,'大阪市此花区','オオサカシコノハナク','Konohana-ku, Osaka-shi',34.683038,135.452225),(1211,'271063','27','Nishi-ku, Osaka-shi',NULL,'Nishi-ku, Osaka-shi, Osaka · 大阪府大阪市西区',1204,1,'大阪市西区','オオサカシニシク','Nishi-ku, Osaka-shi',34.676384,135.486111),(1212,'271071','27','Minato-ku, Osaka-shi',NULL,'Minato-ku, Osaka-shi, Osaka · 大阪府大阪市港区',1205,1,'大阪市港区','オオサカシミナトク','Minato-ku, Osaka-shi',34.663918,135.460611),(1213,'271080','27','Taisho-ku, Osaka-shi',NULL,'Taisho-ku, Osaka-shi, Osaka · 大阪府大阪市大正区',1206,1,'大阪市大正区','オオサカシタイショウク','Taisho-ku, Osaka-shi',34.650417,135.472694),(1214,'271098','27','Tennoji-ku, Osaka-shi',NULL,'Tennoji-ku, Osaka-shi, Osaka · 大阪府大阪市天王寺区',1207,1,'大阪市天王寺区','オオサカシテンノウジク','Tennoji-ku, Osaka-shi',34.657869,135.519370),(1215,'271110','27','Naniwa-ku, Osaka-shi',NULL,'Naniwa-ku, Osaka-shi, Osaka · 大阪府大阪市浪速区',1208,1,'大阪市浪速区','オオサカシナニワク','Naniwa-ku, Osaka-shi',34.659419,135.499550),(1216,'271136','27','Nishiyodogawa-ku, Osaka-shi',NULL,'Nishiyodogawa-ku, Osaka-shi, Osaka · 大阪府大阪市西淀川区',1209,1,'大阪市西淀川区','オオサカシニシヨドガワク','Nishiyodogawa-ku, Osaka-shi',34.711409,135.456202),(1217,'271144','27','Higashiyodogawa-ku, Osaka-shi',NULL,'Higashiyodogawa-ku, Osaka-shi, Osaka · 大阪府大阪市東淀川区',1210,1,'大阪市東淀川区','オオサカシヒガシヨドガワク','Higashiyodogawa-ku, Osaka-shi',34.741313,135.529340),(1218,'271152','27','Higashinari-ku, Osaka-shi',NULL,'Higashinari-ku, Osaka-shi, Osaka · 大阪府大阪市東成区',1211,1,'大阪市東成区','オオサカシヒガシナリク','Higashinari-ku, Osaka-shi',34.669996,135.541223),(1219,'271161','27','Ikuno-ku, Osaka-shi',NULL,'Ikuno-ku, Osaka-shi, Osaka · 大阪府大阪市生野区',1212,1,'大阪市生野区','オオサカシイクノク','Ikuno-ku, Osaka-shi',34.653745,135.534447),(1220,'271179','27','Asahi-ku, Osaka-shi',NULL,'Asahi-ku, Osaka-shi, Osaka · 大阪府大阪市旭区',1213,1,'大阪市旭区','オオサカシアサヒク','Asahi-ku, Osaka-shi',34.721266,135.544142),(1221,'271187','27','Joto-ku, Osaka-shi',NULL,'Joto-ku, Osaka-shi, Osaka · 大阪府大阪市城東区',1214,1,'大阪市城東区','オオサカシジョウトウク','Joto-ku, Osaka-shi',34.703253,135.544472),(1222,'271195','27','Abeno-ku, Osaka-shi',NULL,'Abeno-ku, Osaka-shi, Osaka · 大阪府大阪市阿倍野区',1215,1,'大阪市阿倍野区','オオサカシアベノク','Abeno-ku, Osaka-shi',34.638969,135.518499),(1223,'271209','27','Sumiyoshi-ku, Osaka-shi',NULL,'Sumiyoshi-ku, Osaka-shi, Osaka · 大阪府大阪市住吉区',1216,1,'大阪市住吉区','オオサカシスミヨシク','Sumiyoshi-ku, Osaka-shi',34.603718,135.500498),(1224,'271217','27','Higashisumiyoshi-ku, Osaka-shi',NULL,'Higashisumiyoshi-ku, Osaka-shi, Osaka · 大阪府大阪市東住吉区',1217,1,'大阪市東住吉区','オオサカシヒガシスミヨシク','Higashisumiyoshi-ku, Osaka-shi',34.622118,135.526612),(1225,'271225','27','Nishinari-ku, Osaka-shi',NULL,'Nishinari-ku, Osaka-shi, Osaka · 大阪府大阪市西成区',1218,1,'大阪市西成区','オオサカシニシナリク','Nishinari-ku, Osaka-shi',34.635058,135.494587),(1226,'271233','27','Yodogawa-ku, Osaka-shi',NULL,'Yodogawa-ku, Osaka-shi, Osaka · 大阪府大阪市淀川区',1219,1,'大阪市淀川区','オオサカシヨドガワク','Yodogawa-ku, Osaka-shi',34.721006,135.486746),(1227,'271241','27','Tsurumi-ku, Osaka-shi',NULL,'Tsurumi-ku, Osaka-shi, Osaka · 大阪府大阪市鶴見区',1220,1,'大阪市鶴見区','オオサカシツルミク','Tsurumi-ku, Osaka-shi',34.704566,135.574190),(1228,'271250','27','Suminoe-ku, Osaka-shi',NULL,'Suminoe-ku, Osaka-shi, Osaka · 大阪府大阪市住之江区',1221,1,'大阪市住之江区','オオサカシスミノエク','Suminoe-ku, Osaka-shi',34.609670,135.482717),(1229,'271268','27','Hirano-ku, Osaka-shi',NULL,'Hirano-ku, Osaka-shi, Osaka · 大阪府大阪市平野区',1222,1,'大阪市平野区','オオサカシヒラノク','Hirano-ku, Osaka-shi',34.621160,135.545999),(1230,'271276','27','Kita-ku, Osaka-shi',NULL,'Kita-ku, Osaka-shi, Osaka · 大阪府大阪市北区',1223,1,'大阪市北区','オオサカシキタク','Kita-ku, Osaka-shi',34.705581,135.510095),(1231,'271284','27','Chuo-ku, Osaka-shi',NULL,'Chuo-ku, Osaka-shi, Osaka · 大阪府大阪市中央区',1224,1,'大阪市中央区','オオサカシチュウオウク','Chuo-ku, Osaka-shi',34.681225,135.509687),(1232,'271411','27','Sakai-ku, Sakai-shi',NULL,'Sakai-ku, Sakai-shi, Osaka · 大阪府堺市堺区',1225,1,'堺市堺区','サカイシサカイク','Sakai-ku, Sakai-shi',34.573354,135.483020),(1233,'271420','27','Naka-ku, Sakai-shi',NULL,'Naka-ku, Sakai-shi, Osaka · 大阪府堺市中区',1226,1,'堺市中区','サカイシナカク','Naka-ku, Sakai-shi',34.528312,135.498724),(1234,'271438','27','Higashi-ku, Sakai-shi',NULL,'Higashi-ku, Sakai-shi, Osaka · 大阪府堺市東区',1227,1,'堺市東区','サカイシヒガシク','Higashi-ku, Sakai-shi',34.538228,135.536474),(1235,'271446','27','Nishi-ku, Sakai-shi',NULL,'Nishi-ku, Sakai-shi, Osaka · 大阪府堺市西区',1228,1,'堺市西区','サカイシニシク','Nishi-ku, Sakai-shi',34.535063,135.464014),(1236,'271454','27','Minami-ku, Sakai-shi',NULL,'Minami-ku, Sakai-shi, Osaka · 大阪府堺市南区',1229,1,'堺市南区','サカイシミナミク','Minami-ku, Sakai-shi',34.486444,135.490540),(1237,'271462','27','Kita-ku, Sakai-shi',NULL,'Kita-ku, Sakai-shi, Osaka · 大阪府堺市北区',1230,1,'堺市北区','サカイシキタク','Kita-ku, Sakai-shi',34.565478,135.517192),(1238,'271471','27','Mihara-ku, Sakai-shi',NULL,'Mihara-ku, Sakai-shi, Osaka · 大阪府堺市美原区',1231,1,'堺市美原区','サカイシミハラク','Mihara-ku, Sakai-shi',34.538483,135.559827),(1239,'272027','27','Kishiwada-shi',NULL,'Kishiwada-shi, Osaka · 大阪府岸和田市',1232,1,'岸和田市','キシワダシ','Kishiwada-shi',34.460597,135.370871),(1240,'272035','27','Toyonaka-shi',NULL,'Toyonaka-shi, Osaka · 大阪府豊中市',1233,1,'豊中市','トヨナカシ','Toyonaka-shi',34.781239,135.469889),(1241,'272043','27','Ikeda-shi',NULL,'Ikeda-shi, Osaka · 大阪府池田市',1234,1,'池田市','イケダシ','Ikeda-shi',34.821686,135.428555),(1242,'272051','27','Suita-shi',NULL,'Suita-shi, Osaka · 大阪府吹田市',1235,1,'吹田市','スイタシ','Suita-shi',34.759405,135.516799),(1243,'272060','27','Izumiotsu-shi',NULL,'Izumiotsu-shi, Osaka · 大阪府泉大津市',1236,1,'泉大津市','イズミオオツシ','Izumiotsu-shi',34.504268,135.410472),(1244,'272078','27','Takatsuki-shi',NULL,'Takatsuki-shi, Osaka · 大阪府高槻市',1237,1,'高槻市','タカツキシ','Takatsuki-shi',34.846100,135.617216),(1245,'272086','27','Kaizuka-shi',NULL,'Kaizuka-shi, Osaka · 大阪府貝塚市',1238,1,'貝塚市','カイヅカシ','Kaizuka-shi',34.437686,135.358431),(1246,'272094','27','Moriguchi-shi',NULL,'Moriguchi-shi, Osaka · 大阪府守口市',1239,1,'守口市','モリグチシ','Moriguchi-shi',34.735935,135.561714),(1247,'272108','27','Hirakata-shi',NULL,'Hirakata-shi, Osaka · 大阪府枚方市',1240,1,'枚方市','ヒラカタシ','Hirakata-shi',34.814465,135.650642),(1248,'272116','27','Ibaraki-shi',NULL,'Ibaraki-shi, Osaka · 大阪府茨木市',1241,1,'茨木市','イバラキシ','Ibaraki-shi',34.816153,135.568503),(1249,'272124','27','Yao-shi',NULL,'Yao-shi, Osaka · 大阪府八尾市',1242,1,'八尾市','ヤオシ','Yao-shi',34.626884,135.600948),(1250,'272132','27','Izumisano-shi',NULL,'Izumisano-shi, Osaka · 大阪府泉佐野市',1243,1,'泉佐野市','イズミサノシ','Izumisano-shi',34.406839,135.327337),(1251,'272141','27','Tondabayashi-shi',NULL,'Tondabayashi-shi, Osaka · 大阪府富田林市',1244,1,'富田林市','トンダバヤシシ','Tondabayashi-shi',34.499675,135.597280),(1252,'272159','27','Neyagawa-shi',NULL,'Neyagawa-shi, Osaka · 大阪府寝屋川市',1245,1,'寝屋川市','ネヤガワシ','Neyagawa-shi',34.766079,135.628008),(1253,'272167','27','Kawachinagano-shi',NULL,'Kawachinagano-shi, Osaka · 大阪府河内長野市',1246,1,'河内長野市','カワチナガノシ','Kawachinagano-shi',34.458107,135.564119),(1254,'272175','27','Matsubara-shi',NULL,'Matsubara-shi, Osaka · 大阪府松原市',1247,1,'松原市','マツバラシ','Matsubara-shi',34.577901,135.551786),(1255,'272183','27','Daito-shi',NULL,'Daito-shi, Osaka · 大阪府大東市',1248,1,'大東市','ダイトウシ','Daito-shi',34.712013,135.623471),(1256,'272191','27','Izumi-shi',NULL,'Izumi-shi, Osaka · 大阪府和泉市',1249,1,'和泉市','イズミシ','Izumi-shi',34.483635,135.423624),(1257,'272205','27','Mino-shi',NULL,'Mino-shi, Osaka · 大阪府箕面市',1250,1,'箕面市','ミノオシ','Mino-shi',34.826929,135.470379),(1258,'272213','27','Kashiwara-shi',NULL,'Kashiwara-shi, Osaka · 大阪府柏原市',1251,1,'柏原市','カシワラシ','Kashiwara-shi',34.579305,135.628642),(1259,'272221','27','Habikino-shi',NULL,'Habikino-shi, Osaka · 大阪府羽曳野市',1252,1,'羽曳野市','ハビキノシ','Habikino-shi',34.558002,135.606226),(1260,'272230','27','Kadoma-shi',NULL,'Kadoma-shi, Osaka · 大阪府門真市',1253,1,'門真市','カドマシ','Kadoma-shi',34.739145,135.586899),(1261,'272248','27','Settsu-shi',NULL,'Settsu-shi, Osaka · 大阪府摂津市',1254,1,'摂津市','セッツシ','Settsu-shi',34.777387,135.561886),(1262,'272256','27','Takaishi-shi',NULL,'Takaishi-shi, Osaka · 大阪府高石市',1255,1,'高石市','タカイシシ','Takaishi-shi',34.520673,135.442395),(1263,'272264','27','Fujiidera-shi',NULL,'Fujiidera-shi, Osaka · 大阪府藤井寺市',1256,1,'藤井寺市','フジイデラシ','Fujiidera-shi',34.574283,135.597476),(1264,'272272','27','Higashiosaka-shi',NULL,'Higashiosaka-shi, Osaka · 大阪府東大阪市',1257,1,'東大阪市','ヒガシオオサカシ','Higashiosaka-shi',34.679324,135.600898),(1265,'272281','27','Sennan-shi',NULL,'Sennan-shi, Osaka · 大阪府泉南市',1258,1,'泉南市','センナンシ','Sennan-shi',34.366007,135.273300),(1266,'272299','27','Shijonawate-shi',NULL,'Shijonawate-shi, Osaka · 大阪府四條畷市',1259,1,'四條畷市','シジョウナワテシ','Shijonawate-shi',34.740064,135.639727),(1267,'272302','27','Katano-shi',NULL,'Katano-shi, Osaka · 大阪府交野市',1260,1,'交野市','カタノシ','Katano-shi',34.787958,135.679978),(1268,'272311','27','Osakasayama-shi',NULL,'Osakasayama-shi, Osaka · 大阪府大阪狭山市',1261,1,'大阪狭山市','オオサカサヤマシ','Osakasayama-shi',34.503718,135.555748),(1269,'272329','27','Hannan-shi',NULL,'Hannan-shi, Osaka · 大阪府阪南市',1262,1,'阪南市','ハンナンシ','Hannan-shi',34.359594,135.239656),(1270,'273015','27','Shimamoto-cho',NULL,'Shimamoto-cho, Osaka · 大阪府島本町',1263,1,'島本町','シマモトチョウ','Shimamoto-cho',34.883819,135.663009),(1271,'273210','27','Toyono-cho',NULL,'Toyono-cho, Osaka · 大阪府豊能町',1264,1,'豊能町','トヨノチョウ','Toyono-cho',34.918850,135.494096),(1272,'273228','27','Nose-cho',NULL,'Nose-cho, Osaka · 大阪府能勢町',1265,1,'能勢町','ノセチョウ','Nose-cho',34.972445,135.414189),(1273,'273414','27','Tadaoka-cho',NULL,'Tadaoka-cho, Osaka · 大阪府忠岡町',1266,1,'忠岡町','タダオカチョウ','Tadaoka-cho',34.487125,135.401497),(1274,'273619','27','Kumatori-cho',NULL,'Kumatori-cho, Osaka · 大阪府熊取町',1267,1,'熊取町','クマトリチョウ','Kumatori-cho',34.401308,135.355863),(1275,'273627','27','Tajiri-cho',NULL,'Tajiri-cho, Osaka · 大阪府田尻町',1268,1,'田尻町','タジリチョウ','Tajiri-cho',34.393782,135.291176),(1276,'273660','27','Misaki-cho',NULL,'Misaki-cho, Osaka · 大阪府岬町',1269,1,'岬町','ミサキチョウ','Misaki-cho',34.316900,135.142085),(1277,'273813','27','Taishi-cho',NULL,'Taishi-cho, Osaka · 大阪府太子町',1270,1,'太子町','タイシチョウ','Taishi-cho',34.518656,135.647734),(1278,'273821','27','Kanan-cho',NULL,'Kanan-cho, Osaka · 大阪府河南町',1271,1,'河南町','カナンチョウ','Kanan-cho',34.491637,135.629880),(1279,'273830','27','Chihayaakasaka-mura',NULL,'Chihayaakasaka-mura, Osaka · 大阪府千早赤阪村',1272,1,'千早赤阪村','チハヤアカサカムラ','Chihayaakasaka-mura',34.464601,135.622531),(1280,'281018','28','Higashinada-ku, Kobe-shi',NULL,'Higashinada-ku, Kobe-shi, Hyogo · 兵庫県神戸市東灘区',1273,1,'神戸市東灘区','コウベシヒガシナダク','Higashinada-ku, Kobe-shi',34.720159,135.265429),(1281,'281026','28','Nada-ku, Kobe-shi',NULL,'Nada-ku, Kobe-shi, Hyogo · 兵庫県神戸市灘区',1274,1,'神戸市灘区','コウベシナダク','Nada-ku, Kobe-shi',34.712356,135.239571),(1282,'281051','28','Hyogo-ku, Kobe-shi',NULL,'Hyogo-ku, Kobe-shi, Hyogo · 兵庫県神戸市兵庫区',1275,1,'神戸市兵庫区','コウベシヒョウゴク','Hyogo-ku, Kobe-shi',34.680584,135.165383),(1283,'281069','28','Nagata-ku, Kobe-shi',NULL,'Nagata-ku, Kobe-shi, Hyogo · 兵庫県神戸市長田区',1276,1,'神戸市長田区','コウベシナガタク','Nagata-ku, Kobe-shi',34.665686,135.150854),(1284,'281077','28','Suma-ku, Kobe-shi',NULL,'Suma-ku, Kobe-shi, Hyogo · 兵庫県神戸市須磨区',1277,1,'神戸市須磨区','コウベシスマク','Suma-ku, Kobe-shi',34.658634,135.133702),(1285,'281085','28','Tarumi-ku, Kobe-shi',NULL,'Tarumi-ku, Kobe-shi, Hyogo · 兵庫県神戸市垂水区',1278,1,'神戸市垂水区','コウベシタルミク','Tarumi-ku, Kobe-shi',34.630610,135.056919),(1286,'281093','28','Kita-ku, Kobe-shi',NULL,'Kita-ku, Kobe-shi, Hyogo · 兵庫県神戸市北区',1279,1,'神戸市北区','コウベシキタク','Kita-ku, Kobe-shi',34.724128,135.146196),(1287,'281107','28','Chuo-ku, Kobe-shi',NULL,'Chuo-ku, Kobe-shi, Hyogo · 兵庫県神戸市中央区',1280,1,'神戸市中央区','コウベシチュウオウク','Chuo-ku, Kobe-shi',34.689678,135.194948),(1288,'281115','28','Nishi-ku, Kobe-shi',NULL,'Nishi-ku, Kobe-shi, Hyogo · 兵庫県神戸市西区',1281,1,'神戸市西区','コウベシニシク','Nishi-ku, Kobe-shi',34.720883,135.019349),(1289,'282014','28','Himeji-shi',NULL,'Himeji-shi, Hyogo · 兵庫県姫路市',1282,1,'姫路市','ヒメジシ','Himeji-shi',34.815496,134.685458),(1290,'282022','28','Amagasaki-shi',NULL,'Amagasaki-shi, Hyogo · 兵庫県尼崎市',1283,1,'尼崎市','アマガサキシ','Amagasaki-shi',34.733554,135.406394),(1291,'282031','28','Akashi-shi',NULL,'Akashi-shi, Hyogo · 兵庫県明石市',1284,1,'明石市','アカシシ','Akashi-shi',34.643109,134.997182),(1292,'282049','28','Nishinomiya-shi',NULL,'Nishinomiya-shi, Hyogo · 兵庫県西宮市',1285,1,'西宮市','ニシノミヤシ','Nishinomiya-shi',34.737691,135.341830),(1293,'282057','28','Sumoto-shi',NULL,'Sumoto-shi, Hyogo · 兵庫県洲本市',1286,1,'洲本市','スモトシ','Sumoto-shi',34.342522,134.895653),(1294,'282065','28','Ashiya-shi',NULL,'Ashiya-shi, Hyogo · 兵庫県芦屋市',1287,1,'芦屋市','アシヤシ','Ashiya-shi',34.726522,135.304179),(1295,'282073','28','Itami-shi',NULL,'Itami-shi, Hyogo · 兵庫県伊丹市',1288,1,'伊丹市','イタミシ','Itami-shi',34.784295,135.400933),(1296,'282081','28','Aioi-shi',NULL,'Aioi-shi, Hyogo · 兵庫県相生市',1289,1,'相生市','アイオイシ','Aioi-shi',34.803662,134.468193),(1297,'282090','28','Toyoka-shi',NULL,'Toyoka-shi, Hyogo · 兵庫県豊岡市',1290,1,'豊岡市','トヨオカシ','Toyoka-shi',35.544475,134.820187),(1298,'282103','28','Kakogawa-shi',NULL,'Kakogawa-shi, Hyogo · 兵庫県加古川市',1291,1,'加古川市','カコガワシ','Kakogawa-shi',34.756620,134.840900),(1299,'282120','28','Ako-shi',NULL,'Ako-shi, Hyogo · 兵庫県赤穂市',1292,1,'赤穂市','アコウシ','Ako-shi',34.754974,134.390354),(1300,'282138','28','Nishiwaki-shi',NULL,'Nishiwaki-shi, Hyogo · 兵庫県西脇市',1293,1,'西脇市','ニシワキシ','Nishiwaki-shi',34.983421,134.979696),(1301,'282146','28','Takarazuka-shi',NULL,'Takarazuka-shi, Hyogo · 兵庫県宝塚市',1294,1,'宝塚市','タカラヅカシ','Takarazuka-shi',34.799817,135.360098),(1302,'282154','28','Miki-shi',NULL,'Miki-shi, Hyogo · 兵庫県三木市',1295,1,'三木市','ミキシ','Miki-shi',34.796922,134.990188),(1303,'282162','28','Takasago-shi',NULL,'Takasago-shi, Hyogo · 兵庫県高砂市',1296,1,'高砂市','タカサゴシ','Takasago-shi',34.766248,134.790478),(1304,'282171','28','Kawanishi-shi',NULL,'Kawanishi-shi, Hyogo · 兵庫県川西市',1297,1,'川西市','カワニシシ','Kawanishi-shi',34.830132,135.417222),(1305,'282189','28','Ono-shi',NULL,'Ono-shi, Hyogo · 兵庫県小野市',1298,1,'小野市','オノシ','Ono-shi',34.857922,134.940106),(1306,'282197','28','Sanda-shi',NULL,'Sanda-shi, Hyogo · 兵庫県三田市',1299,1,'三田市','サンダシ','Sanda-shi',34.889672,135.225290),(1307,'282201','28','Kasai-shi',NULL,'Kasai-shi, Hyogo · 兵庫県加西市',1300,1,'加西市','カサイシ','Kasai-shi',34.927811,134.841819),(1308,'282219','28','Tambasasayama-shi',NULL,'Tambasasayama-shi, Hyogo · 兵庫県丹波篠山市',1301,1,'丹波篠山市','タンバササヤマシ','Tambasasayama-shi',35.075673,135.219002),(1309,'282227','28','Yabu-shi',NULL,'Yabu-shi, Hyogo · 兵庫県養父市',1302,1,'養父市','ヤブシ','Yabu-shi',35.404640,134.767668),(1310,'282235','28','Tamba-shi',NULL,'Tamba-shi, Hyogo · 兵庫県丹波市',1303,1,'丹波市','タンバシ','Tamba-shi',35.177259,135.035816),(1311,'282243','28','Minamiawaji-shi',NULL,'Minamiawaji-shi, Hyogo · 兵庫県南あわじ市',1304,1,'南あわじ市','ミナミアワジシ','Minamiawaji-shi',34.294479,134.779839),(1312,'282251','28','Asago-shi',NULL,'Asago-shi, Hyogo · 兵庫県朝来市',1305,1,'朝来市','アサゴシ','Asago-shi',35.339835,134.853117),(1313,'282260','28','Awaji-shi',NULL,'Awaji-shi, Hyogo · 兵庫県淡路市',1306,1,'淡路市','アワジシ','Awaji-shi',34.439847,134.914633),(1314,'282278','28','Shiso-shi',NULL,'Shiso-shi, Hyogo · 兵庫県宍粟市',1307,1,'宍粟市','シソウシ','Shiso-shi',35.004435,134.549377),(1315,'282286','28','Kato-shi',NULL,'Kato-shi, Hyogo · 兵庫県加東市',1308,1,'加東市','カトウシ','Kato-shi',34.917370,134.973345),(1316,'282294','28','Tatsuno-shi',NULL,'Tatsuno-shi, Hyogo · 兵庫県たつの市',1309,1,'たつの市','タツノシ','Tatsuno-shi',34.857847,134.545397),(1317,'283011','28','Inagawa-cho',NULL,'Inagawa-cho, Hyogo · 兵庫県猪名川町',1310,1,'猪名川町','イナガワチョウ','Inagawa-cho',34.894966,135.376164),(1318,'283657','28','Taka-cho',NULL,'Taka-cho, Hyogo · 兵庫県多可町',1311,1,'多可町','タカチョウ','Taka-cho',35.050315,134.923359),(1319,'283819','28','Inami-cho',NULL,'Inami-cho, Hyogo · 兵庫県稲美町',1312,1,'稲美町','イナミチョウ','Inami-cho',34.748627,134.913384),(1320,'283827','28','Harima-cho',NULL,'Harima-cho, Hyogo · 兵庫県播磨町',1313,1,'播磨町','ハリマチョウ','Harima-cho',34.715302,134.867911),(1321,'284424','28','Ichikawa-cho',NULL,'Ichikawa-cho, Hyogo · 兵庫県市川町',1314,1,'市川町','イチカワチョウ','Ichikawa-cho',34.989365,134.763289),(1322,'284432','28','Fukusaki-cho',NULL,'Fukusaki-cho, Hyogo · 兵庫県福崎町',1315,1,'福崎町','フクサキチョウ','Fukusaki-cho',34.950319,134.760245),(1323,'284467','28','Kamikawa-cho',NULL,'Kamikawa-cho, Hyogo · 兵庫県神河町',1316,1,'神河町','カミカワチョウ','Kamikawa-cho',35.064235,134.739856),(1324,'284645','28','Taishi-cho',NULL,'Taishi-cho, Hyogo · 兵庫県太子町',1317,1,'太子町','タイシチョウ','Taishi-cho',34.833250,134.572343),(1325,'284815','28','Kamigori-cho',NULL,'Kamigori-cho, Hyogo · 兵庫県上郡町',1318,1,'上郡町','カミゴオリチョウ','Kamigori-cho',34.873583,134.356098),(1326,'285013','28','Sayo-cho',NULL,'Sayo-cho, Hyogo · 兵庫県佐用町',1319,1,'佐用町','サヨウチョウ','Sayo-cho',35.004296,134.355771),(1327,'285854','28','Kami-cho',NULL,'Kami-cho, Hyogo · 兵庫県香美町',1320,1,'香美町','カミチョウ','Kami-cho',35.632169,134.629175),(1328,'285862','28','Shinonsen-cho',NULL,'Shinonsen-cho, Hyogo · 兵庫県新温泉町',1321,1,'新温泉町','シンオンセンチョウ','Shinonsen-cho',35.623509,134.448942),(1329,'292010','29','Nara-shi',NULL,'Nara-shi, Nara · 奈良県奈良市',1322,1,'奈良市','ナラシ','Nara-shi',34.685117,135.804995),(1330,'292028','29','Yamatotakada-shi',NULL,'Yamatotakada-shi, Nara · 奈良県大和高田市',1323,1,'大和高田市','ヤマトタカダシ','Yamatotakada-shi',34.515724,135.737388),(1331,'292036','29','Yamatokoriyama-shi',NULL,'Yamatokoriyama-shi, Nara · 奈良県大和郡山市',1324,1,'大和郡山市','ヤマトコオリヤマシ','Yamatokoriyama-shi',34.649368,135.782747),(1332,'292044','29','Tenri-shi',NULL,'Tenri-shi, Nara · 奈良県天理市',1325,1,'天理市','テンリシ','Tenri-shi',34.596617,135.837367),(1333,'292052','29','Kashihara-shi',NULL,'Kashihara-shi, Nara · 奈良県橿原市',1326,1,'橿原市','カシハラシ','Kashihara-shi',34.509453,135.792756),(1334,'292061','29','Sakurai-shi',NULL,'Sakurai-shi, Nara · 奈良県桜井市',1327,1,'桜井市','サクライシ','Sakurai-shi',34.518700,135.843232),(1335,'292079','29','Gojo-shi',NULL,'Gojo-shi, Nara · 奈良県五條市',1328,1,'五條市','ゴジョウシ','Gojo-shi',34.356271,135.695588),(1336,'292087','29','Gose-shi',NULL,'Gose-shi, Nara · 奈良県御所市',1329,1,'御所市','ゴセシ','Gose-shi',34.463340,135.740177),(1337,'292095','29','Ikoma-shi',NULL,'Ikoma-shi, Nara · 奈良県生駒市',1330,1,'生駒市','イコマシ','Ikoma-shi',34.691979,135.700553),(1338,'292109','29','Kashiba-shi',NULL,'Kashiba-shi, Nara · 奈良県香芝市',1331,1,'香芝市','カシバシ','Kashiba-shi',34.541295,135.699162),(1339,'292117','29','Katsuragi-shi',NULL,'Katsuragi-shi, Nara · 奈良県葛城市',1332,1,'葛城市','カツラギシ','Katsuragi-shi',34.489158,135.726563),(1340,'292125','29','Uda-shi',NULL,'Uda-shi, Nara · 奈良県宇陀市',1333,1,'宇陀市','ウダシ','Uda-shi',34.528073,135.952303),(1341,'293229','29','Yamazoe-mura',NULL,'Yamazoe-mura, Nara · 奈良県山添村',1334,1,'山添村','ヤマゾエムラ','Yamazoe-mura',34.681261,136.043797),(1342,'293423','29','Heguri-cho',NULL,'Heguri-cho, Nara · 奈良県平群町',1335,1,'平群町','ヘグリチョウ','Heguri-cho',34.629155,135.700711),(1343,'293431','29','Sango-cho',NULL,'Sango-cho, Nara · 奈良県三郷町',1336,1,'三郷町','サンゴウチョウ','Sango-cho',34.599977,135.695425),(1344,'293440','29','Ikaruga-cho',NULL,'Ikaruga-cho, Nara · 奈良県斑鳩町',1337,1,'斑鳩町','イカルガチョウ','Ikaruga-cho',34.608852,135.730593),(1345,'293458','29','Ando-cho',NULL,'Ando-cho, Nara · 奈良県安堵町',1338,1,'安堵町','アンドチョウ','Ando-cho',34.606488,135.756781),(1346,'293610','29','Kawanishi-cho',NULL,'Kawanishi-cho, Nara · 奈良県川西町',1339,1,'川西町','カワニシチョウ','Kawanishi-cho',34.584383,135.773871),(1347,'293628','29','Miyake-cho',NULL,'Miyake-cho, Nara · 奈良県三宅町',1340,1,'三宅町','ミヤケチョウ','Miyake-cho',34.573678,135.773177),(1348,'293636','29','Tawaramoto-cho',NULL,'Tawaramoto-cho, Nara · 奈良県田原本町',1341,1,'田原本町','タワラモトチョウ','Tawaramoto-cho',34.556667,135.794940),(1349,'293857','29','Soni-mura',NULL,'Soni-mura, Nara · 奈良県曽爾村',1342,1,'曽爾村','ソニムラ','Soni-mura',34.510664,136.124475),(1350,'293865','29','Mitsue-mura',NULL,'Mitsue-mura, Nara · 奈良県御杖村',1343,1,'御杖村','ミツエムラ','Mitsue-mura',34.488391,136.165910),(1351,'294012','29','Takatori-cho',NULL,'Takatori-cho, Nara · 奈良県高取町',1344,1,'高取町','タカトリチョウ','Takatori-cho',34.449468,135.793168),(1352,'294021','29','Asuka-mura',NULL,'Asuka-mura, Nara · 奈良県明日香村',1345,1,'明日香村','アスカムラ','Asuka-mura',34.470547,135.812500),(1353,'294241','29','Kammaki-cho',NULL,'Kammaki-cho, Nara · 奈良県上牧町',1346,1,'上牧町','カンマキチョウ','Kammaki-cho',34.562723,135.716674),(1354,'294250','29','Oji-cho',NULL,'Oji-cho, Nara · 奈良県王寺町',1347,1,'王寺町','オウジチョウ','Oji-cho',34.594696,135.706705),(1355,'294268','29','Koryo-cho',NULL,'Koryo-cho, Nara · 奈良県広陵町',1348,1,'広陵町','コウリョウチョウ','Koryo-cho',34.542740,135.750842),(1356,'294276','29','Kawai-cho',NULL,'Kawai-cho, Nara · 奈良県河合町',1349,1,'河合町','カワイチョウ','Kawai-cho',34.578369,135.736699),(1357,'294411','29','Yoshino-cho',NULL,'Yoshino-cho, Nara · 奈良県吉野町',1350,1,'吉野町','ヨシノチョウ','Yoshino-cho',34.396046,135.857612),(1358,'294420','29','Oyodo-cho',NULL,'Oyodo-cho, Nara · 奈良県大淀町',1351,1,'大淀町','オオヨドチョウ','Oyodo-cho',34.390529,135.789798),(1359,'294438','29','Shimoichi-cho',NULL,'Shimoichi-cho, Nara · 奈良県下市町',1352,1,'下市町','シモイチチョウ','Shimoichi-cho',34.360951,135.791872),(1360,'294446','29','Kurotaki-mura',NULL,'Kurotaki-mura, Nara · 奈良県黒滝村',1353,1,'黒滝村','クロタキムラ','Kurotaki-mura',34.309276,135.852250),(1361,'294462','29','Tenkawa-mura',NULL,'Tenkawa-mura, Nara · 奈良県天川村',1354,1,'天川村','テンカワムラ','Tenkawa-mura',34.241929,135.855135),(1362,'294471','29','Nosegawa-mura',NULL,'Nosegawa-mura, Nara · 奈良県野迫川村',1355,1,'野迫川村','ノセガワムラ','Nosegawa-mura',34.166293,135.633030),(1363,'294497','29','Totsukawa-mura',NULL,'Totsukawa-mura, Nara · 奈良県十津川村',1356,1,'十津川村','トツカワムラ','Totsukawa-mura',33.988504,135.792611),(1364,'294501','29','Shimokitayama-mura',NULL,'Shimokitayama-mura, Nara · 奈良県下北山村',1357,1,'下北山村','シモキタヤマムラ','Shimokitayama-mura',34.005080,135.955178),(1365,'294519','29','Kamikitayama-mura',NULL,'Kamikitayama-mura, Nara · 奈良県上北山村',1358,1,'上北山村','カミキタヤマムラ','Kamikitayama-mura',34.134321,136.000149),(1366,'294527','29','Kawakami-mura',NULL,'Kawakami-mura, Nara · 奈良県川上村',1359,1,'川上村','カワカミムラ','Kawakami-mura',34.338121,135.954303),(1367,'294535','29','Higashiyoshino-mura',NULL,'Higashiyoshino-mura, Nara · 奈良県東吉野村',1360,1,'東吉野村','ヒガシヨシノムラ','Higashiyoshino-mura',34.403541,135.968307),(1368,'302015','30','Wakayama-shi',NULL,'Wakayama-shi, Wakayama · 和歌山県和歌山市',1361,1,'和歌山市','ワカヤマシ','Wakayama-shi',34.230514,135.170808),(1369,'302023','30','Kainan-shi',NULL,'Kainan-shi, Wakayama · 和歌山県海南市',1362,1,'海南市','カイナンシ','Kainan-shi',34.157601,135.239634),(1370,'302031','30','Hashimoto-shi',NULL,'Hashimoto-shi, Wakayama · 和歌山県橋本市',1363,1,'橋本市','ハシモトシ','Hashimoto-shi',34.314876,135.605208),(1371,'302040','30','Arida-shi',NULL,'Arida-shi, Wakayama · 和歌山県有田市',1364,1,'有田市','アリダシ','Arida-shi',34.083104,135.127727),(1372,'302058','30','Gobo-shi',NULL,'Gobo-shi, Wakayama · 和歌山県御坊市',1365,1,'御坊市','ゴボウシ','Gobo-shi',33.891412,135.152406),(1373,'302066','30','Tanabe-shi',NULL,'Tanabe-shi, Wakayama · 和歌山県田辺市',1366,1,'田辺市','タナベシ','Tanabe-shi',33.728010,135.377662),(1374,'302074','30','Shingu-shi',NULL,'Shingu-shi, Wakayama · 和歌山県新宮市',1367,1,'新宮市','シングウシ','Shingu-shi',33.724224,135.992525),(1375,'302082','30','Kinokawa-shi',NULL,'Kinokawa-shi, Wakayama · 和歌山県紀の川市',1368,1,'紀の川市','キノカワシ','Kinokawa-shi',34.269538,135.362576),(1376,'302091','30','Iwade-shi',NULL,'Iwade-shi, Wakayama · 和歌山県岩出市',1369,1,'岩出市','イワデシ','Iwade-shi',34.256267,135.311451),(1377,'303046','30','Kimino-cho',NULL,'Kimino-cho, Wakayama · 和歌山県紀美野町',1370,1,'紀美野町','キミノチョウ','Kimino-cho',34.167192,135.307575),(1378,'303411','30','Katsuragi-cho',NULL,'Katsuragi-cho, Wakayama · 和歌山県かつらぎ町',1371,1,'かつらぎ町','カツラギチョウ','Katsuragi-cho',34.296416,135.503802),(1379,'303437','30','Kudoyama-cho',NULL,'Kudoyama-cho, Wakayama · 和歌山県九度山町',1372,1,'九度山町','クドヤマチョウ','Kudoyama-cho',34.287192,135.562242),(1380,'303445','30','Koya-cho',NULL,'Koya-cho, Wakayama · 和歌山県高野町',1373,1,'高野町','コウヤチョウ','Koya-cho',34.216077,135.586536),(1381,'303615','30','Yuasa-cho',NULL,'Yuasa-cho, Wakayama · 和歌山県湯浅町',1374,1,'湯浅町','ユアサチョウ','Yuasa-cho',34.029411,135.190474),(1382,'303623','30','Hirogawa-cho',NULL,'Hirogawa-cho, Wakayama · 和歌山県広川町',1375,1,'広川町','ヒロガワチョウ','Hirogawa-cho',34.029957,135.173072),(1383,'303666','30','Aridagawa-cho',NULL,'Aridagawa-cho, Wakayama · 和歌山県有田川町',1376,1,'有田川町','アリダガワチョウ','Aridagawa-cho',34.057488,135.216116),(1384,'303810','30','Mihama-cho',NULL,'Mihama-cho, Wakayama · 和歌山県美浜町',1377,1,'美浜町','ミハマチョウ','Mihama-cho',33.893806,135.133265),(1385,'303828','30','Hidaka-cho',NULL,'Hidaka-cho, Wakayama · 和歌山県日高町',1378,1,'日高町','ヒダカチョウ','Hidaka-cho',33.925677,135.141072),(1386,'303836','30','Yura-cho',NULL,'Yura-cho, Wakayama · 和歌山県由良町',1379,1,'由良町','ユラチョウ','Yura-cho',33.959251,135.118187),(1387,'303909','30','Inami-cho',NULL,'Inami-cho, Wakayama · 和歌山県印南町',1380,1,'印南町','イナミチョウ','Inami-cho',33.819553,135.222639),(1388,'303917','30','Minabe-cho',NULL,'Minabe-cho, Wakayama · 和歌山県みなべ町',1381,1,'みなべ町','ミナベチョウ','Minabe-cho',33.772333,135.321434),(1389,'303925','30','Hidakagawa-cho',NULL,'Hidakagawa-cho, Wakayama · 和歌山県日高川町',1382,1,'日高川町','ヒダカガワチョウ','Hidakagawa-cho',33.911693,135.186000),(1390,'304018','30','Shirahama-cho',NULL,'Shirahama-cho, Wakayama · 和歌山県白浜町',1383,1,'白浜町','シラハマチョウ','Shirahama-cho',33.678188,135.348108),(1391,'304042','30','Kamitonda-cho',NULL,'Kamitonda-cho, Wakayama · 和歌山県上富田町',1384,1,'上富田町','カミトンダチョウ','Kamitonda-cho',33.696352,135.428802),(1392,'304069','30','Susami-cho',NULL,'Susami-cho, Wakayama · 和歌山県すさみ町',1385,1,'すさみ町','スサミチョウ','Susami-cho',33.550062,135.496678),(1393,'304212','30','Nachikatsura-cho',NULL,'Nachikatsura-cho, Wakayama · 和歌山県那智勝浦町',1386,1,'那智勝浦町','ナチカツウラチョウ','Nachikatsura-cho',33.625988,135.941042),(1394,'304221','30','Taiji-cho',NULL,'Taiji-cho, Wakayama · 和歌山県太地町',1387,1,'太地町','タイジチョウ','Taiji-cho',33.594037,135.943964),(1395,'304247','30','Kozagawa-cho',NULL,'Kozagawa-cho, Wakayama · 和歌山県古座川町',1388,1,'古座川町','コザガワチョウ','Kozagawa-cho',33.531990,135.814999),(1396,'304271','30','Kitayama-mura',NULL,'Kitayama-mura, Wakayama · 和歌山県北山村',1389,1,'北山村','キタヤマムラ','Kitayama-mura',33.932100,135.969303),(1397,'304280','30','Kushimoto-cho',NULL,'Kushimoto-cho, Wakayama · 和歌山県串本町',1390,1,'串本町','クシモトチョウ','Kushimoto-cho',33.485693,135.786998),(1398,'312011','31','Tottori-shi',NULL,'Tottori-shi, Tottori · 鳥取県鳥取市',1391,1,'鳥取市','トットリシ','Tottori-shi',35.494395,134.222122),(1399,'312029','31','Yonago-shi',NULL,'Yonago-shi, Tottori · 鳥取県米子市',1392,1,'米子市','ヨナゴシ','Yonago-shi',35.428136,133.330939),(1400,'312037','31','Kurayoshi-shi',NULL,'Kurayoshi-shi, Tottori · 鳥取県倉吉市',1393,1,'倉吉市','クラヨシシ','Kurayoshi-shi',35.430182,133.825561),(1401,'312045','31','Sakaiminato-shi',NULL,'Sakaiminato-shi, Tottori · 鳥取県境港市',1394,1,'境港市','サカイミナトシ','Sakaiminato-shi',35.539606,133.231600),(1402,'313025','31','Iwami-cho',NULL,'Iwami-cho, Tottori · 鳥取県岩美町',1395,1,'岩美町','イワミチョウ','Iwami-cho',35.575896,134.332087),(1403,'313254','31','Wakasa-cho',NULL,'Wakasa-cho, Tottori · 鳥取県若桜町',1396,1,'若桜町','ワカサチョウ','Wakasa-cho',35.340149,134.400992),(1404,'313289','31','Chizu-cho',NULL,'Chizu-cho, Tottori · 鳥取県智頭町',1397,1,'智頭町','チヅチョウ','Chizu-cho',35.265081,134.226587),(1405,'313297','31','Yazu-cho',NULL,'Yazu-cho, Tottori · 鳥取県八頭町',1398,1,'八頭町','ヤズチョウ','Yazu-cho',35.409211,134.250526),(1406,'313645','31','Misasa-cho',NULL,'Misasa-cho, Tottori · 鳥取県三朝町',1399,1,'三朝町','ミササチョウ','Misasa-cho',35.408461,133.861742),(1407,'313700','31','Yurihama-cho',NULL,'Yurihama-cho, Tottori · 鳥取県湯梨浜町',1400,1,'湯梨浜町','ユリハマチョウ','Yurihama-cho',35.489915,133.864681),(1408,'313718','31','Kotora-cho',NULL,'Kotora-cho, Tottori · 鳥取県琴浦町',1401,1,'琴浦町','コトウラチョウ','Kotora-cho',35.495184,133.692779),(1409,'313726','31','Hokuei-cho',NULL,'Hokuei-cho, Tottori · 鳥取県北栄町',1402,1,'北栄町','ホクエイチョウ','Hokuei-cho',35.490011,133.758294),(1410,'313840','31','Hiezu-son',NULL,'Hiezu-son, Tottori · 鳥取県日吉津村',1403,1,'日吉津村','ヒエヅソン','Hiezu-son',35.440179,133.380808),(1411,'313866','31','Daisen-cho',NULL,'Daisen-cho, Tottori · 鳥取県大山町',1404,1,'大山町','ダイセンチョウ','Daisen-cho',35.510779,133.496123),(1412,'313891','31','Nambu-cho',NULL,'Nambu-cho, Tottori · 鳥取県南部町',1405,1,'南部町','ナンブチョウ','Nambu-cho',35.340330,133.326829),(1413,'313904','31','Hoki-cho',NULL,'Hoki-cho, Tottori · 鳥取県伯耆町',1406,1,'伯耆町','ホウキチョウ','Hoki-cho',35.385230,133.407384),(1414,'314013','31','Nichinan-cho',NULL,'Nichinan-cho, Tottori · 鳥取県日南町',1407,1,'日南町','ニチナンチョウ','Nichinan-cho',35.163311,133.306255),(1415,'314021','31','Hino-cho',NULL,'Hino-cho, Tottori · 鳥取県日野町',1408,1,'日野町','ヒノチョウ','Hino-cho',35.240793,133.442659),(1416,'314030','31','Kofu-cho',NULL,'Kofu-cho, Tottori · 鳥取県江府町',1409,1,'江府町','コウフチョウ','Kofu-cho',35.275764,133.479128),(1417,'322016','32','Matsue-shi',NULL,'Matsue-shi, Shimane · 島根県松江市',1410,1,'松江市','マツエシ','Matsue-shi',35.468039,133.048527),(1418,'322024','32','Hamada-shi',NULL,'Hamada-shi, Shimane · 島根県浜田市',1411,1,'浜田市','ハマダシ','Hamada-shi',34.899255,132.079883),(1419,'322032','32','Izumo-shi',NULL,'Izumo-shi, Shimane · 島根県出雲市',1412,1,'出雲市','イズモシ','Izumo-shi',35.366917,132.754712),(1420,'322041','32','Masuda-shi',NULL,'Masuda-shi, Shimane · 島根県益田市',1413,1,'益田市','マスダシ','Masuda-shi',34.674814,131.842862),(1421,'322059','32','Oda-shi',NULL,'Oda-shi, Shimane · 島根県大田市',1414,1,'大田市','オオダシ','Oda-shi',35.192090,132.499351),(1422,'322067','32','Yasugi-shi',NULL,'Yasugi-shi, Shimane · 島根県安来市',1415,1,'安来市','ヤスギシ','Yasugi-shi',35.431451,133.250851),(1423,'322075','32','Gotsu-shi',NULL,'Gotsu-shi, Shimane · 島根県江津市',1416,1,'江津市','ゴウツシ','Gotsu-shi',35.011624,132.217707),(1424,'322091','32','Unnan-shi',NULL,'Unnan-shi, Shimane · 島根県雲南市',1417,1,'雲南市','ウンナンシ','Unnan-shi',35.307648,132.900297),(1425,'323438','32','Okuizumo-cho',NULL,'Okuizumo-cho, Shimane · 島根県奥出雲町',1418,1,'奥出雲町','オクイズモチョウ','Okuizumo-cho',35.197428,133.002549),(1426,'323861','32','Iinan-cho',NULL,'Iinan-cho, Shimane · 島根県飯南町',1419,1,'飯南町','イイナンチョウ','Iinan-cho',34.998609,132.713304),(1427,'324418','32','Kawamoto-machi',NULL,'Kawamoto-machi, Shimane · 島根県川本町',1420,1,'川本町','カワモトマチ','Kawamoto-machi',34.995217,132.495895),(1428,'324485','32','Misato-cho',NULL,'Misato-cho, Shimane · 島根県美郷町',1421,1,'美郷町','ミサトチョウ','Misato-cho',35.076512,132.590543),(1429,'324493','32','Onan-cho',NULL,'Onan-cho, Shimane · 島根県邑南町',1422,1,'邑南町','オオナンチョウ','Onan-cho',34.893934,132.437976),(1430,'325015','32','Tsuwano-cho',NULL,'Tsuwano-cho, Shimane · 島根県津和野町',1423,1,'津和野町','ツワノチョウ','Tsuwano-cho',34.542002,131.834956),(1431,'325058','32','Yoshika-cho',NULL,'Yoshika-cho, Shimane · 島根県吉賀町',1424,1,'吉賀町','ヨシカチョウ','Yoshika-cho',34.353510,131.935137),(1432,'325252','32','Ama-cho',NULL,'Ama-cho, Shimane · 島根県海士町',1425,1,'海士町','アマチョウ','Ama-cho',36.096561,133.096701),(1433,'325261','32','Nishinoshima-cho',NULL,'Nishinoshima-cho, Shimane · 島根県西ノ島町',1426,1,'西ノ島町','ニシノシマチョウ','Nishinoshima-cho',36.091826,133.013454),(1434,'325279','32','Chibu-mura',NULL,'Chibu-mura, Shimane · 島根県知夫村',1427,1,'知夫村','チブムラ','Chibu-mura',36.013927,133.039372),(1435,'325287','32','Okinoshima-cho',NULL,'Okinoshima-cho, Shimane · 島根県隠岐の島町',1428,1,'隠岐の島町','オキノシマチョウ','Okinoshima-cho',36.213368,133.311705),(1436,'331015','33','Kita-ku, Okayama-shi',NULL,'Kita-ku, Okayama-shi, Okayama · 岡山県岡山市北区',1429,1,'岡山市北区','オカヤマシキタク','Kita-ku, Okayama-shi',34.655107,133.919566),(1437,'331023','33','Naka-ku, Okayama-shi',NULL,'Naka-ku, Okayama-shi, Okayama · 岡山県岡山市中区',1430,1,'岡山市中区','オカヤマシナカク','Naka-ku, Okayama-shi',34.670776,133.943144),(1438,'331031','33','Higashi-ku, Okayama-shi',NULL,'Higashi-ku, Okayama-shi, Okayama · 岡山県岡山市東区',1431,1,'岡山市東区','オカヤマシヒガシク','Higashi-ku, Okayama-shi',34.651400,134.029443),(1439,'331040','33','Minami-ku, Okayama-shi',NULL,'Minami-ku, Okayama-shi, Okayama · 岡山県岡山市南区',1432,1,'岡山市南区','オカヤマシミナミク','Minami-ku, Okayama-shi',34.599771,133.919553),(1440,'332020','33','Kurashiki-shi',NULL,'Kurashiki-shi, Okayama · 岡山県倉敷市',1433,1,'倉敷市','クラシキシ','Kurashiki-shi',34.584677,133.772281),(1441,'332038','33','Tsuyama-shi',NULL,'Tsuyama-shi, Okayama · 岡山県津山市',1434,1,'津山市','ツヤマシ','Tsuyama-shi',35.069118,134.004543),(1442,'332046','33','Tamano-shi',NULL,'Tamano-shi, Okayama · 岡山県玉野市',1435,1,'玉野市','タマノシ','Tamano-shi',34.491979,133.946012),(1443,'332054','33','Kasaoka-shi',NULL,'Kasaoka-shi, Okayama · 岡山県笠岡市',1436,1,'笠岡市','カサオカシ','Kasaoka-shi',34.507182,133.507441),(1444,'332071','33','Ibara-shi',NULL,'Ibara-shi, Okayama · 岡山県井原市',1437,1,'井原市','イバラシ','Ibara-shi',34.597709,133.463798),(1445,'332089','33','Soja-shi',NULL,'Soja-shi, Okayama · 岡山県総社市',1438,1,'総社市','ソウジャシ','Soja-shi',34.672810,133.746531),(1446,'332097','33','Takahashi-shi',NULL,'Takahashi-shi, Okayama · 岡山県高梁市',1439,1,'高梁市','タカハシシ','Takahashi-shi',34.791361,133.616678),(1447,'332101','33','Niimi-shi',NULL,'Niimi-shi, Okayama · 岡山県新見市',1440,1,'新見市','ニイミシ','Niimi-shi',34.977058,133.470345),(1448,'332119','33','Bizen-shi',NULL,'Bizen-shi, Okayama · 岡山県備前市',1441,1,'備前市','ビゼンシ','Bizen-shi',34.745129,134.188137),(1449,'332127','33','Setochi-shi',NULL,'Setochi-shi, Okayama · 岡山県瀬戸内市',1442,1,'瀬戸内市','セトウチシ','Setochi-shi',34.664893,134.092849),(1450,'332135','33','Akaiwa-shi',NULL,'Akaiwa-shi, Okayama · 岡山県赤磐市',1443,1,'赤磐市','アカイワシ','Akaiwa-shi',34.755396,134.018848),(1451,'332143','33','Maniwa-shi',NULL,'Maniwa-shi, Okayama · 岡山県真庭市',1444,1,'真庭市','マニワシ','Maniwa-shi',35.075591,133.752756),(1452,'332151','33','Mimasaka-shi',NULL,'Mimasaka-shi, Okayama · 岡山県美作市',1445,1,'美作市','ミマサカシ','Mimasaka-shi',35.008594,134.148578),(1453,'332160','33','Asakuchi-shi',NULL,'Asakuchi-shi, Okayama · 岡山県浅口市',1446,1,'浅口市','アサクチシ','Asakuchi-shi',34.527834,133.584911),(1454,'333468','33','Wake-cho',NULL,'Wake-cho, Okayama · 岡山県和気町',1447,1,'和気町','ワケチョウ','Wake-cho',34.802882,134.157539),(1455,'334235','33','Hayashima-cho',NULL,'Hayashima-cho, Okayama · 岡山県早島町',1448,1,'早島町','ハヤシマチョウ','Hayashima-cho',34.600822,133.828304),(1456,'334456','33','Satosho-cho',NULL,'Satosho-cho, Okayama · 岡山県里庄町',1449,1,'里庄町','サトショウチョウ','Satosho-cho',34.513740,133.556886),(1457,'334618','33','Yakage-cho',NULL,'Yakage-cho, Okayama · 岡山県矢掛町',1450,1,'矢掛町','ヤカゲチョウ','Yakage-cho',34.627596,133.587085),(1458,'335860','33','Shinjo-son',NULL,'Shinjo-son, Okayama · 岡山県新庄村',1451,1,'新庄村','シンジョウソン','Shinjo-son',35.179367,133.567638),(1459,'336068','33','Kagamino-cho',NULL,'Kagamino-cho, Okayama · 岡山県鏡野町',1452,1,'鏡野町','カガミノチョウ','Kagamino-cho',35.091772,133.933030),(1460,'336220','33','Shoo-cho',NULL,'Shoo-cho, Okayama · 岡山県勝央町',1453,1,'勝央町','ショウオウチョウ','Shoo-cho',35.041790,134.116172),(1461,'336238','33','Nagi-cho',NULL,'Nagi-cho, Okayama · 岡山県奈義町',1454,1,'奈義町','ナギチョウ','Nagi-cho',35.123024,134.177427),(1462,'336432','33','Nishiawakura-son',NULL,'Nishiawakura-son, Okayama · 岡山県西粟倉村',1455,1,'西粟倉村','ニシアワクラソン','Nishiawakura-son',35.171853,134.335708),(1463,'336637','33','Kumenan-cho',NULL,'Kumenan-cho, Okayama · 岡山県久米南町',1456,1,'久米南町','クメナンチョウ','Kumenan-cho',34.929179,133.960647),(1464,'336661','33','Misaki-cho',NULL,'Misaki-cho, Okayama · 岡山県美咲町',1457,1,'美咲町','ミサキチョウ','Misaki-cho',34.997967,133.958149),(1465,'336815','33','Kibichuo-cho',NULL,'Kibichuo-cho, Okayama · 岡山県吉備中央町',1458,1,'吉備中央町','キビチュウオウチョウ','Kibichuo-cho',34.863404,133.693500),(1466,'341011','34','Naka-ku, Hiroshima-shi',NULL,'Naka-ku, Hiroshima-shi, Hiroshima · 広島県広島市中区',1459,1,'広島市中区','ヒロシマシナカク','Naka-ku, Hiroshima-shi',34.386288,132.454992),(1467,'341029','34','Higashi-ku, Hiroshima-shi',NULL,'Higashi-ku, Hiroshima-shi, Hiroshima · 広島県広島市東区',1460,1,'広島市東区','ヒロシマシヒガシク','Higashi-ku, Hiroshima-shi',34.395333,132.482384),(1468,'341037','34','Minami-ku, Hiroshima-shi',NULL,'Minami-ku, Hiroshima-shi, Hiroshima · 広島県広島市南区',1461,1,'広島市南区','ヒロシマシミナミク','Minami-ku, Hiroshima-shi',34.379884,132.469061),(1469,'341045','34','Nishi-ku, Hiroshima-shi',NULL,'Nishi-ku, Hiroshima-shi, Hiroshima · 広島県広島市西区',1462,1,'広島市西区','ヒロシマシニシク','Nishi-ku, Hiroshima-shi',34.393966,132.434426),(1470,'341053','34','Asaminami-ku, Hiroshima-shi',NULL,'Asaminami-ku, Hiroshima-shi, Hiroshima · 広島県広島市安佐南区',1463,1,'広島市安佐南区','ヒロシマシアサミナミク','Asaminami-ku, Hiroshima-shi',34.451850,132.471641),(1471,'341061','34','Asakita-ku, Hiroshima-shi',NULL,'Asakita-ku, Hiroshima-shi, Hiroshima · 広島県広島市安佐北区',1464,1,'広島市安佐北区','ヒロシマシアサキタク','Asakita-ku, Hiroshima-shi',34.518282,132.507633),(1472,'341070','34','Aki-ku, Hiroshima-shi',NULL,'Aki-ku, Hiroshima-shi, Hiroshima · 広島県広島市安芸区',1465,1,'広島市安芸区','ヒロシマシアキク','Aki-ku, Hiroshima-shi',34.371756,132.525542),(1473,'341088','34','Saeki-ku, Hiroshima-shi',NULL,'Saeki-ku, Hiroshima-shi, Hiroshima · 広島県広島市佐伯区',1466,1,'広島市佐伯区','ヒロシマシサエキク','Saeki-ku, Hiroshima-shi',34.364460,132.360820),(1474,'342025','34','Kure-shi',NULL,'Kure-shi, Hiroshima · 広島県呉市',1467,1,'呉市','クレシ','Kure-shi',34.249254,132.565805),(1475,'342033','34','Takehara-shi',NULL,'Takehara-shi, Hiroshima · 広島県竹原市',1468,1,'竹原市','タケハラシ','Takehara-shi',34.341794,132.907091),(1476,'342041','34','Mihara-shi',NULL,'Mihara-shi, Hiroshima · 広島県三原市',1469,1,'三原市','ミハラシ','Mihara-shi',34.397470,133.078525),(1477,'342050','34','Onomichi-shi',NULL,'Onomichi-shi, Hiroshima · 広島県尾道市',1470,1,'尾道市','オノミチシ','Onomichi-shi',34.408891,133.204966),(1478,'342076','34','Fukuyama-shi',NULL,'Fukuyama-shi, Hiroshima · 広島県福山市',1471,1,'福山市','フクヤマシ','Fukuyama-shi',34.485927,133.362340),(1479,'342084','34','Fuchu-shi',NULL,'Fuchu-shi, Hiroshima · 広島県府中市',1472,1,'府中市','フチュウシ','Fuchu-shi',34.568349,133.236323),(1480,'342092','34','Miyoshi-shi',NULL,'Miyoshi-shi, Hiroshima · 広島県三次市',1473,1,'三次市','ミヨシシ','Miyoshi-shi',34.805627,132.851790),(1481,'342106','34','Shobara-shi',NULL,'Shobara-shi, Hiroshima · 広島県庄原市',1474,1,'庄原市','ショウバラシ','Shobara-shi',34.857743,133.017264),(1482,'342114','34','Otake-shi',NULL,'Otake-shi, Hiroshima · 広島県大竹市',1475,1,'大竹市','オオタケシ','Otake-shi',34.237952,132.222361),(1483,'342122','34','Higashihiroshima-shi',NULL,'Higashihiroshima-shi, Hiroshima · 広島県東広島市',1476,1,'東広島市','ヒガシヒロシマシ','Higashihiroshima-shi',34.426787,132.743746),(1484,'342131','34','Hatsukaichi-shi',NULL,'Hatsukaichi-shi, Hiroshima · 広島県廿日市市',1477,1,'廿日市市','ハツカイチシ','Hatsukaichi-shi',34.348416,132.331541),(1485,'342149','34','Akitakata-shi',NULL,'Akitakata-shi, Hiroshima · 広島県安芸高田市',1478,1,'安芸高田市','アキタカタシ','Akitakata-shi',34.666099,132.703977),(1486,'342157','34','Etajima-shi',NULL,'Etajima-shi, Hiroshima · 広島県江田島市',1479,1,'江田島市','エタジマシ','Etajima-shi',34.174983,132.462196),(1487,'343021','34','Fuchu-cho',NULL,'Fuchu-cho, Hiroshima · 広島県府中町',1480,1,'府中町','フチュウチョウ','Fuchu-cho',34.392589,132.504544),(1488,'343048','34','Kaita-cho',NULL,'Kaita-cho, Hiroshima · 広島県海田町',1481,1,'海田町','カイタチョウ','Kaita-cho',34.366394,132.536246),(1489,'343072','34','Kumano-cho',NULL,'Kumano-cho, Hiroshima · 広島県熊野町',1482,1,'熊野町','クマノチョウ','Kumano-cho',34.335783,132.584604),(1490,'343099','34','Saka-cho',NULL,'Saka-cho, Hiroshima · 広島県坂町',1483,1,'坂町','サカチョウ','Saka-cho',34.341282,132.513639),(1491,'343684','34','Akiota-cho',NULL,'Akiota-cho, Hiroshima · 広島県安芸太田町',1484,1,'安芸太田町','アキオオタチョウ','Akiota-cho',34.576731,132.227141),(1492,'343692','34','Kitahiroshima-cho',NULL,'Kitahiroshima-cho, Hiroshima · 広島県北広島町',1485,1,'北広島町','キタヒロシマチョウ','Kitahiroshima-cho',34.674541,132.538427),(1493,'344311','34','Osakikamijima-cho',NULL,'Osakikamijima-cho, Hiroshima · 広島県大崎上島町',1486,1,'大崎上島町','オオサキカミジマチョウ','Osakikamijima-cho',34.269552,132.914963),(1494,'344621','34','Sera-cho',NULL,'Sera-cho, Hiroshima · 広島県世羅町',1487,1,'世羅町','セラチョウ','Sera-cho',34.586837,133.056631),(1495,'345458','34','Jinsekikogen-cho',NULL,'Jinsekikogen-cho, Hiroshima · 広島県神石高原町',1488,1,'神石高原町','ジンセキコウゲンチョウ','Jinsekikogen-cho',34.703641,133.251527),(1496,'352012','35','Shimonoseki-shi',NULL,'Shimonoseki-shi, Yamaguchi · 山口県下関市',1489,1,'下関市','シモノセキシ','Shimonoseki-shi',33.957828,130.941459),(1497,'352021','35','Ube-shi',NULL,'Ube-shi, Yamaguchi · 山口県宇部市',1490,1,'宇部市','ウベシ','Ube-shi',33.951623,131.246761),(1498,'352039','35','Yamaguchi-shi',NULL,'Yamaguchi-shi, Yamaguchi · 山口県山口市',1491,1,'山口市','ヤマグチシ','Yamaguchi-shi',34.178178,131.473511),(1499,'352047','35','Hagi-shi',NULL,'Hagi-shi, Yamaguchi · 山口県萩市',1492,1,'萩市','ハギシ','Hagi-shi',34.408107,131.399104),(1500,'352063','35','Hofu-shi',NULL,'Hofu-shi, Yamaguchi · 山口県防府市',1493,1,'防府市','ホウフシ','Hofu-shi',34.051756,131.562627),(1501,'352071','35','Kudamatsu-shi',NULL,'Kudamatsu-shi, Yamaguchi · 山口県下松市',1494,1,'下松市','クダマツシ','Kudamatsu-shi',34.015038,131.870317),(1502,'352080','35','Iwakuni-shi',NULL,'Iwakuni-shi, Yamaguchi · 山口県岩国市',1495,1,'岩国市','イワクニシ','Iwakuni-shi',34.166500,132.218942),(1503,'352101','35','Hikari-shi',NULL,'Hikari-shi, Yamaguchi · 山口県光市',1496,1,'光市','ヒカリシ','Hikari-shi',33.961916,131.942296),(1504,'352110','35','Nagato-shi',NULL,'Nagato-shi, Yamaguchi · 山口県長門市',1497,1,'長門市','ナガトシ','Nagato-shi',34.370959,131.182198),(1505,'352128','35','Yanai-shi',NULL,'Yanai-shi, Yamaguchi · 山口県柳井市',1498,1,'柳井市','ヤナイシ','Yanai-shi',33.963839,132.101597),(1506,'352136','35','Mine-shi',NULL,'Mine-shi, Yamaguchi · 山口県美祢市',1499,1,'美祢市','ミネシ','Mine-shi',34.166670,131.205703),(1507,'352152','35','Shunan-shi',NULL,'Shunan-shi, Yamaguchi · 山口県周南市',1500,1,'周南市','シュウナンシ','Shunan-shi',34.055135,131.806293),(1508,'352161','35','Sanyoonoda-shi',NULL,'Sanyoonoda-shi, Yamaguchi · 山口県山陽小野田市',1501,1,'山陽小野田市','サンヨウオノダシ','Sanyoonoda-shi',34.003089,131.181786),(1509,'353051','35','Suooshima-cho',NULL,'Suooshima-cho, Yamaguchi · 山口県周防大島町',1502,1,'周防大島町','スオウオオシマチョウ','Suooshima-cho',33.927628,132.195320),(1510,'353213','35','Waki-cho',NULL,'Waki-cho, Yamaguchi · 山口県和木町',1503,1,'和木町','ワキチョウ','Waki-cho',34.202408,132.220405),(1511,'353418','35','Kaminoseki-cho',NULL,'Kaminoseki-cho, Yamaguchi · 山口県上関町',1504,1,'上関町','カミノセキチョウ','Kaminoseki-cho',33.830801,132.110193),(1512,'353434','35','Tabuse-cho',NULL,'Tabuse-cho, Yamaguchi · 山口県田布施町',1505,1,'田布施町','タブセチョウ','Tabuse-cho',33.954677,132.041366),(1513,'353442','35','Hirao-cho',NULL,'Hirao-cho, Yamaguchi · 山口県平生町',1506,1,'平生町','ヒラオチョウ','Hirao-cho',33.937969,132.073057),(1514,'355020','35','Abu-cho',NULL,'Abu-cho, Yamaguchi · 山口県阿武町',1507,1,'阿武町','アブチョウ','Abu-cho',34.503352,131.471360),(1515,'362018','36','Tokushima-shi',NULL,'Tokushima-shi, Tokushima · 徳島県徳島市',1508,1,'徳島市','トクシマシ','Tokushima-shi',34.070234,134.554713),(1516,'362026','36','Naruto-shi',NULL,'Naruto-shi, Tokushima · 徳島県鳴門市',1509,1,'鳴門市','ナルトシ','Naruto-shi',34.172590,134.608800),(1517,'362034','36','Komatsushima-shi',NULL,'Komatsushima-shi, Tokushima · 徳島県小松島市',1510,1,'小松島市','コマツシマシ','Komatsushima-shi',34.004713,134.590603),(1518,'362042','36','Anan-shi',NULL,'Anan-shi, Tokushima · 徳島県阿南市',1511,1,'阿南市','アナンシ','Anan-shi',33.921762,134.659566),(1519,'362051','36','Yoshinogawa-shi',NULL,'Yoshinogawa-shi, Tokushima · 徳島県吉野川市',1512,1,'吉野川市','ヨシノガワシ','Yoshinogawa-shi',34.066235,134.358667),(1520,'362069','36','Awa-shi',NULL,'Awa-shi, Tokushima · 徳島県阿波市',1513,1,'阿波市','アワシ','Awa-shi',34.102131,134.297578),(1521,'362077','36','Mima-shi',NULL,'Mima-shi, Tokushima · 徳島県美馬市',1514,1,'美馬市','ミマシ','Mima-shi',34.053387,134.169725),(1522,'362085','36','Miyoshi-shi',NULL,'Miyoshi-shi, Tokushima · 徳島県三好市',1515,1,'三好市','ミヨシシ','Miyoshi-shi',34.026044,133.807180),(1523,'363014','36','Katsura-cho',NULL,'Katsura-cho, Tokushima · 徳島県勝浦町',1516,1,'勝浦町','カツウラチョウ','Katsura-cho',33.931453,134.511251),(1524,'363022','36','Kamikatsu-cho',NULL,'Kamikatsu-cho, Tokushima · 徳島県上勝町',1517,1,'上勝町','カミカツチョウ','Kamikatsu-cho',33.888841,134.401819),(1525,'363219','36','Sanagochi-son',NULL,'Sanagochi-son, Tokushima · 徳島県佐那河内村',1518,1,'佐那河内村','サナゴウチソン','Sanagochi-son',33.993775,134.457555),(1526,'363413','36','Ishii-cho',NULL,'Ishii-cho, Tokushima · 徳島県石井町',1519,1,'石井町','イシイチョウ','Ishii-cho',34.074698,134.440764),(1527,'363421','36','Kamiyama-cho',NULL,'Kamiyama-cho, Tokushima · 徳島県神山町',1520,1,'神山町','カミヤマチョウ','Kamiyama-cho',33.967225,134.350531),(1528,'363685','36','Naka-cho',NULL,'Naka-cho, Tokushima · 徳島県那賀町',1521,1,'那賀町','ナカチョウ','Naka-cho',33.857497,134.496736),(1529,'363839','36','Mugi-cho',NULL,'Mugi-cho, Tokushima · 徳島県牟岐町',1522,1,'牟岐町','ムギチョウ','Mugi-cho',33.668312,134.420661),(1530,'363871','36','Minami-cho',NULL,'Minami-cho, Tokushima · 徳島県美波町',1523,1,'美波町','ミナミチョウ','Minami-cho',33.734573,134.535413),(1531,'363880','36','Kaiyo-cho',NULL,'Kaiyo-cho, Tokushima · 徳島県海陽町',1524,1,'海陽町','カイヨウチョウ','Kaiyo-cho',33.602043,134.351866),(1532,'364011','36','Matsushige-cho',NULL,'Matsushige-cho, Tokushima · 徳島県松茂町',1525,1,'松茂町','マツシゲチョウ','Matsushige-cho',34.133849,134.580537),(1533,'364029','36','Kitajima-cho',NULL,'Kitajima-cho, Tokushima · 徳島県北島町',1526,1,'北島町','キタジマチョウ','Kitajima-cho',34.125594,134.546985),(1534,'364037','36','Aizumi-cho',NULL,'Aizumi-cho, Tokushima · 徳島県藍住町',1527,1,'藍住町','アイズミチョウ','Aizumi-cho',34.126776,134.495112),(1535,'364045','36','Itano-cho',NULL,'Itano-cho, Tokushima · 徳島県板野町',1528,1,'板野町','イタノチョウ','Itano-cho',34.144364,134.462616),(1536,'364053','36','Kamiita-cho',NULL,'Kamiita-cho, Tokushima · 徳島県上板町',1529,1,'上板町','カミイタチョウ','Kamiita-cho',34.121365,134.404999),(1537,'364681','36','Tsurugi-cho',NULL,'Tsurugi-cho, Tokushima · 徳島県つるぎ町',1530,1,'つるぎ町','ツルギチョウ','Tsurugi-cho',34.037307,134.064051),(1538,'364894','36','Higashimiyoshi-cho',NULL,'Higashimiyoshi-cho, Tokushima · 徳島県東みよし町',1531,1,'東みよし町','ヒガシミヨシチョウ','Higashimiyoshi-cho',34.036785,133.936760),(1539,'372013','37','Takamatsu-shi',NULL,'Takamatsu-shi, Kagawa · 香川県高松市',1532,1,'高松市','タカマツシ','Takamatsu-shi',34.342791,134.046574),(1540,'372021','37','Marugame-shi',NULL,'Marugame-shi, Kagawa · 香川県丸亀市',1533,1,'丸亀市','マルガメシ','Marugame-shi',34.289878,133.798708),(1541,'372030','37','Sakaide-shi',NULL,'Sakaide-shi, Kagawa · 香川県坂出市',1534,1,'坂出市','サカイデシ','Sakaide-shi',34.316478,133.860502),(1542,'372048','37','Zentsuji-shi',NULL,'Zentsuji-shi, Kagawa · 香川県善通寺市',1535,1,'善通寺市','ゼンツウジシ','Zentsuji-shi',34.228417,133.787153),(1543,'372056','37','Kanonji-shi',NULL,'Kanonji-shi, Kagawa · 香川県観音寺市',1536,1,'観音寺市','カンオンジシ','Kanonji-shi',34.127370,133.661597),(1544,'372064','37','Sanuki-shi',NULL,'Sanuki-shi, Kagawa · 香川県さぬき市',1537,1,'さぬき市','サヌキシ','Sanuki-shi',34.325210,134.171989),(1545,'372072','37','Higashikagawa-shi',NULL,'Higashikagawa-shi, Kagawa · 香川県東かがわ市',1538,1,'東かがわ市','ヒガシカガワシ','Higashikagawa-shi',34.243803,134.358837),(1546,'372081','37','Mitoyo-shi',NULL,'Mitoyo-shi, Kagawa · 香川県三豊市',1539,1,'三豊市','ミトヨシ','Mitoyo-shi',34.182556,133.715203),(1547,'373222','37','Tonosho-cho',NULL,'Tonosho-cho, Kagawa · 香川県土庄町',1540,1,'土庄町','トノショウチョウ','Tonosho-cho',34.486626,134.188626),(1548,'373249','37','Shodoshima-cho',NULL,'Shodoshima-cho, Kagawa · 香川県小豆島町',1541,1,'小豆島町','ショウドシマチョウ','Shodoshima-cho',34.479684,134.308875),(1549,'373419','37','Miki-cho',NULL,'Miki-cho, Kagawa · 香川県三木町',1542,1,'三木町','ミキチョウ','Miki-cho',34.268355,134.134363),(1550,'373648','37','Naoshima-cho',NULL,'Naoshima-cho, Kagawa · 香川県直島町',1543,1,'直島町','ナオシマチョウ','Naoshima-cho',34.459830,133.995638),(1551,'373869','37','Utazu-cho',NULL,'Utazu-cho, Kagawa · 香川県宇多津町',1544,1,'宇多津町','ウタヅチョウ','Utazu-cho',34.310301,133.825566),(1552,'373877','37','Ayagawa-cho',NULL,'Ayagawa-cho, Kagawa · 香川県綾川町',1545,1,'綾川町','アヤガワチョウ','Ayagawa-cho',34.249613,133.923099),(1553,'374032','37','Kotohira-cho',NULL,'Kotohira-cho, Kagawa · 香川県琴平町',1546,1,'琴平町','コトヒラチョウ','Kotohira-cho',34.191420,133.823303),(1554,'374041','37','Tadotsu-cho',NULL,'Tadotsu-cho, Kagawa · 香川県多度津町',1547,1,'多度津町','タドツチョウ','Tadotsu-cho',34.272919,133.758668),(1555,'374067','37','Manno-cho',NULL,'Manno-cho, Kagawa · 香川県まんのう町',1548,1,'まんのう町','マンノウチョウ','Manno-cho',34.192334,133.841393),(1556,'382019','38','Matsuyama-shi',NULL,'Matsuyama-shi, Ehime · 愛媛県松山市',1549,1,'松山市','マツヤマシ','Matsuyama-shi',33.839157,132.765556),(1557,'382027','38','Imabari-shi',NULL,'Imabari-shi, Ehime · 愛媛県今治市',1550,1,'今治市','イマバリシ','Imabari-shi',34.066043,132.997658),(1558,'382035','38','Uwajima-shi',NULL,'Uwajima-shi, Ehime · 愛媛県宇和島市',1551,1,'宇和島市','ウワジマシ','Uwajima-shi',33.223340,132.560563),(1559,'382043','38','Yawatahama-shi',NULL,'Yawatahama-shi, Ehime · 愛媛県八幡浜市',1552,1,'八幡浜市','ヤワタハマシ','Yawatahama-shi',33.462898,132.423334),(1560,'382051','38','Niihama-shi',NULL,'Niihama-shi, Ehime · 愛媛県新居浜市',1553,1,'新居浜市','ニイハマシ','Niihama-shi',33.960329,133.283379),(1561,'382060','38','Saijo-shi',NULL,'Saijo-shi, Ehime · 愛媛県西条市',1554,1,'西条市','サイジョウシ','Saijo-shi',33.919615,133.181186),(1562,'382078','38','Ozu-shi',NULL,'Ozu-shi, Ehime · 愛媛県大洲市',1555,1,'大洲市','オオズシ','Ozu-shi',33.506285,132.544514),(1563,'382108','38','Iyo-shi',NULL,'Iyo-shi, Ehime · 愛媛県伊予市',1556,1,'伊予市','イヨシ','Iyo-shi',33.757461,132.703778),(1564,'382132','38','Shikokuchuo-shi',NULL,'Shikokuchuo-shi, Ehime · 愛媛県四国中央市',1557,1,'四国中央市','シコクチュウオウシ','Shikokuchuo-shi',33.980694,133.549188),(1565,'382141','38','Seiyo-shi',NULL,'Seiyo-shi, Ehime · 愛媛県西予市',1558,1,'西予市','セイヨシ','Seiyo-shi',33.363017,132.510967),(1566,'382159','38','Toon-shi',NULL,'Toon-shi, Ehime · 愛媛県東温市',1559,1,'東温市','トウオンシ','Toon-shi',33.791027,132.872299),(1567,'383562','38','Kamijima-cho',NULL,'Kamijima-cho, Ehime · 愛媛県上島町',1560,1,'上島町','カミジマチョウ','Kamijima-cho',34.257467,133.204492),(1568,'383864','38','Kumakogen-cho',NULL,'Kumakogen-cho, Ehime · 愛媛県久万高原町',1561,1,'久万高原町','クマコウゲンチョウ','Kumakogen-cho',33.655603,132.901665),(1569,'384011','38','Masaki-cho',NULL,'Masaki-cho, Ehime · 愛媛県松前町',1562,1,'松前町','マサキチョウ','Masaki-cho',33.787479,132.711364),(1570,'384020','38','Tobe-cho',NULL,'Tobe-cho, Ehime · 愛媛県砥部町',1563,1,'砥部町','トベチョウ','Tobe-cho',33.749260,132.792233),(1571,'384224','38','Uchiko-cho',NULL,'Uchiko-cho, Ehime · 愛媛県内子町',1564,1,'内子町','ウチコチョウ','Uchiko-cho',33.532937,132.658083),(1572,'384429','38','Ikata-cho',NULL,'Ikata-cho, Ehime · 愛媛県伊方町',1565,1,'伊方町','イカタチョウ','Ikata-cho',33.488593,132.353998),(1573,'384844','38','Matsuno-cho',NULL,'Matsuno-cho, Ehime · 愛媛県松野町',1566,1,'松野町','マツノチョウ','Matsuno-cho',33.227150,132.710935),(1574,'384887','38','Kihoku-cho',NULL,'Kihoku-cho, Ehime · 愛媛県鬼北町',1567,1,'鬼北町','キホクチョウ','Kihoku-cho',33.255792,132.684032),(1575,'385069','38','Ainan-cho',NULL,'Ainan-cho, Ehime · 愛媛県愛南町',1568,1,'愛南町','アイナンチョウ','Ainan-cho',32.962168,132.583326),(1576,'392014','39','Kochi-shi',NULL,'Kochi-shi, Kochi · 高知県高知市',1569,1,'高知市','コウチシ','Kochi-shi',33.558788,133.531166),(1577,'392022','39','Muroto-shi',NULL,'Muroto-shi, Kochi · 高知県室戸市',1570,1,'室戸市','ムロトシ','Muroto-shi',33.289955,134.151981),(1578,'392031','39','Aki-shi',NULL,'Aki-shi, Kochi · 高知県安芸市',1571,1,'安芸市','アキシ','Aki-shi',33.502416,133.907074),(1579,'392049','39','Nankoku-shi',NULL,'Nankoku-shi, Kochi · 高知県南国市',1572,1,'南国市','ナンコクシ','Nankoku-shi',33.575673,133.641460),(1580,'392057','39','Tosa-shi',NULL,'Tosa-shi, Kochi · 高知県土佐市',1573,1,'土佐市','トサシ','Tosa-shi',33.496012,133.425326),(1581,'392065','39','Susaki-shi',NULL,'Susaki-shi, Kochi · 高知県須崎市',1574,1,'須崎市','スサキシ','Susaki-shi',33.400745,133.282959),(1582,'392081','39','Sukumo-shi',NULL,'Sukumo-shi, Kochi · 高知県宿毛市',1575,1,'宿毛市','スクモシ','Sukumo-shi',32.933992,132.702050),(1583,'392090','39','Tosashimizu-shi',NULL,'Tosashimizu-shi, Kochi · 高知県土佐清水市',1576,1,'土佐清水市','トサシミズシ','Tosashimizu-shi',32.781603,132.955100),(1584,'392103','39','Shimanto-shi',NULL,'Shimanto-shi, Kochi · 高知県四万十市',1577,1,'四万十市','シマントシ','Shimanto-shi',32.991385,132.933750),(1585,'392111','39','Konan-shi',NULL,'Konan-shi, Kochi · 高知県香南市',1578,1,'香南市','コウナンシ','Konan-shi',33.564124,133.700543),(1586,'392120','39','Kami-shi',NULL,'Kami-shi, Kochi · 高知県香美市',1579,1,'香美市','カミシ','Kami-shi',33.603860,133.686245),(1587,'393011','39','Toyo-cho',NULL,'Toyo-cho, Kochi · 高知県東洋町',1580,1,'東洋町','トウヨウチョウ','Toyo-cho',33.527976,134.279968),(1588,'393029','39','Nahari-cho',NULL,'Nahari-cho, Kochi · 高知県奈半利町',1581,1,'奈半利町','ナハリチョウ','Nahari-cho',33.424179,134.020957),(1589,'393037','39','Tano-cho',NULL,'Tano-cho, Kochi · 高知県田野町',1582,1,'田野町','タノチョウ','Tano-cho',33.427713,134.008209),(1590,'393045','39','Yasuda-cho',NULL,'Yasuda-cho, Kochi · 高知県安田町',1583,1,'安田町','ヤスダチョウ','Yasuda-cho',33.438601,133.981080),(1591,'393053','39','Kitagawa-mura',NULL,'Kitagawa-mura, Kochi · 高知県北川村',1584,1,'北川村','キタガワムラ','Kitagawa-mura',33.447694,134.042069),(1592,'393061','39','Umaji-mura',NULL,'Umaji-mura, Kochi · 高知県馬路村',1585,1,'馬路村','ウマジムラ','Umaji-mura',33.555347,134.048114),(1593,'393070','39','Geisei-mura',NULL,'Geisei-mura, Kochi · 高知県芸西村',1586,1,'芸西村','ゲイセイムラ','Geisei-mura',33.526915,133.808942),(1594,'393410','39','Motoyama-cho',NULL,'Motoyama-cho, Kochi · 高知県本山町',1587,1,'本山町','モトヤマチョウ','Motoyama-cho',33.759642,133.586728),(1595,'393444','39','Otoyo-cho',NULL,'Otoyo-cho, Kochi · 高知県大豊町',1588,1,'大豊町','オオトヨチョウ','Otoyo-cho',33.768535,133.642958),(1596,'393631','39','Tosa-cho',NULL,'Tosa-cho, Kochi · 高知県土佐町',1589,1,'土佐町','トサチョウ','Tosa-cho',33.736896,133.532107),(1597,'393649','39','Okawa-mura',NULL,'Okawa-mura, Kochi · 高知県大川村',1590,1,'大川村','オオカワムラ','Okawa-mura',33.783927,133.466613),(1598,'393860','39','Ino-cho',NULL,'Ino-cho, Kochi · 高知県いの町',1591,1,'いの町','イノチョウ','Ino-cho',33.548689,133.427583),(1599,'393878','39','Niyodogawa-cho',NULL,'Niyodogawa-cho, Kochi · 高知県仁淀川町',1592,1,'仁淀川町','ニヨドガワチョウ','Niyodogawa-cho',33.575226,133.171104),(1600,'394017','39','Nakatosa-cho',NULL,'Nakatosa-cho, Kochi · 高知県中土佐町',1593,1,'中土佐町','ナカトサチョウ','Nakatosa-cho',33.329246,133.224823),(1601,'394025','39','Sakawa-cho',NULL,'Sakawa-cho, Kochi · 高知県佐川町',1594,1,'佐川町','サカワチョウ','Sakawa-cho',33.500809,133.286642),(1602,'394033','39','Ochi-cho',NULL,'Ochi-cho, Kochi · 高知県越知町',1595,1,'越知町','オチチョウ','Ochi-cho',33.532848,133.252221),(1603,'394050','39','Yusuhara-cho',NULL,'Yusuhara-cho, Kochi · 高知県檮原町',1596,1,'檮原町','ユスハラチョウ','Yusuhara-cho',33.392170,132.927065),(1604,'394106','39','Hidaka-mura',NULL,'Hidaka-mura, Kochi · 高知県日高村',1597,1,'日高村','ヒダカムラ','Hidaka-mura',33.534833,133.373342),(1605,'394114','39','Tsuno-cho',NULL,'Tsuno-cho, Kochi · 高知県津野町',1598,1,'津野町','ツノチョウ','Tsuno-cho',33.446666,133.199377),(1606,'394122','39','Shimanto-cho',NULL,'Shimanto-cho, Kochi · 高知県四万十町',1599,1,'四万十町','シマントチョウ','Shimanto-cho',33.211574,133.137018),(1607,'394246','39','Otsuki-cho',NULL,'Otsuki-cho, Kochi · 高知県大月町',1600,1,'大月町','オオツキチョウ','Otsuki-cho',32.841534,132.707092),(1608,'394271','39','Mihara-mura',NULL,'Mihara-mura, Kochi · 高知県三原村',1601,1,'三原村','ミハラムラ','Mihara-mura',32.906019,132.847233),(1609,'394289','39','Kuroshio-cho',NULL,'Kuroshio-cho, Kochi · 高知県黒潮町',1602,1,'黒潮町','クロシオチョウ','Kuroshio-cho',33.024929,133.004184),(1610,'401013','40','Moji-ku, Kitakyushu-shi',NULL,'Moji-ku, Kitakyushu-shi, Fukuoka · 福岡県北九州市門司区',1603,1,'北九州市門司区','キタキュウシュウシモジク','Moji-ku, Kitakyushu-shi',33.941238,130.959546),(1611,'401030','40','Wakamatsu-ku, Kitakyushu-shi',NULL,'Wakamatsu-ku, Kitakyushu-shi, Fukuoka · 福岡県北九州市若松区',1604,1,'北九州市若松区','キタキュウシュウシワカマツク','Wakamatsu-ku, Kitakyushu-shi',33.905435,130.811221),(1612,'401056','40','Tobata-ku, Kitakyushu-shi',NULL,'Tobata-ku, Kitakyushu-shi, Fukuoka · 福岡県北九州市戸畑区',1605,1,'北九州市戸畑区','キタキュウシュウシトバタク','Tobata-ku, Kitakyushu-shi',33.893442,130.829800),(1613,'401064','40','Kokurakita-ku, Kitakyushu-shi',NULL,'Kokurakita-ku, Kitakyushu-shi, Fukuoka · 福岡県北九州市小倉北区',1606,1,'北九州市小倉北区','キタキュウシュウシコクラキタク','Kokurakita-ku, Kitakyushu-shi',33.880890,130.873361),(1614,'401072','40','Kokuraminami-ku, Kitakyushu-shi',NULL,'Kokuraminami-ku, Kitakyushu-shi, Fukuoka · 福岡県北九州市小倉南区',1607,1,'北九州市小倉南区','キタキュウシュウシコクラミナミク','Kokuraminami-ku, Kitakyushu-shi',33.846525,130.884808),(1615,'401081','40','Yahatahigashi-ku, Kitakyushu-shi',NULL,'Yahatahigashi-ku, Kitakyushu-shi, Fukuoka · 福岡県北九州市八幡東区',1608,1,'北九州市八幡東区','キタキュウシュウシヤハタヒガシク','Yahatahigashi-ku, Kitakyushu-shi',33.863540,130.811892),(1616,'401099','40','Yahatanishi-ku, Kitakyushu-shi',NULL,'Yahatanishi-ku, Kitakyushu-shi, Fukuoka · 福岡県北九州市八幡西区',1609,1,'北九州市八幡西区','キタキュウシュウシヤハタニシク','Yahatanishi-ku, Kitakyushu-shi',33.866505,130.765167),(1617,'401315','40','Higashi-ku, Fukuoka-shi',NULL,'Higashi-ku, Fukuoka-shi, Fukuoka · 福岡県福岡市東区',1610,1,'福岡市東区','フクオカシヒガシク','Higashi-ku, Fukuoka-shi',33.617744,130.417363),(1618,'401323','40','Hakata-ku, Fukuoka-shi',NULL,'Hakata-ku, Fukuoka-shi, Fukuoka · 福岡県福岡市博多区',1611,1,'福岡市博多区','フクオカシハカタク','Hakata-ku, Fukuoka-shi',33.590787,130.414372),(1619,'401331','40','Chuo-ku, Fukuoka-shi',NULL,'Chuo-ku, Fukuoka-shi, Fukuoka · 福岡県福岡市中央区',1612,1,'福岡市中央区','フクオカシチュウオウク','Chuo-ku, Fukuoka-shi',33.589238,130.392819),(1620,'401340','40','Minami-ku, Fukuoka-shi',NULL,'Minami-ku, Fukuoka-shi, Fukuoka · 福岡県福岡市南区',1613,1,'福岡市南区','フクオカシミナミク','Minami-ku, Fukuoka-shi',33.561561,130.426442),(1621,'401358','40','Nishi-ku, Fukuoka-shi',NULL,'Nishi-ku, Fukuoka-shi, Fukuoka · 福岡県福岡市西区',1614,1,'福岡市西区','フクオカシニシク','Nishi-ku, Fukuoka-shi',33.582917,130.323133),(1622,'401366','40','Jonan-ku, Fukuoka-shi',NULL,'Jonan-ku, Fukuoka-shi, Fukuoka · 福岡県福岡市城南区',1615,1,'福岡市城南区','フクオカシジョウナンク','Jonan-ku, Fukuoka-shi',33.575686,130.369912),(1623,'401374','40','Sawara-ku, Fukuoka-shi',NULL,'Sawara-ku, Fukuoka-shi, Fukuoka · 福岡県福岡市早良区',1616,1,'福岡市早良区','フクオカシサワラク','Sawara-ku, Fukuoka-shi',33.581944,130.348409),(1624,'402028','40','Omuta-shi',NULL,'Omuta-shi, Fukuoka · 福岡県大牟田市',1617,1,'大牟田市','オオムタシ','Omuta-shi',33.030248,130.446058),(1625,'402036','40','Kurume-shi',NULL,'Kurume-shi, Fukuoka · 福岡県久留米市',1618,1,'久留米市','クルメシ','Kurume-shi',33.319286,130.508371),(1626,'402044','40','Nogata-shi',NULL,'Nogata-shi, Fukuoka · 福岡県直方市',1619,1,'直方市','ノオガタシ','Nogata-shi',33.744181,130.729652),(1627,'402052','40','Iizuka-shi',NULL,'Iizuka-shi, Fukuoka · 福岡県飯塚市',1620,1,'飯塚市','イイヅカシ','Iizuka-shi',33.646083,130.691422),(1628,'402061','40','Tagawa-shi',NULL,'Tagawa-shi, Fukuoka · 福岡県田川市',1621,1,'田川市','タガワシ','Tagawa-shi',33.638760,130.806298),(1629,'402079','40','Yanagawa-shi',NULL,'Yanagawa-shi, Fukuoka · 福岡県柳川市',1622,1,'柳川市','ヤナガワシ','Yanagawa-shi',33.163064,130.405739),(1630,'402109','40','Yame-shi',NULL,'Yame-shi, Fukuoka · 福岡県八女市',1623,1,'八女市','ヤメシ','Yame-shi',33.211952,130.557912),(1631,'402117','40','Chikugo-shi',NULL,'Chikugo-shi, Fukuoka · 福岡県筑後市',1624,1,'筑後市','チクゴシ','Chikugo-shi',33.212246,130.502151),(1632,'402125','40','Okawa-shi',NULL,'Okawa-shi, Fukuoka · 福岡県大川市',1625,1,'大川市','オオカワシ','Okawa-shi',33.206622,130.383922),(1633,'402133','40','Yukuhashi-shi',NULL,'Yukuhashi-shi, Fukuoka · 福岡県行橋市',1626,1,'行橋市','ユクハシシ','Yukuhashi-shi',33.728762,130.982963),(1634,'402141','40','Buzen-shi',NULL,'Buzen-shi, Fukuoka · 福岡県豊前市',1627,1,'豊前市','ブゼンシ','Buzen-shi',33.611538,131.129941),(1635,'402150','40','Nakama-shi',NULL,'Nakama-shi, Fukuoka · 福岡県中間市',1628,1,'中間市','ナカマシ','Nakama-shi',33.816699,130.709026),(1636,'402168','40','Ogori-shi',NULL,'Ogori-shi, Fukuoka · 福岡県小郡市',1629,1,'小郡市','オゴオリシ','Ogori-shi',33.396450,130.555541),(1637,'402176','40','Chikushino-shi',NULL,'Chikushino-shi, Fukuoka · 福岡県筑紫野市',1630,1,'筑紫野市','チクシノシ','Chikushino-shi',33.487473,130.526038),(1638,'402184','40','Kasuga-shi',NULL,'Kasuga-shi, Fukuoka · 福岡県春日市',1631,1,'春日市','カスガシ','Kasuga-shi',33.532629,130.470368),(1639,'402192','40','Onojo-shi',NULL,'Onojo-shi, Fukuoka · 福岡県大野城市',1632,1,'大野城市','オオノジョウシ','Onojo-shi',33.536290,130.478697),(1640,'402206','40','Munakata-shi',NULL,'Munakata-shi, Fukuoka · 福岡県宗像市',1633,1,'宗像市','ムナカタシ','Munakata-shi',33.805448,130.540651),(1641,'402214','40','Dazaifu-shi',NULL,'Dazaifu-shi, Fukuoka · 福岡県太宰府市',1634,1,'太宰府市','ダザイフシ','Dazaifu-shi',33.512835,130.523876),(1642,'402231','40','Koga-shi',NULL,'Koga-shi, Fukuoka · 福岡県古賀市',1635,1,'古賀市','コガシ','Koga-shi',33.728664,130.469995),(1643,'402249','40','Fukutsu-shi',NULL,'Fukutsu-shi, Fukuoka · 福岡県福津市',1636,1,'福津市','フクツシ','Fukutsu-shi',33.766897,130.491038),(1644,'402257','40','Ukiha-shi',NULL,'Ukiha-shi, Fukuoka · 福岡県うきは市',1637,1,'うきは市','ウキハシ','Ukiha-shi',33.347305,130.754928),(1645,'402265','40','Miyawaka-shi',NULL,'Miyawaka-shi, Fukuoka · 福岡県宮若市',1638,1,'宮若市','ミヤワカシ','Miyawaka-shi',33.723523,130.666716),(1646,'402273','40','Kama-shi',NULL,'Kama-shi, Fukuoka · 福岡県嘉麻市',1639,1,'嘉麻市','カマシ','Kama-shi',33.598437,130.719189),(1647,'402281','40','Asakura-shi',NULL,'Asakura-shi, Fukuoka · 福岡県朝倉市',1640,1,'朝倉市','アサクラシ','Asakura-shi',33.423412,130.665573),(1648,'402290','40','Miyama-shi',NULL,'Miyama-shi, Fukuoka · 福岡県みやま市',1641,1,'みやま市','ミヤマシ','Miyama-shi',33.152434,130.474734),(1649,'402303','40','Itoshima-shi',NULL,'Itoshima-shi, Fukuoka · 福岡県糸島市',1642,1,'糸島市','イトシマシ','Itoshima-shi',33.557450,130.195500),(1650,'402311','40','Nakagawa-shi',NULL,'Nakagawa-shi, Fukuoka · 福岡県那珂川市',1643,1,'那珂川市','ナカガワシ','Nakagawa-shi',33.499597,130.422200),(1651,'403415','40','Umi-machi',NULL,'Umi-machi, Fukuoka · 福岡県宇美町',1644,1,'宇美町','ウミマチ','Umi-machi',33.567768,130.511208),(1652,'403423','40','Sasaguri-machi',NULL,'Sasaguri-machi, Fukuoka · 福岡県篠栗町',1645,1,'篠栗町','ササグリマチ','Sasaguri-machi',33.623869,130.526207),(1653,'403431','40','Shime-machi',NULL,'Shime-machi, Fukuoka · 福岡県志免町',1646,1,'志免町','シメマチ','Shime-machi',33.591503,130.479807),(1654,'403440','40','Sue-machi',NULL,'Sue-machi, Fukuoka · 福岡県須恵町',1647,1,'須恵町','スエマチ','Sue-machi',33.587268,130.507234),(1655,'403458','40','Shingu-machi',NULL,'Shingu-machi, Fukuoka · 福岡県新宮町',1648,1,'新宮町','シングウマチ','Shingu-machi',33.715313,130.446567),(1656,'403482','40','Hisayama-machi',NULL,'Hisayama-machi, Fukuoka · 福岡県久山町',1649,1,'久山町','ヒサヤママチ','Hisayama-machi',33.646726,130.499906),(1657,'403491','40','Kasuya-machi',NULL,'Kasuya-machi, Fukuoka · 福岡県粕屋町',1650,1,'粕屋町','カスヤマチ','Kasuya-machi',33.610854,130.480582),(1658,'403814','40','Ashiya-machi',NULL,'Ashiya-machi, Fukuoka · 福岡県芦屋町',1651,1,'芦屋町','アシヤマチ','Ashiya-machi',33.893862,130.663874),(1659,'403822','40','Mizumaki-machi',NULL,'Mizumaki-machi, Fukuoka · 福岡県水巻町',1652,1,'水巻町','ミズマキマチ','Mizumaki-machi',33.854850,130.694783),(1660,'403831','40','Okagaki-machi',NULL,'Okagaki-machi, Fukuoka · 福岡県岡垣町',1653,1,'岡垣町','オカガキマチ','Okagaki-machi',33.853491,130.611749),(1661,'403849','40','Onga-cho',NULL,'Onga-cho, Fukuoka · 福岡県遠賀町',1654,1,'遠賀町','オンガチョウ','Onga-cho',33.848166,130.668341),(1662,'404012','40','Kotake-machi',NULL,'Kotake-machi, Fukuoka · 福岡県小竹町',1655,1,'小竹町','コタケマチ','Kotake-machi',33.696521,130.707988),(1663,'404021','40','Kurate-machi',NULL,'Kurate-machi, Fukuoka · 福岡県鞍手町',1656,1,'鞍手町','クラテマチ','Kurate-machi',33.792112,130.674008),(1664,'404217','40','Keisen-machi',NULL,'Keisen-machi, Fukuoka · 福岡県桂川町',1657,1,'桂川町','ケイセンマチ','Keisen-machi',33.578889,130.678118),(1665,'404471','40','Chikuzen-machi',NULL,'Chikuzen-machi, Fukuoka · 福岡県筑前町',1658,1,'筑前町','チクゼンマチ','Chikuzen-machi',33.457033,130.595183),(1666,'404489','40','Toho-mura',NULL,'Toho-mura, Fukuoka · 福岡県東峰村',1659,1,'東峰村','トウホウムラ','Toho-mura',33.397314,130.869919),(1667,'405035','40','Tachiarai-machi',NULL,'Tachiarai-machi, Fukuoka · 福岡県大刀洗町',1660,1,'大刀洗町','タチアライマチ','Tachiarai-machi',33.372425,130.622454),(1668,'405221','40','Oki-machi',NULL,'Oki-machi, Fukuoka · 福岡県大木町',1661,1,'大木町','オオキマチ','Oki-machi',33.210453,130.439806),(1669,'405442','40','Hirokawa-machi',NULL,'Hirokawa-machi, Fukuoka · 福岡県広川町',1662,1,'広川町','ヒロカワマチ','Hirokawa-machi',33.241499,130.551416),(1670,'406015','40','Kawara-machi',NULL,'Kawara-machi, Fukuoka · 福岡県香春町',1663,1,'香春町','カワラマチ','Kawara-machi',33.668006,130.847401),(1671,'406023','40','Soeda-machi',NULL,'Soeda-machi, Fukuoka · 福岡県添田町',1664,1,'添田町','ソエダマチ','Soeda-machi',33.571816,130.854088),(1672,'406040','40','Itoda-machi',NULL,'Itoda-machi, Fukuoka · 福岡県糸田町',1665,1,'糸田町','イトダマチ','Itoda-machi',33.652736,130.778958),(1673,'406058','40','Kawasaki-machi',NULL,'Kawasaki-machi, Fukuoka · 福岡県川崎町',1666,1,'川崎町','カワサキマチ','Kawasaki-machi',33.599956,130.814915),(1674,'406082','40','Oto-machi',NULL,'Oto-machi, Fukuoka · 福岡県大任町',1667,1,'大任町','オオトウマチ','Oto-machi',33.612164,130.853742),(1675,'406091','40','Aka-mura',NULL,'Aka-mura, Fukuoka · 福岡県赤村',1668,1,'赤村','アカムラ','Aka-mura',33.616692,130.870855),(1676,'406104','40','Fukuchi-machi',NULL,'Fukuchi-machi, Fukuoka · 福岡県福智町',1669,1,'福智町','フクチマチ','Fukuchi-machi',33.683263,130.780120),(1677,'406210','40','Kanda-machi',NULL,'Kanda-machi, Fukuoka · 福岡県苅田町',1670,1,'苅田町','カンダマチ','Kanda-machi',33.776006,130.980475),(1678,'406252','40','Miyako-machi',NULL,'Miyako-machi, Fukuoka · 福岡県みやこ町',1671,1,'みやこ町','ミヤコマチ','Miyako-machi',33.699236,130.920096),(1679,'406422','40','Yoshitomi-machi',NULL,'Yoshitomi-machi, Fukuoka · 福岡県吉富町',1672,1,'吉富町','ヨシトミマチ','Yoshitomi-machi',33.602643,131.175951),(1680,'406465','40','Koge-machi',NULL,'Koge-machi, Fukuoka · 福岡県上毛町',1673,1,'上毛町','コウゲマチ','Koge-machi',33.578425,131.164207),(1681,'406473','40','Chikujo-machi',NULL,'Chikujo-machi, Fukuoka · 福岡県築上町',1674,1,'築上町','チクジョウマチ','Chikujo-machi',33.656146,131.056040),(1682,'412015','41','Saga-shi',NULL,'Saga-shi, Saga · 佐賀県佐賀市',1675,1,'佐賀市','サガシ','Saga-shi',33.263543,130.300835),(1683,'412023','41','Karatsu-shi',NULL,'Karatsu-shi, Saga · 佐賀県唐津市',1676,1,'唐津市','カラツシ','Karatsu-shi',33.450103,129.967970),(1684,'412031','41','Tosu-shi',NULL,'Tosu-shi, Saga · 佐賀県鳥栖市',1677,1,'鳥栖市','トスシ','Tosu-shi',33.377758,130.506244),(1685,'412040','41','Taku-shi',NULL,'Taku-shi, Saga · 佐賀県多久市',1678,1,'多久市','タクシ','Taku-shi',33.288513,130.110061),(1686,'412058','41','Imari-shi',NULL,'Imari-shi, Saga · 佐賀県伊万里市',1679,1,'伊万里市','イマリシ','Imari-shi',33.264741,129.880702),(1687,'412066','41','Takeo-shi',NULL,'Takeo-shi, Saga · 佐賀県武雄市',1680,1,'武雄市','タケオシ','Takeo-shi',33.194733,130.021171),(1688,'412074','41','Kashima-shi',NULL,'Kashima-shi, Saga · 佐賀県鹿島市',1681,1,'鹿島市','カシマシ','Kashima-shi',33.103804,130.098603),(1689,'412082','41','Ogi-shi',NULL,'Ogi-shi, Saga · 佐賀県小城市',1682,1,'小城市','オギシ','Ogi-shi',33.273776,130.217280),(1690,'412091','41','Ureshino-shi',NULL,'Ureshino-shi, Saga · 佐賀県嬉野市',1683,1,'嬉野市','ウレシノシ','Ureshino-shi',33.128092,130.060120),(1691,'412104','41','Kanzaki-shi',NULL,'Kanzaki-shi, Saga · 佐賀県神埼市',1684,1,'神埼市','カンザキシ','Kanzaki-shi',33.311531,130.371811),(1692,'413275','41','Yoshinogari-cho',NULL,'Yoshinogari-cho, Saga · 佐賀県吉野ヶ里町',1685,1,'吉野ヶ里町','ヨシノガリチョウ','Yoshinogari-cho',33.321148,130.398753),(1693,'413411','41','Kiyama-cho',NULL,'Kiyama-cho, Saga · 佐賀県基山町',1686,1,'基山町','キヤマチョウ','Kiyama-cho',33.426944,130.523052),(1694,'413453','41','Kamimine-cho',NULL,'Kamimine-cho, Saga · 佐賀県上峰町',1687,1,'上峰町','カミミネチョウ','Kamimine-cho',33.319626,130.426137),(1695,'413461','41','Miyaki-cho',NULL,'Miyaki-cho, Saga · 佐賀県みやき町',1688,1,'みやき町','ミヤキチョウ','Miyaki-cho',33.324943,130.454590),(1696,'413879','41','Genkai-cho',NULL,'Genkai-cho, Saga · 佐賀県玄海町',1689,1,'玄海町','ゲンカイチョウ','Genkai-cho',33.472164,129.874689),(1697,'414018','41','Arita-cho',NULL,'Arita-cho, Saga · 佐賀県有田町',1690,1,'有田町','アリタチョウ','Arita-cho',33.210574,129.849014),(1698,'414239','41','Omachi-cho',NULL,'Omachi-cho, Saga · 佐賀県大町町',1691,1,'大町町','オオマチチョウ','Omachi-cho',33.213871,130.116032),(1699,'414247','41','Kohoku-machi',NULL,'Kohoku-machi, Saga · 佐賀県江北町',1692,1,'江北町','コウホクマチ','Kohoku-machi',33.220464,130.157310),(1700,'414255','41','Shiroishi-cho',NULL,'Shiroishi-cho, Saga · 佐賀県白石町',1693,1,'白石町','シロイシチョウ','Shiroishi-cho',33.181082,130.143493),(1701,'414417','41','Tara-cho',NULL,'Tara-cho, Saga · 佐賀県太良町',1694,1,'太良町','タラチョウ','Tara-cho',33.019436,130.179126),(1702,'422011','42','Nagasaki-shi',NULL,'Nagasaki-shi, Nagasaki · 長崎県長崎市',1695,1,'長崎市','ナガサキシ','Nagasaki-shi',32.749547,129.879636),(1703,'422029','42','Sasebo-shi',NULL,'Sasebo-shi, Nagasaki · 長崎県佐世保市',1696,1,'佐世保市','サセボシ','Sasebo-shi',33.179904,129.715090),(1704,'422037','42','Shimabara-shi',NULL,'Shimabara-shi, Nagasaki · 長崎県島原市',1697,1,'島原市','シマバラシ','Shimabara-shi',32.788120,130.370526),(1705,'422045','42','Isahaya-shi',NULL,'Isahaya-shi, Nagasaki · 長崎県諫早市',1698,1,'諫早市','イサハヤシ','Isahaya-shi',32.844208,130.053601),(1706,'422053','42','Omura-shi',NULL,'Omura-shi, Nagasaki · 長崎県大村市',1699,1,'大村市','オオムラシ','Omura-shi',32.899960,129.958217),(1707,'422070','42','Hirado-shi',NULL,'Hirado-shi, Nagasaki · 長崎県平戸市',1700,1,'平戸市','ヒラドシ','Hirado-shi',33.368065,129.553671),(1708,'422088','42','Matsura-shi',NULL,'Matsura-shi, Nagasaki · 長崎県松浦市',1701,1,'松浦市','マツウラシ','Matsura-shi',33.341023,129.709047),(1709,'422096','42','Tsushima-shi',NULL,'Tsushima-shi, Nagasaki · 長崎県対馬市',1702,1,'対馬市','ツシマシ','Tsushima-shi',34.202643,129.287518),(1710,'422100','42','Iki-shi',NULL,'Iki-shi, Nagasaki · 長崎県壱岐市',1703,1,'壱岐市','イキシ','Iki-shi',33.749978,129.691377),(1711,'422118','42','Goto-shi',NULL,'Goto-shi, Nagasaki · 長崎県五島市',1704,1,'五島市','ゴトウシ','Goto-shi',32.695541,128.840815),(1712,'422126','42','Saikai-shi',NULL,'Saikai-shi, Nagasaki · 長崎県西海市',1705,1,'西海市','サイカイシ','Saikai-shi',32.933117,129.642962),(1713,'422134','42','Unzen-shi',NULL,'Unzen-shi, Nagasaki · 長崎県雲仙市',1706,1,'雲仙市','ウンゼンシ','Unzen-shi',32.835214,130.187508),(1714,'422142','42','Minamishimabara-shi',NULL,'Minamishimabara-shi, Nagasaki · 長崎県南島原市',1707,1,'南島原市','ミナミシマバラシ','Minamishimabara-shi',32.659716,130.297783),(1715,'423076','42','Nagayo-cho',NULL,'Nagayo-cho, Nagasaki · 長崎県長与町',1708,1,'長与町','ナガヨチョウ','Nagayo-cho',32.825170,129.875071),(1716,'423084','42','Togitsu-cho',NULL,'Togitsu-cho, Nagasaki · 長崎県時津町',1709,1,'時津町','トギツチョウ','Togitsu-cho',32.828885,129.848527),(1717,'423211','42','Higashisonogi-cho',NULL,'Higashisonogi-cho, Nagasaki · 長崎県東彼杵町',1710,1,'東彼杵町','ヒガシソノギチョウ','Higashisonogi-cho',33.037031,129.917149),(1718,'423220','42','Kawatana-cho',NULL,'Kawatana-cho, Nagasaki · 長崎県川棚町',1711,1,'川棚町','カワタナチョウ','Kawatana-cho',33.072679,129.861562),(1719,'423238','42','Hasami-cho',NULL,'Hasami-cho, Nagasaki · 長崎県波佐見町',1712,1,'波佐見町','ハサミチョウ','Hasami-cho',33.137890,129.895548),(1720,'423831','42','Ojika-cho',NULL,'Ojika-cho, Nagasaki · 長崎県小値賀町',1713,1,'小値賀町','オヂカチョウ','Ojika-cho',33.191074,129.058766),(1721,'423912','42','Saza-cho',NULL,'Saza-cho, Nagasaki · 長崎県佐々町',1714,1,'佐々町','サザチョウ','Saza-cho',33.238426,129.650353),(1722,'424111','42','Shinkamigoto-cho',NULL,'Shinkamigoto-cho, Nagasaki · 長崎県新上五島町',1715,1,'新上五島町','シンカミゴトウチョウ','Shinkamigoto-cho',32.984553,129.073401),(1723,'431010','43','Chuo-ku, Kumamoto-shi',NULL,'Chuo-ku, Kumamoto-shi, Kumamoto · 熊本県熊本市中央区',1716,1,'熊本市中央区','クマモトシチュウオウク','Chuo-ku, Kumamoto-shi',32.803218,130.708223),(1724,'431028','43','Higashi-ku, Kumamoto-shi',NULL,'Higashi-ku, Kumamoto-shi, Kumamoto · 熊本県熊本市東区',1717,1,'熊本市東区','クマモトシヒガシク','Higashi-ku, Kumamoto-shi',32.780530,130.768061),(1725,'431036','43','Nishi-ku, Kumamoto-shi',NULL,'Nishi-ku, Kumamoto-shi, Kumamoto · 熊本県熊本市西区',1718,1,'熊本市西区','クマモトシニシク','Nishi-ku, Kumamoto-shi',32.776371,130.647562),(1726,'431044','43','Minami-ku, Kumamoto-shi',NULL,'Minami-ku, Kumamoto-shi, Kumamoto · 熊本県熊本市南区',1719,1,'熊本市南区','クマモトシミナミク','Minami-ku, Kumamoto-shi',32.715360,130.678922),(1727,'431052','43','Kita-ku, Kumamoto-shi',NULL,'Kita-ku, Kumamoto-shi, Kumamoto · 熊本県熊本市北区',1720,1,'熊本市北区','クマモトシキタク','Kita-ku, Kumamoto-shi',32.903561,130.694263),(1728,'432024','43','Yatsushiro-shi',NULL,'Yatsushiro-shi, Kumamoto · 熊本県八代市',1721,1,'八代市','ヤツシロシ','Yatsushiro-shi',32.507412,130.601888),(1729,'432032','43','Hitoyoshi-shi',NULL,'Hitoyoshi-shi, Kumamoto · 熊本県人吉市',1722,1,'人吉市','ヒトヨシシ','Hitoyoshi-shi',32.205720,130.760280),(1730,'432041','43','Arao-shi',NULL,'Arao-shi, Kumamoto · 熊本県荒尾市',1723,1,'荒尾市','アラオシ','Arao-shi',32.986838,130.433036),(1731,'432059','43','Minamata-shi',NULL,'Minamata-shi, Kumamoto · 熊本県水俣市',1724,1,'水俣市','ミナマタシ','Minamata-shi',32.211883,130.408725),(1732,'432067','43','Tamana-shi',NULL,'Tamana-shi, Kumamoto · 熊本県玉名市',1725,1,'玉名市','タマナシ','Tamana-shi',32.935244,130.562773),(1733,'432083','43','Yamaga-shi',NULL,'Yamaga-shi, Kumamoto · 熊本県山鹿市',1726,1,'山鹿市','ヤマガシ','Yamaga-shi',33.016728,130.691297),(1734,'432105','43','Kikuchi-shi',NULL,'Kikuchi-shi, Kumamoto · 熊本県菊池市',1727,1,'菊池市','キクチシ','Kikuchi-shi',32.979485,130.813241),(1735,'432113','43','Uto-shi',NULL,'Uto-shi, Kumamoto · 熊本県宇土市',1728,1,'宇土市','ウトシ','Uto-shi',32.687292,130.658562),(1736,'432121','43','Kamiamakusa-shi',NULL,'Kamiamakusa-shi, Kumamoto · 熊本県上天草市',1729,1,'上天草市','カミアマクサシ','Kamiamakusa-shi',32.587422,130.430405),(1737,'432130','43','Uki-shi',NULL,'Uki-shi, Kumamoto · 熊本県宇城市',1730,1,'宇城市','ウキシ','Uki-shi',32.647814,130.684287),(1738,'432148','43','Aso-shi',NULL,'Aso-shi, Kumamoto · 熊本県阿蘇市',1731,1,'阿蘇市','アソシ','Aso-shi',32.951869,131.121031),(1739,'432156','43','Amakusa-shi',NULL,'Amakusa-shi, Kumamoto · 熊本県天草市',1732,1,'天草市','アマクサシ','Amakusa-shi',32.458621,130.193010),(1740,'432164','43','Koshi-shi',NULL,'Koshi-shi, Kumamoto · 熊本県合志市',1733,1,'合志市','コウシシ','Koshi-shi',32.885996,130.789726),(1741,'433489','43','Misato-machi',NULL,'Misato-machi, Kumamoto · 熊本県美里町',1734,1,'美里町','ミサトマチ','Misato-machi',32.639609,130.789007),(1742,'433641','43','Gyokuto-machi',NULL,'Gyokuto-machi, Kumamoto · 熊本県玉東町',1735,1,'玉東町','ギョクトウマチ','Gyokuto-machi',32.918905,130.628593),(1743,'433675','43','Nankan-machi',NULL,'Nankan-machi, Kumamoto · 熊本県南関町',1736,1,'南関町','ナンカンマチ','Nankan-machi',33.059500,130.543547),(1744,'433683','43','Nagasu-machi',NULL,'Nagasu-machi, Kumamoto · 熊本県長洲町',1737,1,'長洲町','ナガスマチ','Nagasu-machi',32.929756,130.452700),(1745,'433691','43','Nagomi-machi',NULL,'Nagomi-machi, Kumamoto · 熊本県和水町',1738,1,'和水町','ナゴミマチ','Nagomi-machi',32.978158,130.605813),(1746,'434035','43','Ozu-machi',NULL,'Ozu-machi, Kumamoto · 熊本県大津町',1739,1,'大津町','オオヅマチ','Ozu-machi',32.878990,130.868284),(1747,'434043','43','Kikuyo-machi',NULL,'Kikuyo-machi, Kumamoto · 熊本県菊陽町',1740,1,'菊陽町','キクヨウマチ','Kikuyo-machi',32.862515,130.828654),(1748,'434230','43','Minamioguni-machi',NULL,'Minamioguni-machi, Kumamoto · 熊本県南小国町',1741,1,'南小国町','ミナミオグニマチ','Minamioguni-machi',33.098192,131.070724),(1749,'434248','43','Oguni-machi',NULL,'Oguni-machi, Kumamoto · 熊本県小国町',1742,1,'小国町','オグニマチ','Oguni-machi',33.121568,131.068157),(1750,'434256','43','Ubuyama-mura',NULL,'Ubuyama-mura, Kumamoto · 熊本県産山村',1743,1,'産山村','ウブヤマムラ','Ubuyama-mura',32.995747,131.216799),(1751,'434281','43','Takamori-machi',NULL,'Takamori-machi, Kumamoto · 熊本県高森町',1744,1,'高森町','タカモリマチ','Takamori-machi',32.827271,131.122023),(1752,'434329','43','Nishihara-mura',NULL,'Nishihara-mura, Kumamoto · 熊本県西原村',1745,1,'西原村','ニシハラムラ','Nishihara-mura',32.834719,130.903036),(1753,'434337','43','Minamiaso-mura',NULL,'Minamiaso-mura, Kumamoto · 熊本県南阿蘇村',1746,1,'南阿蘇村','ミナミアソムラ','Minamiaso-mura',32.845116,131.017708),(1754,'434418','43','Mifune-machi',NULL,'Mifune-machi, Kumamoto · 熊本県御船町',1747,1,'御船町','ミフネマチ','Mifune-machi',32.714585,130.801821),(1755,'434426','43','Kashima-machi',NULL,'Kashima-machi, Kumamoto · 熊本県嘉島町',1748,1,'嘉島町','カシママチ','Kashima-machi',32.740072,130.757303),(1756,'434434','43','Mashiki-machi',NULL,'Mashiki-machi, Kumamoto · 熊本県益城町',1749,1,'益城町','マシキマチ','Mashiki-machi',32.791397,130.816418),(1757,'434442','43','Kosa-machi',NULL,'Kosa-machi, Kumamoto · 熊本県甲佐町',1750,1,'甲佐町','コウサマチ','Kosa-machi',32.651356,130.811466),(1758,'434477','43','Yamato-cho',NULL,'Yamato-cho, Kumamoto · 熊本県山都町',1751,1,'山都町','ヤマトチョウ','Yamato-cho',32.685448,130.990425),(1759,'434680','43','Hikawa-cho',NULL,'Hikawa-cho, Kumamoto · 熊本県氷川町',1752,1,'氷川町','ヒカワチョウ','Hikawa-cho',32.582402,130.673753),(1760,'434825','43','Ashikita-machi',NULL,'Ashikita-machi, Kumamoto · 熊本県芦北町',1753,1,'芦北町','アシキタマチ','Ashikita-machi',32.299102,130.493119),(1761,'434841','43','Tsunagi-machi',NULL,'Tsunagi-machi, Kumamoto · 熊本県津奈木町',1754,1,'津奈木町','ツナギマチ','Tsunagi-machi',32.233892,130.439621),(1762,'435015','43','Nishiki-machi',NULL,'Nishiki-machi, Kumamoto · 熊本県錦町',1755,1,'錦町','ニシキマチ','Nishiki-machi',32.200987,130.840902),(1763,'435058','43','Taragi-machi',NULL,'Taragi-machi, Kumamoto · 熊本県多良木町',1756,1,'多良木町','タラギマチ','Taragi-machi',32.264039,130.935795),(1764,'435066','43','Yunomae-machi',NULL,'Yunomae-machi, Kumamoto · 熊本県湯前町',1757,1,'湯前町','ユノマエマチ','Yunomae-machi',32.276144,130.980987),(1765,'435074','43','Mizukami-mura',NULL,'Mizukami-mura, Kumamoto · 熊本県水上村',1758,1,'水上村','ミズカミムラ','Mizukami-mura',32.314379,131.009541),(1766,'435104','43','Sagara-mura',NULL,'Sagara-mura, Kumamoto · 熊本県相良村',1759,1,'相良村','サガラムラ','Sagara-mura',32.235260,130.797985),(1767,'435112','43','Itsuki-mura',NULL,'Itsuki-mura, Kumamoto · 熊本県五木村',1760,1,'五木村','イツキムラ','Itsuki-mura',32.397343,130.827833),(1768,'435121','43','Yamae-mura',NULL,'Yamae-mura, Kumamoto · 熊本県山江村',1761,1,'山江村','ヤマエムラ','Yamae-mura',32.246465,130.767136),(1769,'435139','43','Kuma-mura',NULL,'Kuma-mura, Kumamoto · 熊本県球磨村',1762,1,'球磨村','クマムラ','Kuma-mura',32.252621,130.651274),(1770,'435147','43','Asagiri-cho',NULL,'Asagiri-cho, Kumamoto · 熊本県あさぎり町',1763,1,'あさぎり町','アサギリチョウ','Asagiri-cho',32.240321,130.897827),(1771,'435317','43','Reihoku-machi',NULL,'Reihoku-machi, Kumamoto · 熊本県苓北町',1764,1,'苓北町','レイホクマチ','Reihoku-machi',32.513413,130.054720),(1772,'442011','44','Oita-shi',NULL,'Oita-shi, Oita · 大分県大分市',1765,1,'大分市','オオイタシ','Oita-shi',33.239526,131.609377),(1773,'442020','44','Beppu-shi',NULL,'Beppu-shi, Oita · 大分県別府市',1766,1,'別府市','ベップシ','Beppu-shi',33.284642,131.491328),(1774,'442038','44','Nakatsu-shi',NULL,'Nakatsu-shi, Oita · 大分県中津市',1767,1,'中津市','ナカツシ','Nakatsu-shi',33.598302,131.188240),(1775,'442046','44','Hita-shi',NULL,'Hita-shi, Oita · 大分県日田市',1768,1,'日田市','ヒタシ','Hita-shi',33.321041,130.941310),(1776,'442054','44','Saiki-shi',NULL,'Saiki-shi, Oita · 大分県佐伯市',1769,1,'佐伯市','サイキシ','Saiki-shi',32.960204,131.899499),(1777,'442062','44','Usuki-shi',NULL,'Usuki-shi, Oita · 大分県臼杵市',1770,1,'臼杵市','ウスキシ','Usuki-shi',33.125948,131.804638),(1778,'442071','44','Tsukumi-shi',NULL,'Tsukumi-shi, Oita · 大分県津久見市',1771,1,'津久見市','ツクミシ','Tsukumi-shi',33.072339,131.861219),(1779,'442089','44','Taketa-shi',NULL,'Taketa-shi, Oita · 大分県竹田市',1772,1,'竹田市','タケタシ','Taketa-shi',32.973673,131.397838),(1780,'442097','44','Bungotakada-shi',NULL,'Bungotakada-shi, Oita · 大分県豊後高田市',1773,1,'豊後高田市','ブンゴタカダシ','Bungotakada-shi',33.556238,131.446990),(1781,'442101','44','Kitsuki-shi',NULL,'Kitsuki-shi, Oita · 大分県杵築市',1774,1,'杵築市','キツキシ','Kitsuki-shi',33.417029,131.616089),(1782,'442119','44','Usa-shi',NULL,'Usa-shi, Oita · 大分県宇佐市',1775,1,'宇佐市','ウサシ','Usa-shi',33.531972,131.349545),(1783,'442127','44','Bungoono-shi',NULL,'Bungoono-shi, Oita · 大分県豊後大野市',1776,1,'豊後大野市','ブンゴオオノシ','Bungoono-shi',32.977555,131.584105),(1784,'442135','44','Yufu-shi',NULL,'Yufu-shi, Oita · 大分県由布市',1777,1,'由布市','ユフシ','Yufu-shi',33.179996,131.426792),(1785,'442143','44','Kunisaki-shi',NULL,'Kunisaki-shi, Oita · 大分県国東市',1778,1,'国東市','クニサキシ','Kunisaki-shi',33.563310,131.732238),(1786,'443221','44','Himeshima-mura',NULL,'Himeshima-mura, Oita · 大分県姫島村',1779,1,'姫島村','ヒメシマムラ','Himeshima-mura',33.724536,131.645143),(1787,'443417','44','Hiji-machi',NULL,'Hiji-machi, Oita · 大分県日出町',1780,1,'日出町','ヒジマチ','Hiji-machi',33.369442,131.532536),(1788,'444618','44','Kokonoe-machi',NULL,'Kokonoe-machi, Oita · 大分県九重町',1781,1,'九重町','ココノエマチ','Kokonoe-machi',33.228521,131.188797),(1789,'444626','44','Kusu-machi',NULL,'Kusu-machi, Oita · 大分県玖珠町',1782,1,'玖珠町','クスマチ','Kusu-machi',33.283135,131.151553),(1790,'452017','45','Miyazaki-shi',NULL,'Miyazaki-shi, Miyazaki · 宮崎県宮崎市',1783,1,'宮崎市','ミヤザキシ','Miyazaki-shi',31.907676,131.420244),(1791,'452025','45','Miyakonojo-shi',NULL,'Miyakonojo-shi, Miyazaki · 宮崎県都城市',1784,1,'都城市','ミヤコノジョウシ','Miyakonojo-shi',31.719519,131.061497),(1792,'452033','45','Nobeoka-shi',NULL,'Nobeoka-shi, Miyazaki · 宮崎県延岡市',1785,1,'延岡市','ノベオカシ','Nobeoka-shi',32.582401,131.664859),(1793,'452041','45','Nichinan-shi',NULL,'Nichinan-shi, Miyazaki · 宮崎県日南市',1786,1,'日南市','ニチナンシ','Nichinan-shi',31.601932,131.378731),(1794,'452050','45','Kobayashi-shi',NULL,'Kobayashi-shi, Miyazaki · 宮崎県小林市',1787,1,'小林市','コバヤシシ','Kobayashi-shi',31.996657,130.972674),(1795,'452068','45','Hyuga-shi',NULL,'Hyuga-shi, Miyazaki · 宮崎県日向市',1788,1,'日向市','ヒュウガシ','Hyuga-shi',32.422947,131.623962),(1796,'452076','45','Kushima-shi',NULL,'Kushima-shi, Miyazaki · 宮崎県串間市',1789,1,'串間市','クシマシ','Kushima-shi',31.464539,131.228306),(1797,'452084','45','Saito-shi',NULL,'Saito-shi, Miyazaki · 宮崎県西都市',1790,1,'西都市','サイトシ','Saito-shi',32.108562,131.401266),(1798,'452092','45','Ebino-shi',NULL,'Ebino-shi, Miyazaki · 宮崎県えびの市',1791,1,'えびの市','エビノシ','Ebino-shi',32.045347,130.810841),(1799,'453412','45','Mimata-cho',NULL,'Mimata-cho, Miyazaki · 宮崎県三股町',1792,1,'三股町','ミマタチョウ','Mimata-cho',31.730686,131.124920),(1800,'453617','45','Takaharu-cho',NULL,'Takaharu-cho, Miyazaki · 宮崎県高原町',1793,1,'高原町','タカハルチョウ','Takaharu-cho',31.928413,131.007870),(1801,'453820','45','Kunitomi-cho',NULL,'Kunitomi-cho, Miyazaki · 宮崎県国富町',1794,1,'国富町','クニトミチョウ','Kunitomi-cho',31.990646,131.323535),(1802,'453838','45','Aya-cho',NULL,'Aya-cho, Miyazaki · 宮崎県綾町',1795,1,'綾町','アヤチョウ','Aya-cho',31.999135,131.253182),(1803,'454010','45','Takanabe-cho',NULL,'Takanabe-cho, Miyazaki · 宮崎県高鍋町',1796,1,'高鍋町','タカナベチョウ','Takanabe-cho',32.127990,131.503341),(1804,'454028','45','Shintomi-cho',NULL,'Shintomi-cho, Miyazaki · 宮崎県新富町',1797,1,'新富町','シントミチョウ','Shintomi-cho',32.068937,131.487984),(1805,'454036','45','Nishimera-son',NULL,'Nishimera-son, Miyazaki · 宮崎県西米良村',1798,1,'西米良村','ニシメラソン','Nishimera-son',32.226354,131.154452),(1806,'454044','45','Kijo-cho',NULL,'Kijo-cho, Miyazaki · 宮崎県木城町',1799,1,'木城町','キジョウチョウ','Kijo-cho',32.163784,131.473354),(1807,'454052','45','Kawaminami-cho',NULL,'Kawaminami-cho, Miyazaki · 宮崎県川南町',1800,1,'川南町','カワミナミチョウ','Kawaminami-cho',32.192009,131.525816),(1808,'454061','45','Tsuno-cho',NULL,'Tsuno-cho, Miyazaki · 宮崎県都農町',1801,1,'都農町','ツノチョウ','Tsuno-cho',32.256434,131.559690),(1809,'454214','45','Kadogawa-cho',NULL,'Kadogawa-cho, Miyazaki · 宮崎県門川町',1802,1,'門川町','カドガワチョウ','Kadogawa-cho',32.470894,131.646461),(1810,'454290','45','Morotsuka-son',NULL,'Morotsuka-son, Miyazaki · 宮崎県諸塚村',1803,1,'諸塚村','モロツカソン','Morotsuka-son',32.512184,131.330319),(1811,'454303','45','Shiiba-son',NULL,'Shiiba-son, Miyazaki · 宮崎県椎葉村',1804,1,'椎葉村','シイバソン','Shiiba-son',32.466712,131.157554),(1812,'454311','45','Misato-cho',NULL,'Misato-cho, Miyazaki · 宮崎県美郷町',1805,1,'美郷町','ミサトチョウ','Misato-cho',32.440256,131.423106),(1813,'454419','45','Takachiho-cho',NULL,'Takachiho-cho, Miyazaki · 宮崎県高千穂町',1806,1,'高千穂町','タカチホチョウ','Takachiho-cho',32.711651,131.307871),(1814,'454427','45','Hinokage-cho',NULL,'Hinokage-cho, Miyazaki · 宮崎県日之影町',1807,1,'日之影町','ヒノカゲチョウ','Hinokage-cho',32.659411,131.380922),(1815,'454435','45','Gokase-cho',NULL,'Gokase-cho, Miyazaki · 宮崎県五ヶ瀬町',1808,1,'五ヶ瀬町','ゴカセチョウ','Gokase-cho',32.682915,131.196247),(1816,'462012','46','Kagoshima-shi',NULL,'Kagoshima-shi, Kagoshima · 鹿児島県鹿児島市',1809,1,'鹿児島市','カゴシマシ','Kagoshima-shi',31.596789,130.557339),(1817,'462039','46','Kanoya-shi',NULL,'Kanoya-shi, Kagoshima · 鹿児島県鹿屋市',1810,1,'鹿屋市','カノヤシ','Kanoya-shi',31.378268,130.852223),(1818,'462047','46','Makurazaki-shi',NULL,'Makurazaki-shi, Kagoshima · 鹿児島県枕崎市',1811,1,'枕崎市','マクラザキシ','Makurazaki-shi',31.272922,130.296991),(1819,'462063','46','Akune-shi',NULL,'Akune-shi, Kagoshima · 鹿児島県阿久根市',1812,1,'阿久根市','アクネシ','Akune-shi',32.014364,130.192622),(1820,'462080','46','Izumi-shi',NULL,'Izumi-shi, Kagoshima · 鹿児島県出水市',1813,1,'出水市','イズミシ','Izumi-shi',32.090458,130.352647),(1821,'462101','46','Ibusuki-shi',NULL,'Ibusuki-shi, Kagoshima · 鹿児島県指宿市',1814,1,'指宿市','イブスキシ','Ibusuki-shi',31.252805,130.633118),(1822,'462136','46','Nishinomote-shi',NULL,'Nishinomote-shi, Kagoshima · 鹿児島県西之表市',1815,1,'西之表市','ニシノオモテシ','Nishinomote-shi',30.732453,130.997035),(1823,'462144','46','Tarumizu-shi',NULL,'Tarumizu-shi, Kagoshima · 鹿児島県垂水市',1816,1,'垂水市','タルミズシ','Tarumizu-shi',31.492758,130.700930),(1824,'462152','46','Satsumasendai-shi',NULL,'Satsumasendai-shi, Kagoshima · 鹿児島県薩摩川内市',1817,1,'薩摩川内市','サツマセンダイシ','Satsumasendai-shi',31.813486,130.303950),(1825,'462161','46','Hioki-shi',NULL,'Hioki-shi, Kagoshima · 鹿児島県日置市',1818,1,'日置市','ヒオキシ','Hioki-shi',31.633709,130.402436),(1826,'462179','46','So-shi',NULL,'So-shi, Kagoshima · 鹿児島県曽於市',1819,1,'曽於市','ソオシ','So-shi',31.653622,131.019255),(1827,'462187','46','Kirishima-shi',NULL,'Kirishima-shi, Kagoshima · 鹿児島県霧島市',1820,1,'霧島市','キリシマシ','Kirishima-shi',31.741015,130.763136),(1828,'462195','46','Ichikikushikino-shi',NULL,'Ichikikushikino-shi, Kagoshima · 鹿児島県いちき串木野市',1821,1,'いちき串木野市','イチキクシキノシ','Ichikikushikino-shi',31.714542,130.271934),(1829,'462209','46','Minamisatsuma-shi',NULL,'Minamisatsuma-shi, Kagoshima · 鹿児島県南さつま市',1822,1,'南さつま市','ミナミサツマシ','Minamisatsuma-shi',31.416570,130.323494),(1830,'462217','46','Shibushi-shi',NULL,'Shibushi-shi, Kagoshima · 鹿児島県志布志市',1823,1,'志布志市','シブシシ','Shibushi-shi',31.477446,131.099752),(1831,'462225','46','Amami-shi',NULL,'Amami-shi, Kagoshima · 鹿児島県奄美市',1824,1,'奄美市','アマミシ','Amami-shi',28.377273,129.493780),(1832,'462233','46','Minamikyushu-shi',NULL,'Minamikyushu-shi, Kagoshima · 鹿児島県南九州市',1825,1,'南九州市','ミナミキュウシュウシ','Minamikyushu-shi',31.378251,130.441623),(1833,'462241','46','Isa-shi',NULL,'Isa-shi, Kagoshima · 鹿児島県伊佐市',1826,1,'伊佐市','イサシ','Isa-shi',32.057152,130.612934),(1834,'462250','46','Aira-shi',NULL,'Aira-shi, Kagoshima · 鹿児島県姶良市',1827,1,'姶良市','アイラシ','Aira-shi',31.728213,130.627758),(1835,'463035','46','Mishima-mura',NULL,'Mishima-mura, Kagoshima · 鹿児島県三島村',1828,1,'三島村','ミシマムラ','Mishima-mura',31.594533,130.560735),(1836,'463043','46','Toshima-mura',NULL,'Toshima-mura, Kagoshima · 鹿児島県十島村',1829,1,'十島村','トシマムラ','Toshima-mura',31.593157,130.560588),(1837,'463922','46','Satsuma-cho',NULL,'Satsuma-cho, Kagoshima · 鹿児島県さつま町',1830,1,'さつま町','サツマチョウ','Satsuma-cho',31.906331,130.455340),(1838,'464040','46','Nagashima-cho',NULL,'Nagashima-cho, Kagoshima · 鹿児島県長島町',1831,1,'長島町','ナガシマチョウ','Nagashima-cho',32.199320,130.176919),(1839,'464520','46','Yusui-cho',NULL,'Yusui-cho, Kagoshima · 鹿児島県湧水町',1832,1,'湧水町','ユウスイチョウ','Yusui-cho',31.951654,130.721025),(1840,'464686','46','Osaki-cho',NULL,'Osaki-cho, Kagoshima · 鹿児島県大崎町',1833,1,'大崎町','オオサキチョウ','Osaki-cho',31.429102,131.005592),(1841,'464821','46','Higashikushira-cho',NULL,'Higashikushira-cho, Kagoshima · 鹿児島県東串良町',1834,1,'東串良町','ヒガシクシラチョウ','Higashikushira-cho',31.385790,130.973339),(1842,'464902','46','Kinko-cho',NULL,'Kinko-cho, Kagoshima · 鹿児島県錦江町',1835,1,'錦江町','キンコウチョウ','Kinko-cho',31.243572,130.787485),(1843,'464911','46','Minamiosumi-cho',NULL,'Minamiosumi-cho, Kagoshima · 鹿児島県南大隅町',1836,1,'南大隅町','ミナミオオスミチョウ','Minamiosumi-cho',31.217205,130.768061),(1844,'464929','46','Kimotsuki-cho',NULL,'Kimotsuki-cho, Kagoshima · 鹿児島県肝付町',1837,1,'肝付町','キモツキチョウ','Kimotsuki-cho',31.344362,130.945223),(1845,'465011','46','Nakatane-cho',NULL,'Nakatane-cho, Kagoshima · 鹿児島県中種子町',1838,1,'中種子町','ナカタネチョウ','Nakatane-cho',30.532821,130.958847),(1846,'465020','46','Minamitane-cho',NULL,'Minamitane-cho, Kagoshima · 鹿児島県南種子町',1839,1,'南種子町','ミナミタネチョウ','Minamitane-cho',30.413916,130.900867),(1847,'465054','46','Yakushima-cho',NULL,'Yakushima-cho, Kagoshima · 鹿児島県屋久島町',1840,1,'屋久島町','ヤクシマチョウ','Yakushima-cho',30.390044,130.651297),(1848,'465232','46','Yamato-son',NULL,'Yamato-son, Kagoshima · 鹿児島県大和村',1841,1,'大和村','ヤマトソン','Yamato-son',28.358076,129.395262),(1849,'465241','46','Uken-son',NULL,'Uken-son, Kagoshima · 鹿児島県宇検村',1842,1,'宇検村','ウケンソン','Uken-son',28.280773,129.297208),(1850,'465259','46','Setochi-cho',NULL,'Setochi-cho, Kagoshima · 鹿児島県瀬戸内町',1843,1,'瀬戸内町','セトウチチョウ','Setochi-cho',28.146484,129.314734),(1851,'465275','46','Tatsugo-cho',NULL,'Tatsugo-cho, Kagoshima · 鹿児島県龍郷町',1844,1,'龍郷町','タツゴウチョウ','Tatsugo-cho',28.413300,129.589355),(1852,'465291','46','Kikai-cho',NULL,'Kikai-cho, Kagoshima · 鹿児島県喜界町',1845,1,'喜界町','キカイチョウ','Kikai-cho',28.316838,129.940035),(1853,'465305','46','Tokunoshima-cho',NULL,'Tokunoshima-cho, Kagoshima · 鹿児島県徳之島町',1846,1,'徳之島町','トクノシマチョウ','Tokunoshima-cho',27.726621,129.018504),(1854,'465313','46','Amagi-cho',NULL,'Amagi-cho, Kagoshima · 鹿児島県天城町',1847,1,'天城町','アマギチョウ','Amagi-cho',27.811660,128.897749),(1855,'465321','46','Isen-cho',NULL,'Isen-cho, Kagoshima · 鹿児島県伊仙町',1848,1,'伊仙町','イセンチョウ','Isen-cho',27.673583,128.937584),(1856,'465330','46','Wadomari-cho',NULL,'Wadomari-cho, Kagoshima · 鹿児島県和泊町',1849,1,'和泊町','ワドマリチョウ','Wadomari-cho',27.392559,128.655253),(1857,'465348','46','China-cho',NULL,'China-cho, Kagoshima · 鹿児島県知名町',1850,1,'知名町','チナチョウ','China-cho',27.333676,128.573732),(1858,'465356','46','Yoron-cho',NULL,'Yoron-cho, Kagoshima · 鹿児島県与論町',1851,1,'与論町','ヨロンチョウ','Yoron-cho',27.044917,128.421587),(1859,'472018','47','Naha-shi',NULL,'Naha-shi, Okinawa · 沖縄県那覇市',1852,1,'那覇市','ナハシ','Naha-shi',26.212295,127.679218),(1860,'472051','47','Ginowan-shi',NULL,'Ginowan-shi, Okinawa · 沖縄県宜野湾市',1853,1,'宜野湾市','ギノワンシ','Ginowan-shi',26.281581,127.778637),(1861,'472077','47','Ishigaki-shi',NULL,'Ishigaki-shi, Okinawa · 沖縄県石垣市',1854,1,'石垣市','イシガキシ','Ishigaki-shi',24.344467,124.185253),(1862,'472085','47','Urasoe-shi',NULL,'Urasoe-shi, Okinawa · 沖縄県浦添市',1855,1,'浦添市','ウラソエシ','Urasoe-shi',26.245816,127.721804),(1863,'472093','47','Nago-shi',NULL,'Nago-shi, Okinawa · 沖縄県名護市',1856,1,'名護市','ナゴシ','Nago-shi',26.591555,127.977474),(1864,'472107','47','Itoman-shi',NULL,'Itoman-shi, Okinawa · 沖縄県糸満市',1857,1,'糸満市','イトマンシ','Itoman-shi',26.123542,127.665823),(1865,'472115','47','Okinawa-shi',NULL,'Okinawa-shi, Okinawa · 沖縄県沖縄市',1858,1,'沖縄市','オキナワシ','Okinawa-shi',26.334354,127.805694),(1866,'472123','47','Tomigusuku-shi',NULL,'Tomigusuku-shi, Okinawa · 沖縄県豊見城市',1859,1,'豊見城市','トミグスクシ','Tomigusuku-shi',26.177142,127.681233),(1867,'472131','47','Uruma-shi',NULL,'Uruma-shi, Okinawa · 沖縄県うるま市',1860,1,'うるま市','ウルマシ','Uruma-shi',26.379151,127.857480),(1868,'472140','47','Miyakojima-shi',NULL,'Miyakojima-shi, Okinawa · 沖縄県宮古島市',1861,1,'宮古島市','ミヤコジマシ','Miyakojima-shi',24.789929,125.294722),(1869,'472158','47','Nanjo-shi',NULL,'Nanjo-shi, Okinawa · 沖縄県南城市',1862,1,'南城市','ナンジョウシ','Nanjo-shi',26.163142,127.770626),(1870,'473014','47','Kunigami-son',NULL,'Kunigami-son, Okinawa · 沖縄県国頭村',1863,1,'国頭村','クニガミソン','Kunigami-son',26.745638,128.177907),(1871,'473022','47','Ogimi-son',NULL,'Ogimi-son, Okinawa · 沖縄県大宜味村',1864,1,'大宜味村','オオギミソン','Ogimi-son',26.701712,128.120159),(1872,'473031','47','Higashi-son',NULL,'Higashi-son, Okinawa · 沖縄県東村',1865,1,'東村','ヒガシソン','Higashi-son',26.633284,128.156870),(1873,'473065','47','Nakijin-son',NULL,'Nakijin-son, Okinawa · 沖縄県今帰仁村',1866,1,'今帰仁村','ナキジンソン','Nakijin-son',26.682527,127.972739),(1874,'473081','47','Motobu-cho',NULL,'Motobu-cho, Okinawa · 沖縄県本部町',1867,1,'本部町','モトブチョウ','Motobu-cho',26.658035,127.898175),(1875,'473111','47','Onna-son',NULL,'Onna-son, Okinawa · 沖縄県恩納村',1868,1,'恩納村','オンナソン','Onna-son',26.497502,127.853570),(1876,'473138','47','Ginoza-son',NULL,'Ginoza-son, Okinawa · 沖縄県宜野座村',1869,1,'宜野座村','ギノザソン','Ginoza-son',26.481585,127.975615),(1877,'473146','47','Kin-cho',NULL,'Kin-cho, Okinawa · 沖縄県金武町',1870,1,'金武町','キンチョウ','Kin-cho',26.456137,127.926011),(1878,'473154','47','Ie-son',NULL,'Ie-son, Okinawa · 沖縄県伊江村',1871,1,'伊江村','イエソン','Ie-son',26.713490,127.807014),(1879,'473243','47','Yomitan-son',NULL,'Yomitan-son, Okinawa · 沖縄県読谷村',1872,1,'読谷村','ヨミタンソン','Yomitan-son',26.396154,127.744424),(1880,'473251','47','Kadena-cho',NULL,'Kadena-cho, Okinawa · 沖縄県嘉手納町',1873,1,'嘉手納町','カデナチョウ','Kadena-cho',26.361723,127.755380),(1881,'473260','47','Chatan-cho',NULL,'Chatan-cho, Okinawa · 沖縄県北谷町',1874,1,'北谷町','チャタンチョウ','Chatan-cho',26.320069,127.763901),(1882,'473278','47','Kitanakagusuku-son',NULL,'Kitanakagusuku-son, Okinawa · 沖縄県北中城村',1875,1,'北中城村','キタナカグスクソン','Kitanakagusuku-son',26.301079,127.793027),(1883,'473286','47','Nakagusuku-son',NULL,'Nakagusuku-son, Okinawa · 沖縄県中城村',1876,1,'中城村','ナカグスクソン','Nakagusuku-son',26.261986,127.789605),(1884,'473294','47','Nishihara-cho',NULL,'Nishihara-cho, Okinawa · 沖縄県西原町',1877,1,'西原町','ニシハラチョウ','Nishihara-cho',26.222856,127.758819),(1885,'473481','47','Yonabaru-cho',NULL,'Yonabaru-cho, Okinawa · 沖縄県与那原町',1878,1,'与那原町','ヨナバルチョウ','Yonabaru-cho',26.199502,127.754794),(1886,'473502','47','Haebaru-cho',NULL,'Haebaru-cho, Okinawa · 沖縄県南風原町',1879,1,'南風原町','ハエバルチョウ','Haebaru-cho',26.191122,127.728554),(1887,'473537','47','Tokashiki-son',NULL,'Tokashiki-son, Okinawa · 沖縄県渡嘉敷村',1880,1,'渡嘉敷村','トカシキソン','Tokashiki-son',26.197331,127.364289),(1888,'473545','47','Zamami-son',NULL,'Zamami-son, Okinawa · 沖縄県座間味村',1881,1,'座間味村','ザマミソン','Zamami-son',26.228949,127.303205),(1889,'473553','47','Aguni-son',NULL,'Aguni-son, Okinawa · 沖縄県粟国村',1882,1,'粟国村','アグニソン','Aguni-son',26.581744,127.228863),(1890,'473561','47','Tonaki-son',NULL,'Tonaki-son, Okinawa · 沖縄県渡名喜村',1883,1,'渡名喜村','トナキソン','Tonaki-son',26.372110,127.141122),(1891,'473570','47','Minamidaito-son',NULL,'Minamidaito-son, Okinawa · 沖縄県南大東村',1884,1,'南大東村','ミナミダイトウソン','Minamidaito-son',25.828903,131.231872),(1892,'473588','47','Kitadaito-son',NULL,'Kitadaito-son, Okinawa · 沖縄県北大東村',1885,1,'北大東村','キタダイトウソン','Kitadaito-son',25.945712,131.298917),(1893,'473596','47','Iheya-son',NULL,'Iheya-son, Okinawa · 沖縄県伊平屋村',1886,1,'伊平屋村','イヘヤソン','Iheya-son',27.039169,127.968620),(1894,'473600','47','Izena-son',NULL,'Izena-son, Okinawa · 沖縄県伊是名村',1887,1,'伊是名村','イゼナソン','Izena-son',26.924069,127.941188),(1895,'473618','47','Kumejima-cho',NULL,'Kumejima-cho, Okinawa · 沖縄県久米島町',1888,1,'久米島町','クメジマチョウ','Kumejima-cho',26.340669,126.805021),(1896,'473626','47','Yaese-cho',NULL,'Yaese-cho, Okinawa · 沖縄県八重瀬町',1889,1,'八重瀬町','ヤエセチョウ','Yaese-cho',26.158192,127.718645),(1897,'473758','47','Tarama-son',NULL,'Tarama-son, Okinawa · 沖縄県多良間村',1890,1,'多良間村','タラマソン','Tarama-son',24.669449,124.701693),(1898,'473812','47','Taketomi-cho',NULL,'Taketomi-cho, Okinawa · 沖縄県竹富町',1891,1,'竹富町','タケトミチョウ','Taketomi-cho',24.339763,124.154437),(1899,'473821','47','Yonaguni-cho',NULL,'Yonaguni-cho, Okinawa · 沖縄県与那国町',1892,1,'与那国町','ヨナグニチョウ','Yonaguni-cho',24.468034,123.004496);
/*!40000 ALTER TABLE `areas` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `banned_keywords`
--

DROP TABLE IF EXISTS `banned_keywords`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `banned_keywords` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `keyword` varchar(100) NOT NULL,
  `created_by` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `keyword` (`keyword`),
  KEY `fk_kw_creator` (`created_by`),
  CONSTRAINT `fk_kw_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=149 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `banned_keywords`
--

LOCK TABLES `banned_keywords` WRITE;
/*!40000 ALTER TABLE `banned_keywords` DISABLE KEYS */;
INSERT INTO `banned_keywords` VALUES (1,'vay nóng',NULL,'2026-10-16 08:39:32'),(2,'cá độ',NULL,'2026-10-16 08:39:32'),(3,'visa nhanh',NULL,'2026-10-16 08:39:32'),(4,'việc nhẹ lương cao',NULL,'2026-10-16 08:39:32'),(5,'kèo nhà cái',NULL,'2026-10-21 13:43:03'),(6,'nhà cái uy tín',NULL,'2026-10-21 13:43:03'),(7,'nhà cái online',NULL,'2026-10-21 13:43:03'),(8,'nhà cái hàng đầu',NULL,'2026-10-21 13:43:03'),(9,'nhà cái bóng đá',NULL,'2026-10-21 13:43:03'),(10,'nhà cái cá cược',NULL,'2026-10-21 13:43:03'),(11,'soi kèo',NULL,'2026-10-21 13:43:03'),(12,'tỷ lệ kèo',NULL,'2026-10-21 13:43:03'),(13,'tỉ lệ kèo',NULL,'2026-10-21 13:43:03'),(14,'cá cược bóng đá',NULL,'2026-10-21 13:43:03'),(15,'cá cược online',NULL,'2026-10-21 13:43:03'),(16,'cá cược thể thao',NULL,'2026-10-21 13:43:03'),(17,'lô đề online',NULL,'2026-10-21 13:43:03'),(18,'chơi lô đề',NULL,'2026-10-21 13:43:03'),(19,'đánh lô đề',NULL,'2026-10-21 13:43:03'),(20,'soi cầu lô',NULL,'2026-10-21 13:43:03'),(21,'tài xỉu',NULL,'2026-10-21 13:43:03'),(22,'xóc đĩa',NULL,'2026-10-21 13:43:03'),(23,'game bài đổi thưởng',NULL,'2026-10-21 13:43:03'),(24,'bắn cá đổi thưởng',NULL,'2026-10-21 13:43:03'),(25,'nổ hũ đổi thưởng',NULL,'2026-10-21 13:43:03'),(26,'nổ hũ online',NULL,'2026-10-21 13:43:03'),(27,'quay hũ',NULL,'2026-10-21 13:43:03'),(28,'casino online',NULL,'2026-10-21 13:43:03'),(29,'sòng bạc online',NULL,'2026-10-21 13:43:03'),(30,'đá gà online',NULL,'2026-10-21 13:43:03'),(31,'đá gà trực tiếp',NULL,'2026-10-21 13:43:03'),(32,'đá gà thomo',NULL,'2026-10-21 13:43:03'),(33,'baccarat',NULL,'2026-10-21 13:43:03'),(34,'online casino',NULL,'2026-10-21 13:43:03'),(35,'sports betting',NULL,'2026-10-21 13:43:03'),(36,'fb88',NULL,'2026-10-21 13:43:03'),(37,'w88',NULL,'2026-10-21 13:43:03'),(38,'m88',NULL,'2026-10-21 13:43:03'),(39,'188bet',NULL,'2026-10-21 13:43:03'),(40,'12bet',NULL,'2026-10-21 13:43:03'),(41,'fun88',NULL,'2026-10-21 13:43:03'),(42,'8xbet',NULL,'2026-10-21 13:43:03'),(43,'kubet',NULL,'2026-10-21 13:43:03'),(44,'jun88',NULL,'2026-10-21 13:43:03'),(45,'new88',NULL,'2026-10-21 13:43:03'),(46,'hi88',NULL,'2026-10-21 13:43:03'),(47,'f8bet',NULL,'2026-10-21 13:43:03'),(48,'shbet',NULL,'2026-10-21 13:43:03'),(49,'789bet',NULL,'2026-10-21 13:43:03'),(50,'vn88',NULL,'2026-10-21 13:43:03'),(51,'ae888',NULL,'2026-10-21 13:43:03'),(52,'five88',NULL,'2026-10-21 13:43:03'),(53,'sv388',NULL,'2026-10-21 13:43:03'),(54,'bong88',NULL,'2026-10-21 13:43:03'),(55,'lucky88',NULL,'2026-10-21 13:43:03'),(56,'go88',NULL,'2026-10-21 13:43:03'),(57,'sunwin',NULL,'2026-10-21 13:43:03'),(58,'hitclub',NULL,'2026-10-21 13:43:03'),(59,'rikvip',NULL,'2026-10-21 13:43:03'),(60,'thabet',NULL,'2026-10-21 13:43:03'),(61,'オンラインカジノ',NULL,'2026-10-21 13:43:03'),(62,'ネットカジノ',NULL,'2026-10-21 13:43:03'),(63,'闇カジノ',NULL,'2026-10-21 13:43:03'),(64,'裏カジノ',NULL,'2026-10-21 13:43:03'),(65,'闇スロ',NULL,'2026-10-21 13:43:03'),(66,'gái gọi cao cấp',NULL,'2026-10-21 13:43:03'),(67,'gái gọi giá rẻ',NULL,'2026-10-21 13:43:03'),(68,'dịch vụ gái gọi',NULL,'2026-10-21 13:43:03'),(69,'gái làng chơi',NULL,'2026-10-21 13:43:03'),(70,'nhận đi khách',NULL,'2026-10-21 13:43:03'),(71,'đi khách qua đêm',NULL,'2026-10-21 13:43:03'),(72,'nhận bao nuôi',NULL,'2026-10-21 13:43:03'),(73,'tìm người bao nuôi',NULL,'2026-10-21 13:43:03'),(74,'cần người bao nuôi',NULL,'2026-10-21 13:43:03'),(75,'kích dục',NULL,'2026-10-21 13:43:03'),(76,'dịch vụ tươi mát',NULL,'2026-10-21 13:43:03'),(77,'massage happy ending',NULL,'2026-10-21 13:43:03'),(78,'happy ending massage',NULL,'2026-10-21 13:43:03'),(79,'erotic massage',NULL,'2026-10-21 13:43:03'),(80,'chat sex',NULL,'2026-10-21 13:43:03'),(81,'sugar baby',NULL,'2026-10-21 13:43:03'),(82,'sugar daddy',NULL,'2026-10-21 13:43:03'),(83,'sugar mommy',NULL,'2026-10-21 13:43:03'),(84,'call girl',NULL,'2026-10-21 13:43:03'),(85,'escort service',NULL,'2026-10-21 13:43:03'),(86,'援助交際',NULL,'2026-10-21 13:43:03'),(87,'パパ活',NULL,'2026-10-21 13:43:03'),(88,'ママ活',NULL,'2026-10-21 13:43:03'),(89,'デリヘル',NULL,'2026-10-21 13:43:03'),(90,'ソープランド',NULL,'2026-10-21 13:43:03'),(91,'ピンサロ',NULL,'2026-10-21 13:43:03'),(92,'立ちんぼ',NULL,'2026-10-21 13:43:03'),(93,'jkビジネス',NULL,'2026-10-21 13:43:03'),(94,'裏風俗',NULL,'2026-10-21 13:43:03'),(95,'風俗求人',NULL,'2026-10-21 13:43:03'),(96,'papa katsu',NULL,'2026-10-21 13:43:03'),(97,'enjo kosai',NULL,'2026-10-21 13:43:03'),(98,'cờ bạc',NULL,'2026-10-21 14:26:09'),(99,'đánh bạc',NULL,'2026-10-21 14:26:09'),(100,'bài bạc',NULL,'2026-10-21 14:26:09'),(101,'sòng bạc',NULL,'2026-10-21 14:26:09'),(102,'cá cược',NULL,'2026-10-21 14:26:09'),(103,'đặt cược',NULL,'2026-10-21 14:26:09'),(104,'nhà cái',NULL,'2026-10-21 14:26:09'),(105,'lô đề',NULL,'2026-10-21 14:26:09'),(106,'số đề',NULL,'2026-10-21 14:26:09'),(107,'đánh đề',NULL,'2026-10-21 14:26:09'),(108,'ghi đề',NULL,'2026-10-21 14:26:09'),(109,'nổ hũ',NULL,'2026-10-21 14:26:09'),(110,'đá gà',NULL,'2026-10-21 14:26:09'),(111,'bầu cua',NULL,'2026-10-21 14:26:09'),(112,'xì dách',NULL,'2026-10-21 14:26:09'),(113,'xì tố',NULL,'2026-10-21 14:26:09'),(114,'casino',NULL,'2026-10-21 14:26:09'),(115,'gambling',NULL,'2026-10-21 14:26:09'),(116,'betting',NULL,'2026-10-21 14:26:09'),(117,'poker',NULL,'2026-10-21 14:26:09'),(118,'blackjack',NULL,'2026-10-21 14:26:09'),(119,'roulette',NULL,'2026-10-21 14:26:09'),(120,'bookmaker',NULL,'2026-10-21 14:26:09'),(121,'pachinko',NULL,'2026-10-21 14:26:09'),(122,'賭博',NULL,'2026-10-21 14:26:09'),(123,'ギャンブル',NULL,'2026-10-21 14:26:09'),(124,'カジノ',NULL,'2026-10-21 14:26:09'),(125,'パチンコ',NULL,'2026-10-21 14:26:09'),(126,'パチスロ',NULL,'2026-10-21 14:26:09'),(127,'賭け麻雀',NULL,'2026-10-21 14:26:09'),(128,'mại dâm',NULL,'2026-10-21 14:26:09'),(129,'bán dâm',NULL,'2026-10-21 14:26:09'),(130,'mua dâm',NULL,'2026-10-21 14:26:09'),(131,'gái gọi',NULL,'2026-10-21 14:26:09'),(132,'gái bao',NULL,'2026-10-21 14:26:09'),(133,'trai bao',NULL,'2026-10-21 14:26:09'),(134,'gái điếm',NULL,'2026-10-21 14:26:09'),(135,'đi khách',NULL,'2026-10-21 14:26:09'),(136,'khách làng chơi',NULL,'2026-10-21 14:26:09'),(137,'nhà thổ',NULL,'2026-10-21 14:26:09'),(138,'lầu xanh',NULL,'2026-10-21 14:26:09'),(139,'bao nuôi',NULL,'2026-10-21 14:26:09'),(140,'dịch vụ tình dục',NULL,'2026-10-21 14:26:09'),(141,'prostitution',NULL,'2026-10-21 14:26:09'),(142,'prostitute',NULL,'2026-10-21 14:26:09'),(143,'brothel',NULL,'2026-10-21 14:26:09'),(144,'hooker',NULL,'2026-10-21 14:26:09'),(145,'売春',NULL,'2026-10-21 14:26:09'),(146,'買春',NULL,'2026-10-21 14:26:09'),(147,'風俗',NULL,'2026-10-21 14:26:09'),(148,'援交',NULL,'2026-10-21 14:26:09');
/*!40000 ALTER TABLE `banned_keywords` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `blog_likes`
--

DROP TABLE IF EXISTS `blog_likes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `blog_likes` (
  `blog_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`blog_id`,`user_id`),
  KEY `fk_blike_user` (`user_id`),
  CONSTRAINT `fk_blike_blog` FOREIGN KEY (`blog_id`) REFERENCES `blogs` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_blike_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `blog_likes`
--

LOCK TABLES `blog_likes` WRITE;
/*!40000 ALTER TABLE `blog_likes` DISABLE KEYS */;
INSERT INTO `blog_likes` VALUES (1,1,'2026-10-17 05:56:43'),(20,5,'2026-10-22 07:13:11');
/*!40000 ALTER TABLE `blog_likes` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `blog_media`
--

DROP TABLE IF EXISTS `blog_media`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `blog_media` (
  `blog_id` bigint NOT NULL,
  `media_id` bigint NOT NULL,
  `sort_order` smallint NOT NULL DEFAULT '0',
  PRIMARY KEY (`blog_id`,`media_id`),
  KEY `fk_bmedia_media` (`media_id`),
  CONSTRAINT `fk_bmedia_blog` FOREIGN KEY (`blog_id`) REFERENCES `blogs` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_bmedia_media` FOREIGN KEY (`media_id`) REFERENCES `media` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `blog_media`
--

LOCK TABLES `blog_media` WRITE;
/*!40000 ALTER TABLE `blog_media` DISABLE KEYS */;
INSERT INTO `blog_media` VALUES (7,1,0),(21,6,0),(23,7,0),(23,59,1),(23,61,2),(23,62,1),(23,63,1),(38,36,0),(41,39,0),(41,40,1),(50,51,0),(52,53,0),(53,54,0),(54,57,0),(55,58,0),(56,65,0),(58,83,0);
/*!40000 ALTER TABLE `blog_media` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `blog_place_links`
--

DROP TABLE IF EXISTS `blog_place_links`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `blog_place_links` (
  `blog_id` bigint NOT NULL,
  `place_id` bigint NOT NULL,
  PRIMARY KEY (`blog_id`,`place_id`),
  KEY `fk_bplace_place` (`place_id`),
  CONSTRAINT `fk_bplace_blog` FOREIGN KEY (`blog_id`) REFERENCES `blogs` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_bplace_place` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `blog_place_links`
--

LOCK TABLES `blog_place_links` WRITE;
/*!40000 ALTER TABLE `blog_place_links` DISABLE KEYS */;
INSERT INTO `blog_place_links` VALUES (1,1),(2,1),(7,1),(21,1),(23,1),(38,1),(3,4),(1,6),(5,6),(5,8),(41,8),(3,10),(9,13),(54,13),(55,13),(56,13),(10,14),(50,14),(20,15),(58,18);
/*!40000 ALTER TABLE `blog_place_links` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `blog_topic_links`
--

DROP TABLE IF EXISTS `blog_topic_links`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `blog_topic_links` (
  `blog_id` bigint NOT NULL,
  `topic_id` bigint NOT NULL,
  PRIMARY KEY (`blog_id`,`topic_id`),
  KEY `fk_btopic_topic` (`topic_id`),
  CONSTRAINT `fk_btopic_blog` FOREIGN KEY (`blog_id`) REFERENCES `blogs` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_btopic_topic` FOREIGN KEY (`topic_id`) REFERENCES `blog_topics` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `blog_topic_links`
--

LOCK TABLES `blog_topic_links` WRITE;
/*!40000 ALTER TABLE `blog_topic_links` DISABLE KEYS */;
INSERT INTO `blog_topic_links` VALUES (1,1),(4,1),(7,1),(8,1),(10,1),(20,1),(21,1),(23,1),(38,1),(41,1),(50,1),(52,1),(53,1),(54,1),(55,1),(56,1),(58,1),(2,2),(1,3),(5,3),(9,4),(4,5),(2,6),(3,7),(7,7),(8,7),(1,8),(7,8),(6,11),(6,12),(5,13);
/*!40000 ALTER TABLE `blog_topic_links` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `blog_topics`
--

DROP TABLE IF EXISTS `blog_topics`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `blog_topics` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(60) NOT NULL,
  `is_other` tinyint(1) NOT NULL DEFAULT '0',
  `sort_order` int NOT NULL DEFAULT '0',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `blog_topics`
--

LOCK TABLES `blog_topics` WRITE;
/*!40000 ALTER TABLE `blog_topics` DISABLE KEYS */;
INSERT INTO `blog_topics` VALUES (1,'Kinh nghiệm',0,1,1),(2,'Cảnh báo lừa đảo',0,2,1),(3,'Thủ tục giấy tờ',0,3,1),(4,'Visa và cư trú',0,4,1),(5,'Nhà ở',0,5,1),(6,'Việc làm thêm',0,6,1),(7,'Ăn uống',0,7,1),(8,'Đi lại',0,8,1),(9,'Học tiếng Nhật',0,9,1),(10,'Y tế',0,10,1),(11,'Mua sắm',0,11,1),(12,'Ngân hàng và tiền',0,12,1),(13,'Sau khi về nước',0,13,1),(14,'Khác',1,99,1);
/*!40000 ALTER TABLE `blog_topics` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `blogs`
--

DROP TABLE IF EXISTS `blogs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `blogs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `author_id` bigint NOT NULL,
  `blog_type` varchar(10) NOT NULL DEFAULT 'POST',
  `title` varchar(200) NOT NULL,
  `content` mediumtext NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PROCESSING',
  `is_pinned` tinyint(1) NOT NULL DEFAULT '0',
  `best_comment_id` bigint DEFAULT NULL,
  `is_edited` tinyint(1) NOT NULL DEFAULT '0',
  `removed_reason` varchar(255) DEFAULT NULL,
  `removed_at` datetime DEFAULT NULL,
  `purge_at` datetime DEFAULT NULL,
  `like_count` int NOT NULL DEFAULT '0',
  `comment_count` int NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_blogs_status_created` (`status`,`created_at`),
  KEY `idx_blogs_purge` (`purge_at`),
  KEY `fk_blogs_author` (`author_id`),
  KEY `fk_blogs_best_comment` (`best_comment_id`),
  FULLTEXT KEY `ft_blogs_search` (`title`,`content`) /*!50100 WITH PARSER `ngram` */ ,
  CONSTRAINT `fk_blogs_author` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_blogs_best_comment` FOREIGN KEY (`best_comment_id`) REFERENCES `comments` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_blogs_status` CHECK ((`status` in (_utf8mb4'PROCESSING',_utf8mb4'PUBLISHED',_utf8mb4'REJECTED',_utf8mb4'HIDDEN',_utf8mb4'REMOVED'))),
  CONSTRAINT `ck_blogs_type` CHECK ((`blog_type` in (_utf8mb4'POST',_utf8mb4'QA')))
) ENGINE=InnoDB AUTO_INCREMENT=59 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `blogs`
--

LOCK TABLES `blogs` WRITE;
/*!40000 ALTER TABLE `blogs` DISABLE KEYS */;
INSERT INTO `blogs` VALUES (1,5,'POST','Năm điều mình ước biết trước tuần đầu ở Tokyo','Mua thẻ IC đi tàu ngay ở ga đầu tiên, vì gần như mọi tuyến đều dùng được.\nĐăng ký cư trú tại văn phòng quận trong 14 ngày kể từ khi có chỗ ở.\nMang sẵn tiền mặt: nhiều quán nhỏ quanh Nippori chưa nhận thẻ.\nHỏi lịch đổ rác của tòa nhà ngay hôm nhận phòng.\nLưu lại vài mẫu câu ở cửa hàng tiện lợi để dùng ngay.','PUBLISHED',1,NULL,0,NULL,NULL,NULL,1,2,'2026-09-28 08:39:39','2026-10-16 08:39:39'),(2,5,'POST','Tin nhắn tuyển việc làm thêm lương cao quanh ga','Gần đây có tài khoản nhắn tin mời làm thêm lương cao, không cần tiếng Nhật, yêu cầu gửi ảnh thẻ cư trú trước.\nĐừng gửi ảnh giấy tờ cho người lạ. Việc làm thêm hợp pháp luôn có hợp đồng và nơi làm việc rõ ràng.\nNếu gặp tin như vậy trên Senpai ơi, hãy bấm Báo cáo.','PUBLISHED',0,NULL,0,NULL,NULL,NULL,31,0,'2026-10-07 08:39:39','2026-10-16 08:39:39'),(3,6,'POST','Hai quán ăn dưới 1.000 yên quanh Nippori','Quán cơm Hana: cơm phần 700 yên, chủ quán hiểu tiếng Việt cơ bản.\nRamen Aoba: ramen shoyu khoảng 950 yên, mua vé ở máy trước khi ngồi.','PUBLISHED',0,NULL,0,NULL,NULL,NULL,15,0,'2026-10-12 08:39:39','2026-10-21 20:46:56'),(4,6,'POST','Phân loại rác ở khu Nippori','Rác cháy được, rác không cháy được và rác tái chế bỏ vào các ngày khác nhau.\nLịch thường dán ở bảng tin của tòa nhà. Nếu không thấy, hãy hỏi quản lý tòa nhà.','PUBLISHED',0,NULL,0,NULL,NULL,NULL,9,0,'2026-09-21 08:39:39','2026-10-29 13:56:09'),(5,5,'POST','Checklist một tháng trước khi về nước','Nộp báo chuyển đi (転出届) ở văn phòng quận trước ngày bay.\nĐóng tài khoản ngân hàng sau khi nhận khoản lương cuối.\nSau khi về, nộp hồ sơ xin hoàn tiền lương hưu (脱退一時金) trong vòng 2 năm.','PUBLISHED',0,NULL,0,NULL,NULL,NULL,19,0,'2026-08-17 08:39:39','2026-10-16 08:39:39'),(6,1,'QA','Mua SIM data giá rẻ ở đâu gần Nippori ạ?','Em mới sang được 3 ngày, cần SIM data rẻ để dùng bản đồ. Mọi người đang dùng nhà mạng nào và mua ở đâu ạ?','PUBLISHED',0,NULL,0,NULL,NULL,NULL,3,2,'2026-10-15 08:39:39','2026-10-16 08:39:39'),(7,1,'POST','Năm điều hữu ích mình biết tại Tokyo','Mua vé IC, nên chuẩn bị tiền mặt, và xu ở ga','PUBLISHED',0,NULL,0,NULL,NULL,NULL,0,0,'2026-10-18 09:42:32','2026-10-18 09:42:32'),(8,1,'QA','Anh chị cho em hỏi cửa hàng đồ việt nào rẻ nhất ở Nippori ạ','','PUBLISHED',0,16,0,NULL,NULL,NULL,0,3,'2026-10-18 11:25:28','2026-10-18 11:25:28'),(9,1,'POST','alo','ww','PUBLISHED',0,NULL,0,NULL,NULL,NULL,0,1,'2026-10-19 12:59:06','2026-10-19 12:59:06'),(10,1,'POST','hello','hello1','PUBLISHED',0,NULL,1,NULL,NULL,NULL,0,1,'2026-10-19 13:43:19','2026-10-19 14:03:02'),(20,1,'POST','Hà nội','Hà nội','PUBLISHED',0,NULL,0,NULL,NULL,NULL,1,1,'2026-10-22 06:45:54','2026-10-22 06:45:54'),(21,1,'POST','Kinh nghiệm tại ga Nippori','Mua vé IC','PUBLISHED',0,NULL,0,NULL,NULL,NULL,0,1,'2026-10-22 15:00:12','2026-10-22 15:00:12'),(23,1,'POST','Kinh nghiệm tại ga tàu Nippori','Mua vé tàu','REJECTED',0,NULL,1,NULL,NULL,NULL,0,0,'2026-10-24 01:24:48','2026-10-30 03:56:14'),(38,1,'POST','tàu nhanh nippori','xả','REJECTED',0,NULL,0,NULL,NULL,NULL,0,0,'2026-10-24 09:01:46','2026-10-24 09:01:46'),(41,5,'POST','Tàu nhanh lh em','Tàu nhanh lh em','REJECTED',0,NULL,1,NULL,NULL,NULL,0,0,'2026-10-24 09:28:17','2026-10-24 09:28:57'),(50,1,'QA','Đi date tại Nippori','Siu lãng mạn','REJECTED',0,NULL,1,NULL,NULL,NULL,0,0,'2026-10-27 14:52:30','2026-10-27 14:59:09'),(52,1,'POST','Thử ảnh vi phạm','Bài thử để xem thông báo vi phạm.','REJECTED',0,NULL,0,NULL,NULL,NULL,0,0,'2026-10-27 15:17:36','2026-10-27 15:17:36'),(53,1,'POST','Thử trên điện thoại','Bài thử giao diện điện thoại.','REJECTED',0,NULL,0,NULL,NULL,NULL,0,0,'2026-10-27 15:20:12','2026-10-27 15:20:12'),(54,1,'POST','Mã QR','Kinh nghiệm tại tokyo','REJECTED',0,NULL,0,NULL,NULL,NULL,0,0,'2026-10-27 15:32:18','2026-10-27 15:32:18'),(55,1,'POST','Kình nghiệm','kinh nghiệm','REJECTED',0,NULL,0,NULL,NULL,NULL,0,0,'2026-10-28 07:09:01','2026-10-28 07:09:01'),(56,1,'POST','c o ba ccc de','c oo baccc đe','REJECTED',0,NULL,0,NULL,NULL,NULL,0,0,'2026-10-31 06:47:16','2026-10-31 06:47:16'),(58,13,'QA','Đường đi từ Arakawa đến Tokyo Skytree','Anh chị cho em xin kinh nghiệm đi từ Arakawa đến tokyo skytree ạ','PUBLISHED',0,17,1,NULL,NULL,NULL,0,1,'2026-11-07 06:14:05','2026-11-07 06:15:13');
/*!40000 ALTER TABLE `blogs` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `category_google_types`
--

DROP TABLE IF EXISTS `category_google_types`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `category_google_types` (
  `google_type` varchar(50) NOT NULL,
  `category_id` varchar(20) NOT NULL,
  PRIMARY KEY (`google_type`),
  KEY `fk_gtype_cat` (`category_id`),
  CONSTRAINT `fk_gtype_cat` FOREIGN KEY (`category_id`) REFERENCES `place_categories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `category_google_types`
--

LOCK TABLES `category_google_types` WRITE;
/*!40000 ALTER TABLE `category_google_types` DISABLE KEYS */;
INSERT INTO `category_google_types` VALUES ('atm','bank'),('bank','bank'),('post_office','bank'),('bakery','food'),('cafe','food'),('chinese_restaurant','food'),('coffee_shop','food'),('fast_food_restaurant','food'),('food_court','food'),('hamburger_restaurant','food'),('indian_restaurant','food'),('japanese_restaurant','food'),('korean_restaurant','food'),('meal_delivery','food'),('meal_takeaway','food'),('pizza_restaurant','food'),('ramen_restaurant','food'),('restaurant','food'),('sushi_restaurant','food'),('thai_restaurant','food'),('vietnamese_restaurant','food'),('dental_clinic','hospital'),('dentist','hospital'),('doctor','hospital'),('hospital','hospital'),('real_estate_agency','housing'),('convenience_store','konbini'),('laundry','laundry'),('national_park','park'),('park','park'),('drugstore','pharmacy'),('pharmacy','pharmacy'),('cell_phone_store','phone'),('electronics_store','phone'),('primary_school','school'),('school','school'),('secondary_school','school'),('university','school'),('light_rail_station','station'),('subway_station','station'),('train_station','station'),('asian_grocery_store','super'),('department_store','super'),('discount_store','super'),('grocery_store','super'),('home_goods_store','super'),('market','super'),('shopping_mall','super'),('supermarket','super'),('buddhist_temple','temple'),('hindu_temple','temple'),('shinto_shrine','temple'),('city_hall','ward'),('government_office','ward'),('library','ward'),('local_government_office','ward');
/*!40000 ALTER TABLE `category_google_types` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `category_osm_tags`
--

DROP TABLE IF EXISTS `category_osm_tags`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `category_osm_tags` (
  `osm_tag` varchar(80) NOT NULL,
  `category_id` varchar(20) NOT NULL,
  PRIMARY KEY (`osm_tag`),
  KEY `fk_cot_cat` (`category_id`),
  CONSTRAINT `fk_cot_cat` FOREIGN KEY (`category_id`) REFERENCES `place_categories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `category_osm_tags`
--

LOCK TABLES `category_osm_tags` WRITE;
/*!40000 ALTER TABLE `category_osm_tags` DISABLE KEYS */;
INSERT INTO `category_osm_tags` VALUES ('amenity=atm','bank'),('amenity=bank','bank'),('amenity=bureau_de_change','bank'),('amenity=post_office','bank'),('amenity=cafe','food'),('amenity=fast_food','food'),('amenity=food_court','food'),('amenity=ice_cream','food'),('amenity=pub','food'),('amenity=restaurant','food'),('shop=bakery','food'),('amenity=clinic','hospital'),('amenity=dentist','hospital'),('amenity=doctors','hospital'),('amenity=hospital','hospital'),('healthcare=clinic','hospital'),('healthcare=dentist','hospital'),('healthcare=doctor','hospital'),('healthcare=hospital','hospital'),('office=estate_agent','housing'),('shop=estate_agent','housing'),('shop=convenience','konbini'),('shop=dry_cleaning','laundry'),('shop=laundry','laundry'),('leisure=garden','park'),('leisure=park','park'),('amenity=pharmacy','pharmacy'),('healthcare=pharmacy','pharmacy'),('shop=chemist','pharmacy'),('shop=electronics','phone'),('shop=mobile_phone','phone'),('shop=telecommunication','phone'),('amenity=college','school'),('amenity=language_school','school'),('amenity=school','school'),('amenity=university','school'),('building=train_station','station'),('public_transport=station','station'),('railway=halt','station'),('railway=station','station'),('shop=department_store','super'),('shop=general','super'),('shop=mall','super'),('shop=supermarket','super'),('shop=variety_store','super'),('amenity=place_of_worship','temple'),('amenity=townhall','ward'),('office=government','ward');
/*!40000 ALTER TABLE `category_osm_tags` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chat_blocks`
--

DROP TABLE IF EXISTS `chat_blocks`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_blocks` (
  `blocker_id` bigint NOT NULL,
  `blocked_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`blocker_id`,`blocked_id`),
  KEY `idx_chat_blocks_blocked` (`blocked_id`),
  CONSTRAINT `fk_chat_blocked` FOREIGN KEY (`blocked_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_chat_blocker` FOREIGN KEY (`blocker_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_chat_block_self` CHECK ((`blocker_id` <> `blocked_id`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_blocks`
--

LOCK TABLES `chat_blocks` WRITE;
/*!40000 ALTER TABLE `chat_blocks` DISABLE KEYS */;
/*!40000 ALTER TABLE `chat_blocks` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chat_conversations`
--

DROP TABLE IF EXISTS `chat_conversations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_conversations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_low_id` bigint NOT NULL,
  `user_high_id` bigint NOT NULL,
  `started_by` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_chat_pair` (`user_low_id`,`user_high_id`),
  KEY `idx_chat_started` (`started_by`,`created_at`),
  KEY `fk_chat_high` (`user_high_id`),
  CONSTRAINT `fk_chat_high` FOREIGN KEY (`user_high_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_chat_low` FOREIGN KEY (`user_low_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_chat_starter` FOREIGN KEY (`started_by`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_chat_pair` CHECK ((`user_low_id` < `user_high_id`))
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_conversations`
--

LOCK TABLES `chat_conversations` WRITE;
/*!40000 ALTER TABLE `chat_conversations` DISABLE KEYS */;
INSERT INTO `chat_conversations` VALUES (1,1,5,1,'2026-10-27 16:58:02'),(2,1,6,6,'2026-10-27 17:14:05'),(3,5,6,6,'2026-10-28 07:39:38');
/*!40000 ALTER TABLE `chat_conversations` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chat_files`
--

DROP TABLE IF EXISTS `chat_files`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_files` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `uploader_id` bigint NOT NULL,
  `message_id` bigint DEFAULT NULL,
  `kind` varchar(10) NOT NULL,
  `storage_key` varchar(500) NOT NULL,
  `size_bytes` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `storage_key` (`storage_key`),
  KEY `idx_chat_files_message` (`message_id`),
  KEY `idx_chat_files_uploader` (`uploader_id`,`created_at`),
  KEY `fk_chat_file_conv` (`conversation_id`),
  CONSTRAINT `fk_chat_file_conv` FOREIGN KEY (`conversation_id`) REFERENCES `chat_conversations` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_chat_file_message` FOREIGN KEY (`message_id`) REFERENCES `chat_messages` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_chat_file_uploader` FOREIGN KEY (`uploader_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_chat_file_kind` CHECK ((`kind` in (_utf8mb4'IMAGE',_utf8mb4'VIDEO',_utf8mb4'AUDIO')))
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_files`
--

LOCK TABLES `chat_files` WRITE;
/*!40000 ALTER TABLE `chat_files` DISABLE KEYS */;
INSERT INTO `chat_files` VALUES (1,1,5,13,'IMAGE','chat/2026/10/75fb9293-1ca5-4422-b765-28fcb125eee4.bin',7986,'2026-10-28 04:18:09',NULL),(2,2,6,20,'IMAGE','chat/2026/10/774f203e-5c96-48bd-aa4d-58f8051cbb21.bin',446309,'2026-10-28 06:52:28','2026-10-28 06:54:42'),(3,2,6,21,'AUDIO','chat/2026/10/6b52fe01-49c0-45a8-ad55-0a3f09c02f0d.bin',40277,'2026-10-28 06:52:47','2026-10-28 06:54:31'),(4,2,1,33,'IMAGE','chat/2026/10/fd54d66b-7ad9-46aa-8823-d6ec31954b7f.bin',22261,'2026-10-28 07:52:31',NULL),(5,2,6,34,'AUDIO','chat/2026/10/ffa164c4-e1b6-4247-8764-97d8ee0d0521.bin',20705,'2026-10-28 07:52:57',NULL),(6,2,6,36,'IMAGE','chat/2026/10/4c162e55-98e1-4f20-ab75-2a3005422281.bin',628795,'2026-10-29 11:15:22',NULL),(7,2,6,40,'IMAGE','chat/2026/10/dd8fcae1-bf15-4010-a1e8-6c60f53912b2.bin',74514,'2026-10-30 03:57:59',NULL);
/*!40000 ALTER TABLE `chat_files` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chat_keys`
--

DROP TABLE IF EXISTS `chat_keys`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_keys` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `public_key` varchar(120) NOT NULL,
  `wrapped_private_key` varchar(1000) NOT NULL,
  `wrap_salt` varchar(64) NOT NULL,
  `wrap_iv` varchar(32) NOT NULL,
  `wrap_iterations` int NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `revoked_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_chat_keys_user` (`user_id`,`revoked_at`),
  CONSTRAINT `fk_chat_key_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_keys`
--

LOCK TABLES `chat_keys` WRITE;
/*!40000 ALTER TABLE `chat_keys` DISABLE KEYS */;
INSERT INTO `chat_keys` VALUES (1,1,'BHHJCs92IFFTZPskNrgRiXqv5JBtZDW26iq2s+luEXry6vPduy182z8bSJrj7atUDdLp+5KBA8KsB9LQL+aYkto=','+JnzA2dkTK/d27ZJm0fXkIv54cAM2sjLoHv8Kjeb/bg2U0n7VMBO93gjQdlzhIiFA+uTDD6ZnNFw0MNz2vnafylArWOLbwhiw14IoQKrg9zMqTWxJeGTkNZsmCByQYolab61nYBNljU661kqs0rfT1xk0F/aUxURqoleqzsJqn5FOUjkhpoxDyWMU2Gzh0yIiJuqMo1V7lBpjA==','+mraQpN6PVkzP6hhc+BppA==','8Rgz+ANRdczv+hYT',600000,'2026-10-28 04:15:51','2026-10-28 06:49:11'),(2,5,'BF2YK2xvYLWF1Y7ewSHj6RvqsgdqrwDSKH+sgIBC0uCW+JgKAd8yTKDYKNkKKoXwRz4dmhtjPvh+bHDwNAZdLXc=','Xka1OvC8aqPYk+ae3JGPNHtY/EXT8hMdCFlOCrh31ONUYTpxB+awtAlEE5rd9MitisGTZzhnXRbDfUdQQsDMxNogGbR5fQ8qQh/UOzxSJSAIUvf/A2/B2E/7Z84lwqd+kCpHINgU0x6C8/bhnuhcVJuKSIM7Gce2synCoVODkdDfHYxLiScGqOMWyp84dpmcx1AFkJerj80slw==','3CW6FZfWg0PYjnVwRFNP8w==','Pb3WFWti+bVy+GYL',600000,'2026-10-28 04:16:18',NULL),(3,6,'BKNeev5AxDvqFWEun/bRR7xKwWnWVW9bfdmx8TWmUlGqKo+iHW0ooRuMQQ5QVKFInXsT2gfM6KjVV7hpjjGsOnc=','jtsbiKFMzv44YjIsmtHfIioJGhaJDwJkC5b9JEUjZoTP9rcRrZ89LFPQ329idTGzHJXg3jR/pOdNR+Oqdihh7Ll6ib1+tbnibxyyWte5HBOA5Yrv8HhTM2zhP6rrPjD2cUcNNHuz8tsNi69XRFeL2wcvB7p9wyrFF8qcbz/gUEF7g9wIOjQZuUOQlBNle73fyieQQpOcPo5Tmw==','dTJOt6pi5AIzSBYeg3N4ZA==','7LBSPSWr+znjEoGv',600000,'2026-10-28 04:49:17',NULL),(4,1,'BMddxApF6IWYB+iiY7mAaPtLr1jepFvb4twdRKYVLl8v3OugVj7ElSncUH0QDlExo9f+TZoFhdV4sOmtQQplgJY=','ozuHEkiyND7pM7qCx/g5UgDyAKKTbV74vXDJQp1Ni55tKjYAf4Rkuai4hTa7WYZLTKDZ+md7H5RtMEZGLbUCtA17UA9meUT2cBHKf0xcrDaS+G2RUzRetvnY7zGFai38H8FjiuaPkKAHst5EG88WmAQ6LBgnQ5rHXQesDjRfUYnoXXLbHD5sgMPbiye3Ok29qgbxGOlISiX68g==','zm5XFA7X/HcpMrndG9D+7g==','ssqO01DG+8/25WNI',600000,'2026-10-28 06:49:11',NULL);
/*!40000 ALTER TABLE `chat_keys` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chat_members`
--

DROP TABLE IF EXISTS `chat_members`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_members` (
  `conversation_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `state` varchar(10) NOT NULL,
  `last_read_id` bigint NOT NULL DEFAULT '0',
  `cleared_id` bigint NOT NULL DEFAULT '0',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `notified_up_to_id` bigint NOT NULL DEFAULT '0',
  `emailed_at` datetime DEFAULT NULL,
  `pushed_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`conversation_id`,`user_id`),
  KEY `idx_chat_members_user` (`user_id`,`state`),
  CONSTRAINT `fk_chat_member_conv` FOREIGN KEY (`conversation_id`) REFERENCES `chat_conversations` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_chat_member_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_chat_member_state` CHECK ((`state` in (_utf8mb4'REQUEST',_utf8mb4'ACCEPTED',_utf8mb4'DECLINED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_members`
--

LOCK TABLES `chat_members` WRITE;
/*!40000 ALTER TABLE `chat_members` DISABLE KEYS */;
INSERT INTO `chat_members` VALUES (1,1,'ACCEPTED',18,0,'2026-10-27 16:58:02',16,'2026-10-28 04:31:14',NULL),(1,5,'ACCEPTED',18,0,'2026-10-27 16:58:37',0,NULL,NULL),(2,1,'ACCEPTED',40,0,'2026-10-27 17:14:28',38,'2026-10-29 13:04:29',NULL),(2,6,'ACCEPTED',40,0,'2026-10-27 17:14:05',10,'2026-10-28 04:16:14',NULL),(3,5,'DECLINED',30,30,'2026-10-28 07:41:29',0,NULL,NULL),(3,6,'ACCEPTED',30,30,'2026-10-28 07:41:29',0,NULL,NULL);
/*!40000 ALTER TABLE `chat_members` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chat_message_edits`
--

DROP TABLE IF EXISTS `chat_message_edits`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_message_edits` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `message_id` bigint NOT NULL,
  `content` varchar(2000) DEFAULT NULL,
  `ciphertext` mediumtext,
  `iv` varchar(32) DEFAULT NULL,
  `franking_tag` char(64) DEFAULT NULL,
  `replaced_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_chat_edits_message` (`message_id`),
  CONSTRAINT `fk_chat_edit_message` FOREIGN KEY (`message_id`) REFERENCES `chat_messages` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_message_edits`
--

LOCK TABLES `chat_message_edits` WRITE;
/*!40000 ALTER TABLE `chat_message_edits` DISABLE KEYS */;
INSERT INTO `chat_message_edits` VALUES (1,12,NULL,'eSmcLg6TozGEhOsRqDHBYwBDEeTKi1WB2plpZgR0OCcSlQ7Cq+RfK2Fky1VQUo/zqni9Buka6UNPV5bV9rsJ+g9LhP6sWXjDIm4pjs6wlBIPBsUm1HKQ27weCZ1I0hSU+M6poqJjCild3YWppst13zBNHw==','DCuM5iQPCZisttgA','afad40163d498f5033752a0d00b1d648d54c9643399f7f58fec78d610aa88340','2026-10-28 04:17:37');
/*!40000 ALTER TABLE `chat_message_edits` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chat_messages`
--

DROP TABLE IF EXISTS `chat_messages`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `sender_id` bigint NOT NULL,
  `content` varchar(2000) DEFAULT NULL,
  `ciphertext` mediumtext,
  `iv` varchar(32) DEFAULT NULL,
  `sender_key_id` bigint DEFAULT NULL,
  `recipient_key_id` bigint DEFAULT NULL,
  `franking_tag` char(64) DEFAULT NULL,
  `reply_to_id` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `edited_at` datetime DEFAULT NULL,
  `recalled_at` datetime DEFAULT NULL,
  `changed_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_chat_messages_conv` (`conversation_id`,`id`),
  KEY `idx_chat_messages_sender` (`sender_id`,`created_at`),
  KEY `idx_chat_messages_changed` (`conversation_id`,`changed_at`),
  KEY `fk_chat_message_reply` (`reply_to_id`),
  KEY `fk_chat_message_sender_key` (`sender_key_id`),
  KEY `fk_chat_message_recipient_key` (`recipient_key_id`),
  CONSTRAINT `fk_chat_message_conv` FOREIGN KEY (`conversation_id`) REFERENCES `chat_conversations` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_chat_message_recipient_key` FOREIGN KEY (`recipient_key_id`) REFERENCES `chat_keys` (`id`),
  CONSTRAINT `fk_chat_message_reply` FOREIGN KEY (`reply_to_id`) REFERENCES `chat_messages` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_chat_message_sender` FOREIGN KEY (`sender_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_chat_message_sender_key` FOREIGN KEY (`sender_key_id`) REFERENCES `chat_keys` (`id`),
  CONSTRAINT `ck_chat_message_body` CHECK (((`content` is not null) or (`ciphertext` is not null) or (`recalled_at` is not null)))
) ENGINE=InnoDB AUTO_INCREMENT=41 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_messages`
--

LOCK TABLES `chat_messages` WRITE;
/*!40000 ALTER TABLE `chat_messages` DISABLE KEYS */;
INSERT INTO `chat_messages` VALUES (1,1,1,'Chị Lan Anh ơi, em mới sang Nippori, làm thẻ cư trú ở phường mất bao lâu ạ?\nEm cảm ơn chị!',NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-27 16:58:02',NULL,NULL,NULL),(2,1,5,'Chào em! Làm thẻ cư trú chỉ mất khoảng 1 tiếng thôi. Xem thêm https://www.city.arakawa.tokyo.jp nhé.',NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-27 16:58:37',NULL,NULL,NULL),(3,1,1,'Dạ em cảm ơn chị nhiều ạ!',NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-27 16:59:00',NULL,NULL,NULL),(4,2,6,'Chào anh ạ',NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-27 17:14:05',NULL,NULL,NULL),(5,2,1,'Chào bạn',NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-27 17:14:35',NULL,NULL,NULL),(6,2,1,'Bạn cần mình giúp gì',NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-27 17:19:50',NULL,NULL,NULL),(7,2,1,'Mình sẽ giúp',NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-27 17:20:08',NULL,NULL,NULL),(8,2,6,'Dạ em cảm ơn anh ạ',NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-27 17:20:24',NULL,NULL,NULL),(9,2,1,'À e ơi',NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-27 17:59:55',NULL,NULL,NULL),(10,2,1,'Alo alo',NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-27 18:00:17',NULL,NULL,NULL),(11,1,5,NULL,'PxIkW47RWZjRsLcAiX+kyvjsJesVOLsyFGZTgb0h6yrdISLE7DGaFfRrn36Uok8Cd9XguMjen14iPyzivMcIqGYVr3WWTdSeWD+GdFdNniopVJII4HRGbPHfbhbblHf1wPIV0+qeFSpLgceT97/YRDYhxBr4cMyHcajIMCS4XedsGwxEjhtOxvMVO3rH','CDHGV1FYIdPTEt6G',2,1,'04172f1eaef78d3c1101a705c45c37bae274607d1c6d34b69a518b7c14348ac2',NULL,'2026-10-28 04:16:30',NULL,NULL,'2026-10-28 04:17:09.395'),(12,1,1,NULL,'WkHzugleMRA56G9K85wNcA8NV64OOHrd5sGlEPOVWN6eAjB0NKBLEJiBnwC+q7EDNXpOXFTVSpy58aW+Ew+5sH031FJ4EiAx+3CT6BK2ikpl/1Au3p3yD0bSDC4qkZIEI/Ht6HGgKQ3qj8FXl9A04RSXg732Q2M=','SYx5KPk32JnFJeex',1,2,'81b8fbd9fcfd4dda2ad041037f83fc273be33276420c9a0890039c01bd16e482',11,'2026-10-28 04:17:23','2026-10-28 04:17:37','2026-10-28 04:20:20','2026-10-28 04:20:19.965'),(13,1,5,NULL,'jxeQGUMwhIHWtWO2DT1d2q/gSM0ZWlbKBAlV+Z3jgHt1hKcFv3abPFPceUMkjOMlHVg20U1RnDPhbcmLJ/LViLhU6Bh2El40wl8QMg+n6ZDLvHNVpC8yuTZHQ+ktZFhs2L85Bwt3Qjjx7UZJji1Y1TbZaEvVH5M2MCxhB229eQq6Tfp8EJe7AORT6C5XsMo67djPwdc4E480TGNIYtGy31LFXt2xls7gkhJq0+/5j7krLsDAXPXkdLj2H4hZh2DnbGr7Ttn521NkLH9cyfS8CmBz0mXfQIV1HKSt0N36Xmf5RsZulw8A8didnID6e+VvxV9vRBNR5gq0aDY=','mEQlzmbkhVQKMQ2s',2,1,'621b819c915acab97b823b60ea25747ea786eb86af39572085eddd1e956a2dee',NULL,'2026-10-28 04:18:09',NULL,NULL,NULL),(14,1,5,NULL,'RBOdg4eti3yBesW2X/rTADRsm6CpzsLWaf8rA2t9WZndIxM5O/z+yLckf0wBC7ahGblwFY99P579BEzL1w0loPUBsJwAP2SUF+5XNxh57Blzls0s19osJe8Hfu6GBZ8=','iTCyJkZmucsDh9m0',2,1,'8667f7888cc33f427ecec59e91a6616d440fe6921c0982bd6eeb095acb3e9de7',NULL,'2026-10-28 04:19:59',NULL,NULL,NULL),(15,1,5,NULL,'zEBCtyb/QHyRxVUolKI8XbtGYT0cclfu0U/C6r7pXEtdjxHNA0cTJa+q1lnzUwISmLv3bDKE+HyPOypQrObla0Ca9F8HtA7T5HJFdLSTlOUgkG793Cbu6bJhKXQI0xs+pJQMCZc1mf8KhkFnPKm0g+f/2A==','NWNq59WqorLWFNQT',2,1,'de69aea62d4b1f0147aef22b002eb46846d26eb09cd22fbba2fb6d6698ee242f',NULL,'2026-10-28 04:21:02',NULL,NULL,NULL),(16,1,5,NULL,'lMQ11vPkX/HqxNboB1q6SLFbZODs3bl8utzj8U4eN3PGLsIIvO1tAaVL09rphOkQtIGGiXe2M2bkpMTRvHkITqiAAwETOeV+9pRIlbp2SNv1xNkY277+KrU9SJYtifKm3KIskJ9MDFOzYiHEd34q9E0=','YDn32GZ16SaPUF28',2,1,'06f56638cf16507b2afb55099dbf551a435843a2211c6c5fbad67d73fd662bb4',NULL,'2026-10-28 04:22:13',NULL,NULL,NULL),(17,1,5,NULL,'mxZAES+WuKw/Vdj5Ei/5J8j2wyl6Gi/kyGBKI8Nro6iNe7VjBZ1fus2W/jhrunMOUbvJCzsiDBbeN4yERIbTxLzwjhE7dNaWJZ7q1QRLrL2HqMmaXPQbiPoG70qopRBVU0Q26VLK5Z8mAh9IcGY=','7K+l0/gmTuS0MQBQ',2,1,'7a94da75c9ea335bc41e8338444b7d5f7d2a9807804fc04fefd3ca97a0f71e9b',NULL,'2026-10-28 04:40:47',NULL,NULL,NULL),(18,1,1,NULL,'bj3vBBsyAfSdpld/wWVtsWZAlgHdPR5E/DZ6ZSoq3SxONpvvOug6p+z2pauFWSCqQWpee7gl9M2C5SU7Tw04RiIeaGEOPvBrwto0fsHoH0GWB6DIls0HGv55/a5siA==','LcqJ+kPr1tmzZAyf',1,2,'381d8774a2420bb9c754a34642098a4bdaedcb40c7f494be5ff26975b1b41506',NULL,'2026-10-28 04:40:49',NULL,NULL,NULL),(19,2,6,NULL,'wvjdGTiAezz66zcutCg5AX7N0r1mSLhSTTMqpcFfdxmLhPEak+QEC+HDUaVvGYRx73Sa0ZD5QBVa+j+EBrsOD59Wyjvg7s+cGoB5PA==','9/12OflODLqk8trs',3,4,'d6a06d5dffdf85379de36627a2ba87487f003b9a173eef42beef07c2b6fecbe9',NULL,'2026-10-28 06:52:05',NULL,NULL,NULL),(20,2,6,NULL,'dThB6KlgzohPg8vV1wLPwsN4NqQ1X7EjxuIXKKBBFGjuXUHdolSSNaej0B0HL8cUOQTSqdwG8VHMidNdqjyqts6jPaNNtNaHk4OSaqDxVQrUCY7dsQIxa9CC35o5VvDQJyWYhghp/RvfClIhbA1C40g6/ww/pvfNQLp2y5HIr5Rx6VqDqecblildhW1VNFWoXk87MEPtqrbzyjyBTbPZRp8zjt4ewFvDsEN0C0ib+0B3r7p76G4yzqJL//svjAwjU01UbmtHw84iYdSTkb9M56CdRlU9aNA24g==','imOgR1OaRsN9WKGh',3,4,'32cdd60ff23938d1f78c40abb97cde1538da8be3be36412f08f7aac3c11c6c26',NULL,'2026-10-28 06:52:28',NULL,'2026-10-28 06:54:42','2026-10-28 06:54:41.803'),(21,2,6,NULL,'GqlyckOzx0/2uSE6yQ2TEVaG+I3hKqWLhST3TuiSHC5M2X+JPx01TrKI61RqQjQEjD1ckDJMWvE6Yrj4EbLA/fQeuZTbjwvOCvfeCrldRonHqjJlyIGAsWGZWCi98F8bAbB4h/mzWZ8/z5zLUcjq+OQEDwB3WGfXJfCLds2IXAqgXyAwH9FEe2njvaOM8+nQmiRKEQO15+XZxT+e2eNUBgc4t77hUZPQWPu0GykyGylp6UA9cqqw5socQHE4RqNq0WwjK0uJt2RHV0qnD/aXVPctS2s1u1GJpOMlXjDP9cUfjg==','Nmp1U26sxzgfH9gG',3,4,'3e676687786063303644e0d0d957181f9083f41afab20afcd43b2a3895c84b4d',NULL,'2026-10-28 06:52:47',NULL,'2026-10-28 06:54:31','2026-10-28 06:54:31.124'),(22,2,1,NULL,'VI2/eGtNHUv0mveXo3VetcaQChPfhO54McYvKc7BA7/KOxqOJMax+Ea0lTEHGNog99MCn7ZeBVA3Tc9lunJRelduZ742rZURjzU0HKAo4dKvnY65JjC6Kzhi/hunwD++kfElrQjUSw==','JBobD+XFD0ClLR+u',4,3,'a7c27029e55179486ffebbfd05db11265aba9bdec960b9370484703bbf2ac11f',NULL,'2026-10-28 06:53:28',NULL,NULL,NULL),(23,2,6,NULL,'rvmR2LgApWA+daUkRT0DkvPmZHlmgwll0sR/uXwvFaObqBZFj9WZUX/yKdEk0YhWilSlIMHjWl+pZjm5hn0bE2E7G9QufOVz','WZ8Rtsk5B1NK/yay',3,4,'dd3662f719fdeb1cb1a73067461f930932ea18e12412f383718403d2303fed19',NULL,'2026-10-28 06:57:24',NULL,NULL,NULL),(24,2,1,NULL,'MxbjboCve83FwV7lGXzXm3JqdDenJd7T4ZDvFcrdbk4GOPlqHuiDPyBHfaRetM5UO7PEAxjM//oLEiaWs33/WrWlXtpZDc0k0KA=','HZhfL2zB/c7EdHU6',4,3,'29778245167ffb72bdeadf82d7f0828695e7184e10921db57f75da9d7b2a5fac',NULL,'2026-10-28 06:58:16',NULL,NULL,NULL),(25,2,1,NULL,'TZ9V8vFlf7f/LtLmZKy5d9uN1IXJCFzPht391isZOwGUKa+iN/4H5Zn6xpgcEhPlic9wpNlvcjjGPoRFTU4z3+is+qY=','CnZJgbKaLwBEKsUy',4,3,'c19e91dbfc232c8328320bac583074ddcc1dbe6ecc8dc233dbd7c811ee9bcc1c',NULL,'2026-10-28 06:58:32',NULL,NULL,NULL),(26,2,1,NULL,'mkR4KJkB7S3DaS9ssQp46UW1cV7RigbQGlXSgGWBfCG82iaMQ9wkIoVXw9GhY6KtQrIbIOvVK0OBbvrsGT1MKF6fyZQ=','FlPBJXZznAag1zc+',4,3,'42ed43e11313e7eabe38357db3b0402caa3d9bee67fd69eeb659be0eabd1e352',NULL,'2026-10-28 06:59:00',NULL,NULL,NULL),(27,2,1,NULL,'v/xnVYsV15rz8JSrCPIcs+G/eQvw88dM10YX1GMS7XdbYLDc97sBDNn4WLRtVCtEvtv3hdNQYoV4Ab1B2aRtxwB6ow2N','IFFKvBiFi2+IVa1R',4,3,'b52d2f71e09072d7ca0738bce9487d0631720a4862b1413b09c5376e6f34c712',NULL,'2026-10-28 06:59:11',NULL,NULL,NULL),(28,3,6,'Chào chị Lan Anh, em thử tin nhắn báo ngay ạ',NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-28 07:39:38',NULL,NULL,NULL),(29,3,6,'Tin thứ hai để đo thời gian',NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-28 07:40:05',NULL,NULL,NULL),(30,3,6,'Tin thu ba hien ngay',NULL,NULL,NULL,NULL,NULL,NULL,'2026-10-28 07:40:55',NULL,NULL,NULL),(31,2,1,NULL,'YIHvGMOz6nEXubO6KKnq7ppTMRdSgbX0dq8Mfwwr2Xds69idkWjZSAl0LfUuR+b84y6Gmu27GRLdMGG6UPN/i8S14IZh','FNnTuQnaLPsRXBHp',4,3,'171b269f6cf10fee077068bc708cbddae4df3bbcda9459013c56a23fc1387f78',NULL,'2026-10-28 07:51:42',NULL,NULL,NULL),(32,2,6,NULL,'KeszyzRLkGONkgs03SSwkhuTDW/voYlP5THX1Sit9w+A28ilDNXyBHvjpJyCDvA9jKXE8AX+hIwUdM6nDYD/ODMzPCc=','tCscXvIey38DviHR',3,4,'38af93d76eb3cdaf51af781636e9e4b508aa1df9250f600e6097c85f322dba27',NULL,'2026-10-28 07:52:22',NULL,NULL,NULL),(33,2,1,NULL,'Lbzv+5NB9CBmdRDxt3q5Lo1G8AClnj5Tj7I8uC2SnQlFvjBcnKEw16XNKOYNt7nTJwvQtpKIMsGsUIYCl0J86NUWGNstgn1TEV0OV1Pe3nTGlIJ2/h/VTirN5yCr4MXmsacrBj8yzys4WoODluFU1L6iHwGn1GQXZSHPG8XUP2ZwhFay6zQW/+su9wboFq53/HqKc1UeIjDldlFxxCcOkYoBJ9lC+sKF6vibOwiQh+T/69EKsevSTaYwFQjx7av6dDcnT8qUcHaQWic3NpPWCEbEx6PQUmupvNR2j7zDeO3IvzZA','VlcG/Q9NxlRocAOA',4,3,'a930bd6326c62e796cdca98b645e937da4dd5762da2865526efe0cfa33c8b843',NULL,'2026-10-28 07:52:31',NULL,NULL,NULL),(34,2,6,NULL,'GoMvNUVBdi0MZK9Efa9HJYWaePex+kpua4JZTSXN1GjVO+zsSytGDVEasSHOnBBD8zo1373ye0CGpW5zK1oCIJeHPJLrV8ehLUdERNh+A5WupPt1smIvfvWRr8i8mHyU+qSFnF8JAG5+oHCNPVNJSLoKRlgABpjTHZa9BhLzQEG03zBzeF2zxGZmrD9xBhab9ZTsYx/d2SWFcKO4L7gTuBhdNMh3tRN7+31wzaqtbmf2RbErT7KDEEII6bJd7U6w0baWadQ7JuIfVXYEM89gtLkRGCHB+yVcDvr+x2FhDanQ+w==','d2VfIDiJLCiw0GSg',3,4,'fb887bb2bf5a6a2b06e417a2081ac1b2e9ba181c0934d8e778a9b27e1219a439',NULL,'2026-10-28 07:52:58',NULL,NULL,NULL),(35,2,6,NULL,'ltxJC9uB7SINAvI0ZgC9RYtLCuiL683AwAaCTVJwDjvgEX1rqkNZKY2Bx563L2j84LVM6ZoXIVm6FJ0dn/9FrRLRylpXQOXp','s4n8ueE39RQQ1pB+',3,4,'7894bf52c43024e82c15ce6ab89012ae5519df5dcf27d83867c9d940fdb42e58',NULL,'2026-10-29 11:15:08',NULL,NULL,NULL),(36,2,6,NULL,'VyRBIQQSQ56oqwjxOHXYBleXgfr4wLHam2NLLuLxcJ6o+XxUE9XrLtgNoHLXOOYQw5jP/20gjo6QW3coRUlVGomjX0Zc2vbFcO68Pl4dTgRv9JDI/yOg8aY9Q5+By+vZygCKzHMTGzaY9Rq02L6SUbjbyqAMPrFOGR+7qX9U2EO9BjO68MnNKRZQChjq0vt7xlJylJFtsW8eG72PVyO92N9wvhPsmliLTlTQHFDQPeiFfuDCOaBlMifuTJQ11yL3w1RXYoca2jRRhVED2CMLniJI1wc2j+2ifV9Z4gkSbGeYg9JpyP0n','hFwvR7B1O3QpdLjg',3,4,'2b456b9978a36a7fcb9d90930cc532a70fe44b1392145f7dcc47f702bba682a6',NULL,'2026-10-29 11:15:23',NULL,NULL,'2026-10-29 11:15:42.467'),(37,2,6,NULL,'j71+jSHoKaSxIxbozMT4/dcalbNQg2f0sPuprLnv0UXJcrsri1U4AEVVuwSoE68r5rcryINqfomWgCJs2GzrrVyOqcidepzaTWQ=','qfNNZ+KDf6Nx4QvC',3,4,'4d68d79de4af42044e5a17a5f81014877792b7fbcfc39ae6b4fcf96b6f2e7eb6',NULL,'2026-10-29 12:53:04',NULL,NULL,NULL),(38,2,6,NULL,'rLfmPaL1VirD8kGvXKuuOCPi6O1nYwToIa1CMi1MUpvYi7Ax9Zsv1efmp7qkScDCZT97l/m+V1UeVav1CyY980RUufY1xLNm08ryl6439xg=','N2FGsCf71XZNKdjv',3,4,'f282590c82ec193f11f577b1031df6fc99c7b5c173c34aaeb1fee99f353d50ca',NULL,'2026-10-29 12:53:21',NULL,NULL,NULL),(39,2,1,NULL,'FK/UZD1qvVx6mlnoNxQroFqRZNKtnbS5idohMI5j8ahr213lEbTIfKGQ//b+uLrcaWMqPH2RybzREpH1e6gQxiOoLBX/RV2nZdu5NQ==','DO4QJNzZaadOQ33+',4,3,'4b115bf4cabbb9782ffd86a40275ca19c49f8beee264d0b78713ca63e4305679',NULL,'2026-10-30 03:57:38',NULL,NULL,NULL),(40,2,6,NULL,'ISaX2A+pQc6PJ+U4yWfDumZVccAiz19xAZtv6FKkKUxZRLbEexIrUliepMD3kTW1gMwghYxxxURniul6fEe+2YliBVTFqmX5o+EO3wg0/j3bT4k/yAl9oUP3GFhh38XkHIDizs7xxSundF5iZmyWaDA9bBEs3UTrbXwrwE3OdkHkuHvOLkNQ83E39SOenwW7h1zMYwo0cbgy1rMetRm7YVKH/y2qhreAMwcr5WS2bYDkQmeV2VYrxrt86CHW1eTDPbRuIA09yjOk7G0E6qhbxz+KnQbIx9Q=','4oS77mgiMAfMo0yD',3,4,'5e735f960695c8e8f63f8fa1ef1571b4fff3242b455fa2168afaf003d676bb84',NULL,'2026-10-30 03:57:59',NULL,NULL,NULL);
/*!40000 ALTER TABLE `chat_messages` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chat_notify_settings`
--

DROP TABLE IF EXISTS `chat_notify_settings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_notify_settings` (
  `user_id` bigint NOT NULL,
  `email_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `push_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_chat_notify_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_notify_settings`
--

LOCK TABLES `chat_notify_settings` WRITE;
/*!40000 ALTER TABLE `chat_notify_settings` DISABLE KEYS */;
/*!40000 ALTER TABLE `chat_notify_settings` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chat_reactions`
--

DROP TABLE IF EXISTS `chat_reactions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_reactions` (
  `message_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `emoji` varchar(16) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`message_id`,`user_id`),
  KEY `fk_chat_reaction_user` (`user_id`),
  CONSTRAINT `fk_chat_reaction_message` FOREIGN KEY (`message_id`) REFERENCES `chat_messages` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_chat_reaction_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_reactions`
--

LOCK TABLES `chat_reactions` WRITE;
/*!40000 ALTER TABLE `chat_reactions` DISABLE KEYS */;
INSERT INTO `chat_reactions` VALUES (11,1,'❤️','2026-10-28 04:17:09'),(36,1,'❤️','2026-10-29 11:15:42');
/*!40000 ALTER TABLE `chat_reactions` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `chat_report_evidence`
--

DROP TABLE IF EXISTS `chat_report_evidence`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_report_evidence` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `report_id` bigint NOT NULL,
  `message_id` bigint NOT NULL,
  `plaintext` mediumtext NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_chat_evidence_report` (`report_id`),
  KEY `fk_chat_evidence_message` (`message_id`),
  CONSTRAINT `fk_chat_evidence_message` FOREIGN KEY (`message_id`) REFERENCES `chat_messages` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_chat_evidence_report` FOREIGN KEY (`report_id`) REFERENCES `reports` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `chat_report_evidence`
--

LOCK TABLES `chat_report_evidence` WRITE;
/*!40000 ALTER TABLE `chat_report_evidence` DISABLE KEYS */;
INSERT INTO `chat_report_evidence` VALUES (1,2,11,'{\"v\":1,\"text\":\"Tin này đã được mã hóa đầu cuối nhé em. Zalo chị 0901234567\"}','2026-10-28 04:20:39');
/*!40000 ALTER TABLE `chat_report_evidence` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `comment_likes`
--

DROP TABLE IF EXISTS `comment_likes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comment_likes` (
  `comment_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`comment_id`,`user_id`),
  KEY `fk_clike_user` (`user_id`),
  CONSTRAINT `fk_clike_comment` FOREIGN KEY (`comment_id`) REFERENCES `comments` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_clike_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `comment_likes`
--

LOCK TABLES `comment_likes` WRITE;
/*!40000 ALTER TABLE `comment_likes` DISABLE KEYS */;
/*!40000 ALTER TABLE `comment_likes` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `comments`
--

DROP TABLE IF EXISTS `comments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `blog_id` bigint NOT NULL,
  `author_id` bigint NOT NULL,
  `parent_id` bigint DEFAULT NULL,
  `content` varchar(2000) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PROCESSING',
  `is_edited` tinyint(1) NOT NULL DEFAULT '0',
  `removed_at` datetime DEFAULT NULL,
  `purge_at` datetime DEFAULT NULL,
  `like_count` int NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_comments_blog` (`blog_id`,`created_at`),
  KEY `idx_comments_purge` (`purge_at`),
  KEY `fk_comments_author` (`author_id`),
  KEY `fk_comments_parent` (`parent_id`),
  CONSTRAINT `fk_comments_author` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_comments_blog` FOREIGN KEY (`blog_id`) REFERENCES `blogs` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_comments_parent` FOREIGN KEY (`parent_id`) REFERENCES `comments` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_comments_status` CHECK ((`status` in (_utf8mb4'PROCESSING',_utf8mb4'PUBLISHED',_utf8mb4'REJECTED',_utf8mb4'HIDDEN',_utf8mb4'REMOVED')))
) ENGINE=InnoDB AUTO_INCREMENT=18 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `comments`
--

LOCK TABLES `comments` WRITE;
/*!40000 ALTER TABLE `comments` DISABLE KEYS */;
INSERT INTO `comments` VALUES (1,1,1,NULL,'Đến ga1','PUBLISHED',1,NULL,NULL,0,'2026-10-17 05:56:54','2026-10-19 14:03:52'),(2,1,1,NULL,'AAA','PUBLISHED',0,NULL,NULL,0,'2026-10-17 11:28:19','2026-10-17 11:28:19'),(4,8,1,NULL,'alo','PUBLISHED',0,NULL,NULL,0,'2026-10-19 13:19:14','2026-10-19 13:19:14'),(5,8,1,4,'alo','PUBLISHED',0,NULL,NULL,0,'2026-10-19 13:19:23','2026-10-19 13:19:23'),(8,10,1,NULL,'alo1','PUBLISHED',1,NULL,NULL,0,'2026-10-19 13:43:33','2026-10-19 14:03:19'),(10,9,1,NULL,'alo','PUBLISHED',0,NULL,NULL,0,'2026-10-19 13:55:34','2026-10-19 13:55:34'),(11,6,1,NULL,'không bảy bảy sáu chín bốn sáu chín sáu bảy','PUBLISHED',0,NULL,NULL,0,'2026-10-20 17:10:45','2026-10-20 17:10:45'),(12,6,1,NULL,'alo alo','PUBLISHED',0,NULL,NULL,0,'2026-10-20 17:11:45','2026-10-20 17:11:45'),(13,20,5,NULL,'Kinh nghiệm là gì thế bạn','PUBLISHED',0,NULL,NULL,0,'2026-10-22 07:13:26','2026-10-22 07:13:26'),(14,21,1,NULL,'ádlo','PUBLISHED',0,NULL,NULL,0,'2026-10-22 15:01:37','2026-10-22 15:01:37'),(16,8,6,NULL,'Bạn tìm Tạp hoá Nippori bạn nhé','PUBLISHED',0,NULL,NULL,0,'2026-11-06 18:34:05','2026-11-06 18:34:05'),(17,58,1,NULL,'1.Đến ga Machiya Station (町屋駅).\n\n2.Đi Tokyo Metro Chiyoda Line (màu xanh lá) đến Kita-Senju (北千住).\n\n3.Đổi sang Tobu Skytree Line.\n\n4.Xuống ga Tokyo Skytree (とうきょうスカイツリー).\n\n5.Đi bộ vào tháp.','PUBLISHED',0,NULL,NULL,0,'2026-11-07 06:16:45','2026-11-07 06:16:45');
/*!40000 ALTER TABLE `comments` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `duplicate_dismissals`
--

DROP TABLE IF EXISTS `duplicate_dismissals`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `duplicate_dismissals` (
  `place_a_id` bigint NOT NULL,
  `place_b_id` bigint NOT NULL,
  `dismissed_by` bigint NOT NULL,
  `dismissed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`place_a_id`,`place_b_id`),
  KEY `fk_dismiss_b` (`place_b_id`),
  KEY `fk_dismiss_by` (`dismissed_by`),
  CONSTRAINT `fk_dismiss_a` FOREIGN KEY (`place_a_id`) REFERENCES `places` (`id`),
  CONSTRAINT `fk_dismiss_b` FOREIGN KEY (`place_b_id`) REFERENCES `places` (`id`),
  CONSTRAINT `fk_dismiss_by` FOREIGN KEY (`dismissed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `ck_dismiss_order` CHECK ((`place_a_id` < `place_b_id`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `duplicate_dismissals`
--

LOCK TABLES `duplicate_dismissals` WRITE;
/*!40000 ALTER TABLE `duplicate_dismissals` DISABLE KEYS */;
/*!40000 ALTER TABLE `duplicate_dismissals` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `email_verification_codes`
--

DROP TABLE IF EXISTS `email_verification_codes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `email_verification_codes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(255) NOT NULL,
  `purpose` varchar(20) NOT NULL,
  `code_hash` char(64) NOT NULL,
  `attempts` tinyint NOT NULL DEFAULT '0',
  `expires_at` datetime NOT NULL,
  `verified_at` datetime DEFAULT NULL,
  `ticket_hash` char(64) DEFAULT NULL,
  `ticket_expires_at` datetime DEFAULT NULL,
  `used_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `ticket_hash` (`ticket_hash`),
  KEY `idx_codes_email` (`email`,`purpose`,`created_at`),
  CONSTRAINT `ck_codes_purpose` CHECK ((`purpose` in (_utf8mb4'REGISTER',_utf8mb4'RESET_PASSWORD')))
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `email_verification_codes`
--

LOCK TABLES `email_verification_codes` WRITE;
/*!40000 ALTER TABLE `email_verification_codes` DISABLE KEYS */;
INSERT INTO `email_verification_codes` VALUES (7,'fux34920@laoia.com','REGISTER','b67546f86d157a2f85f8f1032620c0474772c3ed79b026688130a0615d700bf6',0,'2026-11-07 01:15:11','2026-11-07 01:06:30','ff0091da925fa4f830815cb9d8988e6d764807921256d0f158bab1ae500701f7','2026-11-07 01:36:30','2026-11-07 01:06:57','2026-11-07 01:05:11'),(8,'egy06211@laoia.com','REGISTER','173b4b10ad6de8c2473e62df3730a7b926cd22b8d8036858b37eb8dc4f946d41',0,'2026-11-07 01:49:46','2026-11-07 01:40:59','5b14fa1f55c966cf084fb3dcd7955464ca652cf6f94795931082bd92ae05b120','2026-11-07 02:10:59','2026-11-07 01:41:09','2026-11-07 01:39:46'),(9,'ynq67250@laoia.com','REGISTER','8e27ade61a494b61f883c4a8ccf6ca303945539d3b8d1a7e73b319a7b7a8f697',0,'2026-11-07 06:07:04','2026-11-07 05:58:12','fb4baea6202e052351cf1111929723ccd5e0328d8f2167a76df0bbcba7528a9f','2026-11-07 06:28:12','2026-11-07 05:58:32','2026-11-07 05:57:04'),(10,'ductqhe181164@fpt.edu.vn','REGISTER','bfbf5030e4e85d7dcd46361d52716b3facd4b8293a4422e9446ce99b1c0b13ea',0,'2026-11-07 06:18:44','2026-11-07 06:09:04','542d0fb5a57b435cfc7b0585ff0f3540027217b4d37bdb0e796b5fa2f7091ead','2026-11-07 06:39:04','2026-11-07 06:09:16','2026-11-07 06:08:44');
/*!40000 ALTER TABLE `email_verification_codes` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `events`
--

DROP TABLE IF EXISTS `events`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(160) NOT NULL,
  `cover_media_id` bigint DEFAULT NULL,
  `start_date` date NOT NULL,
  `end_date` date NOT NULL,
  `location_name` varchar(160) NOT NULL,
  `lat` decimal(9,6) DEFAULT NULL,
  `lng` decimal(9,6) DEFAULT NULL,
  `cost_note` varchar(120) DEFAULT NULL,
  `tip` varchar(500) DEFAULT NULL,
  `created_by` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_events_dates` (`start_date`,`end_date`),
  KEY `fk_events_cover` (`cover_media_id`),
  KEY `fk_events_creator` (`created_by`),
  CONSTRAINT `fk_events_cover` FOREIGN KEY (`cover_media_id`) REFERENCES `media` (`id`),
  CONSTRAINT `fk_events_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`),
  CONSTRAINT `ck_events_dates` CHECK ((`end_date` >= `start_date`))
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `events`
--

LOCK TABLES `events` WRITE;
/*!40000 ALTER TABLE `events` DISABLE KEYS */;
INSERT INTO `events` VALUES (1,'Lễ hội Obon công viên Yanaka',NULL,'2026-08-17','2026-08-19','Công viên Yanaka',35.725800,139.766000,'Miễn phí','Buổi tối có múa Bon Odori quanh sân khấu giữa công viên.',2,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(2,'Chợ phiên cuối tuần phố Yanaka Ginza',NULL,'2026-10-15','2026-10-17','Phố Yanaka Ginza',35.727400,139.765800,'Miễn phí vào cửa','Nhiều món ăn vặt 100 đến 300 yên, nên mang tiền lẻ.',2,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(3,'Lễ hội phố vải Nippori',NULL,'2026-10-19','2026-10-20','Phố vải Nippori, Higashi-Nippori',35.726500,139.774200,'Miễn phí','Nhiều cửa hàng vải giảm giá trong hai ngày lễ.',2,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(4,'Lễ hội mùa thu đền Suwa',NULL,'2026-10-30','2026-10-31','Đền Suwa, Nishi-Nippori',35.730300,139.768200,'Miễn phí','Có quầy đồ ăn và biểu diễn trống taiko buổi tối.',2,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(5,'Ngắm hoa anh đào ở công viên Ueno',NULL,'2027-03-25','2027-04-05','Công viên Ueno, cách Nippori hai ga',NULL,NULL,'Miễn phí','Đi trước 9 giờ sáng để có chỗ ngồi.',2,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL);
/*!40000 ALTER TABLE `events` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `faq_entries`
--

DROP TABLE IF EXISTS `faq_entries`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `faq_entries` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `question` varchar(300) NOT NULL,
  `answer` mediumtext NOT NULL,
  `topic_code` varchar(30) NOT NULL,
  `published` tinyint(1) NOT NULL DEFAULT '1',
  `created_by` bigint NOT NULL,
  `updated_by` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_faq_public_topic` (`published`,`topic_code`,`updated_at`),
  KEY `fk_faq_topic` (`topic_code`),
  KEY `fk_faq_creator` (`created_by`),
  KEY `fk_faq_updater` (`updated_by`),
  CONSTRAINT `fk_faq_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_faq_topic` FOREIGN KEY (`topic_code`) REFERENCES `faq_topics` (`code`),
  CONSTRAINT `fk_faq_updater` FOREIGN KEY (`updated_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `faq_entries`
--

LOCK TABLES `faq_entries` WRITE;
/*!40000 ALTER TABLE `faq_entries` DISABLE KEYS */;
INSERT INTO `faq_entries` VALUES (1,'Nhập cảnh tại sân bay Nhật Bản cần làm gì?','Trước khi nhập cảnh mọi người hãy chuẩn bị sẵn mã qr khai báo hải quan qua website của Nhật Bản. cùng với hộ chiếu và giấy tờ công việc nếu có.','PREPARATION',1,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19'),(2,'Trước khi sang Nhật cần chuẩn bị những giấy tờ gì?','Bạn nên chuẩn bị hộ chiếu còn hiệu lực, visa phù hợp, giấy tờ liên quan đến tư cách lưu trú (như COE nếu được yêu cầu), vé máy bay, thông tin nơi ở và giấy tờ từ trường học hoặc đơn vị tiếp nhận.\n\nNgoài ra, nên lưu bản điện tử của giấy tờ quan trọng và chuẩn bị thông tin liên hệ khẩn cấp tại Nhật.','PREPARATION',1,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19'),(3,'Khi mới sang Nhật nên mang theo bao nhiêu tiền mặt?','Số tiền cần mang phụ thuộc vào nơi ở, thời điểm nhận lương hoặc học bổng và những khoản đã thanh toán trước.\n\nĐối với người đã có ký túc xá, có thể dự trù khoảng 30.000–50.000 yên cho chi tiêu ban đầu như ăn uống, đi lại và vật dụng thiết yếu. Đây chỉ là mức tham khảo, không bao gồm tiền thuê nhà, đặt cọc hoặc các khoản phí lớn.','PREPARATION',1,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19'),(4,'Làm thế nào để mua SIM điện thoại khi mới sang Nhật?','Bạn có thể đăng ký SIM tại cửa hàng của nhà mạng hoặc qua website. Thông thường cần giấy tờ xác minh danh tính, địa chỉ cư trú và phương thức thanh toán phù hợp.\n\nNên kiểm tra điện thoại có hỗ trợ băng tần tại Nhật, có bị khóa mạng không, và so sánh phí hằng tháng, dung lượng data trước khi đăng ký.','LIVING',1,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19'),(5,'Phân loại và đổ rác tại Nhật như thế nào?','Rác thường được chia thành các nhóm như rác cháy được (燃えるごみ), rác không cháy được (燃えないごみ), chai PET, lon và rác tái chế.\n\nMỗi địa phương có quy định riêng về loại rác, túi rác và ngày thu gom. Bạn nên xin lịch đổ rác tại cơ quan hành chính hoặc hỏi chủ nhà để thực hiện đúng.','LIVING',1,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19'),(6,'Du học sinh tại Nhật có được làm thêm không?','Du học sinh có thể làm thêm nếu được cấp phép hoạt động ngoài tư cách lưu trú (資格外活動許可).\n\nTheo quy định thông thường, giới hạn là 28 giờ mỗi tuần trong thời gian học và tối đa 8 giờ mỗi ngày trong kỳ nghỉ dài chính thức của trường, với các điều kiện áp dụng. Một số ngành nghề bị cấm dù đã có giấy phép.','LIVING',1,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19'),(7,'Sau khi đến Nhật bao lâu phải đăng ký địa chỉ cư trú?','Người nước ngoài thuộc diện cư trú trung và dài hạn phải đăng ký địa chỉ tại cơ quan hành chính địa phương trong vòng 14 ngày kể từ khi xác lập nơi cư trú.\n\nThông thường cần mang thẻ cư trú hoặc hộ chiếu có ghi chú phù hợp đến 市役所 (Shiyakusho) hoặc 区役所 (Kuyakusho).','PROCEDURES',1,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19'),(8,'Nếu làm mất thẻ cư trú tại Nhật thì phải làm gì?','Khi phát hiện mất thẻ, bạn nên trình báo với cảnh sát và chuẩn bị giấy tờ xác nhận nếu được cấp.\n\nSau đó, liên hệ Cơ quan Quản lý Xuất nhập cảnh để xin cấp lại. Theo quy định thông thường, phải nộp đơn trong vòng 14 ngày kể từ khi biết thẻ bị mất. Bạn cần kiểm tra hồ sơ và các trường hợp ngoại lệ trên website chính thức.','PROCEDURES',1,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19'),(9,'Khi gặp tình huống khẩn cấp tại Nhật cần gọi số nào?','Tại Nhật Bản:\n- 110: Cảnh sát.\n- 119: Cứu hỏa và cấp cứu.\nKhi gọi, hãy cố gắng cung cấp vị trí, tình huống đang xảy ra và số điện thoại liên hệ. Nếu không nói tốt tiếng Nhật, hãy nhờ người xung quanh hỗ trợ khi có thể.','OTHER',1,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19'),(10,'Nếu bị mất hộ chiếu tại Nhật phải làm gì?','Bạn nên trình báo mất hộ chiếu với cảnh sát, sau đó liên hệ Đại sứ quán hoặc Tổng Lãnh sự quán Việt Nam tại Nhật để được hướng dẫn xin cấp lại hộ chiếu hoặc giấy tờ đi lại phù hợp.\n\nNên chuẩn bị bản sao hộ chiếu, giấy tờ tùy thân còn lại và giấy xác nhận từ cảnh sát nếu được yêu cầu.','OTHER',1,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19');
/*!40000 ALTER TABLE `faq_entries` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `faq_resources`
--

DROP TABLE IF EXISTS `faq_resources`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `faq_resources` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `faq_id` bigint NOT NULL,
  `kind` varchar(10) NOT NULL,
  `target_id` bigint DEFAULT NULL,
  `url` varchar(2000) DEFAULT NULL,
  `label` varchar(200) NOT NULL,
  `sort_order` int NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_faq_resource` (`faq_id`),
  CONSTRAINT `fk_faq_resource` FOREIGN KEY (`faq_id`) REFERENCES `faq_entries` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_faq_resource` CHECK ((`kind` in (_utf8mb4'GUIDE',_utf8mb4'BLOG',_utf8mb4'QUESTION',_utf8mb4'EXTERNAL')))
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `faq_resources`
--

LOCK TABLES `faq_resources` WRITE;
/*!40000 ALTER TABLE `faq_resources` DISABLE KEYS */;
INSERT INTO `faq_resources` VALUES (1,1,'GUIDE',21,NULL,'Cách khai báo hải quan qua website',0),(2,5,'GUIDE',25,NULL,'Xem chi tiết về phân loại rác tại Nhật',0);
/*!40000 ALTER TABLE `faq_resources` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `faq_sources`
--

DROP TABLE IF EXISTS `faq_sources`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `faq_sources` (
  `question_id` bigint NOT NULL,
  `answer_id` bigint NOT NULL,
  `faq_id` bigint NOT NULL,
  `processed_by` bigint NOT NULL,
  `processed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`question_id`,`answer_id`),
  KEY `fk_faq_source_answer` (`answer_id`),
  KEY `fk_faq_source_entry` (`faq_id`),
  KEY `fk_faq_source_moderator` (`processed_by`),
  CONSTRAINT `fk_faq_source_answer` FOREIGN KEY (`answer_id`) REFERENCES `comments` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_faq_source_entry` FOREIGN KEY (`faq_id`) REFERENCES `faq_entries` (`id`),
  CONSTRAINT `fk_faq_source_moderator` FOREIGN KEY (`processed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_faq_source_question` FOREIGN KEY (`question_id`) REFERENCES `blogs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `faq_sources`
--

LOCK TABLES `faq_sources` WRITE;
/*!40000 ALTER TABLE `faq_sources` DISABLE KEYS */;
/*!40000 ALTER TABLE `faq_sources` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `faq_topics`
--

DROP TABLE IF EXISTS `faq_topics`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `faq_topics` (
  `code` varchar(30) NOT NULL,
  `title` varchar(100) NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `faq_topics`
--

LOCK TABLES `faq_topics` WRITE;
/*!40000 ALTER TABLE `faq_topics` DISABLE KEYS */;
INSERT INTO `faq_topics` VALUES ('LANGUAGE','Ngôn ngữ và giao tiếp',5),('LIVING','Sinh hoạt và đời sống',2),('OTHER','Khác',99),('PREPARATION','Chuẩn bị trước khi sang Nhật',1),('PROCEDURES','Thủ tục và giấy tờ',4),('STUDY_WORK','Học tập và công việc',3);
/*!40000 ALTER TABLE `faq_topics` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `featured_snapshots`
--

DROP TABLE IF EXISTS `featured_snapshots`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `featured_snapshots` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `period` varchar(10) NOT NULL,
  `computed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `blog_id` bigint NOT NULL,
  `rank_no` tinyint NOT NULL,
  `score` int NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_feat_blog` (`blog_id`),
  CONSTRAINT `fk_feat_blog` FOREIGN KEY (`blog_id`) REFERENCES `blogs` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_feat_period` CHECK ((`period` in (_utf8mb4'WEEK',_utf8mb4'MONTH'))),
  CONSTRAINT `ck_feat_rank` CHECK ((`rank_no` between 1 and 3))
) ENGINE=InnoDB AUTO_INCREMENT=657 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `featured_snapshots`
--

LOCK TABLES `featured_snapshots` WRITE;
/*!40000 ALTER TABLE `featured_snapshots` DISABLE KEYS */;
INSERT INTO `featured_snapshots` VALUES (656,'MONTH','2026-11-07 17:00:00',3,1,20);
/*!40000 ALTER TABLE `featured_snapshots` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `flyway_schema_history`
--

DROP TABLE IF EXISTS `flyway_schema_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flyway_schema_history` (
  `installed_rank` int NOT NULL,
  `version` varchar(50) DEFAULT NULL,
  `description` varchar(200) NOT NULL,
  `type` varchar(20) NOT NULL,
  `script` varchar(1000) NOT NULL,
  `checksum` int DEFAULT NULL,
  `installed_by` varchar(100) NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `flyway_schema_history_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `flyway_schema_history`
--

LOCK TABLES `flyway_schema_history` WRITE;
/*!40000 ALTER TABLE `flyway_schema_history` DISABLE KEYS */;
INSERT INTO `flyway_schema_history` VALUES (1,'1','init schema','SQL','V1__init_schema.sql',1776932722,'root','2026-10-16 01:39:32',2043,1),(2,'2','email codes and legal documents','SQL','V2__email_codes_and_legal_documents.sql',942693247,'root','2026-10-16 01:39:32',36,1),(3,'3','other place category and google types','SQL','V3__other_place_category_and_google_types.sql',-1302598015,'root','2026-10-19 10:34:55',76,1),(4,'4','openstreetmap places','SQL','V4__openstreetmap_places.sql',755956596,'root','2026-10-19 12:42:21',522,1),(5,'5','prostitution gambling keywords','SQL','V5__prostitution_gambling_keywords.sql',2013935996,'root','2026-10-21 13:43:03',27,1),(6,'6','general prostitution gambling keywords','SQL','V6__general_prostitution_gambling_keywords.sql',-1394088429,'root','2026-10-21 14:26:09',5,1),(7,'9','gemini scan engine','SQL','V9__gemini_scan_engine.sql',-904493981,'root','2026-10-24 07:34:54',57,1),(8,'7','guide authoring','SQL','V7__guide_authoring.sql',327625020,'root','2026-10-29 06:53:41',81,1),(9,'8','guide section media','SQL','V8__guide_section_media.sql',-401214659,'root','2026-10-29 06:53:41',45,1),(10,'10','japan areas','SQL','V10__japan_areas.sql',-1527951628,'root','2026-10-29 06:53:41',327,1),(11,'11','guide drafts topics and cover','SQL','V11__guide_drafts_topics_and_cover.sql',-2131050116,'root','2026-10-29 06:53:41',80,1),(12,'12','guide soft delete','SQL','V12__guide_soft_delete.sql',672698293,'root','2026-10-29 06:53:42',87,1),(13,'13','guide preparation checklists','SQL','V13__guide_preparation_checklists.sql',-606720049,'root','2026-10-29 06:53:42',53,1),(14,'14','member chat','SQL','V14__member_chat.sql',-1532422642,'root','2026-10-27 16:54:15',280,1),(15,'15','chat encryption media notifications','SQL','V15__chat_encryption_media_notifications.sql',101725578,'root','2026-10-28 04:08:33',602,1),(16,'16','faq','SQL','V16__faq.sql',1057888229,'root','2026-11-04 17:51:46',128,1),(17,'17','phrase flash cards and favorites','SQL','V17__phrase_flash_cards_and_favorites.sql',763313549,'root','2026-11-05 08:38:45',44,1),(18,'18','drop practice sentences and favorites','SQL','V18__drop_practice_sentences_and_favorites.sql',1324284269,'root','2026-11-05 08:38:45',195,1),(19,'19','local faq and guide test data','SQL','V19__local_faq_and_guide_test_data.sql',1674823915,'root','2026-11-05 19:50:19',242,1),(20,'20','saved guides','SQL','V20__saved_guides.sql',-474884433,'root','2026-11-07 00:55:55',47,1),(21,'21','user interest topics','SQL','V21__user_interest_topics.sql',846927299,'root','2026-11-07 05:55:38',49,1);
/*!40000 ALTER TABLE `flyway_schema_history` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `guide_items`
--

DROP TABLE IF EXISTS `guide_items`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `guide_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stage_code` varchar(10) DEFAULT NULL,
  `title` varchar(255) NOT NULL,
  `due_note` varchar(80) DEFAULT NULL,
  `place_id` bigint DEFAULT NULL,
  `blog_id` bigint DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `summary` text,
  `published` tinyint(1) NOT NULL DEFAULT '1',
  `topic_code` varchar(30) DEFAULT NULL,
  `source_url` varchar(2000) DEFAULT NULL,
  `cover_media_id` bigint DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL,
  `preparation_enabled` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `fk_gitem_place` (`place_id`),
  KEY `fk_gitem_blog` (`blog_id`),
  KEY `fk_gitem_creator` (`created_by`),
  KEY `fk_gitem_updater` (`updated_by`),
  KEY `idx_guide_topic_created` (`stage_code`,`published`,`created_at`),
  KEY `fk_guide_cover` (`cover_media_id`),
  KEY `idx_guide_topic_status` (`topic_code`,`published`,`created_at`),
  KEY `idx_guide_deleted_published` (`deleted_at`,`published`,`created_at`),
  CONSTRAINT `fk_gitem_blog` FOREIGN KEY (`blog_id`) REFERENCES `blogs` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_gitem_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_gitem_place` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_gitem_stage` FOREIGN KEY (`stage_code`) REFERENCES `guide_stages` (`code`),
  CONSTRAINT `fk_gitem_updater` FOREIGN KEY (`updated_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_guide_cover` FOREIGN KEY (`cover_media_id`) REFERENCES `media` (`id`),
  CONSTRAINT `fk_guide_topic` FOREIGN KEY (`topic_code`) REFERENCES `guide_topics` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=26 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `guide_items`
--

LOCK TABLES `guide_items` WRITE;
/*!40000 ALTER TABLE `guide_items` DISABLE KEYS */;
INSERT INTO `guide_items` VALUES (1,'PRE','Hộ chiếu, visa và giấy chứng nhận tư cách lưu trú',NULL,NULL,NULL,1,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(2,'PRE','Tiền mặt bằng yên cho hai tuần đầu',NULL,NULL,NULL,2,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(3,'PRE','Thuốc cá nhân kèm đơn tiếng Anh',NULL,NULL,NULL,3,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(4,'PRE','Ổ cắm chuyển đổi loại A',NULL,NULL,NULL,4,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(5,'ARR','Đăng ký cư trú tại văn phòng quận','Trong 14 ngày',6,NULL,1,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(6,'ARR','Tham gia bảo hiểm y tế quốc dân',NULL,6,NULL,2,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(7,'ARR','Mua thẻ IC đi tàu',NULL,1,1,3,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(8,'ARR','Mở tài khoản ngân hàng',NULL,8,NULL,4,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(9,'ARR','Hỏi lịch đổ rác của tòa nhà',NULL,NULL,4,5,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(10,'ARR','Xin giấy phép làm thêm (資格外活動許可) nếu định đi làm','Trước khi đi làm',NULL,NULL,6,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(11,'LEAVE','Nộp báo chuyển đi (転出届) ở văn phòng quận','Trước ngày bay',6,5,1,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(12,'LEAVE','Đóng tài khoản ngân hàng, hủy hợp đồng điện thoại',NULL,8,NULL,2,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(13,'LEAVE','Thanh toán hóa đơn điện, nước, gas',NULL,NULL,NULL,3,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(14,'LEAVE','Xin giấy chứng nhận tốt nghiệp (卒業証明書) và bảng điểm',NULL,2,NULL,4,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(15,'LEAVE','Gửi đồ về Việt Nam',NULL,8,NULL,5,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(16,'HOME','Nộp lại thẻ cư trú ở sân bay khi xuất cảnh','Ngày bay',NULL,NULL,1,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(17,'HOME','Nộp hồ sơ hoàn tiền lương hưu (脱退一時金)','Trong 2 năm',NULL,5,2,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(18,'HOME','Hợp pháp hóa lãnh sự bằng cấp nếu cần đi làm',NULL,NULL,NULL,3,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(19,'HOME','Xin tag Mentor để giúp các bạn đi sau',NULL,NULL,NULL,4,2,NULL,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL,1,NULL,NULL,NULL,NULL,0),(21,'PRE','TIẾT KIỆM THỜI GIAN NHẬP CẢNH – KHAI BÁO TRỰC TUYẾN VISIT JAPAN WEB',NULL,NULL,NULL,0,9,9,'2026-11-05 19:50:18','2026-11-05 19:50:18','Visit Japan Web là cổng dịch vụ trực tuyến do Chính phủ Nhật Bản cung cấp, cho phép du khách và công dân Nhật Bản thực hiện các thủ tục nhập cảnh, nhập cư và khai báo hải quan trực tuyến trước khi đến Nhật Bản.\nKhai báo hải quan online giúp giảm thời gian chờ đợi và tăng hiệu quả làm thủ tục tại sân bay. Đại sứ quán Nhật Bản tại Việt Nam cũng khuyến khích sử dụng trang web khai báo trực tuyến và cung cấp ngôn ngữ tiếng Việt để hỗ trợ người Việt khai báo một cách dễ dàng nhanh chóng.\nĐể khai báo nhập cảnh trực tuyến thông qua Visit Japan Web, bạn sẽ thực hiện theo các bước chính sau:​',1,'PROCEDURES',NULL,67,NULL,1),(22,'ARR','Thẻ cư trú 在留カード là gì? Những điều cần biết khi mới đến Nhật',NULL,NULL,NULL,0,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19','',1,'PROCEDURES',NULL,72,NULL,1),(23,'ARR','Hướng dẫn thuê nhà tại Nhật: Tiền cọc, tiền lễ và các chi phí ban đầu',NULL,NULL,NULL,0,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19','',1,'HOUSING',NULL,76,NULL,0),(24,'ARR','Hướng dẫn sử dụng tàu điện Nhật Bản cho người mới',NULL,NULL,NULL,0,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19','',1,'TRANSPORT',NULL,77,NULL,1),(25,'ARR','Cách phân loại và bỏ rác tại Nhật Bản – hướng dẫn chi tiết cho người Việt',NULL,NULL,NULL,0,9,9,'2026-11-05 19:50:19','2026-11-05 19:50:19','Phân loại và bỏ rác đúng quy định là một trong những điều quan trọng bậc nhất mà người Việt mới sang Nhật cần nắm vững. Không giống ở Việt Nam, tại Nhật Bản mỗi loại rác có ngày thu gom riêng, cách xử lý riêng và thậm chí túi đựng rác riêng theo từng địa phương. Việc bỏ rác sai cách không chỉ khiến hàng xóm phàn nàn mà còn có thể dẫn đến bị phạt tiền theo pháp luật.',1,'LIVING',NULL,81,NULL,0);
/*!40000 ALTER TABLE `guide_items` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `guide_preparation_progress`
--

DROP TABLE IF EXISTS `guide_preparation_progress`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `guide_preparation_progress` (
  `user_id` bigint NOT NULL,
  `task_id` bigint NOT NULL,
  `completed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`,`task_id`),
  KEY `fk_preparation_progress_task` (`task_id`),
  CONSTRAINT `fk_preparation_progress_task` FOREIGN KEY (`task_id`) REFERENCES `guide_preparation_tasks` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_preparation_progress_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `guide_preparation_progress`
--

LOCK TABLES `guide_preparation_progress` WRITE;
/*!40000 ALTER TABLE `guide_preparation_progress` DISABLE KEYS */;
INSERT INTO `guide_preparation_progress` VALUES (1,6,'2026-11-07 00:57:12'),(1,7,'2026-11-07 00:57:40'),(1,8,'2026-11-07 00:57:51'),(1,9,'2026-11-07 00:57:53'),(1,10,'2026-11-07 00:57:54'),(1,11,'2026-11-07 01:00:41'),(1,12,'2026-11-06 18:00:42'),(1,13,'2026-11-07 01:00:13'),(1,14,'2026-11-07 01:00:14'),(10,3,'2026-11-07 01:13:11'),(10,4,'2026-11-07 01:13:11'),(10,5,'2026-11-07 01:13:11'),(12,3,'2026-11-07 06:03:38'),(12,4,'2026-11-07 06:02:22'),(12,5,'2026-11-07 06:02:28'),(12,12,'2026-11-07 06:01:51'),(13,3,'2026-11-07 06:11:23'),(13,4,'2026-11-07 06:11:26'),(13,5,'2026-11-07 06:11:27'),(13,12,'2026-11-07 10:39:50');
/*!40000 ALTER TABLE `guide_preparation_progress` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `guide_preparation_tasks`
--

DROP TABLE IF EXISTS `guide_preparation_tasks`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `guide_preparation_tasks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `guide_id` bigint NOT NULL,
  `title` varchar(255) NOT NULL,
  `notes` varchar(2000) DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_preparation_guide_order` (`guide_id`,`sort_order`),
  CONSTRAINT `fk_preparation_guide` FOREIGN KEY (`guide_id`) REFERENCES `guide_items` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `guide_preparation_tasks`
--

LOCK TABLES `guide_preparation_tasks` WRITE;
/*!40000 ALTER TABLE `guide_preparation_tasks` DISABLE KEYS */;
INSERT INTO `guide_preparation_tasks` VALUES (3,21,'Chuẩn bị hộ chiếu','Cần có ảnh và thông tin hộ chiếu để đăng ký nhập cảnh',0),(4,21,'Thông tin về địa chỉ, sdt và công việc sẽ làm khi sang Nhật','Hệ thống sẽ hỏi thông tin về địa chỉ và công việc mọi người sẽ làm khi nhập cảnh',1),(5,21,'Đăng ký và lưu lại mã QR đăng ký','Lưu lại ảnh mã QR khi nhập cảnh sẽ đưa cho nhân viên sân bay check sẽ nhập cảnh dễ dàng hơn',2),(6,22,'Hộ chiếu còn hiệu lực','',0),(7,22,'Visa hoặc giấy tờ xác nhận tư cách lưu trú phù hợp','',1),(8,22,'Thẻ cư trú nếu đã được cấp tại sân bay','',2),(9,22,'Địa chỉ nơi ở tại Nhật (kèm mã bưu điện)','',3),(10,22,'Số điện thoại liên hệ nếu đã có','',4),(11,22,'Thông tin chủ nhà, ký túc xá hoặc người hỗ trợ nếu cần','',5),(12,24,'Cài đặt ứng dụng tìm đường','Ứng dụng tìm đường tại nhật có thời gian và vị trí gần như luôn đúng',0),(13,24,'Tìm tuyến tàu đến trường hoặc nơi thực tập','Hãy tìm chuyến tàu phù hợp để tối ưu chi phí và thời gian',1),(14,24,'Nạp đủ tiền đi lại','Nên nạp đủ tiền trong thẻ ic để vừa đi tàu mac cũng có thể thanh toán mua đồ nếu cần',2);
/*!40000 ALTER TABLE `guide_preparation_tasks` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `guide_sections`
--

DROP TABLE IF EXISTS `guide_sections`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `guide_sections` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `guide_id` bigint NOT NULL,
  `sort_order` int NOT NULL,
  `heading` varchar(200) NOT NULL,
  `content` mediumtext NOT NULL,
  `image_url` varchar(2000) DEFAULT NULL,
  `reference_url` varchar(2000) DEFAULT NULL,
  `media_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_guide_section_order` (`guide_id`,`sort_order`),
  KEY `fk_guide_section_media` (`media_id`),
  CONSTRAINT `fk_guide_section` FOREIGN KEY (`guide_id`) REFERENCES `guide_items` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_guide_section_media` FOREIGN KEY (`media_id`) REFERENCES `media` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=28 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `guide_sections`
--

LOCK TABLES `guide_sections` WRITE;
/*!40000 ALTER TABLE `guide_sections` DISABLE KEYS */;
INSERT INTO `guide_sections` VALUES (2,21,0,'1. Đăng ký và đăng nhập tài khoản','Truy cập trang web chính thức của Visit Japan Web tại https://www.vjw.digital.go.jp.​\nTạo tài khoản bằng địa chỉ email cá nhân và thiết lập mật khẩu.​',NULL,NULL,68),(3,21,1,'2. Nhập thông tin cá nhân và hộ chiếu','Nhập thông tin hộ chiếu bằng cách quét bằng camera hoặc nhập thủ công các thông tin như số hộ chiếu, họ tên, quốc tịch, ngày sinh, ngày hết hạn hộ chiếu, nghề nghiệp và địa chỉ hiện tại.',NULL,NULL,69),(4,21,2,'3. Khai báo kế hoạch nhập cảnh','Đăng ký kế hoạch nhập cảnh mới, bao gồm thông tin chuyến bay như mã hiệu chuyến bay, ngày nhập cảnh, tên hãng hàng không và số hiệu chuyến bay.​\n\nNhập địa chỉ lưu trú tại Nhật Bản sau khi nhập cảnh, bao gồm mã bưu điện, tỉnh/thành phố, số nhà và số điện thoại liên hệ.',NULL,NULL,70),(5,21,3,'4. Khai báo tờ khai nhập cảnh và hải quan','Nhập mục đích nhập cảnh, thời gian lưu trú dự kiến và trả lời các câu hỏi liên quan đến tiền sử xuất nhập cảnh.​\n\nKhai báo thông tin về hành lý mang theo, trả lời các câu hỏi về hàng hóa đặc biệt nếu có.​',NULL,NULL,71),(6,22,0,'1. Giới thiệu','Khi đến Nhật Bản để du học, thực tập hoặc làm việc dài hạn, một trong những giấy tờ quan trọng nhất bạn cần biết là thẻ cư trú (在留カード – Zairyū Kādo).\n\nĐây là giấy tờ do Cơ quan Quản lý Xuất nhập cảnh Nhật Bản cấp cho người nước ngoài thuộc diện cư trú trung và dài hạn. Thẻ thể hiện thông tin cá nhân, tư cách lưu trú và thời hạn lưu trú của người sở hữu.\n\nThẻ cư trú thường được sử dụng khi mở tài khoản ngân hàng, đăng ký số điện thoại, thuê nhà và thực hiện các thủ tục hành chính.',NULL,NULL,NULL),(7,22,1,'2. Thẻ cư trú chứa những thông tin gì?','氏名 (Shimei):	Họ và tên\n生年月日 (Seinengappi): Ngày sinh\n国籍・地域 (Kokuseki / Chiiki):	Quốc tịch / khu vực\n住居地 (Jūkyochi): Địa chỉ cư trú\n在留資格 (Zairyū shikaku): Tư cách lưu trú\n在留期間 (Zairyū kikan): Thời hạn lưu trú\n在留期間満了日: Ngày hết hạn lưu trú\n就労制限の有無: Thông tin hạn chế làm việc\n\nLưu ý: Thời hạn của thẻ, thời hạn lưu trú và quyền được làm việc không phải lúc nào cũng giống nhau. Bạn cần kiểm tra đúng thông tin trên thẻ.',NULL,NULL,73),(8,22,2,'3. Nhận thẻ cư trú khi nhập cảnh','Tại một số sân bay lớn như Narita, Haneda, Kansai, Chubu, New Chitose, Hiroshima và Fukuoka, người đủ điều kiện có thể được cấp thẻ cư trú ngay khi làm thủ tục nhập cảnh.\n\nỞ những cửa khẩu khác, thẻ có thể được gửi sau khi bạn đăng ký địa chỉ cư trú tại Nhật.\n\nQuy trình thông thường:\n1. Xuất trình hộ chiếu và giấy tờ nhập cảnh theo yêu cầu.\n2. Hoàn thành kiểm tra nhập cảnh.\n3. Nhận thẻ cư trú nếu cửa khẩu hỗ trợ cấp trực tiếp.\n4. Kiểm tra họ tên, ngày sinh, tư cách và thời hạn lưu trú.\n5. Giữ thẻ cẩn thận để sử dụng trong các thủ tục tiếp theo.',NULL,NULL,74),(9,22,3,'4. Đăng ký địa chỉ cư trú','Sau khi xác lập nơi cư trú tại Nhật, người thuộc diện phải khai báo địa chỉ cần thực hiện thủ tục tại 市役所 (Shiyakusho – Tòa thị chính) hoặc 区役所 (Kuyakusho – Văn phòng quận) trong vòng 14 ngày.\n\nBạn cần:\n1. Đến cơ quan hành chính quản lý khu vực đang sinh sống.\n2. Xuất trình thẻ cư trú hoặc hộ chiếu phù hợp.\n3. Điền mẫu thông báo chuyển đến.\n4. Nộp giấy tờ và chờ xác nhận.\n5. Kiểm tra thông tin địa chỉ được cập nhật.\nKhi chuyển sang địa phương khác, bạn thường cần làm thủ tục thông báo chuyển đi và chuyển đến. Khi đổi địa chỉ trong cùng địa phương, thủ tục có thể khác.',NULL,NULL,75),(10,22,4,'5. Những lưu ý quan trọng','- Người từ 16 tuổi trở lên thuộc diện được cấp thẻ cư trú phải mang theo thẻ theo quy định.\n- Không cho người khác mượn thẻ để sử dụng.\n- Nếu mất thẻ, cần liên hệ cơ quan có thẩm quyền và thực hiện thủ tục cấp lại đúng hạn.\n- Theo dõi ngày hết hạn lưu trú và chuẩn bị gia hạn trước khi hết hạn.\n- Nếu thay đổi các thông tin phải khai báo, cần thực hiện đúng thủ tục và thời hạn áp dụng.',NULL,NULL,NULL),(11,23,0,'1. Giới thiệu','Tìm được nơi ở phù hợp là một trong những việc quan trọng nhất khi bắt đầu cuộc sống tại Nhật Bản. Khác với Việt Nam, việc thuê nhà tại Nhật có thể yêu cầu nhiều khoản phí ban đầu ngoài tiền thuê hằng tháng.\n\nĐối với sinh viên và thực tập sinh mới sang Nhật, hiểu rõ các khoản phí và điều khoản hợp đồng sẽ giúp tránh những chi phí phát sinh không mong muốn.',NULL,NULL,NULL),(12,23,1,'2. Các loại nhà ở phổ biến','**Ký túc xá (寮 – Ryō)**\n\nThường do trường học hoặc đơn vị tiếp nhận bố trí. Có thể được trang bị sẵn nội thất, thuận tiện cho người mới sang Nhật.\n\n**Căn hộ riêng (アパート / マンション)**\n\nPhù hợp với người muốn sinh hoạt độc lập. Thường cần ký hợp đồng và tự thanh toán tiền thuê, điện, nước, gas.\n\n**Nhà ở chung (シェアハウス)**\n\nNgười thuê có phòng riêng hoặc phòng chia sẻ và sử dụng chung một số tiện ích. Chi phí ban đầu có thể thấp hơn căn hộ riêng.',NULL,NULL,NULL),(13,23,2,'3. Những khoản phí cần biết','家賃 (Yachin): Tiền thuê nhà hằng tháng\n敷金 (Shikikin): Tiền đặt cọc, có thể được hoàn lại một phần sau khi trừ chi phí hợp lệ\n礼金 (Reikin): Tiền lễ trả cho chủ nhà, thường không hoàn lại\n共益費 (Kyōekihi): Phí quản lý, khu vực chung\n仲介手数料 (Chūkai tesūryō): Phí môi giới\n保証料 (Hoshōryō): Phí bảo lãnh thuê nhà\n火災保険 (Kasai hoken):  Bảo hiểm cháy nổ',NULL,NULL,NULL),(14,23,3,'4. Ví dụ chi phí thuê nhà ban đầu','Giả sử bạn thuê căn hộ với giá 50.000 yên/tháng. Đây là một ví dụ minh họa, không phải báo giá thị trường.\n\nTiền thuê tháng đầu:	¥50.000\nTiền cọc (1 tháng): 	¥50.000\nTiền lễ (1 tháng):	¥50.000\nPhí môi giới:	¥55.000\nBảo hiểm:	¥18.000\nPhí bảo lãnh:	¥25.000\nTổng dự kiến:	¥248.000\n\nCác khoản khác như tiền thuê tính theo ngày, phí thay khóa, vệ sinh, mua đồ nội thất hoặc phí quản lý có thể làm tổng chi phí cao hơn.',NULL,NULL,NULL),(15,23,4,'5. Quy trình thuê nhà','-Xác định ngân sách thuê nhà và khu vực muốn sinh sống.\n-Tìm nhà thông qua công ty bất động sản, trường học hoặc đơn vị tiếp nhận.\n-Xem hình ảnh, sơ đồ mặt bằng và đến xem nhà nếu có thể.\n-Kiểm tra khoảng cách đến ga tàu, siêu thị, nơi học hoặc làm việc.\n-Xác nhận toàn bộ chi phí ban đầu và phí hằng tháng.\n\n\n\nNộp hồ sơ đăng ký thuê và chờ xét duyệt.\n\n\n\nĐọc kỹ hợp đồng, đặc biệt là điều khoản chấm dứt hợp đồng và phí khi chuyển đi.\n\n\n\nKý hợp đồng, thanh toán và nhận chìa khóa.',NULL,NULL,NULL),(16,23,5,'6. Những điều cần kiểm tra trước khi ký','-Nhà có điều hòa, máy giặt, bếp và internet hay không?\n-Tiền điện, nước, gas đã bao gồm trong tiền thuê chưa?\n-Có yêu cầu người bảo lãnh hoặc công ty bảo lãnh không?\n-Có được ở cùng người khác hoặc nuôi thú cưng không?\n-Phải thông báo trước bao lâu khi trả nhà?\n-Có phí vệ sinh, phí khôi phục hiện trạng hoặc phạt chấm dứt sớm không?',NULL,NULL,NULL),(17,24,0,'1. Giới thiệu','Tàu điện là một trong những phương tiện giao thông phổ biến nhất tại Nhật Bản, đặc biệt ở các thành phố lớn như Tokyo, Osaka, Nagoya và Yokohama.\n\nTuy nhiên, với người mới đến Nhật, hệ thống tàu điện có thể khá phức tạp do nhiều tuyến tàu, loại tàu và công ty vận hành khác nhau.\n\nBài viết này sẽ hướng dẫn bạn cách tìm tuyến, mua vé, sử dụng thẻ IC và xử lý một số tình huống thường gặp.',NULL,NULL,NULL),(18,24,1,'2. Các loại tàu điện phổ biến','Loại tàu	                      Tiếng Nhật	                 Đặc điểm\nLocal	                              普通 (Futsū)	                 Dừng tại tất cả hoặc hầu hết các ga\nRapid	                              快速 (Kaisoku)	         Bỏ qua một số ga\nExpress	                      急行 (Kyūkō)	         Dừng ít ga hơn tàu thường\nLimited Express	      特急 (Tokkyū)	         Thường nhanh hơn, có tuyến cần vé phụ\nShinkansen	              新幹線	                         Tàu cao tốc liên tỉnh\nTên gọi và cách dừng ga có thể khác nhau giữa các công ty đường sắt. Bạn nên kiểm tra tàu có dừng tại ga cần đến hay không.',NULL,NULL,NULL),(19,24,2,'3. Cách tìm tuyến tàu','Bạn có thể sử dụng Google Maps hoặc các ứng dụng tìm đường tại Nhật.\nCác bước thực hiện:\n1. Mở ứng dụng tìm đường.\n2. Nhập ga xuất phát và địa điểm muốn đến.\n3. Chọn phương tiện giao thông công cộng.\n4. Xem các tuyến được đề xuất, thời gian và chi phí.\n5. Kiểm tra tên tuyến, hướng tàu, số sân ga và điểm chuyển tuyến.\n6. Đến sân ga phù hợp và xác nhận thông tin trên bảng điện tử.\nVí dụ: Đi từ Shinjuku đến Shibuya.\nNếu gặp khó khăn trong việc xem map bạn có thể hỏi nhân viên bất kỳ ở nhà ga, bạn sẽ được hướng dẫn chi tiết các đi thậm chí được dẫn tới tận nơi.',NULL,NULL,78),(20,24,3,'4. Sử dụng thẻ Suica và PASMO','Suica và PASMO là những loại thẻ IC được sử dụng để thanh toán vé tàu, xe buýt và một số giao dịch mua sắm.\n\nThay vì mua vé giấy mỗi lần đi tàu, bạn có thể nạp tiền vào thẻ rồi chạm tại cổng soát vé.\n\nCách sử dụng:\n1. Chuẩn bị thẻ IC hoặc phiên bản trên điện thoại tương thích.\n2. Kiểm tra số dư trước khi vào ga.\n3. Chạm thẻ vào đầu đọc tại cổng vào.\n4. Di chuyển đến đúng sân ga.\n5. Lên tàu theo hướng đã chọn.\n6. Khi đến ga đích, chạm thẻ tại cổng ra.\nLưu ý: Một số tuyến hoặc hành trình liên vùng không hỗ trợ thanh toán xuyên suốt bằng một lần chạm thẻ IC. Tàu đặc biệt có thể yêu cầu mua thêm vé.',NULL,NULL,79),(21,24,4,'5. Cách chuyển tuyến tàu','Khi hành trình yêu cầu chuyển tuyến (乗り換え – Norikae), bạn cần xác định ga chuyển và tuyến tiếp theo.\n1. Xuống tàu tại ga chuyển tuyến.\n2. Tìm biển chỉ dẫn có tên tuyến muốn chuyển.\n3. Đi theo mũi tên hướng dẫn.\n4. Kiểm tra có cần đi qua cổng soát vé hay không.\n5. Xác nhận lại hướng tàu và sân ga.\n6. Lên chuyến tàu tiếp theo.\nMột số ga lớn có nhiều tầng và lối chuyển tuyến khá xa. Bạn nên dự phòng thời gian, nhất là khi mang hành lý.',NULL,NULL,80),(22,25,0,'1. Tại sao phân loại rác lại quan trọng ở Nhật Bản?','Luật môi trường nghiêm ngặt\nNhật Bản có Luật Xử lý Chất thải và Vệ sinh Công cộng (廃棄物の処理及び清掃に関する法律) quy định rõ trách nhiệm của từng cá nhân trong việc xử lý rác thải. Theo luật này, việc bỏ rác trái phép (không đúng nơi, không đúng ngày, không phân loại) có thể bị xử phạt hành chính lên đến 30 vạn yên (約30万円) hoặc thậm chí bị truy cứu hình sự đối với hành vi đổ rác bất hợp pháp quy mô lớn.',NULL,'https://www.env.go.jp/recycle/waste/',NULL),(23,25,1,'2. Các loại rác chính tại Nhật Bản','**? Rác đốt được – 燃えるゴミ (moeru gomi)**\nĐây là loại rác phổ biến nhất, thường được thu gom 2–3 lần/tuần. Bao gồm:\n\nThức ăn thừa, vỏ rau củ quả, bã cà phê, túi lọc trà\nGiấy không thể tái chế: giấy ăn, giấy bẩn, hộp đựng thức ăn bằng giấy đã dùng\nVải vóc, quần áo cũ (không còn dùng được)\nGỗ nhỏ, cành cây, lá cây\nCao su, da thuộc\nBỉm, băng vệ sinh đã qua sử dụng\n\n**? Rác không đốt được – 燃えないゴミ (moenai gomi)**\nLoại rác này thường được thu gom 1–2 lần/tháng. Bao gồm:\n\nĐồ nhựa cứng: đồ chơi nhựa, chậu nhựa, hộp nhựa dày\nKim loại nhỏ: muỗng nĩa, nắp hộp, dây điện ngắn\nThủy tinh vỡ, gốm sứ vỡ (cần bọc giấy báo và ghi \"危険 – Nguy hiểm\")\nGương, bóng đèn thông thường (không phải bóng huỳnh quang)\nDụng cụ nhà bếp nhỏ bằng kim loại\n\n**?️ Rác cồng kềnh – 粗大ゴミ (sodai gomi)**\nRác cồng kềnh là những đồ vật có kích thước lớn, không bỏ vào túi rác thông thường được. Đây là loại rác đặc biệt cần đăng ký trước:\n\nĐồ nội thất: bàn, ghế, tủ, giường, nệm\nXe đạp, xe đẩy em bé\nMáy giặt cũ (loại không thuộc luật tái chế điện tử)\nThảm, màn cửa lớn\nQuy trình xử lý rác cồng kềnh gồm 3 bước:\n\nĐăng ký: Gọi điện hoặc đăng ký online trên website phường/thành phố. Sẽ được thông báo ngày thu gom và số tiền phí.\nMua tem (粗大ごみ処理券): Mua tại cửa hàng tiện lợi (Konbini) như 7-Eleven, FamilyMart theo mệnh giá được thông báo (thường 400–2.000 yên/món).\nĐặt ra điểm thu gom: Dán tem lên đồ vật, mang ra điểm quy định trước ngày và giờ đã hẹn.\n\n**? Rác điện tử – theo luật riêng (家電リサイクル法)**\nTheo Luật Tái chế Đồ gia dụng điện tử (家電リサイクル法), 4 loại thiết bị sau KHÔNG được bỏ vào rác thông thường hoặc rác cồng kềnh:\nTivi (テレビ)\nTủ lạnh/Tủ đông (冷蔵庫・冷凍庫)\nMáy giặt/Máy sấy (洗濯機・衣類乾燥機)\nMáy lạnh/Điều hòa (エアコン)\nĐể xử lý các thiết bị này, bạn cần trả phí tái chế và liên hệ nhà sản xuất hoặc cửa hàng điện máy. Chi tiết tham khảo tại trang Bộ Môi trường về tái chế đồ gia dụng.\n\n**♻️ Rác tái chế – 資源ゴミ (shigen gomi)**\nRác tái chế thường được phân thành nhiều loại nhỏ hơn và có ngày thu gom riêng:',NULL,NULL,82),(24,25,2,'3. Lịch đổ rác – tra cứu như thế nào?','Lịch thu gom rác khác nhau hoàn toàn giữa các phường/thành phố, thậm chí giữa các khu vực trong cùng một thành phố. Đây là điều nhiều người Việt mới sang hay nhầm lẫn nhất.\nCách tra cứu lịch đổ rác chính xác:\n\nTìm website phường/thành phố (ví dụ: Osaka City, Nagoya City)\nDùng ứng dụng smartphone 「さんあ～る」 – hỗ trợ nhiều ngôn ngữ, nhập địa chỉ sẽ hiện lịch đổ rác\nHỏi chủ nhà, quản lý tòa nhà (管理人) hoặc hội đồng khu dân cư (自治会)\nXem lịch dán tại bảng thông báo điểm thu gom rác gần nhà',NULL,NULL,NULL),(25,25,3,'4. Túi rác – không phải túi nào cũng được dùng','Đây là điều mà nhiều người nước ngoài mới sang Nhật hay không biết. Tại nhiều thành phố (đặc biệt là Kyoto, Nagoya, Fukuoka…), chính quyền địa phương quy định bắt buộc phải dùng túi rác có nhãn chính thức (指定ごみ袋 – Shitei gomi bukuro). Loại túi này:\n\nBán tại siêu thị, cửa hàng tiện lợi (Konbini), cửa hàng 100 yên\nCó in tên thành phố và màu sắc riêng theo loại rác\nGiá từ 20–60 yên/túi tùy kích cỡ và địa phương\nNếu không dùng đúng túi, rác sẽ không được thu gom\nMột số nơi như Tokyo không bắt buộc dùng túi chính thức (có thể dùng túi siêu thị trong suốt), nhưng bạn vẫn nên hỏi chủ nhà để chắc chắn.',NULL,NULL,NULL),(26,25,4,'5. Cách đổ rác đúng quy định','Dù đã phân loại đúng, việc đổ rác cũng cần tuân thủ các quy tắc sau:\n\n? Đổ rác đúng giờ\n\nRác phải được đưa ra điểm thu gom trước 8 giờ sáng vào ngày thu gom. Không được đổ rác từ tối hôm trước vì sẽ gây mùi và thu hút quạ (カラス – Karasu) phá rác.\n\n? Đổ đúng điểm thu gom\n\nChỉ bỏ rác tại điểm thu gom (ゴミ集積所) do khu dân cư quy định. Không được bỏ rác của nhà mình vào thùng rác nhà hàng xóm hay thùng rác công cộng.\n\n? Dùng lưới chắn quạ\n\nNhiều điểm thu gom có lưới màu xanh (カラスよけネット) để phủ lên rác chống quạ. Sau khi bỏ rác xong, hãy phủ lưới lại đúng cách.\n\n? Giữ vệ sinh điểm thu gom\n\nNhiều khu dân cư có lịch dọn dẹp điểm thu gom rác theo ca luân phiên (当番 – Toban). Nếu được yêu cầu tham gia, hãy thực hiện đầy đủ.',NULL,NULL,NULL),(27,25,5,'6. Lưu ý cho người Việt mới sang Nhật','Những bước đầu tiên bạn nên làm ngay sau khi đến nơi ở mới:\n\nHỏi chủ nhà hoặc quản lý tòa nhà về lịch đổ rác, loại túi được dùng và vị trí điểm thu gom gần nhất.\nTìm brochure hướng dẫn phân loại rác – Nhiều phường/thành phố phát brochure đa ngôn ngữ (trong đó có tiếng Việt) khi bạn đến đăng ký cư trú (住民登録). Hãy xin tại phòng đăng ký của văn phòng phường (市区町村役場).\nTải app「さんあ～る」 – Ứng dụng miễn phí của Bộ Môi trường Nhật, hỗ trợ tiếng Anh và một số ngôn ngữ khác, cung cấp lịch đổ rác theo địa chỉ cụ thể.\nQuan sát hàng xóm – Khi mới đến, hãy chú ý người xung quanh đổ rác lúc nào, dùng loại túi nào, để ý vị trí điểm thu gom.\nKhông ngại hỏi – Người Nhật nhìn chung sẵn lòng chỉ bảo về quy định rác nếu bạn hỏi lịch sự. Hãy nói: 「ゴミの出し方を教えていただけますか？」 (Gomi no dashikata wo oshiete itadakemasu ka?) – Bạn có thể chỉ cho tôi cách bỏ rác không?',NULL,NULL,NULL);
/*!40000 ALTER TABLE `guide_sections` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `guide_stages`
--

DROP TABLE IF EXISTS `guide_stages`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `guide_stages` (
  `code` varchar(10) NOT NULL,
  `title` varchar(120) NOT NULL,
  `icon` varchar(50) NOT NULL,
  `sort_order` int NOT NULL,
  PRIMARY KEY (`code`),
  CONSTRAINT `ck_stage_code` CHECK ((`code` in (_utf8mb4'PRE',_utf8mb4'ARR',_utf8mb4'LEAVE',_utf8mb4'HOME')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `guide_stages`
--

LOCK TABLES `guide_stages` WRITE;
/*!40000 ALTER TABLE `guide_stages` DISABLE KEYS */;
INSERT INTO `guide_stages` VALUES ('ARR','Khi mới đến (tuần đầu đến 3 tháng)','flight_land',2),('HOME','Sau khi về nước','home',4),('LEAVE','Trước khi về nước','flight_takeoff',3),('PRE','Trước khi đi','luggage',1);
/*!40000 ALTER TABLE `guide_stages` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `guide_topics`
--

DROP TABLE IF EXISTS `guide_topics`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `guide_topics` (
  `code` varchar(30) NOT NULL,
  `title` varchar(100) NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `guide_topics`
--

LOCK TABLES `guide_topics` WRITE;
/*!40000 ALTER TABLE `guide_topics` DISABLE KEYS */;
INSERT INTO `guide_topics` VALUES ('HEALTH','Y tế',6),('HOUSING','Nhà ở',4),('LIVING','Sinh hoạt',1),('OTHER','Khác',99),('PROCEDURES','Thủ tục',2),('STUDY','Học tập',3),('TRANSPORT','Đi lại',7),('WORK','Việc làm',5);
/*!40000 ALTER TABLE `guide_topics` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `learning_topics`
--

DROP TABLE IF EXISTS `learning_topics`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `learning_topics` (
  `id` varchar(20) NOT NULL,
  `name` varchar(60) NOT NULL,
  `icon` varchar(50) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `learning_topics`
--

LOCK TABLES `learning_topics` WRITE;
/*!40000 ALTER TABLE `learning_topics` DISABLE KEYS */;
INSERT INTO `learning_topics` VALUES ('ask','Hỏi đường, hỏi đồ','help'),('health','Khám bệnh, mua thuốc','medical_services'),('order','Gọi món','restaurant_menu'),('paper','Giấy tờ, thủ tục','description'),('pay','Thanh toán','payments'),('train','Đi tàu','train');
/*!40000 ALTER TABLE `learning_topics` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `legal_documents`
--

DROP TABLE IF EXISTS `legal_documents`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `legal_documents` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_type` varchar(20) NOT NULL,
  `version` varchar(20) NOT NULL,
  `content` mediumtext NOT NULL,
  `effective_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_doc_version` (`doc_type`,`version`),
  CONSTRAINT `ck_doc_type` CHECK ((`doc_type` in (_utf8mb4'TOS',_utf8mb4'PRIVACY',_utf8mb4'COMMUNITY')))
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `legal_documents`
--

LOCK TABLES `legal_documents` WRITE;
/*!40000 ALTER TABLE `legal_documents` DISABLE KEYS */;
INSERT INTO `legal_documents` VALUES (1,'TOS','1.0','Điều khoản sử dụng Senpai ơi (bản nháp 1.0). ASVSJ là nền tảng phi lợi nhuận do nhóm SEP490-G130 vận hành trong khuôn khổ đồ án tốt nghiệp. Người dùng phải từ đủ 18 tuổi, cung cấp thông tin chính xác và chịu trách nhiệm về hoạt động dưới tài khoản của mình. Tài khoản mới có thể bị giới hạn số bài đăng mỗi ngày để chống spam. Người dùng có thể xóa tài khoản bất kỳ lúc nào.','2026-10-14 00:00:00'),(2,'PRIVACY','1.0','Chính sách bảo mật Senpai ơi (bản nháp 1.0). Chúng tôi lưu email, tên hiển thị và nội dung bạn đăng. Mật khẩu được mã hóa một chiều, không ai đọc được. Khu vực sinh sống chỉ lưu ở mức phường – ga, không lưu địa chỉ nhà. Ảnh minh chứng xin tag bị xóa sau khi duyệt hoặc sau 14 ngày.','2026-10-14 00:00:00'),(3,'COMMUNITY','1.0','Quy tắc cộng đồng Senpai ơi (bản nháp 1.0). Chia sẻ trung thực, tôn trọng người khác, không lừa đảo, không spam, không quảng cáo trá hình. Nội dung vi phạm có thể bị gỡ; bạn có quyền khiếu nại trong 30 ngày.','2026-10-14 00:00:00');
/*!40000 ALTER TABLE `legal_documents` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `media`
--

DROP TABLE IF EXISTS `media`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `media` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_id` bigint NOT NULL,
  `media_type` varchar(10) NOT NULL,
  `storage_key` varchar(500) NOT NULL,
  `mime_type` varchar(50) NOT NULL,
  `size_bytes` bigint NOT NULL,
  `duration_sec` smallint DEFAULT NULL,
  `width` int DEFAULT NULL,
  `height` int DEFAULT NULL,
  `scan_status` varchar(20) NOT NULL DEFAULT 'PROCESSING',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `storage_key` (`storage_key`),
  KEY `fk_media_owner` (`owner_id`),
  CONSTRAINT `fk_media_owner` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`),
  CONSTRAINT `ck_media_duration` CHECK ((`duration_sec` <= 30)),
  CONSTRAINT `ck_media_scan` CHECK ((`scan_status` in (_utf8mb4'PROCESSING',_utf8mb4'PASSED',_utf8mb4'REJECTED'))),
  CONSTRAINT `ck_media_type` CHECK ((`media_type` in (_utf8mb4'IMAGE',_utf8mb4'VIDEO')))
) ENGINE=InnoDB AUTO_INCREMENT=84 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `media`
--

LOCK TABLES `media` WRITE;
/*!40000 ALTER TABLE `media` DISABLE KEYS */;
INSERT INTO `media` VALUES (1,1,'IMAGE','blog/2026/10/a3fa673b-c467-414a-b324-bd2aa37535ea.jpg','image/jpeg',76877,NULL,800,503,'PASSED','2026-10-18 09:42:32',NULL),(2,5,'IMAGE','blog/2026/10/c4a510b2-16f3-4a2e-a4a5-5731bcd3bd84.jpg','image/jpeg',1669,NULL,320,200,'PASSED','2026-10-19 13:43:37','2026-10-19 13:44:13'),(3,5,'IMAGE','blog/2026/10/a01e2f37-10ce-4cb0-b25b-e92bc2152462.jpg','image/jpeg',1669,NULL,320,200,'PASSED','2026-10-19 13:43:37','2026-10-19 13:45:29'),(4,5,'IMAGE','blog/2026/10/46af127e-9427-4f48-be9f-73bb1226d87c.jpg','image/jpeg',1617,NULL,300,200,'PASSED','2026-10-19 13:44:37','2026-10-19 13:45:29'),(5,6,'IMAGE','review/2026/10/f3b7f66a-8ba8-45f2-8e82-399424a6ce5d.jpg','image/jpeg',15070,NULL,640,420,'PASSED','2026-10-22 14:36:56','2026-10-22 14:37:55'),(6,1,'IMAGE','blog/2026/10/9b5791a4-e380-4328-819e-2a528dedf3b4.jpg','image/jpeg',76877,NULL,800,503,'PASSED','2026-10-22 15:00:12',NULL),(7,1,'IMAGE','blog/2026/10/6c578ecc-0271-4470-8d5b-6ed4155d2b26.jpg','image/jpeg',76877,NULL,800,503,'PASSED','2026-10-24 01:24:48',NULL),(8,1,'IMAGE','blog/2026/10/34695f68-d391-4e4f-bf32-f8d759f7e6e9.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:26:29','2026-10-24 08:26:29'),(9,1,'VIDEO','blog/2026/10/f5df8168-370d-4bf2-b82f-ea59f6f7e00e.mp4','video/mp4',529380,6,NULL,NULL,'REJECTED','2026-10-24 08:26:29','2026-10-24 08:26:29'),(10,1,'IMAGE','review/2026/10/c12ce0bf-d183-4430-a948-02df83cdf94d.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:26:30','2026-10-24 08:26:30'),(11,1,'IMAGE','review/2026/10/e9877eac-a8f6-420f-aa36-d11db29c8f24.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:26:30','2026-10-24 08:26:30'),(12,1,'IMAGE','blog/2026/10/6076e61c-7ffa-4730-8336-09505f9d33d0.jpg','image/jpeg',27781,NULL,1200,800,'PASSED','2026-10-24 08:26:47','2026-10-24 08:26:50'),(13,1,'VIDEO','blog/2026/10/76e2fd9f-7172-462a-9f43-04cd9531bb2b.mp4','video/mp4',529380,6,NULL,NULL,'PASSED','2026-10-24 08:26:47','2026-10-24 08:26:51'),(14,1,'IMAGE','review/2026/10/91564476-def7-46e1-88d2-fa93b224bd0d.jpg','image/jpeg',27781,NULL,1200,800,'PASSED','2026-10-24 08:26:47','2026-10-24 08:26:51'),(15,1,'IMAGE','review/2026/10/e44be58e-55de-4fe4-a688-5d78f911622b.jpg','image/jpeg',27781,NULL,1200,800,'PASSED','2026-10-24 08:26:48','2026-10-24 08:26:51'),(16,1,'IMAGE','blog/2026/10/5ca70aea-edff-4fc0-843f-99baae45b725.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:47:31','2026-10-24 08:47:31'),(17,1,'VIDEO','blog/2026/10/0ac1a845-d702-4f0d-ad46-69af60c822cf.mp4','video/mp4',529380,6,NULL,NULL,'REJECTED','2026-10-24 08:47:31','2026-10-24 08:47:31'),(18,1,'IMAGE','review/2026/10/d27645d3-6a13-4495-b5d8-2c6bcee84141.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:47:31','2026-10-24 08:47:31'),(19,1,'IMAGE','review/2026/10/02a6c066-4daf-4b1a-94bd-28909581fd80.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:47:31','2026-10-24 08:47:31'),(20,1,'IMAGE','blog/2026/10/03c59a8e-03ae-424b-95be-6b0fecaae1cf.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:47:51','2026-10-24 08:47:51'),(21,1,'VIDEO','blog/2026/10/46fb53dd-646d-4590-aea2-b42b16e238f0.mp4','video/mp4',529380,6,NULL,NULL,'REJECTED','2026-10-24 08:47:51','2026-10-24 08:47:51'),(22,1,'IMAGE','review/2026/10/db9fce4c-73fb-4da0-ab06-1eb0b0189dd4.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:47:51','2026-10-24 08:47:51'),(23,1,'IMAGE','review/2026/10/c5a963e3-4840-4fb0-b33d-1db27b5f27d2.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:47:51','2026-10-24 08:47:51'),(24,1,'IMAGE','blog/2026/10/39d583c7-6e21-40cc-a2d6-8fa0271b506c.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:48:44','2026-10-24 08:48:44'),(25,1,'VIDEO','blog/2026/10/10cb84f0-5f52-4385-a560-d1511e2df840.mp4','video/mp4',529380,6,NULL,NULL,'REJECTED','2026-10-24 08:48:44','2026-10-24 08:48:44'),(26,1,'IMAGE','review/2026/10/a7e596b2-b4ae-434c-9028-19225721316a.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:48:45','2026-10-24 08:48:45'),(27,1,'IMAGE','review/2026/10/10d6918b-f3c9-4a91-b3db-77d321976722.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:48:45','2026-10-24 08:48:45'),(28,1,'IMAGE','blog/2026/10/2572f001-e080-4d17-860a-e4ab4d872578.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:48:47','2026-10-24 08:48:47'),(29,1,'VIDEO','blog/2026/10/63c7b0ab-db2c-430d-8428-ced5faae98c9.mp4','video/mp4',529380,6,NULL,NULL,'REJECTED','2026-10-24 08:48:47','2026-10-24 08:48:47'),(30,1,'IMAGE','review/2026/10/b8fa273c-1b7e-4639-9134-425536cede15.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:48:47','2026-10-24 08:48:47'),(31,1,'IMAGE','review/2026/10/70fc3934-c185-4e20-9468-6d2aac7c5e40.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:48:47','2026-10-24 08:48:47'),(32,1,'IMAGE','blog/2026/10/e2cb2f66-43ee-4fa7-8838-6cea90542e84.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:48:49','2026-10-24 08:48:49'),(33,1,'VIDEO','blog/2026/10/a00e20d6-2aa1-45b1-bafc-4b568c146c9a.mp4','video/mp4',529380,6,NULL,NULL,'REJECTED','2026-10-24 08:48:49','2026-10-24 08:48:49'),(34,1,'IMAGE','review/2026/10/8ee01096-1d71-4551-b9ff-0c197a2c5217.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:48:50','2026-10-24 08:48:50'),(35,1,'IMAGE','review/2026/10/e2607605-a0a9-4238-b879-ef91607d1380.jpg','image/jpeg',27781,NULL,1200,800,'REJECTED','2026-10-24 08:48:50','2026-10-24 08:48:50'),(36,1,'IMAGE','blog/2026/10/74a96f86-99dc-4688-9ecc-1d72be5926f1.jpg','image/jpeg',183198,NULL,800,1200,'REJECTED','2026-10-24 09:01:46','2026-10-24 09:01:54'),(37,1,'IMAGE','blog/2026/10/5d38516a-62d2-4047-9dc7-93e18b7d1aa1.jpg','image/jpeg',229157,NULL,1200,630,'REJECTED','2026-10-24 09:22:00','2026-10-24 09:22:05'),(38,1,'IMAGE','blog/2026/10/2137a87a-aada-4ee9-a844-eba8e0a5d1e3.jpg','image/jpeg',76877,NULL,800,503,'PASSED','2026-10-24 09:25:46','2026-10-31 06:46:45'),(39,5,'IMAGE','blog/2026/10/5c5a0574-d41c-4bc9-b9d2-7e0f195a3959.jpg','image/jpeg',44269,NULL,400,403,'PASSED','2026-10-24 09:28:17',NULL),(40,5,'IMAGE','blog/2026/10/aaccb8de-acf4-4eff-9b1c-7fc599478c30.jpg','image/jpeg',183198,NULL,800,1200,'REJECTED','2026-10-24 09:28:57','2026-10-24 09:28:59'),(41,5,'IMAGE','blog/2026/10/dd315c36-fc36-4780-9096-84ca81decee1.jpg','image/jpeg',44269,NULL,400,403,'PASSED','2026-10-24 09:29:35','2026-10-24 09:33:58'),(42,5,'IMAGE','blog/2026/10/458b4185-34bf-41ed-8711-acc9ce5b3c7d.jpg','image/jpeg',13503,NULL,782,321,'REJECTED','2026-10-24 09:33:58','2026-10-24 09:34:00'),(43,5,'IMAGE','blog/2026/10/128da44b-3373-47cf-9821-2df36a3949bb.jpg','image/jpeg',44212,NULL,400,403,'PASSED','2026-10-24 09:51:26','2026-10-24 09:51:46'),(44,1,'IMAGE','blog/2026/10/ce515f2f-98dd-4f01-ad6b-8591af528fce.jpg','image/jpeg',27781,NULL,1200,800,'PASSED','2026-10-24 09:51:26','2026-10-24 09:51:46'),(45,1,'VIDEO','blog/2026/10/8d5722f7-ba37-4bb6-b369-b00583c8bd8a.mp4','video/mp4',523290,6,NULL,NULL,'REJECTED','2026-10-24 09:51:27','2026-10-24 09:51:30'),(46,1,'IMAGE','blog/2026/10/a304ff4c-f56d-442f-9843-c5c18c39eb80.jpg','image/jpeg',5900,NULL,240,240,'REJECTED','2026-10-24 09:54:32','2026-10-24 09:54:35'),(47,5,'IMAGE','blog/2026/10/839a4deb-fda3-43be-a506-7c834d860346.jpg','image/jpeg',44212,NULL,400,403,'PASSED','2026-10-24 10:10:07','2026-10-24 10:10:13'),(48,1,'IMAGE','blog/2026/10/c00b2bfd-585d-4cfc-8a44-f3716923de9b.jpg','image/jpeg',27781,NULL,1200,800,'PASSED','2026-10-24 10:10:07','2026-10-24 10:10:13'),(49,1,'VIDEO','blog/2026/10/9ff1806d-2f75-4feb-b9a9-71a9c4b6e06a.mp4','video/mp4',523290,6,NULL,NULL,'REJECTED','2026-10-24 10:10:07','2026-10-24 10:10:11'),(50,1,'VIDEO','blog/2026/10/1e0702b6-808b-4d44-a845-0443c5d49f49.mp4','video/mp4',5798977,29,NULL,NULL,'PASSED','2026-10-27 14:52:30','2026-10-27 14:59:09'),(51,1,'IMAGE','blog/2026/10/e8488689-2ec6-475b-963c-cc9768971a61.jpg','image/jpeg',183198,NULL,800,1200,'REJECTED','2026-10-27 14:59:09','2026-10-27 14:59:13'),(52,1,'IMAGE','blog/2026/10/ea362fff-0b05-4318-bc5d-11a31012daf6.jpg','image/jpeg',5415,NULL,320,240,'PASSED','2026-10-27 15:15:55','2026-10-27 15:30:19'),(53,1,'IMAGE','blog/2026/10/3444ffca-94c5-4c0f-a407-2847dda29d96.jpg','image/jpeg',3012,NULL,320,240,'REJECTED','2026-10-27 15:17:36','2026-10-27 15:17:36'),(54,1,'IMAGE','blog/2026/10/158c20fb-267f-4254-89b2-88351e84ee4b.jpg','image/jpeg',1150,NULL,200,150,'REJECTED','2026-10-27 15:20:12','2026-10-27 15:20:12'),(55,1,'IMAGE','review/2026/10/af9f91f2-167b-44c4-9ac0-72539d1a33a8.jpg','image/jpeg',2751,NULL,240,180,'REJECTED','2026-10-27 15:21:28','2026-10-27 15:21:28'),(56,1,'IMAGE','blog/2026/10/84a14011-1916-4f35-8485-36a56cd0b643.jpg','image/jpeg',183198,NULL,800,1200,'REJECTED','2026-10-27 15:30:19','2026-10-27 15:30:34'),(57,1,'IMAGE','blog/2026/10/7e6a9979-8479-45c9-92a9-8edaae082c8b.jpg','image/jpeg',13503,NULL,782,321,'REJECTED','2026-10-27 15:32:18','2026-10-27 15:32:45'),(58,1,'IMAGE','blog/2026/10/ee2bbbd9-fb85-41fe-a0cc-0f7f7ae0b850.jpg','image/jpeg',13503,NULL,782,321,'REJECTED','2026-10-28 07:09:01','2026-10-28 07:09:03'),(59,1,'IMAGE','blog/2026/10/712440d4-8da3-491f-9c35-e467bd6ef0bc.jpg','image/jpeg',13503,NULL,782,321,'REJECTED','2026-10-30 03:35:24','2026-10-30 03:35:28'),(60,1,'IMAGE','blog/2026/10/18830883-6ae1-4174-9e27-971e01486094.jpg','image/jpeg',12728,NULL,250,148,'PASSED','2026-10-30 03:49:52','2026-10-30 03:50:54'),(61,1,'IMAGE','blog/2026/10/24c5313b-dc68-4008-8471-ec1dd4fc4734.jpg','image/jpeg',101163,NULL,764,401,'REJECTED','2026-10-30 03:50:40','2026-10-30 03:50:43'),(62,1,'IMAGE','blog/2026/10/5db912c5-b3b0-4c46-8ed0-3a0f485aa671.jpg','image/jpeg',101163,NULL,764,401,'REJECTED','2026-10-30 03:55:21','2026-10-30 03:55:23'),(63,1,'IMAGE','blog/2026/10/87d010b3-ba92-47f9-8710-5429f3f59785.jpg','image/jpeg',13503,NULL,782,321,'REJECTED','2026-10-30 03:56:14','2026-10-30 03:56:16'),(64,1,'IMAGE','blog/2026/10/e21b069d-1c62-4236-8f98-7f28156ebda4.jpg','image/jpeg',12728,NULL,250,148,'PASSED','2026-10-31 06:23:49','2026-10-31 06:46:45'),(65,1,'IMAGE','blog/2026/10/d6c8b617-b88f-487e-8256-a727b373d7da.jpg','image/jpeg',12728,NULL,250,148,'REJECTED','2026-10-31 06:47:16','2026-10-31 06:47:20'),(66,1,'VIDEO','blog/2026/10/283e8474-e77f-4e06-8489-32c76c20151d.mp4','video/mp4',1496518,26,NULL,NULL,'REJECTED','2026-10-31 09:04:56','2026-10-31 09:05:05'),(67,9,'IMAGE','guide/2026/11/809174fd-a060-437d-8475-ac34fe86414c.jpg','image/jpeg',49843,NULL,622,392,'PASSED','2026-11-05 19:50:18',NULL),(68,9,'IMAGE','guide/2026/11/4dbae2b6-e1a9-45b7-893b-3ca196c55794.jpg','image/jpeg',42291,NULL,480,547,'PASSED','2026-11-05 19:50:18',NULL),(69,9,'IMAGE','guide/2026/11/9e0647dc-4ea3-461e-a234-184b9f343262.jpg','image/jpeg',31462,NULL,317,600,'PASSED','2026-11-05 19:50:18',NULL),(70,9,'IMAGE','guide/2026/11/e32abf73-e603-46cf-a091-ab7c4721c5f4.jpg','image/jpeg',35690,NULL,412,556,'PASSED','2026-11-05 19:50:18',NULL),(71,9,'IMAGE','guide/2026/11/25e9e30d-d231-47ea-af7e-24421f5edf92.jpg','image/jpeg',35038,NULL,347,547,'PASSED','2026-11-05 19:50:18',NULL),(72,9,'IMAGE','guide/2026/11/6a8a1218-4b16-4c9d-abc5-0a8edaa1a1cb.jpg','image/jpeg',80433,NULL,962,550,'PASSED','2026-11-05 19:50:18',NULL),(73,9,'IMAGE','guide/2026/11/05647f41-31fa-4543-a2e5-c72ffca79d41.jpg','image/jpeg',134960,NULL,772,721,'PASSED','2026-11-05 19:50:18',NULL),(74,9,'IMAGE','guide/2026/11/e230319e-c7e1-4a74-adc1-4a19e1778758.jpg','image/jpeg',46905,NULL,721,375,'PASSED','2026-11-05 19:50:18',NULL),(75,9,'IMAGE','guide/2026/11/b5661026-fca8-41ba-9cc2-577779366c9f.jpg','image/jpeg',148800,NULL,1126,752,'PASSED','2026-11-05 19:50:18',NULL),(76,9,'IMAGE','guide/2026/11/b883208a-ae94-431f-b9f4-6bbad631f154.jpg','image/jpeg',50850,NULL,961,466,'PASSED','2026-11-05 19:50:18',NULL),(77,9,'IMAGE','guide/2026/11/2cce9799-c252-4457-a853-5b444a4dd06c.jpg','image/jpeg',151648,NULL,1181,787,'PASSED','2026-11-05 19:50:18',NULL),(78,9,'IMAGE','guide/2026/11/b3f71461-a2ed-4a11-9927-41fe97acda34.jpg','image/jpeg',167948,NULL,1187,786,'PASSED','2026-11-05 19:50:18',NULL),(79,9,'IMAGE','guide/2026/11/d5e045ce-64f9-4864-8754-aaa533b27783.jpg','image/jpeg',86865,NULL,1352,711,'PASSED','2026-11-05 19:50:18',NULL),(80,9,'IMAGE','guide/2026/11/66501a92-b012-4c71-88a4-9bc8e7f3098a.jpg','image/jpeg',186859,NULL,1062,797,'PASSED','2026-11-05 19:50:18',NULL),(81,9,'IMAGE','guide/2026/11/289eb8b6-c878-4b29-a610-b1baa0758d7e.jpg','image/jpeg',140254,NULL,852,652,'PASSED','2026-11-05 19:50:18',NULL),(82,9,'IMAGE','guide/2026/11/8526def7-5df7-43f3-ae6e-e17107e38722.jpg','image/jpeg',92480,NULL,876,727,'PASSED','2026-11-05 19:50:18',NULL),(83,13,'IMAGE','blog/2026/11/6e8a8d28-f594-4618-90cf-2e3fa84856ae.jpg','image/jpeg',130017,NULL,980,680,'PASSED','2026-11-07 06:14:05',NULL);
/*!40000 ALTER TABLE `media` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `moderation_scans`
--

DROP TABLE IF EXISTS `moderation_scans`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `moderation_scans` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `target_type` varchar(20) NOT NULL,
  `target_id` bigint NOT NULL,
  `engine` varchar(20) NOT NULL,
  `result` varchar(10) NOT NULL,
  `detail` json DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_scans_target` (`target_type`,`target_id`),
  CONSTRAINT `ck_scan_engine` CHECK ((`engine` in (_utf8mb4'KEYWORD',_utf8mb4'OPENAI',_utf8mb4'GEMINI'))),
  CONSTRAINT `ck_scan_result` CHECK ((`result` in (_utf8mb4'PASS',_utf8mb4'FLAG',_utf8mb4'ERROR'))),
  CONSTRAINT `ck_scan_target` CHECK ((`target_type` in (_utf8mb4'REVIEW',_utf8mb4'BLOG',_utf8mb4'COMMENT',_utf8mb4'MEDIA')))
) ENGINE=InnoDB AUTO_INCREMENT=112 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `moderation_scans`
--

LOCK TABLES `moderation_scans` WRITE;
/*!40000 ALTER TABLE `moderation_scans` DISABLE KEYS */;
INSERT INTO `moderation_scans` VALUES (1,'MEDIA',1,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-18 09:42:32'),(2,'MEDIA',2,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-19 13:43:38'),(3,'MEDIA',3,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-19 13:43:38'),(4,'MEDIA',4,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-19 13:44:37'),(5,'MEDIA',5,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-22 14:36:56'),(6,'MEDIA',6,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-22 15:00:12'),(7,'MEDIA',7,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 01:24:48'),(8,'MEDIA',8,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:26:29'),(9,'MEDIA',8,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:26:29'),(10,'MEDIA',9,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:26:29'),(11,'MEDIA',10,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:26:30'),(12,'MEDIA',10,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:26:30'),(13,'MEDIA',11,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:26:30'),(14,'MEDIA',11,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:26:30'),(15,'MEDIA',12,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:26:47'),(16,'MEDIA',14,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:26:48'),(17,'MEDIA',15,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:26:48'),(18,'MEDIA',16,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:47:31'),(19,'MEDIA',16,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:47:31'),(20,'MEDIA',17,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:47:31'),(21,'MEDIA',18,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:47:31'),(22,'MEDIA',18,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:47:31'),(23,'MEDIA',19,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:47:31'),(24,'MEDIA',19,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:47:31'),(25,'MEDIA',20,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:47:51'),(26,'MEDIA',20,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:47:51'),(27,'MEDIA',21,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:47:51'),(28,'MEDIA',22,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:47:51'),(29,'MEDIA',22,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:47:51'),(30,'MEDIA',23,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:47:51'),(31,'MEDIA',23,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:47:51'),(32,'MEDIA',24,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:48:44'),(33,'MEDIA',24,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:48:44'),(34,'MEDIA',25,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:48:44'),(35,'MEDIA',26,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:48:45'),(36,'MEDIA',26,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:48:45'),(37,'MEDIA',27,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:48:45'),(38,'MEDIA',27,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:48:45'),(39,'MEDIA',28,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:48:47'),(40,'MEDIA',28,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:48:47'),(41,'MEDIA',29,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:48:47'),(42,'MEDIA',30,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:48:47'),(43,'MEDIA',30,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:48:47'),(44,'MEDIA',31,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:48:47'),(45,'MEDIA',31,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:48:47'),(46,'MEDIA',32,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:48:49'),(47,'MEDIA',32,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:48:49'),(48,'MEDIA',33,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:48:49'),(49,'MEDIA',34,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:48:50'),(50,'MEDIA',34,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:48:50'),(51,'MEDIA',35,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 08:48:50'),(52,'MEDIA',35,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-24 08:48:50'),(53,'MEDIA',36,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 09:01:46'),(54,'MEDIA',36,'GEMINI','FLAG','{\"flags\": [\"SEXUAL\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2084}','2026-10-24 09:01:54'),(55,'MEDIA',37,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 09:22:00'),(56,'MEDIA',37,'GEMINI','FLAG','{\"flags\": [\"GAMBLING_PROMOTION\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"reason\": \"ảnh hoặc video quảng cáo cờ bạc, cá cược\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2112}','2026-10-24 09:22:05'),(57,'MEDIA',38,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 09:25:46'),(58,'MEDIA',38,'GEMINI','PASS','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"sceneLength\": 178, \"finishReason\": \"STOP\", \"speechLength\": 0, \"messageLength\": 0, \"totalTokenCount\": 2202}','2026-10-24 09:25:48'),(59,'MEDIA',39,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 09:28:17'),(60,'MEDIA',39,'GEMINI','PASS','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": true, \"sceneLength\": 0, \"finishReason\": \"STOP\", \"speechLength\": 0, \"messageLength\": 4, \"totalTokenCount\": 2087}','2026-10-24 09:28:19'),(61,'MEDIA',40,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 09:28:57'),(62,'MEDIA',40,'GEMINI','FLAG','{\"flags\": [\"SEXUAL\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2084}','2026-10-24 09:28:59'),(63,'MEDIA',41,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 09:29:35'),(64,'MEDIA',41,'GEMINI','PASS','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": true, \"sceneLength\": 0, \"finishReason\": \"STOP\", \"speechLength\": 0, \"messageLength\": 4, \"totalTokenCount\": 2087}','2026-10-24 09:29:36'),(65,'MEDIA',42,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 09:33:58'),(66,'MEDIA',42,'GEMINI','FLAG','{\"flags\": [\"SEX_SOLICITATION\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"reason\": \"ảnh hoặc video mời chào mua bán dâm hoặc “bao nuôi”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"Mua bán dâm\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2104}','2026-10-24 09:34:00'),(67,'MEDIA',43,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 09:51:26'),(68,'MEDIA',44,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 09:51:26'),(69,'MEDIA',44,'GEMINI','PASS','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"sceneLength\": 0, \"finishReason\": \"STOP\", \"speechLength\": 0, \"messageLength\": 0, \"totalTokenCount\": 2059}','2026-10-24 09:51:29'),(70,'MEDIA',45,'GEMINI','FLAG','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": true, \"reason\": \"ảnh hoặc video có mã QR (mã QR được tính như link, chỉ tài khoản đã có tag định danh mới được đăng)\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2713}','2026-10-24 09:51:30'),(71,'MEDIA',43,'GEMINI','PASS','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": true, \"sceneLength\": 4, \"finishReason\": \"STOP\", \"speechLength\": 0, \"messageLength\": 0, \"totalTokenCount\": 2071}','2026-10-24 09:51:30'),(72,'MEDIA',46,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 09:54:32'),(73,'MEDIA',46,'GEMINI','FLAG','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": true, \"reason\": \"ảnh hoặc video có mã QR (mã QR được tính như link, chỉ tài khoản đã có tag định danh mới được đăng)\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2068}','2026-10-24 09:54:35'),(74,'MEDIA',47,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 10:10:07'),(75,'MEDIA',48,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-24 10:10:07'),(76,'MEDIA',49,'GEMINI','FLAG','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": true, \"reason\": \"ảnh hoặc video có mã QR (mã QR được tính như link, chỉ tài khoản đã có tag định danh mới được đăng)\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2713}','2026-10-24 10:10:11'),(77,'MEDIA',47,'GEMINI','PASS','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": true, \"sceneLength\": 4, \"finishReason\": \"STOP\", \"speechLength\": 0, \"messageLength\": 0, \"totalTokenCount\": 2071}','2026-10-24 10:10:12'),(78,'MEDIA',48,'GEMINI','PASS','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"sceneLength\": 0, \"finishReason\": \"STOP\", \"speechLength\": 0, \"messageLength\": 0, \"totalTokenCount\": 2059}','2026-10-24 10:10:12'),(79,'MEDIA',50,'GEMINI','PASS','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"sceneLength\": 0, \"finishReason\": \"STOP\", \"speechLength\": 0, \"messageLength\": 0, \"totalTokenCount\": 9301}','2026-10-27 14:52:47'),(80,'MEDIA',51,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-27 14:59:09'),(81,'MEDIA',51,'GEMINI','FLAG','{\"flags\": [\"SEXUAL\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2068}','2026-10-27 14:59:13'),(82,'MEDIA',52,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-27 15:15:55'),(83,'MEDIA',53,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-27 15:17:36'),(84,'MEDIA',53,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-27 15:17:36'),(85,'MEDIA',54,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-27 15:20:12'),(86,'MEDIA',54,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-27 15:20:12'),(87,'MEDIA',55,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-27 15:21:28'),(88,'MEDIA',55,'GEMINI','FLAG','{\"mode\": \"dev\", \"note\": \"No Gemini key configured; file not read\", \"flags\": [], \"qrCode\": false, \"reason\": \"ảnh hoặc video có từ khóa không được phép “fb88”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"fb88\"}','2026-10-27 15:21:28'),(89,'MEDIA',56,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-27 15:30:19'),(90,'MEDIA',56,'GEMINI','FLAG','{\"flags\": [\"SEXUAL\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2068}','2026-10-27 15:30:34'),(91,'MEDIA',57,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-27 15:32:18'),(92,'MEDIA',57,'GEMINI','FLAG','{\"flags\": [\"SEX_SOLICITATION\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"reason\": \"ảnh hoặc video mời chào mua bán dâm hoặc “bao nuôi”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"Mua bán dâm\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2088}','2026-10-27 15:32:45'),(93,'MEDIA',58,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-28 07:09:01'),(94,'MEDIA',58,'GEMINI','FLAG','{\"flags\": [\"SEX_SOLICITATION\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"reason\": \"ảnh hoặc video mời chào mua bán dâm hoặc “bao nuôi”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"Mua bán dâm\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2088}','2026-10-28 07:09:03'),(95,'MEDIA',59,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-30 03:35:24'),(96,'MEDIA',59,'GEMINI','FLAG','{\"flags\": [\"SEX_SOLICITATION\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"reason\": \"ảnh hoặc video mời chào mua bán dâm hoặc “bao nuôi”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"Mua bán dâm\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2088}','2026-10-30 03:35:28'),(97,'MEDIA',60,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-30 03:49:52'),(98,'MEDIA',60,'GEMINI','PASS','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"sceneLength\": 0, \"finishReason\": \"STOP\", \"speechLength\": 0, \"messageLength\": 0, \"totalTokenCount\": 2054}','2026-10-30 03:49:56'),(99,'MEDIA',61,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-30 03:50:40'),(100,'MEDIA',61,'GEMINI','FLAG','{\"flags\": [\"GAMBLING_PROMOTION\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"reason\": \"ảnh hoặc video quảng cáo cờ bạc, cá cược\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2096}','2026-10-30 03:50:43'),(101,'MEDIA',62,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-30 03:55:21'),(102,'MEDIA',62,'GEMINI','FLAG','{\"flags\": [\"GAMBLING_PROMOTION\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"reason\": \"ảnh hoặc video quảng cáo cờ bạc, cá cược\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2096}','2026-10-30 03:55:23'),(103,'MEDIA',63,'OPENAI','PASS','{\"mode\": \"dev\", \"note\": \"No OpenAI key configured; photo not checked\"}','2026-10-30 03:56:14'),(104,'MEDIA',63,'GEMINI','FLAG','{\"flags\": [\"SEX_SOLICITATION\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"reason\": \"ảnh hoặc video mời chào mua bán dâm hoặc “bao nuôi”\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"Mua bán dâm\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2088}','2026-10-30 03:56:16'),(105,'MEDIA',64,'OPENAI','PASS','{\"model\": \"omni-moderation-latest\", \"scores\": {}, \"flagged\": false, \"categories\": []}','2026-10-31 06:23:50'),(106,'MEDIA',64,'GEMINI','PASS','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"sceneLength\": 0, \"finishReason\": \"STOP\", \"speechLength\": 0, \"messageLength\": 0, \"totalTokenCount\": 2054}','2026-10-31 06:23:55'),(107,'MEDIA',65,'OPENAI','PASS','{\"model\": \"omni-moderation-latest\", \"scores\": {}, \"flagged\": false, \"categories\": []}','2026-10-31 06:47:17'),(108,'MEDIA',65,'GEMINI','FLAG','{\"flags\": [\"GAMBLING\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"reason\": \"ảnh hoặc video có cảnh cờ bạc, cá cược\", \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"\", \"finishReason\": \"STOP\", \"totalTokenCount\": 2183}','2026-10-31 06:47:20'),(109,'MEDIA',66,'GEMINI','FLAG','{\"flags\": [\"SEXUAL\"], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"speech\": \"\", \"sceneText\": \"\", \"messageText\": \"\", \"finishReason\": \"STOP\", \"totalTokenCount\": 8660}','2026-10-31 09:05:05'),(110,'MEDIA',83,'OPENAI','PASS','{\"model\": \"omni-moderation-latest\", \"scores\": {}, \"flagged\": false, \"categories\": []}','2026-11-07 06:14:07'),(111,'MEDIA',83,'GEMINI','PASS','{\"flags\": [], \"model\": \"gemini-3.5-flash-lite\", \"qrCode\": false, \"sceneLength\": 15, \"finishReason\": \"STOP\", \"speechLength\": 0, \"messageLength\": 0, \"totalTokenCount\": 2184}','2026-11-07 06:14:11');
/*!40000 ALTER TABLE `moderation_scans` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `notifications`
--

DROP TABLE IF EXISTS `notifications`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notifications` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `type` varchar(30) NOT NULL,
  `content` varchar(500) NOT NULL,
  `ref_type` varchar(20) DEFAULT NULL,
  `ref_id` bigint DEFAULT NULL,
  `is_read` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_notif_user` (`user_id`,`is_read`,`created_at`),
  CONSTRAINT `fk_notif_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=59 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `notifications`
--

LOCK TABLES `notifications` WRITE;
/*!40000 ALTER TABLE `notifications` DISABLE KEYS */;
INSERT INTO `notifications` VALUES (1,5,'NEW_COMMENT','Trần Minh đã bình luận bài “Năm điều mình ước biết trước tuần đầu ở Tokyo” của bạn.','BLOG',1,1,'2026-10-17 05:56:54'),(2,5,'NEW_COMMENT','Trần Minh đã bình luận bài “Năm điều mình ước biết trước tuần đầu ở Tokyo” của bạn.','BLOG',1,1,'2026-10-17 11:28:19'),(3,6,'NEW_COMMENT','Trần Minh đã bình luận bài “Hai quán ăn dưới 1.000 yên quanh Nippori” của bạn.','BLOG',3,1,'2026-10-19 13:37:41'),(4,5,'NEW_COMMENT','Trần Minh đã bình luận bài “Năm điều mình ước biết trước tuần đầu ở Tokyo” của bạn.','BLOG',1,1,'2026-10-19 13:51:18'),(5,1,'NEW_COMMENT','Lan Anh đã bình luận bài “Hà nội” của bạn.','BLOG',20,1,'2026-10-22 07:13:26'),(30,1,'CONTENT_REJECTED','Bài viết “tàu nhanh nippori” chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. Bạn có thể sửa bài rồi gửi lại.','BLOG',38,1,'2026-10-24 09:01:54'),(31,1,'CONTENT_REJECTED','Bài viết “kiếm 1 chân vui vẻ” chưa được đăng vì ảnh hoặc video quảng cáo cờ bạc, cá cược. Bạn có thể sửa bài rồi gửi lại.','BLOG',39,1,'2026-10-24 09:22:05'),(32,5,'CONTENT_REJECTED','Bài viết “Tàu nhanh lh em” chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. Bạn có thể sửa bài rồi gửi lại.','BLOG',41,1,'2026-10-24 09:28:59'),(33,5,'CONTENT_REJECTED','Bài viết “Tàu nhanh lh em” chưa được đăng vì ảnh hoặc video mời chào mua bán dâm hoặc “bao nuôi”. Bạn có thể sửa bài rồi gửi lại.','BLOG',42,1,'2026-10-24 09:34:00'),(37,1,'CONTENT_REJECTED','Câu hỏi “Đi date tại Nippori” chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. Bạn có thể sửa bài rồi gửi lại.','QUESTION',50,1,'2026-10-27 14:59:13'),(38,1,'CONTENT_PUBLISHED','Bài viết “Thử thông báo quét ảnh” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.','BLOG',51,1,'2026-10-27 15:15:55'),(39,1,'CONTENT_REJECTED','Bài viết “Thử ảnh vi phạm” chưa được đăng vì ảnh hoặc video có từ khóa không được phép “fb88”. Bạn có thể sửa bài rồi gửi lại.','BLOG',52,1,'2026-10-27 15:17:36'),(40,1,'CONTENT_PUBLISHED','Bản sửa bài viết “Thử thông báo quét ảnh” đã qua kiểm duyệt ảnh/video và đã được đăng.','BLOG',51,1,'2026-10-27 15:19:34'),(41,1,'CONTENT_REJECTED','Bài viết “Thử trên điện thoại” chưa được đăng vì ảnh hoặc video có từ khóa không được phép “fb88”. Bạn có thể sửa bài rồi gửi lại.','BLOG',53,1,'2026-10-27 15:20:12'),(42,1,'CONTENT_REJECTED','Review về “Ramen Aoba” chưa được đăng vì ảnh hoặc video có từ khóa không được phép “fb88”. Bạn có thể sửa review rồi gửi lại.','REVIEW',25,1,'2026-10-27 15:21:28'),(43,1,'CONTENT_REJECTED','Bài viết “Thử thông báo quét ảnh” chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. Bạn có thể sửa bài rồi gửi lại.','BLOG',51,1,'2026-10-27 15:30:34'),(44,1,'CONTENT_REJECTED','Bài viết “Mã QR” chưa được đăng vì ảnh hoặc video mời chào mua bán dâm hoặc “bao nuôi”. Bạn có thể sửa bài rồi gửi lại.','BLOG',54,1,'2026-10-27 15:32:45'),(45,1,'CONTENT_REJECTED','Bài viết “Kình nghiệm” chưa được đăng vì ảnh hoặc video mời chào mua bán dâm hoặc “bao nuôi”. Bạn có thể sửa bài rồi gửi lại.','BLOG',55,1,'2026-10-28 07:09:03'),(46,1,'CONTENT_REJECTED','Bài viết “Kinh nghiệm tại ga tàu Nippori” chưa được đăng vì ảnh hoặc video mời chào mua bán dâm hoặc “bao nuôi”. Bạn có thể sửa bài rồi gửi lại.','BLOG',23,1,'2026-10-30 03:35:28'),(47,1,'CONTENT_PUBLISHED','Bản sửa bài viết “Kinh nghiệm tại ga tàu Nippori” đã qua kiểm duyệt ảnh/video và đã được đăng.','BLOG',23,1,'2026-10-30 03:49:56'),(48,1,'CONTENT_REJECTED','Bài viết “Kinh nghiệm tại ga tàu Nippori” chưa được đăng vì ảnh hoặc video quảng cáo cờ bạc, cá cược. Bạn có thể sửa bài rồi gửi lại.','BLOG',23,1,'2026-10-30 03:50:43'),(49,1,'CONTENT_REJECTED','Bài viết “Kinh nghiệm tại ga tàu Nippori” chưa được đăng vì ảnh hoặc video quảng cáo cờ bạc, cá cược. Bạn có thể sửa bài rồi gửi lại.','BLOG',23,1,'2026-10-30 03:55:23'),(50,1,'CONTENT_REJECTED','Bài viết “Kinh nghiệm tại ga tàu Nippori” chưa được đăng vì ảnh hoặc video mời chào mua bán dâm hoặc “bao nuôi”. Bạn có thể sửa bài rồi gửi lại.','BLOG',23,1,'2026-10-30 03:56:16'),(51,1,'CONTENT_PUBLISHED','Bản sửa bài viết “Cách đề di chuyển đến bảo tang UENO” đã qua kiểm duyệt ảnh/video và đã được đăng.','BLOG',22,1,'2026-10-31 06:23:55'),(52,1,'CONTENT_REJECTED','Bài viết “c o ba ccc de” chưa được đăng vì ảnh hoặc video có cảnh cờ bạc, cá cược. Bạn có thể sửa bài rồi gửi lại.','BLOG',56,1,'2026-10-31 06:47:20'),(53,1,'CONTENT_REJECTED','Bài viết “kabukicho” chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. Bạn có thể sửa bài rồi gửi lại.','BLOG',57,1,'2026-10-31 09:05:05'),(54,1,'NEW_COMMENT','Hoàng đã trả lời câu hỏi “Anh chị cho em hỏi cửa hàng đồ việt nào rẻ nhất ở Nippori ạ” của bạn.','QUESTION',8,1,'2026-11-06 18:34:05'),(55,6,'BEST_ANSWER','Câu trả lời của bạn cho “Anh chị cho em hỏi cửa hàng đồ việt nào rẻ nhất ở Nippori ạ” được chọn là câu trả lời hay nhất. +5 điểm.','QUESTION',8,0,'2026-11-06 18:34:20'),(56,13,'CONTENT_PUBLISHED','Câu hỏi “Đường đến Tokyo Skytree” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.','QUESTION',58,1,'2026-11-07 06:14:11'),(57,13,'NEW_COMMENT','Trần Minh đã trả lời câu hỏi “Đường đi từ Arakawa đến Tokyo Skytree” của bạn.','QUESTION',58,1,'2026-11-07 06:16:45'),(58,1,'BEST_ANSWER','Câu trả lời của bạn cho “Đường đi từ Arakawa đến Tokyo Skytree” được chọn là câu trả lời hay nhất. +5 điểm.','QUESTION',58,1,'2026-11-07 06:17:03');
/*!40000 ALTER TABLE `notifications` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `phrase_topic_links`
--

DROP TABLE IF EXISTS `phrase_topic_links`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `phrase_topic_links` (
  `phrase_id` bigint NOT NULL,
  `topic_id` varchar(20) NOT NULL,
  PRIMARY KEY (`phrase_id`,`topic_id`),
  KEY `fk_ptopic_topic` (`topic_id`),
  CONSTRAINT `fk_ptopic_phrase` FOREIGN KEY (`phrase_id`) REFERENCES `phrases` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ptopic_topic` FOREIGN KEY (`topic_id`) REFERENCES `learning_topics` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `phrase_topic_links`
--

LOCK TABLES `phrase_topic_links` WRITE;
/*!40000 ALTER TABLE `phrase_topic_links` DISABLE KEYS */;
INSERT INTO `phrase_topic_links` VALUES (4,'ask'),(6,'ask'),(9,'ask'),(12,'ask'),(21,'ask'),(22,'ask'),(17,'health'),(18,'health'),(21,'health'),(5,'order'),(6,'order'),(7,'order'),(8,'order'),(14,'paper'),(15,'paper'),(16,'paper'),(19,'paper'),(20,'paper'),(1,'pay'),(3,'pay'),(7,'pay'),(10,'pay'),(13,'pay'),(19,'pay'),(24,'pay'),(9,'train'),(10,'train'),(11,'train');
/*!40000 ALTER TABLE `phrase_topic_links` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `phrases`
--

DROP TABLE IF EXISTS `phrases`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `phrases` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category_id` varchar(20) NOT NULL,
  `kind` varchar(10) NOT NULL,
  `japanese` varchar(200) NOT NULL,
  `romaji` varchar(200) NOT NULL,
  `meaning_vi` varchar(300) NOT NULL,
  `created_by` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_phrases_cat` (`category_id`),
  KEY `fk_phrases_creator` (`created_by`),
  CONSTRAINT `fk_phrases_cat` FOREIGN KEY (`category_id`) REFERENCES `place_categories` (`id`),
  CONSTRAINT `fk_phrases_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`),
  CONSTRAINT `ck_phrases_kind` CHECK ((`kind` in (_utf8mb4'PHRASE',_utf8mb4'VOCAB')))
) ENGINE=InnoDB AUTO_INCREMENT=25 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `phrases`
--

LOCK TABLES `phrases` WRITE;
/*!40000 ALTER TABLE `phrases` DISABLE KEYS */;
INSERT INTO `phrases` VALUES (1,'konbini','PHRASE','袋はいりません。','Fukuro wa irimasen.','Tôi không cần túi.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(2,'konbini','PHRASE','温めてください。','Atatamete kudasai.','Làm ơn hâm nóng giúp tôi.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(3,'konbini','PHRASE','カードで払えますか。','Kādo de haraemasu ka.','Tôi trả bằng thẻ được không?',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(4,'konbini','VOCAB','〜てください','~te kudasai','Nhờ ai làm gì một cách lịch sự.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(5,'food','PHRASE','注文お願いします。','Chūmon onegai shimasu.','Cho tôi gọi món.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(6,'food','PHRASE','おすすめは何ですか。','Osusume wa nan desu ka.','Quán có món gì nên thử?',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(7,'food','PHRASE','お会計お願いします。','Okaikei onegai shimasu.','Cho tôi thanh toán.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(8,'food','VOCAB','〜にします','~ni shimasu','Chọn một món hoặc một phương án.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(9,'station','PHRASE','この電車は上野に止まりますか。','Kono densha wa Ueno ni tomarimasu ka.','Tàu này có dừng ở Ueno không?',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(10,'station','PHRASE','チャージしたいです。','Chāji shitai desu.','Tôi muốn nạp tiền vào thẻ IC.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(11,'station','VOCAB','乗り換え','norikae','Đổi tàu.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(12,'super','PHRASE','これはどこにありますか。','Kore wa doko ni arimasu ka.','Món này ở đâu?',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(13,'super','VOCAB','割引','waribiki','Giảm giá.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(14,'ward','PHRASE','転入届を出したいです。','Tennyū-todoke o dashitai desu.','Tôi muốn nộp giấy đăng ký chuyển đến.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(15,'ward','VOCAB','在留カード','zairyū kādo','Thẻ cư trú.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(16,'ward','VOCAB','〜たいです','~tai desu','Diễn đạt điều mình muốn làm.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(17,'hospital','PHRASE','熱があります。','Netsu ga arimasu.','Tôi bị sốt.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(18,'hospital','PHRASE','ここが痛いです。','Koko ga itai desu.','Tôi đau ở đây.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(19,'bank','PHRASE','口座を開きたいです。','Kōza o hirakitai desu.','Tôi muốn mở tài khoản.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(20,'bank','PHRASE','荷物を送りたいです。','Nimotsu o okuritai desu.','Tôi muốn gửi bưu kiện.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(21,'pharmacy','PHRASE','風邪薬はありますか。','Kazegusuri wa arimasu ka.','Có thuốc cảm không?',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(22,'school','PHRASE','もう一度お願いします。','Mō ichido onegai shimasu.','Xin nhắc lại một lần nữa.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(23,'school','PHRASE','遅れてすみません。','Okurete sumimasen.','Xin lỗi vì tôi đến muộn.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL),(24,'laundry','VOCAB','両替機','ryōgaeki','Máy đổi tiền xu.',3,'2026-10-16 08:39:39','2026-10-16 08:39:39',NULL);
/*!40000 ALTER TABLE `phrases` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `place_categories`
--

DROP TABLE IF EXISTS `place_categories`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `place_categories` (
  `id` varchar(20) NOT NULL,
  `name_vi` varchar(80) NOT NULL,
  `icon` varchar(50) NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `place_categories`
--

LOCK TABLES `place_categories` WRITE;
/*!40000 ALTER TABLE `place_categories` DISABLE KEYS */;
INSERT INTO `place_categories` VALUES ('bank','Bưu điện và ngân hàng','local_post_office',7),('food','Quán ăn','restaurant',2),('hospital','Bệnh viện và phòng khám','local_hospital',6),('housing','Văn phòng bất động sản','home_work',14),('konbini','Cửa hàng tiện lợi','local_convenience_store',1),('laundry','Tiệm giặt tự động','local_laundry_service',10),('other','Địa điểm khác','place',99),('park','Công viên','park',11),('pharmacy','Hiệu thuốc','local_pharmacy',8),('phone','Cửa hàng điện thoại và SIM','smartphone',13),('school','Trường học','school',9),('station','Ga tàu','train',3),('super','Siêu thị','shopping_cart',4),('temple','Đền và chùa','temple_buddhist',12),('ward','Văn phòng quận','account_balance',5);
/*!40000 ALTER TABLE `place_categories` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `place_closed_reports`
--

DROP TABLE IF EXISTS `place_closed_reports`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `place_closed_reports` (
  `place_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `resolved_at` datetime DEFAULT NULL,
  PRIMARY KEY (`place_id`,`user_id`),
  KEY `fk_closed_user` (`user_id`),
  CONSTRAINT `fk_closed_place` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_closed_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `place_closed_reports`
--

LOCK TABLES `place_closed_reports` WRITE;
/*!40000 ALTER TABLE `place_closed_reports` DISABLE KEYS */;
/*!40000 ALTER TABLE `place_closed_reports` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `place_history`
--

DROP TABLE IF EXISTS `place_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `place_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `place_id` bigint NOT NULL,
  `action` varchar(30) NOT NULL,
  `actor_id` bigint NOT NULL,
  `detail` json DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_phist_place` (`place_id`),
  KEY `fk_phist_actor` (`actor_id`),
  CONSTRAINT `fk_phist_actor` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_phist_place` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_phist_action` CHECK ((`action` in (_utf8mb4'CREATED',_utf8mb4'APPROVED',_utf8mb4'REJECTED',_utf8mb4'EDITED',_utf8mb4'CLOSED',_utf8mb4'REOPENED',_utf8mb4'REMOVED',_utf8mb4'RESTORED',_utf8mb4'MERGED')))
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `place_history`
--

LOCK TABLES `place_history` WRITE;
/*!40000 ALTER TABLE `place_history` DISABLE KEYS */;
INSERT INTO `place_history` VALUES (1,13,'CREATED',1,'{\"via\": \"BLOG\", \"source\": \"OSM\"}','2026-10-19 12:59:06'),(2,14,'CREATED',1,'{\"via\": \"BLOG\", \"source\": \"OSM\"}','2026-10-19 13:43:19'),(3,15,'CREATED',1,'{\"via\": \"BLOG\", \"source\": \"OSM\"}','2026-10-22 06:45:53'),(4,16,'CREATED',1,'{\"via\": \"BLOG\", \"source\": \"OSM\"}','2026-10-24 09:25:45'),(5,17,'CREATED',1,'{\"via\": \"BLOG\", \"source\": \"OSM\"}','2026-10-31 07:16:04'),(6,18,'CREATED',13,'{\"via\": \"BLOG\", \"source\": \"OSM\"}','2026-11-07 06:14:04');
/*!40000 ALTER TABLE `place_history` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `place_merges`
--

DROP TABLE IF EXISTS `place_merges`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `place_merges` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kept_place_id` bigint NOT NULL,
  `merged_place_id` bigint NOT NULL,
  `field_choices` json NOT NULL,
  `moved_reviews` int NOT NULL DEFAULT '0',
  `merged_by` bigint NOT NULL,
  `merged_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `merged_place_id` (`merged_place_id`),
  KEY `fk_merge_kept` (`kept_place_id`),
  KEY `fk_merge_by` (`merged_by`),
  CONSTRAINT `fk_merge_by` FOREIGN KEY (`merged_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_merge_kept` FOREIGN KEY (`kept_place_id`) REFERENCES `places` (`id`),
  CONSTRAINT `fk_merge_merged` FOREIGN KEY (`merged_place_id`) REFERENCES `places` (`id`),
  CONSTRAINT `ck_merge_diff` CHECK ((`kept_place_id` <> `merged_place_id`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `place_merges`
--

LOCK TABLES `place_merges` WRITE;
/*!40000 ALTER TABLE `place_merges` DISABLE KEYS */;
/*!40000 ALTER TABLE `place_merges` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `places`
--

DROP TABLE IF EXISTS `places`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `places` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `google_place_id` varchar(255) DEFAULT NULL,
  `osm_id` varchar(30) DEFAULT NULL,
  `name_vi` varchar(160) NOT NULL,
  `name_ja` varchar(160) DEFAULT NULL,
  `name_en` varchar(160) DEFAULT NULL,
  `category_id` varchar(20) NOT NULL,
  `area_id` bigint DEFAULT NULL,
  `address` varchar(255) DEFAULT NULL,
  `lat` decimal(9,6) DEFAULT NULL,
  `lng` decimal(9,6) DEFAULT NULL,
  `source` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `is_closed` tinyint(1) NOT NULL DEFAULT '0',
  `closed_at` datetime DEFAULT NULL,
  `reject_reason` varchar(255) DEFAULT NULL,
  `removed_reason` varchar(255) DEFAULT NULL,
  `merged_into_id` bigint DEFAULT NULL,
  `created_by` bigint NOT NULL,
  `reviewed_by` bigint DEFAULT NULL,
  `reviewed_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `google_place_id` (`google_place_id`),
  UNIQUE KEY `uq_places_osm` (`osm_id`),
  KEY `idx_places_status_cat` (`status`,`category_id`),
  KEY `fk_places_cat` (`category_id`),
  KEY `fk_places_area` (`area_id`),
  KEY `fk_places_merged` (`merged_into_id`),
  KEY `fk_places_creator` (`created_by`),
  KEY `fk_places_reviewer` (`reviewed_by`),
  CONSTRAINT `fk_places_area` FOREIGN KEY (`area_id`) REFERENCES `areas` (`id`),
  CONSTRAINT `fk_places_cat` FOREIGN KEY (`category_id`) REFERENCES `place_categories` (`id`),
  CONSTRAINT `fk_places_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_places_merged` FOREIGN KEY (`merged_into_id`) REFERENCES `places` (`id`),
  CONSTRAINT `fk_places_reviewer` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `ck_places_merged` CHECK (((`status` = _utf8mb4'MERGED') = (`merged_into_id` is not null))),
  CONSTRAINT `ck_places_position` CHECK (((`google_place_id` is not null) or ((`lat` is not null) and (`lng` is not null)))),
  CONSTRAINT `ck_places_source` CHECK ((`source` in (_utf8mb4'GOOGLE',_utf8mb4'OSM',_utf8mb4'TEAM',_utf8mb4'USER',_utf8mb4'MODERATOR'))),
  CONSTRAINT `ck_places_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'ACTIVE',_utf8mb4'REJECTED',_utf8mb4'REMOVED',_utf8mb4'MERGED')))
) ENGINE=InnoDB AUTO_INCREMENT=19 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `places`
--

LOCK TABLES `places` WRITE;
/*!40000 ALTER TABLE `places` DISABLE KEYS */;
INSERT INTO `places` VALUES (1,NULL,'N772389775','Ga Nippori','日暮里駅','Nippori Station','station',679,'Nishi-Nippori, Arakawa, Tokyo',35.728100,139.770700,'OSM','ACTIVE',0,NULL,NULL,NULL,NULL,4,2,'2026-10-16 08:39:38','2026-10-16 08:39:38','2026-10-29 06:53:41'),(2,NULL,NULL,'Trường Nhật ngữ FPT','FPT日本語学校','FPT Japanese Language School','school',679,'Higashi-Nippori, Arakawa, Tokyo',35.729600,139.772800,'TEAM','ACTIVE',0,NULL,NULL,NULL,NULL,4,2,'2026-10-16 08:39:38','2026-10-16 08:39:38','2026-10-29 06:53:41'),(3,NULL,NULL,'Konbini Midori','コンビニみどり','Midori Mart','konbini',679,'Higashi-Nippori 5',35.727500,139.772000,'TEAM','ACTIVE',0,NULL,NULL,NULL,NULL,4,2,'2026-10-16 08:39:38','2026-10-16 08:39:38','2026-10-29 06:53:41'),(4,NULL,NULL,'Quán cơm Hana','はな食堂','Hana Shokudo','food',679,'Ghim do cộng đồng thêm',35.726700,139.773500,'USER','ACTIVE',0,NULL,NULL,NULL,NULL,5,2,'2026-10-16 08:39:38','2026-10-16 08:39:38','2026-10-29 06:53:41'),(5,NULL,NULL,'Siêu thị Sakura','さくらスーパー','Sakura Super','super',679,'Higashi-Nippori 6',35.730900,139.773700,'TEAM','ACTIVE',0,NULL,NULL,NULL,NULL,4,2,'2026-10-16 08:39:38','2026-10-16 08:39:38','2026-10-29 06:53:41'),(6,NULL,NULL,'Văn phòng quận Arakawa','荒川区役所','Arakawa City Office','ward',679,'Arakawa, Tokyo',35.736000,139.783400,'TEAM','ACTIVE',0,NULL,NULL,NULL,NULL,4,2,'2026-10-16 08:39:38','2026-10-16 08:39:38','2026-10-29 06:53:41'),(7,NULL,NULL,'Phòng khám Aoi','あおいクリニック','Aoi Clinic','hospital',679,'Yanaka 7',35.727900,139.767400,'TEAM','ACTIVE',0,NULL,NULL,NULL,NULL,4,2,'2026-10-16 08:39:38','2026-10-16 08:39:38','2026-10-29 06:53:41'),(8,NULL,NULL,'Bưu điện Nippori','日暮里郵便局','Nippori Post Office','bank',679,'Higashi-Nippori 4',35.725400,139.771800,'TEAM','ACTIVE',0,NULL,NULL,NULL,NULL,4,2,'2026-10-16 08:39:38','2026-10-16 08:39:38','2026-10-29 06:53:41'),(9,NULL,NULL,'Hiệu thuốc Mori','もり薬局','Mori Pharmacy','pharmacy',679,'Higashi-Nippori 5',35.728300,139.772400,'TEAM','ACTIVE',0,NULL,NULL,NULL,NULL,4,2,'2026-10-16 08:39:38','2026-10-16 08:39:38','2026-10-29 06:53:41'),(10,NULL,NULL,'Ramen Aoba','ラーメン青葉','Ramen Aoba','food',679,'Yanaka 3',35.726200,139.767000,'TEAM','ACTIVE',0,NULL,NULL,NULL,NULL,4,2,'2026-10-16 08:39:38','2026-10-16 08:39:38','2026-10-29 06:53:41'),(11,NULL,NULL,'Tiệm giặt Coin Laundry Sora','コインランドリーそら','Coin Laundry Sora','laundry',679,'Nishi-Nippori 2',35.725000,139.768500,'TEAM','ACTIVE',0,NULL,NULL,NULL,NULL,4,2,'2026-10-16 08:39:38','2026-10-16 08:39:38','2026-10-29 06:53:41'),(12,NULL,NULL,'Phở Hà Nội Nippori','フォーハノイ','Pho Hanoi','food',679,'Ghim do cộng đồng thêm',35.724000,139.773000,'USER','PENDING',0,NULL,NULL,NULL,NULL,6,NULL,NULL,'2026-10-16 08:39:38','2026-10-29 06:53:41'),(13,NULL,'N2149761647','Tōkyō','東京','Tōkyō','station',663,'Marunouchi 1, Chiyoda, Nhật Bản',35.681255,139.766706,'OSM','ACTIVE',0,NULL,NULL,NULL,NULL,1,NULL,NULL,'2026-10-19 12:59:06','2026-10-29 06:53:41'),(14,NULL,'W111603914','Nippon Cultural Broadcasting',NULL,NULL,'other',664,'Hamamatsucho 1-chome, Minato, Tokyo, Nhật Bản',35.656945,139.757185,'OSM','ACTIVE',0,NULL,NULL,NULL,NULL,1,NULL,NULL,'2026-10-19 13:43:19','2026-10-29 06:53:41'),(15,NULL,'W208255133','Hanoi Museum',NULL,NULL,'other',NULL,'Pham Hung Road, Me Tri, Tu Liem Ward, Hanoi, Việt Nam',21.010013,105.786309,'OSM','ACTIVE',0,NULL,NULL,NULL,NULL,1,NULL,NULL,'2026-10-22 06:45:53','2026-10-22 06:45:53'),(16,NULL,'W145344085','Ueno Toshogu',NULL,NULL,'temple',667,'Ueno Park, Taito, Tokyo, Nhật Bản',35.715367,139.770632,'OSM','ACTIVE',0,NULL,NULL,NULL,NULL,1,NULL,NULL,'2026-10-24 09:25:45','2026-10-29 06:53:41'),(17,NULL,'N1895223210','Arakawa-itchumae','荒川一中前','Arakawa-itchumae','station',679,'Minami-Senju 1, Arakawa, Nhật Bản',35.733717,139.788930,'OSM','ACTIVE',0,NULL,NULL,NULL,NULL,1,NULL,NULL,'2026-10-31 07:16:04','2026-10-31 07:16:04'),(18,NULL,'N14140294375','TokyoSkyTreeTown',NULL,NULL,'other',668,'Oshiage 1, Sumida, Tokyo, Nhật Bản',35.709843,139.808745,'OSM','ACTIVE',0,NULL,NULL,NULL,NULL,13,NULL,NULL,'2026-11-07 06:14:04','2026-11-07 06:14:04');
/*!40000 ALTER TABLE `places` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `point_transactions`
--

DROP TABLE IF EXISTS `point_transactions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `point_transactions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `delta` int NOT NULL,
  `reason` varchar(40) NOT NULL,
  `ref_type` varchar(20) DEFAULT NULL,
  `ref_id` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_points_user` (`user_id`,`created_at`),
  CONSTRAINT `fk_points_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=58 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `point_transactions`
--

LOCK TABLES `point_transactions` WRITE;
/*!40000 ALTER TABLE `point_transactions` DISABLE KEYS */;
INSERT INTO `point_transactions` VALUES (1,1,10,'BLOG_CREATED','BLOG',7,'2026-10-18 09:42:32'),(2,1,10,'BLOG_CREATED','BLOG',8,'2026-10-18 11:25:28'),(3,1,10,'BLOG_CREATED','BLOG',9,'2026-10-19 12:59:07'),(4,1,10,'BLOG_CREATED','BLOG',10,'2026-10-19 13:43:19'),(5,5,10,'BLOG_CREATED','BLOG',12,'2026-10-19 13:43:38'),(6,5,10,'BLOG_CREATED','BLOG',11,'2026-10-19 13:43:38'),(7,5,-10,'CONTENT_DELETED','BLOG',12,'2026-10-19 13:45:29'),(8,5,-10,'CONTENT_DELETED','BLOG',11,'2026-10-19 13:45:29'),(9,1,10,'BLOG_CREATED','BLOG',13,'2026-10-21 12:43:28'),(10,5,10,'BLOG_CREATED','BLOG',14,'2026-10-21 13:45:13'),(11,5,-10,'CONTENT_DELETED','BLOG',14,'2026-10-21 13:46:32'),(12,1,-10,'CONTENT_DELETED','BLOG',13,'2026-10-21 14:00:37'),(13,1,10,'BLOG_CREATED','BLOG',15,'2026-10-21 14:00:51'),(14,1,-10,'CONTENT_DELETED','BLOG',15,'2026-10-21 14:01:08'),(15,1,10,'BLOG_CREATED','BLOG',16,'2026-10-21 14:08:59'),(16,1,10,'BLOG_CREATED','BLOG',17,'2026-10-21 14:27:50'),(17,1,-10,'CONTENT_DELETED','BLOG',17,'2026-10-21 14:27:50'),(18,1,-10,'CONTENT_DELETED','BLOG',16,'2026-10-21 14:29:15'),(19,1,10,'BLOG_CREATED','BLOG',18,'2026-10-21 14:31:45'),(20,1,-10,'CONTENT_DELETED','BLOG',18,'2026-10-21 14:32:01'),(21,1,10,'BLOG_CREATED','BLOG',19,'2026-10-21 14:32:44'),(22,1,-10,'CONTENT_DELETED','BLOG',19,'2026-10-21 14:32:48'),(23,1,10,'BLOG_CREATED','BLOG',20,'2026-10-22 06:45:54'),(24,6,10,'REVIEW_CREATED','REVIEW',10,'2026-10-22 14:36:56'),(25,6,-10,'CONTENT_DELETED','REVIEW',10,'2026-10-22 14:37:55'),(26,1,10,'BLOG_CREATED','BLOG',21,'2026-10-22 15:00:12'),(27,1,10,'BLOG_CREATED','BLOG',22,'2026-10-22 19:04:00'),(28,1,10,'BLOG_CREATED','BLOG',23,'2026-10-24 01:24:48'),(29,1,10,'BLOG_CREATED','BLOG',26,'2026-10-24 08:26:47'),(30,1,10,'BLOG_CREATED','BLOG',27,'2026-10-24 08:26:47'),(31,1,10,'REVIEW_CREATED','REVIEW',13,'2026-10-24 08:26:48'),(32,1,10,'REVIEW_CREATED','REVIEW',14,'2026-10-24 08:26:48'),(33,1,-10,'CONTENT_DELETED','BLOG',26,'2026-10-24 08:26:50'),(34,1,-10,'CONTENT_DELETED','BLOG',27,'2026-10-24 08:26:51'),(35,1,-10,'CONTENT_DELETED','REVIEW',13,'2026-10-24 08:26:51'),(36,1,-10,'CONTENT_DELETED','REVIEW',14,'2026-10-24 08:26:51'),(37,5,10,'BLOG_CREATED','BLOG',40,'2026-10-24 09:26:58'),(38,5,-10,'CONTENT_DELETED','BLOG',40,'2026-10-24 09:27:09'),(39,5,10,'BLOG_CREATED','BLOG',41,'2026-10-24 09:28:19'),(40,5,10,'BLOG_CREATED','BLOG',42,'2026-10-24 09:29:36'),(41,5,-10,'CONTENT_DELETED','BLOG',42,'2026-10-24 09:44:29'),(42,1,10,'BLOG_CREATED','BLOG',44,'2026-10-24 09:51:29'),(43,5,10,'BLOG_CREATED','BLOG',43,'2026-10-24 09:51:30'),(44,5,-10,'CONTENT_DELETED','BLOG',43,'2026-10-24 09:51:45'),(45,1,-10,'CONTENT_DELETED','BLOG',44,'2026-10-24 09:51:46'),(46,5,10,'BLOG_CREATED','BLOG',47,'2026-10-24 10:10:12'),(47,1,10,'BLOG_CREATED','BLOG',48,'2026-10-24 10:10:12'),(48,5,-10,'CONTENT_DELETED','BLOG',47,'2026-10-24 10:10:13'),(49,1,-10,'CONTENT_DELETED','BLOG',48,'2026-10-24 10:10:13'),(50,1,10,'BLOG_CREATED','BLOG',50,'2026-10-27 14:52:47'),(51,1,10,'BLOG_CREATED','BLOG',51,'2026-10-27 15:15:55'),(52,1,-10,'CONTENT_DELETED','BLOG',51,'2026-10-27 15:31:25'),(53,1,-10,'CONTENT_DELETED','BLOG',22,'2026-10-31 06:46:45'),(54,1,10,'REVIEW_CREATED','REVIEW',26,'2026-10-31 07:16:04'),(55,6,5,'BEST_ANSWER','COMMENT',16,'2026-11-06 18:34:20'),(56,13,10,'BLOG_CREATED','BLOG',58,'2026-11-07 06:14:11'),(57,1,5,'BEST_ANSWER','COMMENT',17,'2026-11-07 06:17:03');
/*!40000 ALTER TABLE `point_transactions` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `prefectures`
--

DROP TABLE IF EXISTS `prefectures`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `prefectures` (
  `code` char(2) NOT NULL,
  `name_ja` varchar(10) NOT NULL,
  `name_kana` varchar(20) NOT NULL,
  `name_en` varchar(20) NOT NULL,
  `lat` decimal(9,6) NOT NULL,
  `lng` decimal(9,6) NOT NULL,
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `prefectures`
--

LOCK TABLES `prefectures` WRITE;
/*!40000 ALTER TABLE `prefectures` DISABLE KEYS */;
INSERT INTO `prefectures` VALUES ('01','北海道','ホッカイドウ','Hokkaido',43.063941,141.347907),('02','青森県','アオモリケン','Aomori',40.824338,140.740087),('03','岩手県','イワテケン','Iwate',39.703647,141.152592),('04','宮城県','ミヤギケン','Miyagi',38.268803,140.871846),('05','秋田県','アキタケン','Akita',39.718058,140.103250),('06','山形県','ヤマガタケン','Yamagata',38.240457,140.363278),('07','福島県','フクシマケン','Fukushima',37.749957,140.467734),('08','茨城県','イバラキケン','Ibaraki',36.341450,140.446735),('09','栃木県','トチギケン','Tochigi',36.565689,139.883528),('10','群馬県','グンマケン','Gumma',36.391192,139.060947),('11','埼玉県','サイタマケン','Saitama',35.856907,139.648854),('12','千葉県','チバケン','Chiba',35.604588,140.123184),('13','東京都','トウキョウト','Tokyo',35.689568,139.691717),('14','神奈川県','カナガワケン','Kanagawa',35.447710,139.642536),('15','新潟県','ニイガタケン','Niigata',37.902238,139.023531),('16','富山県','トヤマケン','Toyama',36.695190,137.211341),('17','石川県','イシカワケン','Ishikawa',36.594652,136.625725),('18','福井県','フクイケン','Fukui',36.065244,136.221791),('19','山梨県','ヤマナシケン','Yamanashi',35.663935,138.568379),('20','長野県','ナガノケン','Nagano',36.651310,138.180991),('21','岐阜県','ギフケン','Gifu',35.391199,136.722168),('22','静岡県','シズオカケン','Shizuoka',34.976906,138.383023),('23','愛知県','アイチケン','Aichi',35.180198,136.906739),('24','三重県','ミエケン','Mie',34.730268,136.508594),('25','滋賀県','シガケン','Shiga',35.004394,135.868292),('26','京都府','キョウトフ','Kyoto',35.021279,135.755635),('27','大阪府','オオサカフ','Osaka',34.686394,135.519994),('28','兵庫県','ヒョウゴケン','Hyogo',34.691304,135.182995),('29','奈良県','ナラケン','Nara',34.685231,135.832883),('30','和歌山県','ワカヤマケン','Wakayama',34.225994,135.167450),('31','鳥取県','トットリケン','Tottori',35.503704,134.238174),('32','島根県','シマネケン','Shimane',35.472212,133.050530),('33','岡山県','オカヤマケン','Okayama',34.661759,133.934894),('34','広島県','ヒロシマケン','Hiroshima',34.396271,132.459369),('35','山口県','ヤマグチケン','Yamaguchi',34.185859,131.471401),('36','徳島県','トクシマケン','Tokushima',34.065728,134.559484),('37','香川県','カガワケン','Kagawa',34.340160,134.043390),('38','愛媛県','エヒメケン','Ehime',33.841646,132.766103),('39','高知県','コウチケン','Kochi',33.559753,133.531115),('40','福岡県','フクオカケン','Fukuoka',33.606261,130.418114),('41','佐賀県','サガケン','Saga',33.249322,130.298799),('42','長崎県','ナガサキケン','Nagasaki',32.749601,129.867969),('43','熊本県','クマモトケン','Kumamoto',32.790374,130.741134),('44','大分県','オオイタケン','Oita',33.238128,131.612605),('45','宮崎県','ミヤザキケン','Miyazaki',31.910975,131.423863),('46','鹿児島県','カゴシマケン','Kagoshima',31.560185,130.558141),('47','沖縄県','オキナワケン','Okinawa',26.212365,127.680975);
/*!40000 ALTER TABLE `prefectures` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `push_subscriptions`
--

DROP TABLE IF EXISTS `push_subscriptions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `push_subscriptions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `endpoint` varchar(1000) NOT NULL,
  `endpoint_hash` char(64) NOT NULL,
  `p256dh` varchar(120) NOT NULL,
  `auth` varchar(48) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_used_at` datetime DEFAULT NULL,
  `failure_count` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `endpoint_hash` (`endpoint_hash`),
  KEY `idx_push_user` (`user_id`),
  CONSTRAINT `fk_push_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `push_subscriptions`
--

LOCK TABLES `push_subscriptions` WRITE;
/*!40000 ALTER TABLE `push_subscriptions` DISABLE KEYS */;
/*!40000 ALTER TABLE `push_subscriptions` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `ranks`
--

DROP TABLE IF EXISTS `ranks`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ranks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(60) NOT NULL,
  `min_points` int NOT NULL,
  `featured_bonus` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`),
  UNIQUE KEY `min_points` (`min_points`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `ranks`
--

LOCK TABLES `ranks` WRITE;
/*!40000 ALTER TABLE `ranks` DISABLE KEYS */;
INSERT INTO `ranks` VALUES (1,'Người mới',0,0),(2,'Người chỉ đường',100,5),(3,'Senpai tin cậy',300,10);
/*!40000 ALTER TABLE `ranks` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `reason_options`
--

DROP TABLE IF EXISTS `reason_options`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reason_options` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `scope` varchar(20) NOT NULL,
  `label` varchar(120) NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_reason` (`scope`,`label`),
  CONSTRAINT `ck_reason_scope` CHECK ((`scope` in (_utf8mb4'REPORT',_utf8mb4'ACADEMIC_REPORT',_utf8mb4'PIN_REJECT',_utf8mb4'PLACE_REMOVE')))
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `reason_options`
--

LOCK TABLES `reason_options` WRITE;
/*!40000 ALTER TABLE `reason_options` DISABLE KEYS */;
INSERT INTO `reason_options` VALUES (1,'REPORT','Lừa đảo',1,1),(2,'REPORT','Sai sự thật',2,1),(3,'REPORT','Xúc phạm',3,1),(4,'REPORT','Spam',4,1),(5,'ACADEMIC_REPORT','Sai tiếng Nhật',1,1),(6,'ACADEMIC_REPORT','Sai nghĩa',2,1),(7,'ACADEMIC_REPORT','Xúc phạm',3,1),(8,'ACADEMIC_REPORT','Spam',4,1),(9,'PIN_REJECT','Không có thật hoặc sai vị trí',1,1),(10,'PIN_REJECT','Trùng địa điểm có sẵn',2,1),(11,'PIN_REJECT','Tên không rõ nghĩa hoặc spam',3,1),(12,'PIN_REJECT','Review đầu tiên vi phạm',4,1),(13,'PLACE_REMOVE','Không còn tồn tại',1,1),(14,'PLACE_REMOVE','Thông tin sai hoặc không có thật',2,1),(15,'PLACE_REMOVE','Vi phạm (lừa đảo, nội dung xấu)',3,1),(16,'PLACE_REMOVE','Chủ địa điểm yêu cầu gỡ',4,1);
/*!40000 ALTER TABLE `reason_options` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `report_cases`
--

DROP TABLE IF EXISTS `report_cases`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `report_cases` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `target_type` varchar(20) NOT NULL,
  `target_id` bigint NOT NULL,
  `handler_role` varchar(20) NOT NULL,
  `report_count` int NOT NULL DEFAULT '0',
  `priority` varchar(10) NOT NULL DEFAULT 'NORMAL',
  `status` varchar(20) NOT NULL DEFAULT 'OPEN',
  `decision` varchar(20) DEFAULT NULL,
  `decision_note` varchar(500) DEFAULT NULL,
  `handled_by` bigint DEFAULT NULL,
  `handled_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `open_flag` tinyint GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'OPEN') then 1 end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_open_case` (`target_type`,`target_id`,`open_flag`),
  KEY `fk_case_handler` (`handled_by`),
  CONSTRAINT `fk_case_handler` FOREIGN KEY (`handled_by`) REFERENCES `users` (`id`),
  CONSTRAINT `ck_case_decision` CHECK ((`decision` in (_utf8mb4'KEEP',_utf8mb4'REMOVE',_utf8mb4'CORRECTED'))),
  CONSTRAINT `ck_case_handler` CHECK ((`handler_role` in (_utf8mb4'MODERATOR',_utf8mb4'ACADEMIC_MANAGER'))),
  CONSTRAINT `ck_case_priority` CHECK ((`priority` in (_utf8mb4'NORMAL',_utf8mb4'URGENT'))),
  CONSTRAINT `ck_case_status` CHECK ((`status` in (_utf8mb4'OPEN',_utf8mb4'RESOLVED'))),
  CONSTRAINT `ck_case_target` CHECK ((`target_type` in (_utf8mb4'REVIEW',_utf8mb4'BLOG',_utf8mb4'COMMENT',_utf8mb4'PHRASE',_utf8mb4'MESSAGE')))
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `report_cases`
--

LOCK TABLES `report_cases` WRITE;
/*!40000 ALTER TABLE `report_cases` DISABLE KEYS */;
INSERT INTO `report_cases` (`id`, `target_type`, `target_id`, `handler_role`, `report_count`, `priority`, `status`, `decision`, `decision_note`, `handled_by`, `handled_at`, `created_at`) VALUES (1,'BLOG',1,'MODERATOR',1,'NORMAL','OPEN',NULL,NULL,NULL,NULL,'2026-10-21 11:53:30'),(2,'MESSAGE',11,'MODERATOR',1,'NORMAL','OPEN',NULL,NULL,NULL,NULL,'2026-10-28 04:20:39');
/*!40000 ALTER TABLE `report_cases` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `reports`
--

DROP TABLE IF EXISTS `reports`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reports` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `case_id` bigint NOT NULL,
  `reporter_id` bigint NOT NULL,
  `reason_id` bigint NOT NULL,
  `note` varchar(500) DEFAULT NULL,
  `is_false` tinyint(1) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_report_once` (`case_id`,`reporter_id`),
  KEY `fk_report_reporter` (`reporter_id`),
  KEY `fk_report_reason` (`reason_id`),
  CONSTRAINT `fk_report_case` FOREIGN KEY (`case_id`) REFERENCES `report_cases` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_report_reason` FOREIGN KEY (`reason_id`) REFERENCES `reason_options` (`id`),
  CONSTRAINT `fk_report_reporter` FOREIGN KEY (`reporter_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `reports`
--

LOCK TABLES `reports` WRITE;
/*!40000 ALTER TABLE `reports` DISABLE KEYS */;
INSERT INTO `reports` VALUES (1,1,1,4,NULL,NULL,'2026-10-21 11:53:30'),(2,2,1,1,'Thử báo cáo tin đã mã hóa',NULL,'2026-10-28 04:20:39');
/*!40000 ALTER TABLE `reports` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `review_likes`
--

DROP TABLE IF EXISTS `review_likes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `review_likes` (
  `review_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`review_id`,`user_id`),
  KEY `fk_rlike_user` (`user_id`),
  CONSTRAINT `fk_rlike_review` FOREIGN KEY (`review_id`) REFERENCES `reviews` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_rlike_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `review_likes`
--

LOCK TABLES `review_likes` WRITE;
/*!40000 ALTER TABLE `review_likes` DISABLE KEYS */;
/*!40000 ALTER TABLE `review_likes` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `review_media`
--

DROP TABLE IF EXISTS `review_media`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `review_media` (
  `review_id` bigint NOT NULL,
  `media_id` bigint NOT NULL,
  `sort_order` smallint NOT NULL DEFAULT '0',
  PRIMARY KEY (`review_id`,`media_id`),
  KEY `fk_rmedia_media` (`media_id`),
  CONSTRAINT `fk_rmedia_media` FOREIGN KEY (`media_id`) REFERENCES `media` (`id`),
  CONSTRAINT `fk_rmedia_review` FOREIGN KEY (`review_id`) REFERENCES `reviews` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `review_media`
--

LOCK TABLES `review_media` WRITE;
/*!40000 ALTER TABLE `review_media` DISABLE KEYS */;
/*!40000 ALTER TABLE `review_media` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `reviews`
--

DROP TABLE IF EXISTS `reviews`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reviews` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `author_id` bigint NOT NULL,
  `place_id` bigint DEFAULT NULL,
  `event_id` bigint DEFAULT NULL,
  `event_year` smallint DEFAULT NULL,
  `rating` tinyint NOT NULL,
  `content` varchar(2000) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PROCESSING',
  `is_edited` tinyint(1) NOT NULL DEFAULT '0',
  `removed_reason` varchar(255) DEFAULT NULL,
  `removed_at` datetime DEFAULT NULL,
  `purge_at` datetime DEFAULT NULL,
  `like_count` int NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_review_place` (`author_id`,`place_id`),
  UNIQUE KEY `uq_review_event` (`author_id`,`event_id`,`event_year`),
  KEY `idx_reviews_place` (`place_id`,`status`),
  KEY `idx_reviews_event` (`event_id`,`event_year`),
  KEY `idx_reviews_purge` (`purge_at`),
  CONSTRAINT `fk_reviews_author` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_reviews_event` FOREIGN KEY (`event_id`) REFERENCES `events` (`id`),
  CONSTRAINT `fk_reviews_place` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`),
  CONSTRAINT `ck_reviews_rating` CHECK ((`rating` between 1 and 5)),
  CONSTRAINT `ck_reviews_status` CHECK ((`status` in (_utf8mb4'PROCESSING',_utf8mb4'PUBLISHED',_utf8mb4'REJECTED',_utf8mb4'HIDDEN',_utf8mb4'REMOVED'))),
  CONSTRAINT `ck_reviews_target` CHECK (((`place_id` is null) <> (`event_id` is null))),
  CONSTRAINT `ck_reviews_year` CHECK (((`event_id` is null) or (`event_year` is not null)))
) ENGINE=InnoDB AUTO_INCREMENT=27 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `reviews`
--

LOCK TABLES `reviews` WRITE;
/*!40000 ALTER TABLE `reviews` DISABLE KEYS */;
INSERT INTO `reviews` VALUES (1,5,1,NULL,NULL,5,'Cổng Bắc gần trường hơn cổng Nam. Máy bán vé có nút đổi sang tiếng Anh ở góc trên bên phải.','PUBLISHED',0,NULL,NULL,NULL,14,'2026-09-26 08:39:38','2026-10-16 08:39:38'),(2,6,1,NULL,NULL,4,'Khoảng 8 giờ sáng rất đông, nên đi sớm 15 phút.','PUBLISHED',0,NULL,NULL,NULL,6,'2025-09-11 08:39:38','2026-10-16 08:39:38'),(3,5,2,NULL,NULL,5,'Tuần đầu nên đến sớm để làm thủ tục và nhận lịch học. Quanh trường có nhiều quán ăn giá sinh viên.','PUBLISHED',0,NULL,NULL,NULL,11,'2026-09-11 08:39:38','2026-10-16 08:39:38'),(4,6,2,NULL,NULL,4,'Mình mới nhập học tháng này. Các anh chị khóa trước chỉ đường rất kỹ nên đỡ bỡ ngỡ.','PUBLISHED',0,NULL,NULL,NULL,3,'2026-10-10 08:39:38','2026-10-16 08:39:38'),(5,6,3,NULL,NULL,5,'Nhân viên quen khách Việt và nói chậm. Có máy ATM rút được thẻ quốc tế.','PUBLISHED',0,NULL,NULL,NULL,9,'2026-10-01 08:39:38','2026-10-16 08:39:38'),(6,5,4,NULL,NULL,5,'Cơm phần 700 yên, chủ quán hiểu tiếng Việt cơ bản. Quán không có trên Google Maps nên mình cắm ghim.','PUBLISHED',0,NULL,NULL,NULL,8,'2026-09-06 08:39:38','2026-10-16 08:39:38'),(7,5,6,NULL,NULL,4,'Đăng ký cư trú ở tầng 1, lấy số ở máy cạnh cửa. Nhớ mang thẻ cư trú và hộ chiếu.','PUBLISHED',0,NULL,NULL,NULL,18,'2026-08-17 08:39:38','2026-10-16 08:39:38'),(8,6,8,NULL,NULL,4,'Gửi đồ về Việt Nam cần điền tờ khai bằng chữ Latin. Quầy có mẫu sẵn.','PUBLISHED',0,NULL,NULL,NULL,4,'2026-07-18 08:39:38','2026-10-16 08:39:38'),(9,5,10,NULL,NULL,4,'Ramen shoyu 950 yên, tăng 100 yên so với năm ngoái. Mua vé ở máy trước khi ngồi.','PUBLISHED',0,NULL,NULL,NULL,12,'2026-10-04 08:39:38','2026-10-16 08:39:38'),(25,1,10,NULL,NULL,5,'Ramen ngon, nước dùng đậm, phục vụ nhanh.','REJECTED',0,NULL,NULL,NULL,0,'2026-10-27 15:21:28','2026-10-27 15:21:28'),(26,1,17,NULL,NULL,5,'Địa điểm phù hợp với người Việt','PUBLISHED',0,NULL,NULL,NULL,0,'2026-10-31 07:16:04','2026-10-31 07:16:04');
/*!40000 ALTER TABLE `reviews` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `saved_blogs`
--

DROP TABLE IF EXISTS `saved_blogs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `saved_blogs` (
  `blog_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`blog_id`,`user_id`),
  KEY `fk_bsave_user` (`user_id`),
  CONSTRAINT `fk_bsave_blog` FOREIGN KEY (`blog_id`) REFERENCES `blogs` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_bsave_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `saved_blogs`
--

LOCK TABLES `saved_blogs` WRITE;
/*!40000 ALTER TABLE `saved_blogs` DISABLE KEYS */;
INSERT INTO `saved_blogs` VALUES (1,1,'2026-10-19 13:51:59'),(8,1,'2026-10-19 13:40:12'),(21,1,'2026-10-22 15:01:40');
/*!40000 ALTER TABLE `saved_blogs` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `saved_guides`
--

DROP TABLE IF EXISTS `saved_guides`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `saved_guides` (
  `user_id` bigint NOT NULL,
  `guide_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`,`guide_id`),
  KEY `idx_saved_guides_user` (`user_id`,`created_at`),
  KEY `fk_sguide_guide` (`guide_id`),
  CONSTRAINT `fk_sguide_guide` FOREIGN KEY (`guide_id`) REFERENCES `guide_items` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_sguide_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `saved_guides`
--

LOCK TABLES `saved_guides` WRITE;
/*!40000 ALTER TABLE `saved_guides` DISABLE KEYS */;
INSERT INTO `saved_guides` VALUES (1,7,'2026-11-07 01:24:36'),(13,1,'2026-11-07 06:11:51');
/*!40000 ALTER TABLE `saved_guides` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `saved_places`
--

DROP TABLE IF EXISTS `saved_places`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `saved_places` (
  `user_id` bigint NOT NULL,
  `place_id` bigint NOT NULL,
  `saved_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`,`place_id`),
  KEY `fk_savedp_place` (`place_id`),
  CONSTRAINT `fk_savedp_place` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_savedp_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `saved_places`
--

LOCK TABLES `saved_places` WRITE;
/*!40000 ALTER TABLE `saved_places` DISABLE KEYS */;
/*!40000 ALTER TABLE `saved_places` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `system_settings`
--

DROP TABLE IF EXISTS `system_settings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_settings` (
  `setting_key` varchar(50) NOT NULL,
  `setting_value` varchar(255) NOT NULL,
  `updated_by` bigint DEFAULT NULL,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`setting_key`),
  KEY `fk_setting_user` (`updated_by`),
  CONSTRAINT `fk_setting_user` FOREIGN KEY (`updated_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `system_settings`
--

LOCK TABLES `system_settings` WRITE;
/*!40000 ALTER TABLE `system_settings` DISABLE KEYS */;
INSERT INTO `system_settings` VALUES ('closed_report_threshold','5',NULL,'2026-10-16 08:39:32'),('duplicate_warn_radius_m','200',NULL,'2026-10-16 08:39:32'),('email_code_attempts','5',NULL,'2026-10-16 08:39:32'),('email_code_minutes','10',NULL,'2026-10-16 08:39:32'),('email_code_resend_seconds','60',NULL,'2026-10-16 08:39:32'),('false_report_max','5',NULL,'2026-10-16 08:39:32'),('featured_min_score','10',NULL,'2026-10-16 08:39:32'),('featured_top_n','3',NULL,'2026-10-16 08:39:32'),('lock_attempts','5',NULL,'2026-10-16 08:39:32'),('lock_minutes','30',NULL,'2026-10-16 08:39:32'),('log_keep_days','90',NULL,'2026-10-16 08:39:32'),('max_moderators','3',NULL,'2026-10-16 08:39:32'),('merge_suggest_radius_m','50',NULL,'2026-10-16 08:39:32'),('mute_days','7',NULL,'2026-10-16 08:39:32'),('new_account_days','7',NULL,'2026-10-16 08:39:32'),('password_min_length','8',NULL,'2026-10-16 08:39:32'),('pins_per_day','2',NULL,'2026-10-16 08:39:32'),('posts_per_day','3',NULL,'2026-10-16 08:39:32'),('proof_keep_days','14',NULL,'2026-10-16 08:39:32'),('register_ticket_minutes','30',NULL,'2026-10-16 08:39:32'),('removed_keep_days','30',NULL,'2026-10-16 08:39:32'),('report_hide_threshold','5',NULL,'2026-10-16 08:39:32'),('reset_token_minutes','30',NULL,'2026-10-16 08:39:32'),('verify_token_hours','24',NULL,'2026-10-16 08:39:32'),('videos_per_day','1',NULL,'2026-10-16 08:39:32');
/*!40000 ALTER TABLE `system_settings` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `tag_proof_types`
--

DROP TABLE IF EXISTS `tag_proof_types`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tag_proof_types` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tag_type_id` bigint NOT NULL,
  `name` varchar(120) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_proof_tag` (`tag_type_id`),
  CONSTRAINT `fk_proof_tag` FOREIGN KEY (`tag_type_id`) REFERENCES `tag_types` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `tag_proof_types`
--

LOCK TABLES `tag_proof_types` WRITE;
/*!40000 ALTER TABLE `tag_proof_types` DISABLE KEYS */;
INSERT INTO `tag_proof_types` VALUES (1,3,'Giấy đăng ký 登録日本語教員'),(2,3,'Chứng nhận đỗ 日本語教育能力検定試験'),(3,3,'Chứng nhận hoàn thành khóa 420 giờ'),(4,4,'Giấy chứng nhận kết quả JLPT'),(5,1,'Thẻ sinh viên (学生証)'),(6,1,'Giấy chứng nhận đang học (在学証明書)'),(7,2,'Giấy chứng nhận tốt nghiệp (卒業証明書)'),(8,2,'Thẻ nhân viên (社員証)'),(9,2,'Giấy xác nhận đang làm việc (在職証明書)'),(10,5,'Ảnh chân dung (selfie)');
/*!40000 ALTER TABLE `tag_proof_types` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `tag_requests`
--

DROP TABLE IF EXISTS `tag_requests`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tag_requests` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `tag_type_id` bigint NOT NULL,
  `proof_type_id` bigint NOT NULL,
  `proof_media_id` bigint DEFAULT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `reject_reason` varchar(255) DEFAULT NULL,
  `submitted_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at` datetime NOT NULL,
  `reviewed_by` bigint DEFAULT NULL,
  `reviewed_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_tagreq_user` (`user_id`),
  KEY `fk_tagreq_tag` (`tag_type_id`),
  KEY `fk_tagreq_proof` (`proof_type_id`),
  KEY `fk_tagreq_media` (`proof_media_id`),
  KEY `fk_tagreq_mod` (`reviewed_by`),
  CONSTRAINT `fk_tagreq_media` FOREIGN KEY (`proof_media_id`) REFERENCES `media` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_tagreq_mod` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_tagreq_proof` FOREIGN KEY (`proof_type_id`) REFERENCES `tag_proof_types` (`id`),
  CONSTRAINT `fk_tagreq_tag` FOREIGN KEY (`tag_type_id`) REFERENCES `tag_types` (`id`),
  CONSTRAINT `fk_tagreq_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_tagreq_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'APPROVED',_utf8mb4'REJECTED',_utf8mb4'EXPIRED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `tag_requests`
--

LOCK TABLES `tag_requests` WRITE;
/*!40000 ALTER TABLE `tag_requests` DISABLE KEYS */;
/*!40000 ALTER TABLE `tag_requests` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `tag_types`
--

DROP TABLE IF EXISTS `tag_types`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tag_types` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(60) NOT NULL,
  `description` varchar(255) NOT NULL,
  `color` varchar(20) NOT NULL,
  `selfie_only` tinyint(1) NOT NULL DEFAULT '0',
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  `created_by` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`),
  KEY `fk_tagtype_creator` (`created_by`),
  CONSTRAINT `fk_tagtype_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `tag_types`
--

LOCK TABLES `tag_types` WRITE;
/*!40000 ALTER TABLE `tag_types` DISABLE KEYS */;
INSERT INTO `tag_types` VALUES (1,'Kouhai','Đang học tại Nhật hoặc mới đến Nhật','#2E7D5B',0,1,NULL,'2026-10-16 08:39:32'),(2,'Mentor','Đã về nước hoặc đang sinh sống tại Nhật','#C2456E',0,1,NULL,'2026-10-16 08:39:32'),(3,'Giáo viên tiếng Nhật','Đang dạy tiếng Nhật','#E8A33C',0,1,NULL,'2026-10-16 08:39:32'),(4,'JLPT N2 trở lên','Có chứng chỉ JLPT N2 hoặc N1','#E8A33C',0,1,NULL,'2026-10-16 08:39:32'),(5,'Người Nhật','Người Nhật dùng Senpai ơi, chủ yếu là người quen của nhóm','#E8A33C',1,1,NULL,'2026-10-16 08:39:32');
/*!40000 ALTER TABLE `tag_types` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_auth_providers`
--

DROP TABLE IF EXISTS `user_auth_providers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_auth_providers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `provider` varchar(20) NOT NULL,
  `provider_user_id` varchar(255) NOT NULL,
  `linked_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_provider_user` (`provider`,`provider_user_id`),
  KEY `fk_auth_user` (`user_id`),
  CONSTRAINT `fk_auth_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_auth_provider` CHECK ((`provider` in (_utf8mb4'GOOGLE',_utf8mb4'FACEBOOK')))
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_auth_providers`
--

LOCK TABLES `user_auth_providers` WRITE;
/*!40000 ALTER TABLE `user_auth_providers` DISABLE KEYS */;
INSERT INTO `user_auth_providers` VALUES (1,13,'GOOGLE','114690907459202692756','2026-11-07 11:00:25'),(2,8,'GOOGLE','107205923088269845062','2026-11-07 11:11:40');
/*!40000 ALTER TABLE `user_auth_providers` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_consents`
--

DROP TABLE IF EXISTS `user_consents`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_consents` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `document_id` bigint NOT NULL,
  `accepted_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_consent_user` (`user_id`),
  KEY `fk_consent_doc` (`document_id`),
  CONSTRAINT `fk_consent_doc` FOREIGN KEY (`document_id`) REFERENCES `legal_documents` (`id`),
  CONSTRAINT `fk_consent_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=37 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_consents`
--

LOCK TABLES `user_consents` WRITE;
/*!40000 ALTER TABLE `user_consents` DISABLE KEYS */;
INSERT INTO `user_consents` VALUES (1,1,1,'2026-10-16 08:39:38'),(2,1,2,'2026-10-16 08:39:38'),(3,1,3,'2026-10-16 08:39:38'),(4,2,1,'2026-10-16 08:39:38'),(5,2,2,'2026-10-16 08:39:38'),(6,2,3,'2026-10-16 08:39:38'),(7,3,1,'2026-10-16 08:39:38'),(8,3,2,'2026-10-16 08:39:38'),(9,3,3,'2026-10-16 08:39:38'),(10,4,1,'2026-10-16 08:39:38'),(11,4,2,'2026-10-16 08:39:38'),(12,4,3,'2026-10-16 08:39:38'),(13,5,1,'2026-10-16 08:39:38'),(14,5,2,'2026-10-16 08:39:38'),(15,5,3,'2026-10-16 08:39:38'),(16,6,1,'2026-10-16 08:39:38'),(17,6,2,'2026-10-16 08:39:38'),(18,6,3,'2026-10-16 08:39:38'),(19,7,1,'2026-10-29 14:08:14'),(20,7,2,'2026-10-29 14:08:14'),(21,7,3,'2026-10-29 14:08:14'),(22,8,1,'2026-10-29 14:16:13'),(23,8,2,'2026-10-29 14:16:13'),(24,8,3,'2026-10-29 14:16:13'),(25,10,1,'2026-11-07 01:06:57'),(26,10,2,'2026-11-07 01:06:57'),(27,10,3,'2026-11-07 01:06:57'),(28,11,1,'2026-11-07 01:41:09'),(29,11,2,'2026-11-07 01:41:09'),(30,11,3,'2026-11-07 01:41:09'),(31,12,1,'2026-11-07 05:58:32'),(32,12,2,'2026-11-07 05:58:32'),(33,12,3,'2026-11-07 05:58:32'),(34,13,1,'2026-11-07 06:09:16'),(35,13,2,'2026-11-07 06:09:16'),(36,13,3,'2026-11-07 06:09:16');
/*!40000 ALTER TABLE `user_consents` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_daily_usage`
--

DROP TABLE IF EXISTS `user_daily_usage`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_daily_usage` (
  `user_id` bigint NOT NULL,
  `usage_date` date NOT NULL,
  `posts` smallint NOT NULL DEFAULT '0',
  `pins` smallint NOT NULL DEFAULT '0',
  `videos` smallint NOT NULL DEFAULT '0',
  PRIMARY KEY (`user_id`,`usage_date`),
  CONSTRAINT `fk_usage_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_daily_usage`
--

LOCK TABLES `user_daily_usage` WRITE;
/*!40000 ALTER TABLE `user_daily_usage` DISABLE KEYS */;
INSERT INTO `user_daily_usage` VALUES (1,'2026-10-18',2,0,0),(1,'2026-10-19',2,0,0),(1,'2026-10-21',5,0,0),(1,'2026-10-22',1,0,0),(1,'2026-10-23',2,0,0),(1,'2026-10-24',36,0,9),(1,'2026-10-27',1,0,1),(1,'2026-10-28',6,0,0),(1,'2026-10-30',0,0,0),(1,'2026-10-31',3,0,1),(1,'2026-11-07',0,0,0),(5,'2026-10-19',2,0,0),(5,'2026-10-21',1,0,0),(5,'2026-10-22',0,0,0),(5,'2026-10-24',5,0,0),(5,'2026-10-28',0,0,0),(6,'2026-10-22',1,0,0),(6,'2026-10-28',0,0,0),(6,'2026-10-29',0,0,0),(6,'2026-10-30',0,0,0),(6,'2026-11-07',0,0,0),(13,'2026-11-07',1,0,0);
/*!40000 ALTER TABLE `user_daily_usage` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_guide_progress`
--

DROP TABLE IF EXISTS `user_guide_progress`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_guide_progress` (
  `user_id` bigint NOT NULL,
  `item_id` bigint NOT NULL,
  `done_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`,`item_id`),
  KEY `fk_gprog_item` (`item_id`),
  CONSTRAINT `fk_gprog_item` FOREIGN KEY (`item_id`) REFERENCES `guide_items` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_gprog_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_guide_progress`
--

LOCK TABLES `user_guide_progress` WRITE;
/*!40000 ALTER TABLE `user_guide_progress` DISABLE KEYS */;
INSERT INTO `user_guide_progress` VALUES (1,1,'2026-10-16 08:39:39'),(1,2,'2026-10-16 08:39:39'),(1,5,'2026-11-07 10:38:49'),(1,6,'2026-11-07 01:23:12'),(1,7,'2026-11-07 10:38:55'),(1,8,'2026-11-07 10:39:02'),(1,9,'2026-11-07 01:23:18'),(1,10,'2026-11-07 10:39:07'),(1,23,'2026-11-07 01:01:06'),(1,25,'2026-11-07 01:21:01'),(10,1,'2026-11-07 01:12:24'),(10,2,'2026-11-07 01:12:34'),(10,3,'2026-11-07 01:12:49'),(10,4,'2026-11-07 01:12:56'),(12,1,'2026-11-07 06:02:59'),(12,2,'2026-11-07 06:03:09'),(12,3,'2026-11-07 06:03:14'),(12,4,'2026-11-07 06:03:19');
/*!40000 ALTER TABLE `user_guide_progress` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_interest_topics`
--

DROP TABLE IF EXISTS `user_interest_topics`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_interest_topics` (
  `user_id` bigint NOT NULL,
  `topic_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`,`topic_id`),
  KEY `idx_uit_topic` (`topic_id`),
  CONSTRAINT `fk_uit_topic` FOREIGN KEY (`topic_id`) REFERENCES `blog_topics` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_uit_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_interest_topics`
--

LOCK TABLES `user_interest_topics` WRITE;
/*!40000 ALTER TABLE `user_interest_topics` DISABLE KEYS */;
INSERT INTO `user_interest_topics` VALUES (12,1,'2026-11-07 05:59:49'),(12,2,'2026-11-07 05:59:49'),(12,3,'2026-11-07 05:59:49'),(13,1,'2026-11-07 06:09:51'),(13,2,'2026-11-07 06:09:51'),(13,4,'2026-11-07 06:09:51');
/*!40000 ALTER TABLE `user_interest_topics` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_phrase_marks`
--

DROP TABLE IF EXISTS `user_phrase_marks`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_phrase_marks` (
  `user_id` bigint NOT NULL,
  `phrase_id` bigint NOT NULL,
  `is_learned` tinyint(1) NOT NULL DEFAULT '0',
  `is_learning` tinyint(1) NOT NULL DEFAULT '0',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`,`phrase_id`),
  KEY `fk_mark_phrase` (`phrase_id`),
  CONSTRAINT `fk_mark_phrase` FOREIGN KEY (`phrase_id`) REFERENCES `phrases` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_mark_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_mark_card` CHECK (((0 = `is_learned`) or (0 = `is_learning`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_phrase_marks`
--

LOCK TABLES `user_phrase_marks` WRITE;
/*!40000 ALTER TABLE `user_phrase_marks` DISABLE KEYS */;
/*!40000 ALTER TABLE `user_phrase_marks` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_tags`
--

DROP TABLE IF EXISTS `user_tags`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_tags` (
  `user_id` bigint NOT NULL,
  `tag_type_id` bigint NOT NULL,
  `request_id` bigint DEFAULT NULL,
  `granted_by` bigint NOT NULL,
  `granted_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `revoked_at` datetime DEFAULT NULL,
  PRIMARY KEY (`user_id`,`tag_type_id`),
  KEY `fk_usertag_tag` (`tag_type_id`),
  KEY `fk_usertag_request` (`request_id`),
  KEY `fk_usertag_granter` (`granted_by`),
  CONSTRAINT `fk_usertag_granter` FOREIGN KEY (`granted_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_usertag_request` FOREIGN KEY (`request_id`) REFERENCES `tag_requests` (`id`),
  CONSTRAINT `fk_usertag_tag` FOREIGN KEY (`tag_type_id`) REFERENCES `tag_types` (`id`),
  CONSTRAINT `fk_usertag_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_tags`
--

LOCK TABLES `user_tags` WRITE;
/*!40000 ALTER TABLE `user_tags` DISABLE KEYS */;
INSERT INTO `user_tags` VALUES (3,3,NULL,3,'2026-10-16 08:39:38',NULL),(3,5,NULL,3,'2026-10-16 08:39:38',NULL),(5,2,NULL,5,'2026-10-16 08:39:38',NULL),(6,1,NULL,6,'2026-10-16 08:39:38',NULL);
/*!40000 ALTER TABLE `user_tags` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_tokens`
--

DROP TABLE IF EXISTS `user_tokens`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `purpose` varchar(20) NOT NULL,
  `token_hash` char(64) NOT NULL,
  `expires_at` datetime NOT NULL,
  `used_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `token_hash` (`token_hash`),
  KEY `idx_tokens_user` (`user_id`,`purpose`),
  CONSTRAINT `fk_tokens_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_tokens_purpose` CHECK ((`purpose` in (_utf8mb4'VERIFY_EMAIL',_utf8mb4'RESET_PASSWORD')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_tokens`
--

LOCK TABLES `user_tokens` WRITE;
/*!40000 ALTER TABLE `user_tokens` DISABLE KEYS */;
/*!40000 ALTER TABLE `user_tokens` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `users`
--

DROP TABLE IF EXISTS `users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(255) NOT NULL,
  `password_hash` varchar(255) DEFAULT NULL,
  `email_verified_at` datetime DEFAULT NULL,
  `failed_login_count` tinyint NOT NULL DEFAULT '0',
  `locked_until` datetime DEFAULT NULL,
  `display_name` varchar(60) NOT NULL,
  `avatar_url` varchar(500) DEFAULT NULL,
  `role` varchar(20) NOT NULL DEFAULT 'USER',
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `is_adult_confirmed` tinyint(1) NOT NULL,
  `area_id` bigint DEFAULT NULL,
  `stage` varchar(10) DEFAULT NULL,
  `event_window_days` smallint NOT NULL DEFAULT '30',
  `points` int NOT NULL DEFAULT '0',
  `false_report_count` int NOT NULL DEFAULT '0',
  `report_muted_until` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `last_login_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `email` (`email`),
  KEY `fk_users_area` (`area_id`),
  CONSTRAINT `fk_users_area` FOREIGN KEY (`area_id`) REFERENCES `areas` (`id`),
  CONSTRAINT `ck_users_role` CHECK ((`role` in (_utf8mb4'USER',_utf8mb4'MODERATOR',_utf8mb4'ACADEMIC_MANAGER',_utf8mb4'ADMIN'))),
  CONSTRAINT `ck_users_stage` CHECK ((`stage` in (_utf8mb4'PRE',_utf8mb4'ARR',_utf8mb4'LEAVE',_utf8mb4'HOME'))),
  CONSTRAINT `ck_users_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'LOCKED',_utf8mb4'DELETED'))),
  CONSTRAINT `ck_users_window` CHECK ((`event_window_days` in (15,30)))
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `users`
--

LOCK TABLES `users` WRITE;
/*!40000 ALTER TABLE `users` DISABLE KEYS */;
INSERT INTO `users` VALUES (1,'user@gmail.com','$2a$10$ctwFggIsTO3nQTVd8XyIJ.D9.H.Mq1aTNygJnyEVyenc5lfBuKueW','2026-10-16 08:39:38',0,NULL,'Trần Minh',NULL,'USER','ACTIVE',1,679,'ARR',30,135,0,NULL,'2026-10-13 08:39:38','2026-11-07 10:41:32','2026-11-07 10:41:32',NULL),(2,'moderator@gmail.com','$2a$10$q3p0nKYvV8bk7z8Kj340q.zRosmo.V4YEd96owBZN8IXQ3VbSsZRe','2026-10-16 08:39:38',0,NULL,'Thu',NULL,'MODERATOR','ACTIVE',1,NULL,NULL,30,0,0,NULL,'2025-12-20 08:39:38','2026-11-06 18:28:44','2026-11-06 18:28:45',NULL),(3,'academic@gmail.com','$2a$10$rdDVkeUAit6fwTVv5.5IgudJB20KBjHeINMQYTaGkd/xJE35DsdRS','2026-10-16 08:39:38',0,NULL,'Cô Yuki',NULL,'ACADEMIC_MANAGER','ACTIVE',1,NULL,NULL,30,0,0,NULL,'2025-12-20 08:39:38','2026-11-06 18:40:54','2026-11-06 18:40:55',NULL),(4,'admin@gmail.com','$2a$10$M.i2OsTtjF5KMqf1nu1/geKJI.nGxeLBAd1trKAjVBxor7W0SBFS2','2026-10-16 08:39:38',0,NULL,'Quản trị viên',NULL,'ADMIN','ACTIVE',1,NULL,NULL,30,0,0,NULL,'2025-12-20 08:39:38','2026-10-29 06:57:54','2026-10-29 06:57:54',NULL),(5,'mentor@gmail.com','$2a$10$AcHlPmTzkzuSWn1rwYbsb..n1M64b4K6.lyF1vys0wvCHCpgZiDRe','2026-10-16 08:39:38',0,NULL,'Lan Anh',NULL,'USER','ACTIVE',1,679,'ARR',30,430,0,NULL,'2025-09-11 08:39:38','2026-10-31 08:42:10','2026-10-31 08:42:11',NULL),(6,'kouhai@gmail.com','$2a$10$i.40hPHQeG4LFnT56XlpaO9SsI4mFp9nY9FQThGvfvcRVz1X9gpMS','2026-10-16 08:39:38',0,NULL,'Hoàng',NULL,'USER','ACTIVE',1,679,'ARR',30,135,0,NULL,'2026-09-06 08:39:38','2026-11-06 18:34:19','2026-11-06 18:33:33',NULL),(7,'kiemtra.dangky.1793282893@example.com','$2a$10$85915UT2rWUG6Q95Hql4d.jdNoi6mqxU7yKANDYxI0GNnnDd5FxU2','2026-10-29 14:08:13',0,NULL,'Kiểm tra đăng ký',NULL,'USER','ACTIVE',1,NULL,NULL,30,0,0,NULL,'2026-10-29 14:08:13','2026-10-29 14:08:13','2026-10-29 14:08:14',NULL),(8,'duct4500@gmail.com','$2a$10$mBJ6CuxJMvBpCl1twrzlku.98s01E/sfHpBdGXVzgtwVorTydR6XW','2026-10-29 14:15:46',0,NULL,'Đức Tổng',NULL,'USER','ACTIVE',1,678,'ARR',30,0,0,NULL,'2026-10-29 14:16:13','2026-11-07 11:11:40','2026-11-07 11:11:40',NULL),(9,'seed-content@local.invalid',NULL,NULL,0,NULL,'Dữ liệu mẫu',NULL,'USER','ACTIVE',1,NULL,NULL,30,0,0,NULL,'2026-11-05 19:50:18','2026-11-05 19:50:18',NULL,NULL),(10,'fux34920@laoia.com','$2a$10$9fmrlKe4EgqWMf202PeO.epT9s8Ii6gH/CFclHJkhBzGh0PgkMdkK','2026-11-07 01:06:30',0,NULL,'Long Hoàng',NULL,'USER','ACTIVE',1,679,'ARR',30,0,0,NULL,'2026-11-07 01:06:56','2026-11-07 01:13:53','2026-11-07 01:07:01',NULL),(11,'egy06211@laoia.com','$2a$10$ivcnlRiLx9fppECaTWNtXuuRG8HSY60nncZq0bNw5OGq.kI7ayYIC','2026-11-07 01:40:59',0,NULL,'ducthang123',NULL,'USER','ACTIVE',1,NULL,NULL,30,0,0,NULL,'2026-11-07 01:41:09','2026-11-07 01:41:13','2026-11-07 01:41:14',NULL),(12,'ynq67250@laoia.com','$2a$10$2eDBUXWEctims9t.BUKJkOR2a/3djnOOphQq0MsvhaLpP4.sDunGa','2026-11-07 05:58:12',0,NULL,'Temu',NULL,'USER','ACTIVE',1,679,'PRE',30,0,0,NULL,'2026-11-07 05:58:31','2026-11-07 05:59:50','2026-11-07 05:58:36',NULL),(13,'ductqhe181164@fpt.edu.vn','$2a$10$pNX1BrGWzMST7pdylVBY1e.qzJbhhpa5sSaKoDkqDjBWwR3oUfYrm','2026-11-07 06:09:04',0,NULL,'LH',NULL,'USER','ACTIVE',1,679,'PRE',30,10,0,NULL,'2026-11-07 06:09:15','2026-11-07 11:10:57','2026-11-07 11:10:57',NULL);
/*!40000 ALTER TABLE `users` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Temporary view structure for view `v_place_rating`
--

DROP TABLE IF EXISTS `v_place_rating`;
/*!50001 DROP VIEW IF EXISTS `v_place_rating`*/;
SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `v_place_rating` AS SELECT 
 1 AS `place_id`,
 1 AS `review_count`,
 1 AS `avg_rating`*/;
SET character_set_client = @saved_cs_client;

--
-- Table structure for table `web_push_keys`
--

DROP TABLE IF EXISTS `web_push_keys`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `web_push_keys` (
  `id` tinyint NOT NULL,
  `public_key` varchar(120) NOT NULL,
  `private_key` varchar(64) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  CONSTRAINT `ck_web_push_keys_one` CHECK ((`id` = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `web_push_keys`
--

LOCK TABLES `web_push_keys` WRITE;
/*!40000 ALTER TABLE `web_push_keys` DISABLE KEYS */;
INSERT INTO `web_push_keys` VALUES (1,'BCdH7uz0Hg_gwbhCFgk0g_B786WpKZpSq4EiUXLQJvLX-QdjhFvWr7CqPVaWhjK0hsalBt6aRWitcfcO6_zgAuc','WO_JYfECpG6JwdNA2pGeDlc5UO2mP6kA_Ku2saMPxCI','2026-10-29 07:10:32');
/*!40000 ALTER TABLE `web_push_keys` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Final view structure for view `v_place_rating`
--

/*!50001 DROP VIEW IF EXISTS `v_place_rating`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`root`@`localhost` SQL SECURITY DEFINER */
/*!50001 VIEW `v_place_rating` AS select `p`.`id` AS `place_id`,count(`r`.`id`) AS `review_count`,round(avg(`r`.`rating`),1) AS `avg_rating` from (`places` `p` left join `reviews` `r` on(((`r`.`place_id` = `p`.`id`) and (`r`.`status` = 'PUBLISHED')))) group by `p`.`id` */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-11-09 23:49:21
