# MySQL Transactions(currently not supported)
Transactions have to be enabled in the source connector, For MySQL
the following flag has to be set
`    "provide.transaction.metadata": "true"`

Transaction events are written to the `<server_name>.transaction` topic.

`SinkRecord{kafkaOffset=6, timestampType=CreateTime} ConnectRecord{topic='SERVER5432.transaction', kafkaPartition=1, key=Struct{id=file=binlog.000002,pos=3680}, keySchema=Schema{io.debezium.connector.common.TransactionMetadataKey:STRUCT}, value=Struct{status=BEGIN,id=file=binlog.000002,pos=3680}, valueSchema=Schema{io.debezium.connector.common.TransactionMetadataValue:STRUCT}, timestamp=1652217855355, headers=ConnectHeaders(headers=)}`


`SinkRecord{kafkaOffset=7, timestampType=CreateTime} ConnectRecord{topic='SERVER5432.transaction', kafkaPartition=1, key=Struct{id=file=binlog.000002,pos=3680}, keySchema=Schema{io.debezium.connector.common.TransactionMetadataKey:STRUCT}, value=Struct{status=END,id=file=binlog.000002,pos=3680,event_count=1,data_collections=[Struct{data_collection=test.products,event_count=1}]}, valueSchema=Schema{io.debezium.connector.common.TransactionMetadataValue:STRUCT}, timestamp=1652217855372, headers=ConnectHeaders(headers=)}`
`SinkRecord{kafkaOffset=119, timestampType=CreateTime} ConnectRecord{topic='SERVER5432.test.products', kafkaPartition=0, key=Struct{productCode=enhance on}, keySchema=Schema{SERVER5432.test.products.Key:STRUCT}, value=Struct{after=Struct{productCode=enhance on,productName=Tiffany Ag,productLine=re-interme,productScale=integrate ,productVendor=Chen-Carls,productDescription=Wilcox PLC,quantityInStock=77,buyPrice=0.58,MSRP=1.59},source=Struct{version=1.9.2.Final,connector=mysql,name=SERVER5432,ts_ms=1652217855000,snapshot=false,db=test,table=products,server_id=1,file=binlog.000002,pos=3836,row=0,thread=21},op=c,ts_ms=1652217855055,transaction=Struct{id=file=binlog.000002,pos=3680,total_order=1,data_collection_order=1}}, valueSchema=Schema{SERVER5432.test.products.Envelope:STRUCT}, timestamp=1652217855372, headers=ConnectHeaders(headers=)}`

Payload records and transaction records can be matched with 
the `binlog` and `pos` information.


`SinkRecord{kafkaOffset=119, timestampType=CreateTime} ConnectRecord{topic='SERVER5432.test.products', kafkaPartition=0, key=Struct{productCode=enhance on}, keySchema=Schema{SERVER5432.test.products.Key:STRUCT}, value=Struct{after=Struct{productCode=enhance on,productName=Tiffany Ag,productLine=re-interme,productScale=integrate ,productVendor=Chen-Carls,productDescription=Wilcox PLC,quantityInStock=77,buyPrice=0.58,MSRP=1.59},source=Struct{version=1.9.2.Final,connector=mysql,name=SERVER5432,ts_ms=1652217855000,snapshot=false,db=test,table=products,server_id=1,file=binlog.000002,pos=3836,row=0,thread=21},op=c,ts_ms=1652217855055,transaction=Struct{id=file=binlog.000002,pos=3680,total_order=1,data_collection_order=1}}, valueSchema=Schema{SERVER5432.test.products.Envelope:STRUCT}, timestamp=1652217855372, headers=ConnectHeaders(headers=)}`
`