package com.altinity.clickhouse.sink.connector.common;

import io.debezium.metadata.ConnectorDescriptor;



    public enum ConnectorType {      
        MYSQL("mysql"),
        POSTGRES("postgres");

        private final String value;
        
        ConnectorType(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }

        public static ConnectorType fromString(String value) {

            ConnectorDescriptor.getDisplayNameForConnectorClass(value);
            return ConnectorType.valueOf(value);
        }
    }
