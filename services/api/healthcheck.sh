#!/usr/bin/env bash
set -eu

exec 3<>/dev/tcp/127.0.0.1/${PORT:-8181}
printf 'GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
IFS= read -r status <&3
[[ "$status" =~ ^HTTP/1\.[01]\ 200([[:space:]]|$) ]]
