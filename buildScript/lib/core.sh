#!/bin/bash

buildScript/lib/core/init.sh
cd libcore
go get -t
cd ..
buildScript/lib/core/build.sh
