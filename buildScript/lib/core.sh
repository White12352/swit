#!/bin/bash

buildScript/lib/core/init.sh
cd libcore
go mod tidy
cd ..
buildScript/lib/core/build.sh
