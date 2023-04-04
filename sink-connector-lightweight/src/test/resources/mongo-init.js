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

db.items.insert([
  {
    uuid: '28105d81-dac5-48a4-b70d-a40b2882a719',
    price: '49.00',
    name: 'T-Shirt',
  },
  {
    uuid: '49b63da2-aa02-4a04-a7f1-62d2da389897',
    price: '89.99',
    name: 'Jeans',
  },
  {
    uuid: 'f0038e77-dc96-4236-a979-f06b993b0332',
    price: '120.00',
    name: 'Jacket',
  },
]);