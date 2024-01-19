#!/bin/bash
echo "!!!!!DON'T FORGET TO SWITCH TO JAVA 17!!!!!"
read -r -p "Enter the version to release: " releaseVersion
read -r -s -p "Enter sonatype password: " sonatypePassword
echo
echo "Starting to release Occurrent $releaseVersion"

versionBeforeRelease=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout) && \
mvn release:prepare -Prelease -DautoVersionSubmodules=true -Dtag="occurrent-${releaseVersion}" -DreleaseVersion="${releaseVersion}" -Darguments="-Dgpg.passphrase=${sonatypePassword}" && \
mvn release:perform -Prelease -Darguments="-Dgpg.passphrase=${sonatypePassword}"
mavenReleaseStatus=$?

if [ $mavenReleaseStatus -eq 0 ]; then
  echo "Release successful, will update version number for modules that were not included in release build." && \
  # Note that we don't use && here because we may already be up-to-date, and if so, pull returns an "error".
  git pull --rebase && \
  versionAfterRelease=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout) && \
  # We normalize versionAfterRelease by escaping dots with \. This is required for sed to work in find.
  normalizedVersionBeforeRelease=$(echo "${versionBeforeRelease}" | sed 's/\./\\./g') && \
  find . -name "pom.xml" -type f -exec sed -i "" "s/${normalizedVersionBeforeRelease}/${versionAfterRelease}/g" {} + && \
  git commit -am "Updated version number for modules that were not included in release build to ${versionAfterRelease}" && \
  git push && \
  echo "Maven release of Occurrent $releaseVersion completed successfully"
else
  echo "Maven release of Occurrent $releaseVersion failed"
  exit $mavenReleaseStatus
fi

