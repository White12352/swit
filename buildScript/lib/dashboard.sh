#!/bin/bash

set -e
# set -x

DIST_NAME="Dash-metacubexd"

bun -v

rm -rf ./${DIST_NAME}

pushd metacubexd/
DASH_VERSION=$(git log --pretty=format:"%ad" --graph --date=short HEAD -1 | tr -cd "[0-9]")
echo "$DASH_VERSION"
bun install
bun run build
mv dist ../${DIST_NAME}
popd

tar --mtime='1970-01-01' -czf "app/src/main/assets/dashboard.tar" ${DIST_NAME}
echo -n "$DASH_VERSION" >app/src/main/assets/dashboard.version.txt

echo ">> install ${DIST_NAME} to app/src/main/assets/dashboard.tar"
sha256sum app/src/main/assets/dashboard.tar
