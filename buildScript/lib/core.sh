#!/bin/bash

buildScript/lib/core/init.sh
cd libcore
go mod tidy
buildScript/lib/core/build.sh
