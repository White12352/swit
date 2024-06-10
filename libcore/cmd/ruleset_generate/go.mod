module main.go

go 1.22.4

require (
	github.com/oschwald/geoip2-golang v1.11.0
	github.com/oschwald/maxminddb-golang v1.13.0
	github.com/sagernet/sing v0.4.1
	github.com/sagernet/sing-box v1.9.3
	github.com/v2fly/v2ray-core/v5 v5.16.1
	google.golang.org/protobuf v1.34.1
)

require (
	github.com/adrg/xdg v0.4.0 // indirect
	github.com/golang/protobuf v1.5.4 // indirect
	github.com/miekg/dns v1.1.59 // indirect
	github.com/sagernet/sing-dns v0.2.0 // indirect
	go4.org/netipx v0.0.0-20231129151722-fdeea329fbba // indirect
	golang.org/x/mod v0.17.0 // indirect
	golang.org/x/net v0.25.0 // indirect
	golang.org/x/sync v0.7.0 // indirect
	golang.org/x/sys v0.21.0 // indirect
	golang.org/x/tools v0.21.0 // indirect
)

replace github.com/sagernet/sing v0.4.1 => github.com/White12352/sing v0.5.0-alpha.9
replace github.com/sagernet/sing-box v1.9.3 => github.com/White12352/sing-box v1.10.0-alpha.11
