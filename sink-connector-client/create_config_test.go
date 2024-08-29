package main

import (
	"context"
	"testing"
	"time"

	"github.com/testcontainers/testcontainers-go"
)

// Use testcontainers to spin up a mysql container
func TestValidateMySQL(t *testing.T) {
	// Spin up a MySQL container
	req := testcontainers.ContainerRequest{
		Image:        "mysql:5.7",
		ExposedPorts: []string{"3306/tcp"},
		Env: map[string]string{
			"MYSQL_ROOT_PASSWORD": "password",
		},
	}
	mysqlContainer, err := testcontainers.GenericContainer(context.Background(), testcontainers.GenericContainerRequest{
		ContainerRequest: req,
		Started:          true,
	})
	if err != nil {
		t.Fatalf("Could not start MySQL container: %s", err)
	}
	defer mysqlContainer.Terminate(context.Background())

	// Wait for container to start
	// sleep till container has started
	time.Sleep(10 * time.Second)

	// Get the MySQL container's IP address
	ip, err := mysqlContainer.Host(context.Background())
	if err != nil {
		t.Fatalf("Could not get MySQL container IP: %s, error %s", ip, err)
	}
	port, err := mysqlContainer.MappedPort(context.Background(), "3306")
	if err != nil {
		t.Fatalf("Could not get MySQL container port: %s error %s", port, err)
	}

	// Set the MySQL container's IP address and port

	// Validate MySQL credentials
	if !validateMySQL("root", "password", ip, port.Port()) {
		t.Fatalf("Could not validate MySQL credentials")
	}
}
