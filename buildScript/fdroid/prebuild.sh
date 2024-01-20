#!/bin/bash

buildScript/lib/source.sh

# Setup go & zig & external library
export PATH=$PWD/build/zig:$PATH
buildScript/init/action/zig.sh
export golang=$PWD/build/golang
export GOPATH=$golang/gopath
export GOROOT=$golang/go
export PATH=$golang/go/bin:$GOPATH/bin:$PATH
buildScript/init/action/go.sh
buildScript/init/action/gradle.sh

# Build libcore
buildScript/lib/core.sh

# Setup Node.js & Build dashboard
buildScript/init/action/node.sh
buildScript/lib/dashboard/init.sh
