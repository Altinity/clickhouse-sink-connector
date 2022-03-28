package com.altinity.clickhouse.sink.connector;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;


public class TopicToTableValidator implements ConfigDef.Validator {
    public TopicToTableValidator() {}

    public void ensureValid(String name, Object value) {
      String s = (String) value;
      if (s != null && !s.isEmpty()) // this value is optional and can be empty
      {
          try {
              if (Utils.parseTopicToTableMap(s) == null) {
                throw new ConfigException(
                    name, value, "Format: <topic-1>:<table-1>,<topic-2>:<table-2>,...");
              }
          } catch (Exception e) {
              e.printStackTrace();
          }
      }
    }

    public String toString() {
      return "Topic to table map format : comma-separated tuples, e.g."
          + " <topic-1>:<table-1>,<topic-2>:<table-2>,... ";
    }
  }