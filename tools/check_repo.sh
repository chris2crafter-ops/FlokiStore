#!/bin/bash
# Prints "owner/repo | newest tag | apk/zip assets | description" for a GitHub repo,
# using the github.com pages (no API quota). Used to vet new catalog candidates.
r=$1
loc=$(curl -s -o /dev/null -w '%{redirect_url}' "https://github.com/$r/releases/latest")
if [[ "$loc" == *"/tag/"* ]]; then tag=${loc##*/tag/}; pre=""; else tag=$(curl -sL "https://github.com/$r/releases" | grep -oiE "/$r/releases/tag/[^\"]+" | head -1 | sed 's#.*/tag/##'); pre="(pre)"; fi
desc=$(curl -sL "https://github.com/$r" | grep -oE '<meta name="description" content="[^"]*"' | head -1 | sed 's/.*content="//;s/"$//;s/ Contribute to .*//' | cut -c1-170)
if [ -z "$tag" ]; then echo "$r | - | NO RELEASES | $desc"; exit; fi
files=$(curl -sL "https://github.com/$r/releases/expanded_assets/$tag" | grep -oE 'href="[^"]+/releases/download/[^"]+\.(apk|zip)"' | sed 's#.*/##;s#"##' | tr '\n' ' ')
echo "$r | $tag $pre | ${files:-NO FILES} | $desc"
