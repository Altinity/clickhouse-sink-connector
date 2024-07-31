package main

import (
	"database/sql"
	"fmt"
	"log"

	_ "github.com/go-sql-driver/mysql"
)

// Function to create a sink connector configuration.

// Add function to connect to MySQL and validate the username/password
// return true if succeeds, return error if it fails.
// Validate MySQL credentials.
// Add function to connect to MySQL and validate the username/password
// return true if succeeds, return error if it fails.
// Validate MySQL credentials.
// Add function to connect to MySQL and validate the username/password
func validateMySQL(sourceUsername string, sourcePassword string, sourceHost string, sourcePort string) bool {
	// Connect to MySQL
	db, err := sql.Open("mysql", fmt.Sprintf("%s:%s@tcp(%s:%s)/", sourceUsername, sourcePassword, sourceHost, sourcePort))
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()

	// Validate MySQL credentials
	err = db.Ping()
	if err != nil {
		log.Fatal(err)
	}

	// check if binlogs are enabled.
	rows, err := db.Query("SHOW VARIABLES LIKE 'log_bin'")
	// if log_bin is not enabled, then return false
	// check if rows has response 'OFF'
	// if it is 'OFF' then return false
	if rows == 'OFF' {
		log.fatal("Binlogs are not enabled")
		return false
	}

	if err != nil {
		log.Fatal(err)
	}
	defer rows.Close()

	return true
}
