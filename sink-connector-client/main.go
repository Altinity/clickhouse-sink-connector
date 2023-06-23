package main

import (
	"encoding/json"
	"fmt"
	"github.com/levigross/grequests"
	cli "github.com/urfave/cli"
	"log"
	"os"
	"time"
)

var requestOptions = &grequests.RequestOptions{}

type UpdateBinLog struct {
	File     string `json:"binlog_file"`
	Position string `json:"binlog_position"`
	Gtid     string `json:"gtid"`
}

const (
	START_REPLICATION_COMMAND = "start_replica"
	STOP_REPLICATION_COMAND   = "stop_replica"
	STATUS_COMMAND            = "show_replica_status"
	UPDATE_BINLOG_COMMAND     = "update_binlog"
)
const (
	START_REPLICATION = "start"
	STOP_REPLICATION  = "stop"
	STATUS            = "status"
	UPDATE_BINLOG     = "binlog"
)

// Fetches the repos for the given Github users
func getHTTPCall(url string) *grequests.Response {
	resp, err := grequests.Get(url, requestOptions)
	// you can modify the request by passing an optional RequestOptions struct
	if err != nil {
		log.Fatalln("Unable to make request: ", err)
	}
	return resp
}

/**
Function to get server url based on the parameters passed
*/
func getServerUrl(action string, c *cli.Context) string {

	var scheme = "http://"
	var hostname = "localhost"
	var port = "7000"
	if c.GlobalString("host") != "" {
		hostname = c.GlobalString("host")
	}
	if c.GlobalString("port") != "" {
		port = c.GlobalString("port")
	}
	if c.GlobalBool("secure") {
		scheme = "https://"
	}

	var serverUrl = scheme + hostname + ":" + port + "/" + action

	return serverUrl
}

func main() {
	app := cli.NewApp()
	app.Name = "Sink Connector Lightweight CLI"
	app.Usage = "CLI for Sink Connector Lightweight, operations to get status, start/stop replication and set binlog/gtid position"
	app.Flags = []cli.Flag{
		cli.StringFlag{
			Name:     "host",
			Usage:    "Host server address of sink connector",
			Required: false,
		},
		cli.StringFlag{
			Name:     "port",
			Usage:    "Port of sink connector",
			Required: false,
		},
		cli.BoolFlag{
			Name:     "secure",
			Usage:    "If true, then use https, else http",
			Required: false,
		},
	}

	// define command for our client
	app.Commands = []cli.Command{

		{
			Name:  START_REPLICATION_COMMAND,
			Usage: "Start the replication",
			Action: func(c *cli.Context) error {
				var serverUrl = getServerUrl(START_REPLICATION, c)
				resp := getHTTPCall(serverUrl)
				log.Println(resp)
				return nil
			},
		},
		{
			Name:  STOP_REPLICATION_COMAND,
			Usage: "Stop the replication",
			Action: func(c *cli.Context) error {
				log.Println("***** Stopping replication..... *****")
				var serverUrl = getServerUrl(STOP_REPLICATION, c)
				resp := getHTTPCall(serverUrl)
				log.Println(resp.String())
				log.Println("***** Replication stopped successfully *****")
				return nil
			},
		},
		{
			Name: STATUS_COMMAND,
			//Aliases: []string{"c"},
			Usage: "Status of replication",
			Action: func(c *cli.Context) error {
				var serverUrl = getServerUrl(STATUS, c)
				resp := getHTTPCall(serverUrl)
				log.Println(resp.String())
				return nil
			},
		},
		{
			Name:  UPDATE_BINLOG_COMMAND,
			Usage: "Update binlog file/position and gtids",
			Flags: []cli.Flag{
				cli.StringFlag{
					Name:     "binlog_file",
					Usage:    "Set binlog file",
					Required: true,
				},
				cli.StringFlag{
					Name:     "binlog_position",
					Usage:    "Set binlog position",
					Required: true,
				},
				cli.StringFlag{
					Name:     "gtid",
					Usage:    "Set GTID",
					Required: true,
				},
			},
			Action: func(c *cli.Context) error {
				handleUpdateBinLogAction(c)
				return nil
			},
		},
	}

	app.Version = "1.0"
	app.Run(os.Args)
}

/**
Function to handle update binlog action
which is used to set binlog file/position and gtids
*/
func handleUpdateBinLogAction(c *cli.Context) bool {
	var binlogFile = c.String("binlog_file")
	var binlogPos = c.String("binlog_position")
	var gtid = c.String("gtid")

	log.Println("***** binlog file: ", binlogFile+"   *****")
	log.Println("***** binlog position:", binlogPos+"   *****")
	log.Println("*****  GTID:", gtid+"   *****")
	log.Println("Are you sure you want to continue? (y/n): ")
	var userInput string
	fmt.Scanln(&userInput)
	if userInput != "y" {
		log.Println("Exiting...")
		return false
	} else {
		log.Println("Continuing...")
	}

	// Step1: Stop replication
	log.Println("Stopping replication...")
	var stopUrl = getServerUrl(STOP_REPLICATION, c)
	resp := getHTTPCall(stopUrl)
	time.Sleep(5 * time.Second)

	// Step2: Update binlog position
	log.Println("Updating binlog file/position and gtids...")
	var updateBinLogBody = UpdateBinLog{File: binlogFile, Position: binlogPos, Gtid: gtid}
	var postBody, _ = json.Marshal(updateBinLogBody)
	var requestOptions_copy = requestOptions
	// Add data to JSON field
	requestOptions_copy.JSON = string(postBody)
	var serverUrl = getServerUrl(UPDATE_BINLOG, c)
	resp, err := grequests.Post(serverUrl, requestOptions_copy)
	log.Println(resp.String())
	if err != nil {
		log.Println(err)
		log.Println("Create request failed for Github API")
	}

	// Step3: Start replication
	time.Sleep(10 * time.Second)
	var startUrl = getServerUrl(START_REPLICATION, c)
	resp1 := getHTTPCall(startUrl)
	log.Println(resp1.String())
	return true
}
