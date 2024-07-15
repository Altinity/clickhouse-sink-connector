package main

import (
	"encoding/json"
	"fmt"
	"github.com/levigross/grequests"
	"github.com/tidwall/pretty"
	cli "github.com/urfave/cli"
	"log"
	"os"
	"time"
)

var requestOptions = &grequests.RequestOptions{}

type UpdateBinLog struct {
	File           string `json:"binlog_file"`
	Position       string `json:"binlog_position"`
	Gtid           string `json:"gtid"`
	SourceHost     string `json:"source_host"`
	SourcePort     string `json:"source_port"`
	SourceUser     string `json:"source_user"`
	SourcePassword string `json:"source_password"`
}

type UpdateLsn struct {
	Lsn string `json:"lsn"`
}

const (
	START_REPLICATION_COMMAND = "start_replica"
	STOP_REPLICATION_COMAND   = "stop_replica"
	STATUS_COMMAND            = "show_replica_status"
	UPDATE_BINLOG_COMMAND     = "change_replication_source"
	UPDATE_LSN_COMMAND        = "lsn"
)
const (
	START_REPLICATION   = "start"
	STOP_REPLICATION    = "stop"
	RESTART_REPLICATION = "restart"
	STATUS              = "status"
	UPDATE_BINLOG       = "binlog"
	UPDATE_LSN          = "lsn"
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
	app.EnableBashCompletion = true
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
		// Restart
		{
			Name:  RESTART_REPLICATION,
			Usage: "Restart the replication, this was also reload the configuration file from disk",
			Action: func(c *cli.Context) error {
				log.Println("***** Restarting replication..... *****")
				var serverUrl = getServerUrl(RESTART_REPLICATION, c)
				resp := getHTTPCall(serverUrl)
				log.Println(resp.String())
				log.Println("***** Replication restarted successfully and configuration file reloaded from disk *****")
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

				//var j, _ = json.MarshalIndent(resp, "", "    ")
				fmt.Println(string(pretty.Pretty([]byte(resp.String()))))
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
					Required: false,
				},
				cli.StringFlag{
					Name:     "binlog_position",
					Usage:    "Set binlog position",
					Required: false,
				},
				cli.StringFlag{
					Name:     "gtid",
					Usage:    "Set GTID",
					Required: false,
				},

				cli.StringFlag{
					Name:     "source_host",
					Usage:    "Source Hostname",
					Required: false,
				},

				cli.StringFlag{
					Name:     "source_port",
					Usage:    "Source Port",
					Required: false,
				},

				cli.StringFlag{
					Name:     "source_username",
					Usage:    "Source Username",
					Required: false,
				},

				cli.StringFlag{
					Name:     "source_password",
					Usage:    "Source Password",
					Required: false,
				},
			},
			Action: func(c *cli.Context) error {

				handleUpdateBinLogAction(c)
				return nil
			},
		},
		{
			Name:  UPDATE_LSN_COMMAND,
			Usage: "Update lsn(For postgreSQL)",
			Flags: []cli.Flag{
				cli.StringFlag{
					Name:     "lsn",
					Usage:    "Set LSN position(For PostgreSQL)",
					Required: true,
				},
			},
			Action: func(c *cli.Context) error {
				handleUpdateLsn(c)
				return nil
			},
		},
	}

	app.Version = "1.0"
	app.Run(os.Args)
}

func handleUpdateLsn(c *cli.Context) bool {
	var lsnPosition = c.String("lsn")
	log.Println("***** lsn position:", lsnPosition+"   *****")
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
	log.Println("Updating lsn position..")
	var updateLsnBody = UpdateLsn{Lsn: lsnPosition}
	var postBody, _ = json.Marshal(updateLsnBody)
	var requestOptions_copy = requestOptions
	// Add data to JSON field
	requestOptions_copy.JSON = string(postBody)
	var serverUrl = getServerUrl(UPDATE_LSN, c)
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

/**
Function to handle update binlog action
which is used to set binlog file/position and gtids
*/
func handleUpdateBinLogAction(c *cli.Context) bool {
	var binlogFile = c.String("binlog_file")
	var binlogPos = c.String("binlog_position")
	var gtid = c.String("gtid")
	var sourceHost = c.String("source_host")
	var sourcePort = c.String("source_port")
	var sourceUsername = c.String("source_username")
	var sourcePassword = c.String("source_password")

	if gtid == "" {
		// If gtid is empty, then a valid binlog file and position
		// needs to be passed.
		//if binlogPos == "" || binlogFile == "" {
		//	log.Println(" ****** A Valid binlog position/file or GTID set is required")
		//	cli.ShowCommandHelp(c, UPDATE_BINLOG_COMMAND)
		//	return false
		//} else if sourceHost == "" || sourcePort == "" || sourceUsername == "" || sourcePassword == "" {
		//	log.Println(" ****** A Valid source host/port/username/password is required")
		//	cli.ShowCommandHelp(c, UPDATE_BINLOG_COMMAND)
		//	return false
		//}
	}
	log.Println("***** binlog file: ", binlogFile+"   *****")
	log.Println("***** binlog position:", binlogPos+"   *****")
	log.Println("*****  GTID:", gtid+"   *****")
	log.Println("*****  Source Host:", sourceHost+"   *****")
	log.Println("*****  Source Port:", sourcePort+"   *****")
	log.Println("*****  Source Username:", sourceUsername+"   *****")
	log.Println("*****  Source Password:", sourcePassword+"   *****")

	log.Println("Are you sure you want to continue? (y/n): ")
	var userInput string
	fmt.Scanln(&userInput)
	if userInput != "y" {
		log.Println("Exiting...")
		return false
	} else {
		log.Println("Continuing...")
	}

	//// Step1: Stop replication
	//log.Println("Stopping replication...")
	//var stopUrl = getServerUrl(STOP_REPLICATION, c)
	//resp := getHTTPCall(stopUrl)
	//time.Sleep(5 * time.Second)

	// Step2: Update binlog position
	log.Println("Updating binlog file/position and gtids...")
	var updateBinLogBody = UpdateBinLog{File: binlogFile, Position: binlogPos, Gtid: gtid, SourceHost: sourceHost, SourcePort: sourcePort, SourceUser: sourceUsername, SourcePassword: sourcePassword}
	var postBody, _ = json.Marshal(updateBinLogBody)
	var requestOptions_copy = requestOptions
	// Add data to JSON field
	requestOptions_copy.JSON = string(postBody)
	var serverUrl = getServerUrl(UPDATE_BINLOG, c)
	resp, err := grequests.Post(serverUrl, requestOptions_copy)
	if resp.StatusCode == 400 {
		log.Println("***** Error: Replication is running, please stop it first ******")
		log.Println("***** Use stop_replica command to stop replication ******")
		log.Println("***** After change_replication_source is successful, use start_replica to start replication *******")
		return false
	}

	log.Println(resp.String())
	if err != nil {
		log.Println(err)
		log.Println("Create request failed")
	}
	//
	//// Step3: Start replication
	//time.Sleep(10 * time.Second)
	//var startUrl = getServerUrl(START_REPLICATION, c)
	//resp1 := getHTTPCall(startUrl)
	//log.Println(resp1.String())
	return true
}
