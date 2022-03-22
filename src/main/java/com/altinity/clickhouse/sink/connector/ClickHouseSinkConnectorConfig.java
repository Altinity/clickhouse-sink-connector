package com.altinity.clickhouse.sink.connector;

import java.util.Map;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ClickHouseSinkConnectorConfig {

  static final String NAME = Const.NAME;
  public static final String TOPICS = "topics";

  public static final String BUFFER_COUNT = "buffer.count";
  public static final long BUFFER_COUNT_DEFAULT = 100;

  private static final Logger log = LoggerFactory.getLogger(ClickHouseSinkConnectorConfig.class.getName());

  public static void setDefaultValues(Map<String, String> config) {
    setFieldToDefaultValues(config, BUFFER_COUNT, BUFFER_COUNT_DEFAULT);
  }

  static void setFieldToDefaultValues(Map<String, String> config, String field, Long value) {
    if (!config.containsKey(field)) {
      config.put(field, "" + value);
      log.info("setFieldToDefaultValues(){}={}", field, value);
    }
  }

  static String getProperty(final Map<String, String> config, final String key) {
    if (config.containsKey(key) && !config.get(key).isEmpty()) {
      return config.get(key);
    } else {
      return null;
    }
  }

  static ConfigDef newConfigDef() {
    return new ConfigDef()
        .define(
            BUFFER_COUNT,
            Type.LONG,
            BUFFER_COUNT_DEFAULT,
            ConfigDef.Range.atLeast(1),
            Importance.LOW,
            "BufCount",
            "Connector",
            1,
            ConfigDef.Width.NONE,
            BUFFER_COUNT);
  }
}
