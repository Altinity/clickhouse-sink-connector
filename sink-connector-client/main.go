package main

import (
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log"
	"os"
	"time"

	"github.com/levigross/grequests"
	cli "github.com/urfave/cli"
)

var GITHUB_TOKEN = os.Getenv("GITHUB_TOKEN")
var requestOptions = &grequests.RequestOptions{Auth: []string{GITHUB_TOKEN, "x-oauth-basic"}}

// Struct for holding response of repositories fetch API
type Repo struct {
	ID       int    `json:"id"`
	Name     string `json:"name"`
	FullName string `json:"full_name"`
	Forks    int    `json:"forks"`
	Private  bool   `json:"private"`
}

// Structs for modelling JSON body in create Gist
type File struct {
	Content string `json:"content"`
}

type Gist struct {
	Description string          `json:"description"`
	Public      bool            `json:"public"`
	Files       map[string]File `json:"files"`
}

type UpdateBinLog struct {
	File     string `json:"binlog_file"`
	Position string `json:"binlog_position"`
	Gtid     string `json:"gtid"`
}

// Fetches the repos for the given Github users
func getStats(url string) *grequests.Response {
	resp, err := grequests.Get(url, requestOptions)
	// you can modify the request by passing an optional RequestOptions struct
	if err != nil {
		log.Fatalln("Unable to make request: ", err)
	}
	return resp
}

// Reads the files provided and creates Gist on github
func createGist(url string, args []string) *grequests.Response {
	// get first teo arguments
	description := args[0]
	// remaining arguments are file names with path
	var fileContents = make(map[string]File)
	for i := 1; i < len(args); i++ {
		dat, err := ioutil.ReadFile(args[i])
		if err != nil {
			log.Println("Please check the filenames. Absolute path (or) same directory are allowed")
			return nil
		}
		var file File
		file.Content = string(dat)
		fileContents[args[i]] = file
	}
	var gist = Gist{Description: description, Public: true, Files: fileContents}
	var postBody, _ = json.Marshal(gist)
	var requestOptions_copy = requestOptions
	// Add data to JSON field
	requestOptions_copy.JSON = string(postBody)
	// make a Post request to Github
	resp, err := grequests.Post(url, requestOptions_copy)
	if err != nil {
		log.Println("Create request failed for Github API")
	}
	return resp
}

func main() {
	app := cli.NewApp()
	app.Name = "Sink Connector Lightweight CLI"
	app.Usage = "CLI for Sink Connector Lightweight, operations to get status, start/stop replication and set binlog/gtid position"
	// define command for our client
	app.Commands = []cli.Command{
		{
			Name: "start",
			//Aliases: []string{"f"},
			Usage: "Start the replication",
			Action: func(c *cli.Context) error {
				//if c.NArg() > 0 {
				// Github API Logic
				//var repos []Repo
				// user := c.Args()[0]
				var repoUrl = fmt.Sprintf("http://localhost:7000/start")
				resp := getStats(repoUrl)
				///]resp.JSON(&repos)
				log.Println(resp)
				//} else {
				//	log.Println("Please give a username. See -h to see help")
				//}
				return nil
			},
		},
		{
			Name: "stop",
			//Aliases: []string{"c"},
			Usage: "Stop the replication",
			Action: func(c *cli.Context) error {
				//if c.NArg() > 1 {
				// Github API Logic
				//args := c.Args()
				log.Println("***** Stopping replication..... *****")
				var repoUrl = "http://localhost:7000/stop"
				resp := getStats(repoUrl)
				log.Println(resp.String())
				log.Println("***** Replication stopped successfully *****")
				//} else {
				//	log.Println("Please give sufficient arguments. See -h to see help")
				//}
				return nil
			},
		},
		{
			Name: "status",
			//Aliases: []string{"c"},
			Usage: "Status of replication",
			Action: func(c *cli.Context) error {
				//if c.NArg() > 1 {
				// Github API Logic
				//args := c.Args()
				var repoUrl = "http://localhost:7000/status"
				resp := getStats(repoUrl)
				log.Println(resp.String())
				//}
				//else {
				//	log.Println("Please give sufficient arguments. See -h to see help")
				//}
				return nil
			},
		},
		{
			Name: "update_binlog",
			//Aliases: []string{"c"},
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
					return nil
				} else {
					log.Println("Continuing...")
				}
				log.Println("Stopping replication...")
				var stopUrl = "http://localhost:7000/stop"
				resp := getStats(stopUrl)
				time.Sleep(5 * time.Second)
				log.Println("Updating binlog file/position and gtids...")

				log.Println("Starting replication...")
				time.Sleep(5 * time.Second)
				log.Println("Replication started successfully")
				var updateBinLogBody = UpdateBinLog{File: binlogFile, Position: binlogPos, Gtid: gtid}
				var postBody, _ = json.Marshal(updateBinLogBody)
				var requestOptions_copy = requestOptions
				// Add data to JSON field
				requestOptions_copy.JSON = string(postBody)
				// make a Post request to Github
				var url = "http://localhost:7000/binlog"
				resp, err := grequests.Post(url, requestOptions_copy)
				log.Println(resp.String())
				if err != nil {
					log.Println(err)
					log.Println("Create request failed for Github API")
				}
				time.Sleep(10 * time.Second)
				var startUrl = "http://localhost:7000/start"
				resp1 := getStats(startUrl)
				log.Println(resp1.String())
				return nil
				//return resp

				//var repoUrl = fmt.Sprintf("http://localhost:7000/update_binlog?binlog_file=%s&binlog_position=%s&gtid=%s", binlogFile, binlogPos, gitd)

				// var repoUrl = "http://localhost:7000/status"
				//resp := getStats(repoUrl)
				//log.Println(resp.String())

				//return nil
			},
		},
	}

	app.Version = "1.0"
	app.Run(os.Args)
}
