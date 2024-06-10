#!/bin/bash

buildScript/lib/core/init.sh
cd libcore
go mod tidy
go get libcore/cmd/ruleset_generate
cd ..
buildScript/lib/core/build.sh
