# Repos widget

Android home-screen widget listing Jono's GitHub repos, most recently pushed first, with each
repo's latest CI run and commit. Tap a repo to open it in the GitHub app; the GitHub icon opens the
app itself. Repos that match an installed app (`com.jono.<repo name without dashes>`) show that
app's icon and an **Update** chip when a newer `build.yml` build is on GitHub.

Needs a read-only fine-grained token (Contents + Actions: read), pasted into the app's setup screen.

Every push to `main` builds `github-widget.apk` and publishes it as the latest release.
