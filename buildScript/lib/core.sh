#!/bin/bash

buildScript/lib/core/init.sh
cd libcore/cmd/ruleset_generate
go mod init main.go
cat go.mod
go mod tidy
cat go.mod
cd ../..
go mod tidy
cd ..
buildScript/lib/core/build.sh
