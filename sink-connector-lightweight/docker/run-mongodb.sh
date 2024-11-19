#!/bin/bash
mongosh <<EOF
db = connect("mongodb://root:example@localhost:27017/admin");
rs.initiate({
  _id: "rs0",
  members: [
    { _id: 0, host: "mongo:27017" }
  ]
});
EOF

