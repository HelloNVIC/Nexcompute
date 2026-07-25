// Package agent - HTTP 工具
package agent

import (
	"net/http"
)

func httpClient() *http.Client {
	return &http.Client{}
}

func newRequest(method, url, token, instance string) *http.Request {
	req, _ := http.NewRequest(method, url, nil)
	req.Header.Set("X-Agent-Token", token)
	req.Header.Set("X-Instance-Number", instance)
	return req
}
