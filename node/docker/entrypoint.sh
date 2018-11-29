#!/usr/bin/env bash
if [ -z "$TENDERMINT_IP" ]; then
  cat >&2 <<EOF
error: \`-e "TENDERMINT_IP=your_external_ip"\` was not specified.
It is required so Tendermint instance can advertise its address to cluster participants
EOF
  exit 1
fi

if [ ! -S /var/run/docker.sock ]; then
    cat >&2 <<EOF
error: '/var/run/docker.sock' not found in container or is not a socket.
Please, pass it as a volume when running the container: \`-v /var/run/docker.sock:/var/run/docker.sock\`
EOF
exit 1
fi

exec "$@"