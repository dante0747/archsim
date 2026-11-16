#!/bin/sh
# The hosted stack's database backups (compose.yaml's backup service, ADR-0080): a pg_dump of the
# database into the backups volume, of which the newest ARCHSIM_BACKUP_KEEP (7) are kept.
#
#   archsim-backup nightly   # the service: a dump every night at ARCHSIM_BACKUP_HOUR (3) UTC
#   archsim-backup now       # one dump now
#
# It connects as PGHOST, PGUSER, PGDATABASE and PGPASSWORD say. A dump is written under a
# temporary name and renamed once complete, so only complete dumps count toward those kept, and a
# failed one never pushes a good one out. Restore one with pg_restore (docs/deployment.md).
set -eu

BACKUPS=/backups
HOUR="${ARCHSIM_BACKUP_HOUR:-3}"
KEEP="${ARCHSIM_BACKUP_KEEP:-7}"
DAY=86400

case "$HOUR" in
	[0-9] | 1[0-9] | 2[0-3]) ;;
	*) echo "archsim-backup: ARCHSIM_BACKUP_HOUR is an hour from 0 to 23, not $HOUR" >&2; exit 2 ;;
esac
case "$KEEP" in
	'' | *[!0-9]* | 0) echo "archsim-backup: ARCHSIM_BACKUP_KEEP is a number of dumps, not $KEEP" >&2; exit 2 ;;
esac

# A dump now, then the oldest dumps beyond the ones kept removed; their names sort by time.
dump() {
	file="$BACKUPS/archsim-$(date -u +%Y-%m-%dT%H%M%SZ).dump"
	if ! pg_dump --format=custom --file="$file.partial"; then
		rm -f "$file.partial"
		echo "archsim-backup: pg_dump failed; the earlier dumps are kept" >&2
		return 1
	fi
	mv "$file.partial" "$file"
	echo "archsim-backup: wrote $file ($(du -h "$file" | cut -f1))"
	ls -1 "$BACKUPS"/archsim-*.dump | sort -r | tail -n +"$((KEEP + 1))" | while read -r old; do
		rm -f "$old"
		echo "archsim-backup: removed $old"
	done
}

# Seconds from now until the next ARCHSIM_BACKUP_HOUR o'clock, UTC.
until_next() {
	now=$(date -u +%s)
	next=$((now - now % DAY + HOUR * 3600))
	if [ "$next" -le "$now" ]; then
		next=$((next + DAY))
	fi
	echo $((next - now))
}

# A dump every night. The sleep runs in the background so that docker stop's SIGTERM ends it at
# once, rather than after the stop's timeout.
nightly() {
	trap 'exit 0' TERM INT
	# a partial dump left by the service stopped mid-dump; none is in progress yet
	rm -f "$BACKUPS"/*.partial
	echo "archsim-backup: a dump every night at $HOUR:00 UTC into $BACKUPS, the newest $KEEP kept"
	while :; do
		sleep "$(until_next)" &
		wait $!
		dump || true
	done
}

case "${1:-}" in
	nightly) nightly ;;
	now) dump ;;
	*)
		echo "usage: archsim-backup nightly | now" >&2
		exit 2
		;;
esac
