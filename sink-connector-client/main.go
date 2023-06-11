package main

import (
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log"
	"os"

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
			Name:    "fetch",
			Aliases: []string{"f"},
			Usage:   "Fetch the repo details with user. [Usage]: githubAPI fetch user",
			Action: func(c *cli.Context) error {
				if c.NArg() > 0 {
					// Github API Logic
					var repos []Repo
					user := c.Args()[0]
					var repoUrl = fmt.Sprintf("https://api.github.com/users/%s/repos", user)
					resp := getStats(repoUrl)
					resp.JSON(&repos)
					log.Println(repos)
				} else {
					log.Println("Please give a username. See -h to see help")
				}
				return nil
			},
		},
		{
			Name:    "create",
			Aliases: []string{"c"},
			Usage:   "Creates a gist from the given text. [Usage]: githubAPI name 'description' sample.txt",
			Action: func(c *cli.Context) error {
				if c.NArg() > 1 {
					// Github API Logic
					args := c.Args()
					var postUrl = "https://api.github.com/gists"
					resp := createGist(postUrl, args)
					log.Println(resp.String())
				} else {
					log.Println("Please give sufficient arguments. See -h to see help")
				}
				return nil
			},
		},
	}

	app.Version = "1.0"
	app.Run(os.Args)
}
