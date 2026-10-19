-- Places picked from Google Maps (Write Post, Đặt câu hỏi, and later Write Review / Map) are stored with a category
-- taken from their Google types through category_google_types. Places whose types fit none of the categories go to
-- the new category "other" (agreed with the team), and the common Google types below now map to a category.
-- Type names are those of Places API (New), Table A.

INSERT INTO place_categories (id, name_vi, icon, sort_order) VALUES
 ('other', 'Địa điểm khác', 'place', 99);

INSERT INTO category_google_types (google_type, category_id) VALUES
 -- stations (not transit_station: bus stops carry it too)
 ('subway_station', 'station'), ('light_rail_station', 'station'),
 -- food and drink
 ('cafe', 'food'), ('coffee_shop', 'food'), ('bakery', 'food'), ('meal_takeaway', 'food'), ('meal_delivery', 'food'),
 ('fast_food_restaurant', 'food'), ('food_court', 'food'), ('ramen_restaurant', 'food'), ('japanese_restaurant', 'food'),
 ('sushi_restaurant', 'food'), ('vietnamese_restaurant', 'food'), ('chinese_restaurant', 'food'),
 ('korean_restaurant', 'food'), ('thai_restaurant', 'food'), ('indian_restaurant', 'food'),
 ('hamburger_restaurant', 'food'), ('pizza_restaurant', 'food'),
 -- shops
 ('grocery_store', 'super'), ('asian_grocery_store', 'super'), ('department_store', 'super'),
 ('discount_store', 'super'), ('market', 'super'),
 ('cell_phone_store', 'phone'), ('electronics_store', 'phone'),
 -- offices, health, money, schools
 ('local_government_office', 'ward'), ('government_office', 'ward'),
 ('dental_clinic', 'hospital'), ('drugstore', 'pharmacy'), ('atm', 'bank'),
 ('university', 'school'), ('primary_school', 'school'), ('secondary_school', 'school'),
 -- places to visit
 ('national_park', 'park'), ('buddhist_temple', 'temple'), ('shinto_shrine', 'temple'), ('hindu_temple', 'temple'),
 -- housing
 ('real_estate_agency', 'housing');
