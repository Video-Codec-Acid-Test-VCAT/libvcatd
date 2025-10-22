#!/usr/bin/env bash
set -euo pipefail

# Clean + build both variants
./gradlew :libvcat:clean :libvcat:assembleRelease :libvcat:assembleDebug

# Publish all publications (release + debug) to GitHub Packages
./gradlew :libvcat:publishAllPublicationsToGitHubPackagesRepository

