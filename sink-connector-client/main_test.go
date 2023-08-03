package main

import (
	"github.com/stretchr/testify/assert"
	"github.com/urfave/cli"
	"testing"
)

func TestGetServerUrl(t *testing.T) {
	var c = cli.Context{
		App:     cli.NewApp(),
		Command: cli.Command{},
	}
	c.App.Flags = []cli.Flag{
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
	//var c = cli.NewContext(nil, nil, nil)
	var serverUrl = getServerUrl("start", &c)
	assert.Equal(t, "http://localhost:7000/start_replica", serverUrl, "they should be equal")
}
