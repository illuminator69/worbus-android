#!/usr/bin/env bash
# The name check (Worbus P5, design §6.2; ruling R1): no tracked file names upstream's package or IDs,
# and no person-visible "Navic" or "Feishin" is left, except where tools/check-names.allow says why.
#
#   tools/check-names.sh            check the working tree's tracked files
#   tools/check-names.sh <rev>      check a commit instead (e.g. the base, to see it red)
#
# Hits: the fixed strings `paige.navic`, `paige/navic`, `org.jeffvli`, and the case-sensitive WHOLE
# words `Navic` and `Feishin` (git grep -w: an identifier that only contains the word, NavicTheme,
# is not a hit; nor is the lower-case kept key navic_secrets). Binary files are skipped.
#
# tools/check-names.allow: one entry per line, three fields separated by TABs:
#   <path glob>	<text the hit's line contains, or *>	<reason>
# A hit is allowed when an entry's glob matches its path (bash pattern, * crosses /) and the line
# contains the text. Lines starting with # and blank lines are ignored; an entry with no reason is an
# error. Exits 0 when every hit is allowed, 1 when one is not (each printed), 2 on a usage error.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
rev="${1:-}"
allow_file="tools/check-names.allow"
[ -f "$allow_file" ] || { echo "check-names: $allow_file is missing" >&2; exit 2; }

globs=(); texts=()
n=0
while IFS= read -r line || [ -n "$line" ]; do
	n=$((n + 1))
	case "$line" in ''|'#'*) continue ;; esac
	IFS=$'\t' read -r glob text reason <<<"$line"
	if [ -z "${glob:-}" ] || [ -z "${text:-}" ] || [ -z "${reason:-}" ]; then
		echo "check-names: $allow_file:$n needs three TAB-separated fields (path glob, text, reason)" >&2; exit 2
	fi
	globs+=("$glob"); texts+=("$text")
done < "$allow_file"

hits() {
	# `git grep` prints <rev>:<path>:<line>:<text> for a rev; strip the rev so both read the same.
	{
		git grep -n -I -F -e 'paige.navic' -e 'paige/navic' -e 'org.jeffvli' ${rev:+"$rev"} -- . || true
		git grep -n -I -w -e 'Navic' -e 'Feishin' ${rev:+"$rev"} -- . || true
	} | if [ -n "$rev" ]; then sed "s|^$rev:||"; else cat; fi | sort -u
}

bad=0; total=0; allowed=0
while IFS= read -r hit; do
	[ -n "$hit" ] || continue
	total=$((total + 1))
	path="${hit%%:*}"; rest="${hit#*:}"; text="${rest#*:}"
	ok=0
	for i in "${!globs[@]}"; do
		# shellcheck disable=SC2053 # the glob is a pattern on purpose
		if [[ "$path" == ${globs[$i]} ]] && { [ "${texts[$i]}" = "*" ] || [[ "$text" == *"${texts[$i]}"* ]]; }; then
			ok=1; break
		fi
	done
	if [ "$ok" = 1 ]; then allowed=$((allowed + 1)); else bad=$((bad + 1)); echo "$hit"; fi
done < <(hits)

echo "check-names: $total hits, $allowed allowed, $bad not allowed${rev:+ (at $rev)}" >&2
[ "$bad" = 0 ]
