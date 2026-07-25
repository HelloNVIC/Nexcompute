# Nexcompute 受控端构建脚本（任务 5.11）
# 需要 Go 1.23+ 与 CGO（Fyne 依赖 CGO）

APP_NAME := nexcompute-agent
VERSION := 0.1.0
LDFLAGS := -X main.version=$(VERSION)

.PHONY: build clean

build:
	go build -ldflags "$(LDFLAGS)" -o $(APP_NAME).exe ./cmd/nexcompute-agent

build-linux:
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o $(APP_NAME) ./cmd/nexcompute-agent

clean:
	rm -f $(APP_NAME) $(APP_NAME).exe

test:
	go test ./...
