#!/bin/bash

buildScript/lib/core/init.sh
cd libcore
go get github.com/sagernet/sing-box/common/geosite
go get github.com/sagernet/sing-box/common/srs
go get github.com/sagernet/sing-box/constant
go get github.com/sagernet/sing-box/option
go get github.com/sagernet/sing/common
go mod tidy
cd ..
buildScript/lib/core/build.sh
