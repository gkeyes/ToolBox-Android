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
    # Main runs queue in order, but a re-run of an older commit can still finish after a newer one
    # was published. Never move the test channel back to a commit the published one already contains.
    current="$(gh api "repos/$GH_REPO/git/ref/tags/$tag" --jq .object.sha 2>/dev/null || true)"
    if [ -n "$current" ] && [ "$current" != "$GITHUB_SHA" ]; then
      order="$(gh api "repos/$GH_REPO/compare/${current}...${GITHUB_SHA}" --jq .status 2>/dev/null || echo unknown)"
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
    staging="$(mktemp -d)"
    cp "$apk" "$staging/toolbox-v${version}-dev.apk"
    replace_assets '^toolbox-v.*\.apk$' "$staging/toolbox-v${version}-dev.apk"
    publish_asset "$dir/BUILD_RECEIPT.txt"
    gh api -X PATCH "repos/$GH_REPO/git/refs/tags/$tag" -f sha="$GITHUB_SHA" -F force=true >/dev/null
    gh release edit "$tag" --notes "main 分支最新通过 CI 的构建，供应用内“测试版”更新渠道使用。

- ToolBox ${version}，提交 ${GITHUB_SHA:0:7}，[构建记录](${GITHUB_SERVER_URL:-https://github.com}/${GH_REPO}/actions/runs/${GITHUB_RUN_ID:-})
- 小工具在各自通过 TBX CI 后单独更新。"
    ;;
  tools)
    shopt -s nullglob
    packages=("$dir"/*.tbx)
    test "${#packages[@]}" -gt 0 || { echo "No TBX packages to publish"; exit 0; }
    for package in "${packages[@]}"; do
      name="$(basename "$package")"
      [[ "$name" =~ ^([a-z0-9][a-z0-9-]*)-v[0-9][0-9A-Za-z.+-]*\.tbx$ ]] || { echo "Unexpected package name: $name" >&2; exit 1; }
      replace_assets "^${BASH_REMATCH[1]}-v[0-9][0-9A-Za-z.+-]*\.tbx$" "$package"
    done
    ;;
  *)
    echo "Unknown kind: $kind" >&2
    exit 1
    ;;
esac
