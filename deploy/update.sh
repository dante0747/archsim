#!/bin/sh
# Moves the hosted kit to a release, or to main, pulls its images and restarts what changed
# (docs/deployment.md, ADR-0080). Run it on the server, from its clone of the repository:
#
#   deploy/update.sh v1.1.0    # a release's tag
#   deploy/update.sh main      # the newest kit, which runs the newest release's images
#
# The demo's deploy workflow (.github/workflows/deploy-demo.yml) runs it over SSH as its key's
# forced command, and the ref is then the command the workflow sent (SSH_ORIGINAL_COMMAND). When
# the images cannot be pulled (a release whose images are not published yet, say), the kit goes
# back to the commit it was on and nothing restarts.
set -eu

# The services that mount a file of the kit: up leaves them on the copy they started with, so
# they start again when a release changes their file.
MOUNTS="caddy:Caddyfile backup:backup.sh"

main() {
	ref="${1:-${SSH_ORIGINAL_COMMAND:-}}"
	case "$ref" in
		*[!A-Za-z0-9.-]*) usage ;;
		main) target=origin/main ;;
		v[0-9]*) target="refs/tags/$ref" ;;
		*) usage ;;
	esac
	cd "$(dirname "$0")"
	git fetch --quiet --tags origin
	previous=$(git rev-parse HEAD)
	git -c advice.detachedHead=false checkout --quiet --detach "$target"
	if ! compose pull --quiet; then
		git -c advice.detachedHead=false checkout --quiet --detach "$previous"
		echo "update.sh: could not pull the images of $ref; the kit stays at $previous" >&2
		exit 1
	fi
	compose up --detach --remove-orphans
	restart_changed_mounts "$previous"
	# the earlier releases' images, which no container uses any more
	docker image prune --all --force --filter label=org.opencontainers.image.source=https://github.com/dante0747/archsim >/dev/null
	echo "update.sh: running $ref, at $(git rev-parse --short HEAD)"
}

compose() {
	docker compose --env-file hosted.env "$@"
}

# Recreates each running service whose mounted file differs from the commit the kit was on.
restart_changed_mounts() {
	running=$(compose ps --services --status running)
	for mount in $MOUNTS; do
		service=${mount%%:*}
		file=${mount#*:}
		if ! git diff --quiet "$1" HEAD -- "$file" && echo "$running" | grep -qx "$service"; then
			compose up --detach --force-recreate --no-deps "$service"
		fi
	done
}

usage() {
	echo "usage: update.sh <a release's tag, such as v1.1.0, or main>" >&2
	exit 2
}

# One line, read whole before it runs, so that the checkout above can replace this file safely.
main "$@"; exit
