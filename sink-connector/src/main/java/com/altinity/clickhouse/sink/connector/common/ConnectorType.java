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
            ConnectorType connectorType = ConnectorType.MYSQL;

            String displayName = ConnectorDescriptor.getIdForConnectorClass(value);
            if(displayName != null) {
                connectorType =ConnectorType.valueOf(displayName);
                if(displayName.contains(MYSQL.getValue())) {
                    connectorType = ConnectorType.MYSQL;
                } else if(displayName.contains(POSTGRES.getValue())) {
                    connectorType = ConnectorType.POSTGRES;
                }
            }
            return connectorType;
        }
    }
