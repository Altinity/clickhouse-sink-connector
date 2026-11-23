package main

import (
	"bufio"
	"fmt"
	"io"
	"log"
	"os"
	"time"

	"github.com/levigross/grequests"
	"github.com/tidwall/pretty"
	cli "github.com/urfave/cli"
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
	START_REPLICATION_COMMAND     = "start_replica"
	STOP_REPLICATION_COMAND       = "stop_replica"
	DDL_TRANSLATE_COMMAND         = "ddl_translate"
	STATUS_COMMAND                = "show_replica_status"
	UPDATE_BINLOG_COMMAND         = "change_replication_source"
	UPDATE_LSN_COMMAND            = "lsn"
	DELETE_OFFSETS_COMMAND        = "delete_offsets"
	DELETE_SCHEMA_HISTORY_COMMAND = "delete_schema_history"
	SHOW_SLAVE_STATUS_COMMAND     = "show_slave_status"
)

const (
	START_REPLICATION     = "start"
	STOP_REPLICATION      = "stop"
	RESTART_REPLICATION   = "restart"
	STATUS                = "status"
	UPDATE_BINLOG         = "binlog"
	UPDATE_LSN            = "lsn"
	DELETE_OFFSETS        = "offsets"
	DELETE_SCHEMA_HISTORY = "schema-history"
	DDL_TRANSLATE         = "ddl-translate"
	SHOW_SLAVE_STATUS     = "show-slave-status"
)

// input is where the confirmation prompts and the DDL text are read from (tests replace it).
var input io.Reader = os.Stdin

// pause is how long the handlers wait after a request so the server can settle (tests replace it).
var pause = time.Sleep

// checkResponse turns a failed request into an error: a transport error (resp is nil then, so it is checked
// first) or any status outside 2xx.
func checkResponse(method string, url string, resp *grequests.Response, err error) (*grequests.Response, error) {
	if err != nil {
		return nil, fmt.Errorf("unable to make %s request to %s: %w", method, url, err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return resp, fmt.Errorf("%s %s failed with HTTP status %d: %s", method, url, resp.StatusCode, resp.String())
	}
	return resp, nil
}

func getHTTPCall(url string) (*grequests.Response, error) {
	resp, err := grequests.Get(url, requestOptions)
	return checkResponse("GET", url, resp, err)
}

func getHTTPDeleteCall(url string) (*grequests.Response, error) {
	resp, err := grequests.Delete(url, requestOptions)
	return checkResponse("DELETE", url, resp, err)
}

// postJSON posts a JSON body with options of its own, so the body is not carried into later requests.
func postJSON(url string, body interface{}) (*grequests.Response, error) {
	resp, err := grequests.Post(url, &grequests.RequestOptions{JSON: body})
	return checkResponse("POST", url, resp, err)
}

func getHTTPPostCall(url string, body string) (*grequests.Response, error) {
	// create a JSON with key as "ddl" and value as the body
	return postJSON(url, map[string]string{"ddl": body})
}

// confirm asks the operator and is true only for "y".
func confirm() bool {
	log.Println("Are you sure you want to continue? (y/n): ")
	var userInput string
	fmt.Fscanln(input, &userInput)
	if userInput != "y" {
		log.Println("Exiting...")
		return false
	}
	log.Println("Continuing...")
	return true
}

/*
*
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

func newApp() *cli.App {
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
				resp, err := getHTTPCall(serverUrl)
				if err != nil {
					return err
				}
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
				resp, err := getHTTPCall(serverUrl)
				if err != nil {
					return err
				}
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
				resp, err := getHTTPCall(serverUrl)
				if err != nil {
					return err
				}
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
				resp, err := getHTTPCall(serverUrl)
				if err != nil {
					return err
				}

				//var j, _ = json.MarshalIndent(resp, "", "    ")
				fmt.Println(string(pretty.Pretty([]byte(resp.String()))))
				return nil
			},
		},
		{
			Name:  DDL_TRANSLATE_COMMAND,
			Usage: "Translate DDL to target database",
			Action: func(c *cli.Context) error {
				return handleDDLTranslate(c)
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

				return handleUpdateBinLogAction(c)
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
				return handleUpdateLsn(c)
			},
		},
		{
			Name:  DELETE_OFFSETS_COMMAND,
			Usage: "Delete offsets from the sink connector",
			Action: func(c *cli.Context) error {
				return handleDeleteOffsets(c)
			},
		},
		{
			Name:  DELETE_SCHEMA_HISTORY_COMMAND,
			Usage: "Delete schema history from the sink connector",
			Action: func(c *cli.Context) error {
				return handleDeleteSchemaHistory(c)
			},
		},
		{
			Name:  SHOW_SLAVE_STATUS_COMMAND,
			Usage: "Show slave status",
			Action: func(c *cli.Context) error {
				return handleShowSlaveStatus(c)
			},
		},
	}
	app.Version = "1.0"
	return app
}

func main() {
	if err := newApp().Run(os.Args); err != nil {
		log.Fatalln("Error:", err)
	}
}

// handleDeleteOffsets deletes the connector offsets after confirmation. Declining is not an error.
func handleDeleteOffsets(c *cli.Context) error {
	log.Println("***** Delete offsets from the sink connector *****")
	if !confirm() {
		return nil
	}
	// Call a REST DELETE API to delete offsets from the sink connector
	var deleteOffsetsUrl = getServerUrl(DELETE_OFFSETS, c)
	log.Println("Sending request to URL: " + deleteOffsetsUrl)
	_, err := getHTTPDeleteCall(deleteOffsetsUrl)
	pause(5 * time.Second)
	if err != nil {
		return fmt.Errorf("error deleting offsets: %w", err)
	}
	log.Println("Offsets deleted successfully")
	return nil
}

func handleDDLTranslate(c *cli.Context) error {
	log.Println("***** Translate MySQL DDL to ClickHouse DDL *****")
	log.Println("Enter DDL to translate: ")
	var userInput string
	scanner := bufio.NewScanner(input)

	if scanner.Scan() {
		userInput = scanner.Text()
	}
	// Call a REST POST API to translate DDL to target database
	var ddlTranslateUrl = getServerUrl(DDL_TRANSLATE, c)
	log.Println("Sending request to URL: with DDL: " + userInput + " to " + ddlTranslateUrl)
	resp, err := getHTTPPostCall(ddlTranslateUrl, userInput)
	pause(5 * time.Second)
	if err != nil {
		return fmt.Errorf("error translating DDL: %w", err)
	}
	log.Println(resp.String())
	return nil
}

func handleDeleteSchemaHistory(c *cli.Context) error {
	log.Println("***** Delete schema history from the sink connector *****")
	if !confirm() {
		return nil
	}
	var deleteSchemaHistoryUrl = getServerUrl(DELETE_SCHEMA_HISTORY, c)
	log.Println("Sending request to URL: " + deleteSchemaHistoryUrl)
	_, err := getHTTPDeleteCall(deleteSchemaHistoryUrl)
	pause(5 * time.Second)
	if err != nil {
		return fmt.Errorf("error deleting schema history: %w", err)
	}
	log.Println("Schema history deleted successfully")
	return nil
}

// handleUpdateLsn stops replication, sets the LSN and starts replication again. Any failing step aborts the
// sequence: the LSN is never updated on a connector that did not stop, and replication is not started after a
// failed update (it stays stopped; start_replica starts it once the cause is fixed).
func handleUpdateLsn(c *cli.Context) error {
	var lsnPosition = c.String("lsn")
	log.Println("***** lsn position:", lsnPosition+"   *****")
	if !confirm() {
		return nil
	}

	// Step1: Stop replication
	log.Println("Stopping replication...")
	var stopUrl = getServerUrl(STOP_REPLICATION, c)
	if _, err := getHTTPCall(stopUrl); err != nil {
		return fmt.Errorf("replication was not stopped, lsn not updated: %w", err)
	}
	pause(5 * time.Second)

	// Step2: Update lsn position
	log.Println("Updating lsn position..")
	var serverUrl = getServerUrl(UPDATE_LSN, c)
	resp, err := postJSON(serverUrl, UpdateLsn{Lsn: lsnPosition})
	if err != nil {
		return fmt.Errorf("lsn update failed, replication is still stopped: %w", err)
	}
	log.Println(resp.String())

	// Step3: Start replication
	pause(10 * time.Second)
	var startUrl = getServerUrl(START_REPLICATION, c)
	resp1, err := getHTTPCall(startUrl)
	if err != nil {
		return fmt.Errorf("lsn updated but replication did not start: %w", err)
	}
	log.Println(resp1.String())
	return nil
}

/*
*
Function to handle update binlog action
which is used to set binlog file/position and gtids
*/
func handleUpdateBinLogAction(c *cli.Context) error {
	var binlogFile = c.String("binlog_file")
	var binlogPos = c.String("binlog_position")
	var gtid = c.String("gtid")
	var sourceHost = c.String("source_host")
	var sourcePort = c.String("source_port")
	var sourceUsername = c.String("source_username")
	var sourcePassword = c.String("source_password")

	log.Println("***** binlog file: ", binlogFile+"   *****")
	log.Println("***** binlog position:", binlogPos+"   *****")
	log.Println("*****  GTID:", gtid+"   *****")
	log.Println("*****  Source Host:", sourceHost+"   *****")
	log.Println("*****  Source Port:", sourcePort+"   *****")
	log.Println("*****  Source Username:", sourceUsername+"   *****")
	if sourcePassword != "" {
		log.Println("*****  Source Password: ****   *****")
	}

	if !confirm() {
		return nil
	}

	log.Println("Updating binlog file/position and gtids...")
	var updateBinLogBody = UpdateBinLog{File: binlogFile, Position: binlogPos, Gtid: gtid, SourceHost: sourceHost, SourcePort: sourcePort, SourceUser: sourceUsername, SourcePassword: sourcePassword}
	var serverUrl = getServerUrl(UPDATE_BINLOG, c)
	resp, err := postJSON(serverUrl, updateBinLogBody)
	if err != nil {
		if resp != nil && resp.StatusCode == 400 {
			log.Println("***** Error: Replication is running, please stop it first ******")
			log.Println("***** Use stop_replica command to stop replication ******")
			log.Println("***** After change_replication_source is successful, use start_replica to start replication *******")
		}
		return fmt.Errorf("binlog update failed: %w", err)
	}
	log.Println(resp.String())
	return nil
}

func handleShowSlaveStatus(c *cli.Context) error {
	log.Println("***** Show slave status ******")
	var serverUrl = getServerUrl(SHOW_SLAVE_STATUS, c)
	resp, err := getHTTPCall(serverUrl)
	if err != nil {
		return err
	}
	log.Println(resp.String())
	return nil
}
