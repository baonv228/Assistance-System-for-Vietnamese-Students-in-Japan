-- The map and the place search use OpenStreetMap (Leaflet + OSM tiles, Photon search, Nominatim lookup) instead of
-- Google Maps Platform, which does not serve Vietnam (a "prohibited territory"), where the team works and presents.
-- OSM data may be stored (ODbL, with the "© OpenStreetMap contributors" credit), so OSM places keep their names,
-- coordinates and address. Google places already stored keep their google_place_id.

-- OSM element of the place: N (node), W (way) or R (relation) + id, e.g. "N772389775" for Nippori station
ALTER TABLE places ADD COLUMN osm_id VARCHAR(30) NULL AFTER google_place_id;
ALTER TABLE places ADD CONSTRAINT uq_places_osm UNIQUE (osm_id);

ALTER TABLE places DROP CHECK ck_places_source;
ALTER TABLE places ADD CONSTRAINT ck_places_source CHECK (source IN ('GOOGLE','OSM','TEAM','USER','MODERATOR'));

-- OSM tag (key=value) → ASVSJ category. A tag with no row here goes to "other" (Địa điểm khác, V3).
CREATE TABLE category_osm_tags (
  osm_tag      VARCHAR(80) PRIMARY KEY,
  category_id  VARCHAR(20) NOT NULL,
  CONSTRAINT fk_cot_cat FOREIGN KEY (category_id) REFERENCES place_categories(id)
) ENGINE=InnoDB;

INSERT INTO category_osm_tags (osm_tag, category_id) VALUES
 ('shop=convenience', 'konbini'),
 ('amenity=restaurant', 'food'), ('amenity=cafe', 'food'), ('amenity=fast_food', 'food'), ('amenity=food_court', 'food'),
 ('amenity=pub', 'food'), ('amenity=ice_cream', 'food'), ('shop=bakery', 'food'),
 ('railway=station', 'station'), ('railway=halt', 'station'), ('public_transport=station', 'station'),
 ('building=train_station', 'station'),
 ('shop=supermarket', 'super'), ('shop=department_store', 'super'), ('shop=mall', 'super'),
 ('shop=variety_store', 'super'), ('shop=general', 'super'),
 ('amenity=townhall', 'ward'), ('office=government', 'ward'),
 ('amenity=hospital', 'hospital'), ('amenity=clinic', 'hospital'), ('amenity=doctors', 'hospital'),
 ('amenity=dentist', 'hospital'), ('healthcare=hospital', 'hospital'), ('healthcare=clinic', 'hospital'),
 ('healthcare=doctor', 'hospital'), ('healthcare=dentist', 'hospital'),
 ('amenity=pharmacy', 'pharmacy'), ('shop=chemist', 'pharmacy'), ('healthcare=pharmacy', 'pharmacy'),
 ('amenity=bank', 'bank'), ('amenity=atm', 'bank'), ('amenity=post_office', 'bank'), ('amenity=bureau_de_change', 'bank'),
 ('amenity=school', 'school'), ('amenity=university', 'school'), ('amenity=college', 'school'),
 ('amenity=language_school', 'school'),
 ('shop=laundry', 'laundry'), ('shop=dry_cleaning', 'laundry'),
 ('leisure=park', 'park'), ('leisure=garden', 'park'),
 ('amenity=place_of_worship', 'temple'),
 ('shop=mobile_phone', 'phone'), ('shop=electronics', 'phone'), ('shop=telecommunication', 'phone'),
 ('office=estate_agent', 'housing'), ('shop=estate_agent', 'housing');
