#!/usr/bin/env bash
# Move the app's Kotlin package (Worbus P5, design §6.3). Kept in the tree so a branch cut before
# the move can be put through the same steps.
#
#   tools/rename-package.sh <from> <to> [<rootProjectName>]
#   tools/rename-package.sh paige.navic app.worbus Worbus
#
# Run from the repository root. Idempotent: a second run changes nothing and prints zeros.
# What it does, and prints a count for:
#   1. `git mv` every source tree <module>/src/<set>/kotlin/<from as a path> to <to as a path>
#      (directory-wise when the target is absent, file-wise into a target that already exists);
#   2. `git mv` Room's schema directories composeApp/schemas/<from>.* to <to>.*;
#   3. rewrites the dotted name (package and import lines, fully qualified uses, KDoc links, string
#      keys) and the slashed name (paths) in tracked *.kt, *.kts, *.pro and AndroidManifest.xml
#      files. A line naming `applicationId` is left alone: the app ID is an ID, not the package;
#   4. with <rootProjectName>, sets `rootProject.name` in settings.gradle.kts; then, whatever the
#      name is, rewrites the Compose resource package (`<name>.composeapp.generated.resources`,
#      lower-cased root project name) in *.kt files to match it.
# It never touches docs (CLAUDE.md, README.md), resources other than the manifest, or itself.
set -euo pipefail

usage() { sed -n 2,19p "$0" >&2; exit 2; }
[ $# -ge 2 ] && [ $# -le 3 ] || usage
from="$1"; to="$2"; root_name="${3:-}"
dotted='^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'
[[ "$from" =~ $dotted ]] || { echo "rename-package: <from> must be a dotted package: $from" >&2; exit 2; }
[[ "$to" =~ $dotted ]] || { echo "rename-package: <to> must be a dotted package: $to" >&2; exit 2; }
[ "$from" != "$to" ] || { echo "rename-package: <from> and <to> are the same" >&2; exit 2; }
[ -f settings.gradle.kts ] && [ -d .git -o -f .git ] || { echo "rename-package: run from the repository root" >&2; exit 2; }
if [ -n "$root_name" ] && ! [[ "$root_name" =~ ^[A-Za-z][A-Za-z0-9]*$ ]]; then
	echo "rename-package: <rootProjectName> must be letters and digits: $root_name" >&2; exit 2
fi

from_path="${from//./\/}"
to_path="${to//./\/}"

# 1. Source trees.
dirs_moved=0; files_moved=0
while IFS= read -r -d '' src; do
	base="${src%/"$from_path"}"
	dest="$base/$to_path"
	if [ ! -e "$dest" ]; then
		mkdir -p "$(dirname "$dest")"
		n=$(git ls-files -- "$src" | wc -l)
		git mv "$src" "$dest"
		dirs_moved=$((dirs_moved + 1)); files_moved=$((files_moved + n))
	else
		while IFS= read -r f; do
			rel="${f#"$src"/}"
			mkdir -p "$(dirname "$dest/$rel")"
			git mv "$f" "$dest/$rel"
			files_moved=$((files_moved + 1))
		done < <(git ls-files -- "$src")
		dirs_moved=$((dirs_moved + 1))
	fi
	# Remove the directories the move emptied (untracked leftovers such as .DS_Store stop it).
	d="$src"
	while [ "$d" != "$base" ] && [ -d "$d" ] && [ -z "$(ls -A "$d")" ]; do rmdir "$d"; d="$(dirname "$d")"; done
done < <(find . -path ./.git -prune -o -path '*/build' -prune -o -type d -path "*/src/*/kotlin/$from_path" -print0)

# 2. Room's schema history (named after each @Database class's fully qualified name).
schemas_moved=0
while IFS= read -r -d '' s; do
	name="$(basename "$s")"
	dest="$(dirname "$s")/$to${name#"$from"}"
	if [ -e "$dest" ]; then echo "rename-package: $dest already exists; not moving $s" >&2; exit 1; fi
	git mv "$s" "$dest"
	schemas_moved=$((schemas_moved + 1))
done < <(find . -path ./.git -prune -o -path '*/build' -prune -o -type d -path '*/schemas/*' -name "$from.*" -print0)

# 3 and 4. Text.
if [ -n "$root_name" ]; then
	perl -0pi -e 's/^rootProject\.name\s*=\s*"[^"]*"/rootProject.name = "'"$root_name"'"/m' settings.gradle.kts
fi
current_root="$(sed -n 's/^rootProject\.name *= *"\([^"]*\)".*/\1/p' settings.gradle.kts | head -1)"
[ -n "$current_root" ] || { echo "rename-package: no rootProject.name in settings.gradle.kts" >&2; exit 1; }
res_pkg="$(printf '%s' "$current_root" | tr '[:upper:]' '[:lower:]').composeapp.generated.resources"

git ls-files -z -- '*.kt' '*.kts' '*.pro' '*AndroidManifest.xml' ':!tools/rename-package.sh' |
	FROM="$from" TO="$to" FROM_PATH="$from_path" TO_PATH="$to_path" RES_PKG="$res_pkg" perl -0 -ne '
	BEGIN { ($from, $to, $fp, $tp, $res) = @ENV{qw(FROM TO FROM_PATH TO_PATH RES_PKG)};
		%n = (files => 0, decl => 0, uses => 0, paths => 0, res => 0); }
	chomp; my $file = $_; next unless -f $file;
	open my $in, "<", $file or die "$file: $!"; local $/; my $text = <$in>; close $in;
	my $kt = $file =~ /\.kt$/;
	my $out = join "", map {
		my $line = $_;
		unless ($line =~ /\bapplicationId\b/) {
			$n{decl} += ($line =~ s/^(\s*(?:package|import)\s+)\Q$from\E(?![\w])/$1$to/);
			$n{uses} += ($line =~ s/(?<![\w.])\Q$from\E(?![\w])/$to/g);
			$n{paths} += ($line =~ s{(?<!\w)\Q$fp\E(?!\w)}{$tp}g);
		}
		if ($kt) {
			$line =~ s{(?<![\w.])([a-z][a-z0-9_]*\.composeapp\.generated\.resources)(?![\w])}{
				$1 eq $res ? $1 : do { $n{res}++; $res }
			}ge;
		}
		$line;
	} split /(?<=\n)/, $text;
	if ($out ne $text) { open my $o, ">", $file or die "$file: $!"; print $o $out; close $o; $n{files}++; }
	END { printf "rewritten: %d files; %d package/import lines, %d other dotted uses, %d paths, %d resource-package names\n",
		@n{qw(files decl uses paths res)}; }
'
echo "moved: $dirs_moved source trees ($files_moved files), $schemas_moved schema directories"
echo "root project: $current_root (resources: $res_pkg)"
