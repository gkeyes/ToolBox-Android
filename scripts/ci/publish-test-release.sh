#!/usr/bin/env bash
# Publishes verified builds from main to the rolling `dev` prerelease that the in-app
# "测试版" update channel reads. Usage: publish-test-release.sh app <delivery dir> | tools <dir of .tbx>
# Needs GH_TOKEN, GH_REPO and GITHUB_SHA. A prerelease never becomes the repository's latest release.
set -euo pipefail

kind="$1"
dir="$2"
tag=dev

if ! gh release view "$tag" >/dev/null 2>&1; then
  # The app and TBX workflows can race to create it; losing the race is fine.
  gh release create "$tag" --prerelease --target "$GITHUB_SHA" --title "ToolBox 测试版" \
    --notes "main 分支最新通过 CI 的构建，供应用内“测试版”更新渠道使用。" \
    || gh release view "$tag" >/dev/null
fi

release_id="$(gh api "repos/$GH_REPO/releases/tags/$tag" --jq .id)"

# A run cancelled mid-swap (newer pushes cancel older runs) leaves its temporary asset behind; drop
# ones old enough that no running publish can still be renaming them.
gh api "repos/$GH_REPO/releases/$release_id/assets?per_page=100" --paginate \
  --jq '.[] | select((.name | startswith("tmp.")) and ((.updated_at | fromdateiso8601) < (now - 1800))) | .id' |
  while read -r stale; do
    # The other publisher may have removed it first.
    gh api -X DELETE "repos/$GH_REPO/releases/assets/$stale" || true
  done

existing() {
  gh release view "$tag" --json assets --jq '.assets[].name'
}

asset_id() {
  gh api "repos/$GH_REPO/releases/$release_id/assets?per_page=100" --paginate \
    --jq ".[] | select(.name == \"$1\") | .id"
}

# `--clobber` deletes the old asset before the new one finishes uploading, so a phone that is
# downloading it meanwhile gets a 404 or a file that no longer matches its checksum. Upload under a
# temporary name first, then swap: the name is missing only between the delete and the rename.
publish_asset() {
  local file="$1" name temp old new staging
  name="$(basename "$file")"
  # The dots keep the temporary name from matching the app or tool patterns the updater reads.
  temp="tmp.${GITHUB_RUN_ID:-local}.$name"
  old="$(asset_id "$name")"
  if [ -z "$old" ]; then
    gh release upload "$tag" "$file"
    return
  fi
  staging="$(mktemp -d)"
  cp "$file" "$staging/$temp"
  gh release upload "$tag" "$staging/$temp" --clobber
  new="$(asset_id "$temp")"
  gh api -X DELETE "repos/$GH_REPO/releases/assets/$old"
  gh api -X PATCH "repos/$GH_REPO/releases/assets/$new" -f name="$name" >/dev/null
}

# Uploads files, then removes older assets matching the pattern, so the release is never empty.
replace_assets() {
  local pattern="$1" file
  shift
  for file in "$@"; do publish_asset "$file"; done
  local keep
  keep="$(printf '%s\n' "$@" | xargs -n1 basename)"
  existing | { grep -E "$pattern" || true; } | while read -r name; do
    if ! grep -qxF "$name" <<<"$keep"; then
      gh release delete-asset "$tag" "$name" --yes
    fi
  done
}

case "$kind" in
  app)
    # A re-run of an older commit, or a manual run next to a push run, can finish after a newer
    # build was published. Never move the test channel back to a commit the published one already
    # contains, and don't publish when the order can't be established.
    lookup() {
      local attempt
      for attempt in 1 2 3; do
        gh api "$@" && return 0
        sleep $((attempt * 5))
      done
      return 1
    }
    current="$(lookup "repos/$GH_REPO/git/ref/tags/$tag" --jq .object.sha)" \
      || { echo "Could not read where $tag points; not publishing." >&2; exit 1; }
    app_assets="$(lookup "repos/$GH_REPO/releases/$release_id/assets?per_page=100" --paginate \
      --jq '.[] | select(.name | test("^toolbox-v.*\\.apk$")) | .name')" \
      || { echo "Could not list $tag assets; not publishing." >&2; exit 1; }
    # Only an app publish moves the tag; one the TBX workflow created without an APK says nothing
    # about which app build is newer.
    if [ -n "$app_assets" ] && [ "$current" != "$GITHUB_SHA" ]; then
      order="$(lookup "repos/$GH_REPO/compare/${current}...${GITHUB_SHA}" --jq .status)" \
        || { echo "Could not compare ${current:0:7} with ${GITHUB_SHA:0:7}; not publishing." >&2; exit 1; }
      if [ "$order" = behind ]; then
        echo "The test channel already has ${current:0:7}, which is newer than ${GITHUB_SHA:0:7}; not publishing."
        exit 0
      fi
    fi
    apk="$(find "$dir" -maxdepth 1 -name 'toolbox-v*-release.apk' -print -quit)"
    test -n "$apk" || { echo "No signed APK in $dir" >&2; exit 1; }
    version="$(basename "$apk")"
    version="${version#toolbox-v}"
    version="${version%-release.apk}"
    # One version number never ships twice: every build has its own bytes (it embeds its run id), so a
    # commit that left the version alone (no new changelog fragment since the published build: CI, docs or
    # scripts only, as app_version.py requires a fragment for anything shipped) keeps the APK already on `dev`. Never offer a lower version either.
    # A failed swap can leave two APKs behind; the highest one is what the channel offers.
    published="$(printf '%s\n' "$app_assets" | sed -n 's/^toolbox-v\(.*\)-dev\.apk$/\1/p' | sort -V | tail -n 1)"
    upload_apk=1
    if [ -n "$published" ]; then
      if [ "$published" = "$version" ]; then
        # The same bytes on `dev` mean this build uploaded them in an earlier attempt that stopped before
        # the receipt or tag; finish those. Any other build of this version is left alone.
        published_digest="$(lookup "repos/$GH_REPO/releases/$release_id/assets?per_page=100" --paginate \
          --jq ".[] | select(.name == \"toolbox-v${version}-dev.apk\") | .digest")" \
          || { echo "Could not read the published APK's checksum; not publishing." >&2; exit 1; }
        if [ "$published_digest" != "sha256:$(sha256sum "$apk" | cut -d' ' -f1)" ]; then
          echo "The test channel already has ToolBox $version; the same version is not published again."
          exit 0
        fi
        echo "The test channel already has this build's APK; finishing its receipt and tag."
        upload_apk=
      fi
      highest="$(printf '%s\n%s\n' "$published" "$version" | sort -V | tail -n 1)"
      if [ "$highest" != "$version" ]; then
        echo "The test channel has ToolBox $published, higher than this build's $version; not publishing." >&2
        exit 1
      fi
    fi
    if [ -n "$upload_apk" ]; then
      staging="$(mktemp -d)"
      cp "$apk" "$staging/toolbox-v${version}-dev.apk"
      replace_assets '^toolbox-v.*\.apk$' "$staging/toolbox-v${version}-dev.apk"
    fi
    publish_asset "$dir/BUILD_RECEIPT.txt"
    gh api -X PATCH "repos/$GH_REPO/git/refs/tags/$tag" -f sha="$GITHUB_SHA" -F force=true >/dev/null
    # The receipt carries the changelog lines of the fragments this version was computed from
    # (CHANGELOG_1, CHANGELOG_2, ... in order) and the version it was raised from.
    changes="$(sed -n 's/^CHANGELOG_[0-9][0-9]*=\(.*\)$/- \1/p' "$dir/BUILD_RECEIPT.txt")"
    base="$(sed -n 's/^VERSION_BASE=\(..*\)$/\1/p' "$dir/BUILD_RECEIPT.txt")"
    gh release edit "$tag" --notes "main 分支最新通过 CI 的构建，供应用内“测试版”更新渠道使用。

- ToolBox ${version}，提交 ${GITHUB_SHA:0:7}，[构建记录](${GITHUB_SERVER_URL:-https://github.com}/${GH_REPO}/actions/runs/${GITHUB_RUN_ID:-})
- 小工具在各自通过 TBX CI 后单独更新。${changes:+

本次更新${base:+（相对 ${base}）}：

${changes}}"
    ;;
  tools)
    shopt -s nullglob
    packages=("$dir"/*.tbx)
    test "${#packages[@]}" -gt 0 || { echo "No TBX packages to publish"; exit 0; }
    # Listing failures stop the script (set -e) rather than risk replacing a published version.
    published="$(existing)"
    for package in "${packages[@]}"; do
      name="$(basename "$package")"
      [[ "$name" =~ ^([a-z0-9][a-z0-9-]*)-v[0-9][0-9A-Za-z.+-]*\.tbx$ ]] || { echo "Unexpected package name: $name" >&2; exit 1; }
      # The file name carries the tool version: an existing one is that version, already published.
      if grep -qxF "$name" <<<"$published"; then
        echo "The test channel already has $name; the same version is not published again."
        continue
      fi
      slug="${BASH_REMATCH[1]}"
      version="${name#"$slug"-v}"
      version="${version%.tbx}"
      # Never replace a higher published version of the same tool with a lower one.
      current="$(sed -n "s/^${slug}-v\([0-9][0-9A-Za-z.+-]*\)\.tbx\$/\1/p" <<<"$published" | sort -V | tail -n 1)"
      if [ -n "$current" ] && [ "$(printf '%s\n%s\n' "$current" "$version" | sort -V | tail -n 1)" != "$version" ]; then
        echo "The test channel has $slug $current, higher than this build's $version; not publishing it." >&2
        refused=1
        continue
      fi
      replace_assets "^${slug}-v[0-9][0-9A-Za-z.+-]*\.tbx$" "$package"
    done
    test -z "${refused:-}"
    ;;
  *)
    echo "Unknown kind: $kind" >&2
    exit 1
    ;;
esac
