package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/urfave/cli"
)

func TestGetServerUrl(t *testing.T) {
	app := newApp()
	globalSet := flag.NewFlagSet("test", flag.ContinueOnError)
	globalSet.String("host", "", "")
	globalSet.String("port", "", "")
	globalSet.Bool("secure", false, "")
	parent := cli.NewContext(app, globalSet, nil)
	subSet := flag.NewFlagSet("sub", flag.ContinueOnError)
	c := cli.NewContext(app, subSet, parent)

	assert.Equal(t, "http://localhost:7000/start_replica", getServerUrl(START_REPLICATION_COMMAND, c))

	assert.NoError(t, globalSet.Set("host", "192.0.2.5"))
	assert.NoError(t, globalSet.Set("port", "8080"))
	assert.NoError(t, globalSet.Set("secure", "true"))
	assert.Equal(t, "https://192.0.2.5:8080/show_replica_status", getServerUrl(STATUS_COMMAND, c))
}

// runAgainst runs the CLI against an httptest server: --host/--port point at it, stdin holds the operator's
// answers and the settle pauses are skipped.
func runAgainst(t *testing.T, server *httptest.Server, stdin string, command ...string) error {
	t.Helper()
	u, err := url.Parse(server.URL)
	assert.NoError(t, err)
	oldInput, oldPause := input, pause
	input, pause = strings.NewReader(stdin), func(time.Duration) {}
	t.Cleanup(func() { input, pause = oldInput, oldPause })
	args := append([]string{"cli", "--host", u.Hostname(), "--port", u.Port()}, command...)
	return newApp().Run(args)
}

func TestActionReturnsErrorOnNon2xxStatus(t *testing.T) {
	for _, status := range []int{400, 404, 500, 503} {
		code := status
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(code)
			fmt.Fprint(w, "boom")
		}))
		err := runAgainst(t, server, "", STATUS_COMMAND)
		server.Close()
		if assert.Error(t, err, "status %d", code) {
			assert.Contains(t, err.Error(), fmt.Sprint(code))
			assert.Contains(t, err.Error(), "boom")
		}
	}
}

func TestActionSucceedsOn2xxStatus(t *testing.T) {
	for _, status := range []int{200, 201, 204} {
		code := status
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(code)
		}))
		err := runAgainst(t, server, "", STOP_REPLICATION_COMAND)
		server.Close()
		assert.NoError(t, err, "status %d", code)
	}
}

func TestActionReturnsErrorOnTransportError(t *testing.T) {
	// a closed server refuses the connection: grequests returns a nil response with an error, which must not
	// be dereferenced
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	server.Close()
	for _, command := range [][]string{{START_REPLICATION_COMMAND}, {STATUS_COMMAND}, {SHOW_SLAVE_STATUS_COMMAND}} {
		assert.Error(t, runAgainst(t, server, "", command...), command[0])
	}
	assert.Error(t, runAgainst(t, server, "y\n", UPDATE_BINLOG_COMMAND, "--gtid", "x"))
	assert.Error(t, runAgainst(t, server, "y\n", DELETE_OFFSETS_COMMAND))
}

func TestUpdateBinlogRunningReplicationIsAnError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
	}))
	defer server.Close()
	err := runAgainst(t, server, "y\n", UPDATE_BINLOG_COMMAND, "--gtid", "uuid:1-5")
	if assert.Error(t, err) {
		assert.Contains(t, err.Error(), "400")
	}
}

func TestUpdateBinlogPostsJSONBody(t *testing.T) {
	var body string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		body = string(raw)
	}))
	defer server.Close()
	assert.NoError(t, runAgainst(t, server, "y\n", UPDATE_BINLOG_COMMAND, "--gtid", "uuid:1-5"))
	assert.Contains(t, body, `"gtid":"uuid:1-5"`)
}

func TestDecliningConfirmationIsNotAnErrorAndSendsNothing(t *testing.T) {
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++ }))
	defer server.Close()
	assert.NoError(t, runAgainst(t, server, "n\n", DELETE_OFFSETS_COMMAND))
	assert.NoError(t, runAgainst(t, server, "", DELETE_SCHEMA_HISTORY_COMMAND))
	assert.Equal(t, 0, calls)
}

func TestUpdateLsnStopFailureAbortsBeforeUpdate(t *testing.T) {
	var paths []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		if r.URL.Path == "/"+STOP_REPLICATION {
			w.WriteHeader(http.StatusInternalServerError)
		}
	}))
	defer server.Close()
	err := runAgainst(t, server, "y\n", UPDATE_LSN_COMMAND, "--lsn", "0/16B3748")
	if assert.Error(t, err) {
		assert.Contains(t, err.Error(), "not stopped")
	}
	assert.Equal(t, []string{"/" + STOP_REPLICATION}, paths, "no lsn update and no start after a failed stop")
}

func TestUpdateLsnStopTransportFailureAborts(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	server.Close()
	assert.Error(t, runAgainst(t, server, "y\n", UPDATE_LSN_COMMAND, "--lsn", "0/1"))
}

func TestUpdateLsnSequenceStopUpdateStart(t *testing.T) {
	var paths []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.Method+" "+r.URL.Path)
	}))
	defer server.Close()
	assert.NoError(t, runAgainst(t, server, "y\n", UPDATE_LSN_COMMAND, "--lsn", "0/1"))
	assert.Equal(t, []string{"GET /stop", "POST /lsn", "GET /start"}, paths)
}

func TestUpdateLsnUpdateFailureDoesNotStartReplication(t *testing.T) {
	var paths []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		if r.URL.Path == "/"+UPDATE_LSN {
			w.WriteHeader(http.StatusBadRequest)
		}
	}))
	defer server.Close()
	assert.Error(t, runAgainst(t, server, "y\n", UPDATE_LSN_COMMAND, "--lsn", "0/1"))
	assert.Equal(t, []string{"/stop", "/lsn"}, paths)
}

func TestDDLTranslateSendsDDLAndFailsOnError(t *testing.T) {
	status := http.StatusOK
	var body string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		body = string(raw)
		w.WriteHeader(status)
	}))
	defer server.Close()
	assert.NoError(t, runAgainst(t, server, "create table t (id int)\n", DDL_TRANSLATE_COMMAND))
	assert.Contains(t, body, "create table t (id int)")
	status = http.StatusInternalServerError
	assert.Error(t, runAgainst(t, server, "create table t (id int)\n", DDL_TRANSLATE_COMMAND))
}

func TestCheckResponseTransportErrorHasNilResponse(t *testing.T) {
	resp, err := checkResponse("GET", "http://x", nil, errors.New("connection refused"))
	assert.Nil(t, resp)
	if assert.Error(t, err) {
		assert.Contains(t, err.Error(), "connection refused")
	}
}
