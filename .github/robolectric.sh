#!/usr/bin/env bash
# Образы Android для Robolectric — заранее и с повторами: сами тесты скачивают их без повторов, и один сбой сети ронял всю проверку
# («Failed to fetch maven artifact», 23–25.11.2026). Кладёт туда же, где их ищет Robolectric.
set -euo pipefail
base=https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented
dest="$HOME/.m2/repository/org/robolectric/android-all-instrumented"
while read -r version; do
  [ -z "$version" ] && continue
  name="android-all-instrumented-$version"
  mkdir -p "$dest/$version"
  for ext in pom pom.sha512 jar jar.sha512; do
    file="$dest/$version/$name.$ext"
    [ -s "$file" ] && continue
    curl -fsSL --retry 6 --retry-delay 5 --retry-all-errors -o "$file.part" "$base/$version/$name.$ext"
    mv "$file.part" "$file"
  done
  echo "$(cut -c1-128 "$dest/$version/$name.jar.sha512")  $dest/$version/$name.jar" | sha512sum -c --quiet - || { rm -f "$dest/$version/$name.jar"; echo "повреждён $name"; exit 1; }
done < "$(dirname "$0")/robolectric.txt"
