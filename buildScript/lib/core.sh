#!/bin/bash

buildScript/lib/core/init.sh
go mod tidy
cd libcore/cmd/ruleset_generate
cat go.mod
go mod tidy
cat go.mod
cd ../..
go mod tidy
cd ..
buildScript/lib/core/build.sh
