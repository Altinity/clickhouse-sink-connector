db.createUser({
  user: 'project',
  pwd: 'project',
  roles: [
    {
      role: 'readWrite',
      db: 'project',
    },
  ],
});

db = new Mongo().getDB('project');

db.createCollection('users', { capped: false });
db.createCollection('items', { capped: false });
db.createCollection('airbnb', {capped: false});

db.airbnb.insert({
                   "_id": "10006546",
                   "listing_url": "https://www.airbnb.com/rooms/10006546",
                   "name": "Ribeira Charming Duplex",
                   "summary": "Fantastic duplex apartment with three bedrooms, located in the historic area of Porto, Ribeira (Cube)...",
                   "interaction": "Cot - 10 € / night Dog - € 7,5 / night",
                   "house_rules": "Make the house your home...",
                   "property_type": "House",
                   "room_type": "Entire home/apt",
                   "bed_type": "Real Bed",
                   "minimum_nights": "2",
                   "maximum_nights": "30",
                   "cancellation_policy": "moderate",
                   "last_scraped": {
                     "$date": {
                       "$numberLong": "1550293200000"
                     }
                   },
                   "calendar_last_scraped": {
                     "$date": {
                       "$numberLong": "1550293200000"
                     }
                   },
                   "first_review": {
                     "$date": {
                       "$numberLong": "1451797200000"
                     }
                   },
                   "last_review": {
                     "$date": {
                       "$numberLong": "1547960400000"
                     }
                   },
                   "accommodates": {
                     "$numberInt": "8"
                   },
                   "bedrooms": {
                     "$numberInt": "3"
                   },
                   "beds": {
                     "$numberInt": "5"
                   },
                   "number_of_reviews": {
                     "$numberInt": "51"
                   },
                   "bathrooms": {
                     "$numberDecimal": "1.0"
                   },
                   "amenities": [
                     "TV",
                     "Cable TV",
                     "Wifi",
                     "Kitchen",
                     "Paid parking off premises",
                     "Smoking allowed",
                     "Pets allowed",
                     "Buzzer/wireless intercom",
                     "Heating",
                     "Family/kid friendly",
                     "Washer",
                     "First aid kit",
                     "Fire extinguisher",
                     "Essentials",
                     "Hangers",
                     "Hair dryer",
                     "Iron",
                     "Pack ’n Play/travel crib",
                     "Room-darkening shades",
                     "Hot water",
                     "Bed linens",
                     "Extra pillows and blankets",
                     "Microwave",
                     "Coffee maker",
                     "Refrigerator",
                     "Dishwasher",
                     "Dishes and silverware",
                     "Cooking basics",
                     "Oven",
                     "Stove",
                     "Cleaning before checkout",
                     "Waterfront"
                   ],
                   "price": {
                     "$numberDecimal": "80.00"
                   },
                   "security_deposit": {
                     "$numberDecimal": "200.00"
                   },
                   "cleaning_fee": {
                     "$numberDecimal": "35.00"
                   },
                   "extra_people": {
                     "$numberDecimal": "15.00"
                   },
                   "guests_included": {
                     "$numberDecimal": "6"
                   },
                   "images": {
                     "thumbnail_url": "",
                     "medium_url": "",
                     "picture_url": "https://a0.muscache.com/im/pictures/e83e702f-ef49-40fb-8fa0-6512d7e26e9b.jpg?aki_policy=large",
                     "xl_picture_url": ""
                   },
                   "host": {
                     "host_id": "51399391",
                     "host_url": "https://www.airbnb.com/users/show/51399391",
                     "host_name": "Ana&Gonçalo",
                     "host_location": "Porto, Porto District, Portugal",
                     "host_about": "Gostamos de passear, de viajar, de conhecer pessoas e locais novos, gostamos de desporto e animais! Vivemos na cidade mais linda do mundo!!!",
                     "host_response_time": "within an hour",
                     "host_thumbnail_url": "https://a0.muscache.com/im/pictures/fab79f25-2e10-4f0f-9711-663cb69dc7d8.jpg?aki_policy=profile_small",
                     "host_picture_url": "https://a0.muscache.com/im/pictures/fab79f25-2e10-4f0f-9711-663cb69dc7d8.jpg?aki_policy=profile_x_medium",
                     "host_neighbourhood": "",
                     "host_response_rate": {
                       "$numberInt": "100"
                     },
                     "host_is_superhost": false,
                     "host_has_profile_pic": true,
                     "host_identity_verified": true,
                     "host_listings_count": {
                       "$numberInt": "3"
                     },
                     "host_total_listings_count": {
                       "$numberInt": "3"
                     },
                     "host_verifications": [
                       "email",
                       "phone",
                       "reviews",
                       "jumio",
                       "offline_government_id",
                       "government_id"
                     ]
                   },
                   "address": {
                     "street": "Porto, Porto, Portugal",
                     "suburb": "",
                     "government_area": "Cedofeita, Ildefonso, Sé, Miragaia, Nicolau, Vitória",
                     "market": "Porto",
                     "country": "Portugal",
                     "country_code": "PT",
                     "location": {
                       "type": "Point",
                       "coordinates": [
                         {
                           "$numberDouble": "-8.61308"
                         },
                         {
                           "$numberDouble": "41.1413"
                         }
                       ],
                       "is_location_exact": false
                     }
                   },
                   "availability": {
                     "availability_30": {
                       "$numberInt": "28"
                     },
                     "availability_60": {
                       "$numberInt": "47"
                     },
                     "availability_90": {
                       "$numberInt": "74"
                     },
                     "availability_365": {
                       "$numberInt": "239"
                     }
                   },
                   "review_scores": {
                     "review_scores_accuracy": {
                       "$numberInt": "9"
                     },
                     "review_scores_cleanliness": {
                       "$numberInt": "9"
                     },
                     "review_scores_checkin": {
                       "$numberInt": "10"
                     },
                     "review_scores_communication": {
                       "$numberInt": "10"
                     },
                     "review_scores_location": {
                       "$numberInt": "10"
                     },
                     "review_scores_value": {
                       "$numberInt": "9"
                     },
                     "review_scores_rating": {
                       "$numberInt": "89"
                     }
                   },
                   "reviews": [
                     {
                       "_id": "362865132",
                       "date": {
                         "$date": {
                           "$numberLong": "1545886800000"
                         }
                       },
                       "listing_id": "10006546",
                       "reviewer_id": "208880077",
                       "reviewer_name": "Thomas",
                       "comments": "Very helpful hosts. Cooked traditional..."
                     },
                     {
                       "_id": "364728730",
                       "date": {
                         "$date": {
                           "$numberLong": "1546232400000"
                         }
                       },
                       "listing_id": "10006546",
                       "reviewer_id": "91827533",
                       "reviewer_name": "Mr",
                       "comments": "Ana & Goncalo were great on communication..."
                     },
                     {
                       "_id": "403055315",
                       "date": {
                         "$date": {
                           "$numberLong": "1547960400000"
                         }
                       },
                       "listing_id": "10006546",
                       "reviewer_id": "15138940",
                       "reviewer_name": "Milo",
                       "comments": "The house was extremely well located..."
                     }
                   ]
                 });

//db.items.insert([
//  {
//    uuid: '28105d81-dac5-48a4-b70d-a40b2882a719',
//    price: '49',
//    name: 'T-Shirt',
//  },
//  {
//    uuid: '49b63da2-aa02-4a04-a7f1-62d2da389897',
//    price: '89',
//    name: 'Jeans',
//  },
//  {
//    uuid: 'f0038e77-dc96-4236-a979-f06b993b0332',
//    price: '120',
//    name: 'Jacket',
//  },
//]);