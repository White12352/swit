#!/bin/bash

buildScript/lib/core/init.sh
cd libcore
go mod tidy
cd ..
go get libcore/cmd/ruleset_generate
buildScript/lib/core/build.sh
