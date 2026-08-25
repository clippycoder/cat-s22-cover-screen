# Attic

Files removed from the build, kept only because nothing in this project is under version control yet.
Delete the whole directory once the tree has a commit.

| File | Why it is here |
|---|---|
| `FlipMonitor.kt.txt` | Flip-state tracker (`HALL_COVER_CHANGED` + display-power fallback). Unreferenced: the app deliberately does no flip gating. Restore it to `app/src/main/java/com/covernotifier/flip/` if the F1 probe in `../../AUDIT.md` shows the cover-screen receivers die while the flip is open. |
| `AndroidManifest.legacy.xml` | Was sitting at `app/AndroidManifest.xml`, outside the source set AGP reads. Declared a package name unrelated to the real one, `minSdkVersion 21`, and components (`SetupActivity`, `MessageRelayListener`) that do not exist. Inert, but the first manifest a reader would open. |
