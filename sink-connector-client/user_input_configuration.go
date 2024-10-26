// Create a go file that will take MySQL username, password, host, port as input and validate the MySQL credentials
// If the credentials are valid, it will print "Credentials are valid"
// If the credentials are invalid, it will print "Credentials are invalid"
// The program should keep asking for the credentials until the user provides valid credentials
// The program should exit when the user provides valid credentials

package main

import (
	"bufio"
	"fmt"
	"os"

	"gopkg.in/yaml.v2"
)

type Config struct {
	Name                               string `yaml:"name"`
	TopicPrefix                        string `yaml:"topic.prefix"`
	DatabaseHostname                   string `yaml:"database.hostname"`
	DatabasePort                       string `yaml:"database.port"`
	DatabaseUser                       string `yaml:"database.user"`
	DatabasePassword                   string `yaml:"database.password"`
	DatabaseServerID                   string `yaml:"database.server.id"`
	DatabaseServerName                 string `yaml:"database.server.name"`
	DatabaseIncludeList                string `yaml:"database.include.list"`
	TableIncludeList                   string `yaml:"table.include.list"`
	ClickhouseServerURL                string `yaml:"clickhouse.server.url"`
	ClickhouseServerUser               string `yaml:"clickhouse.server.user"`
	ClickhouseServerPassword           string `yaml:"clickhouse.server.password"`
	ClickhouseServerPort               string `yaml:"clickhouse.server.port"`
	DatabaseAllowPublicKeyRetrieval    string `yaml:"database.allowPublicKeyRetrieval"`
	SnapshotMode                       string `yaml:"snapshot.mode"`
	OffsetFlushIntervalMs              int    `yaml:"offset.flush.interval.ms"`
	ConnectorClass                     string `yaml:"connector.class"`
	OffsetStorage                      string `yaml:"offset.storage"`
	OffsetStorageJdbcOffsetTableName   string `yaml:"offset.storage.jdbc.offset.table.name"`
	OffsetStorageJdbcURL               string `yaml:"offset.storage.jdbc.url"`
	OffsetStorageJdbcUser              string `yaml:"offset.storage.jdbc.user"`
	OffsetStorageJdbcPassword          string `yaml:"offset.storage.jdbc.password"`
	OffsetStorageJdbcOffsetTableDDL    string `yaml:"offset.storage.jdbc.offset.table.ddl"`
	OffsetStorageJdbcOffsetTableDelete string `yaml:"offset.storage.jdbc.offset.table.delete"`
	OffsetStorageJdbcOffsetTableSelect string `yaml:"offset.storage.jdbc.offset.table.select"`
	SchemaHistoryInternal              string `yaml:"schema.history.internal"`
	SchemaHistoryInternalJdbcURL       string `yaml:"schema.history.internal.jdbc.url"`
	SchemaHistoryInternalJdbcUser      string `yaml:"schema.history.internal.jdbc.user"`
	SchemaHistoryInternalJdbcPassword  string `yaml:"schema.history.internal.jdbc.password"`
	SchemaHistoryInternalJdbcDDL       string `yaml:"schema.history.internal.jdbc.schema.history.table.ddl"`
	SchemaHistoryInternalJdbcTableName string `yaml:"schema.history.internal.jdbc.schema.history.table.name"`
	EnableSnapshotDDL                  string `yaml:"enable.snapshot.ddl"`
	PersistRawBytes                    string `yaml:"persist.raw.bytes"`
	AutoCreateTables                   string `yaml:"auto.create.tables"`
	AutoCreateTablesReplicated         string `yaml:"auto.create.tables.replicated"`
	DatabaseConnectionTimeZone         string `yaml:"database.connectionTimeZone"`
	ClickhouseDatabaseOverrideMap      string `yaml:"clickhouse.database.override.map"`
}

func readConfig() Config {
	config := Config{}
	reader := bufio.NewReader(os.Stdin)

	fmt.Print("Enter name: ")
	config.Name, _ = reader.ReadString('\n')

	fmt.Print("Enter topic.prefix: ")
	config.TopicPrefix, _ = reader.ReadString('\n')

	fmt.Print("Enter database.hostname: ")
	config.DatabaseHostname, _ = reader.ReadString('\n')

	fmt.Print("Enter database.port: ")
	config.DatabasePort, _ = reader.ReadString('\n')

	fmt.Print("Enter database.user: ")
	config.DatabaseUser, _ = reader.ReadString('\n')

	fmt.Print("Enter database.password: ")
	config.DatabasePassword, _ = reader.ReadString('\n')

	// Add other fields similarly...

	fmt.Print("Enter clickhouse.database.override.map: ")
	config.ClickhouseDatabaseOverrideMap, _ = reader.ReadString('\n')

	yamlData, err := yaml.Marshal(&config)
	if err != nil {
		fmt.Printf("Error marshalling YAML: %v\n", err)
		return Config{}
	}

	fmt.Println("\nGenerated YAML:")
	fmt.Println(string(yamlData))
	return config
}
