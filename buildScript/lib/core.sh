#!/bin/bash

buildScript/lib/core/init.sh
cd libcore/cmd/ruleset_generate
go mod init main.go
go mod tidy
cd ../..
go mod tidy
cd ..
buildScript/lib/core/build.sh
