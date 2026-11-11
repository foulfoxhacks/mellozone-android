# Mello Zone for Android

Native Android app for [mellozone.site](https://mellozone.site).
Package name: `com.foulfoxhacks.mellozone`.

It is not a web app and contains no WebView. A Rust engine downloads each page,
converts it into typed content blocks, caches it, and indexes it for search.
A Jetpack Compose layer draws those blocks with real Android widgets.

```
mellozone.site ──HTTPS──▶ rust/ (mellozone-core)          app/ (Kotlin + Compose)
                          ├─ net.rs     ureq + rustls      ├─ core/   JNI bridge, models
                          ├─ parser.rs  HTML → blocks      ├─ ui/blocks   block renderer
                          ├─ store.rs   offline cache      ├─ ui/screens  Home, Journal,
                          ├─ search.rs  full-text search   │              Explore, Saved,
                          ├─ core.rs    API + sync         │              Info, Developer
                          └─ android.rs JNI entry point    └─ ui/MelloRoot  drawer, nav
```

The two halves talk through one JNI function,
`NativeCore.nativeCall(method, jsonPayload) → jsonEnvelope`. Every call runs on
a background thread, so the main thread never waits on the network, the disk
or parsing.

## Build

Requirements: JDK 17 or newer, Android SDK with platform 36, NDK
`28.2.13676358`, Rust with the Android targets, and `cargo-ndk`.

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

```bash
cargo install cargo-ndk
```

Release bundle for Google Play (Gradle builds the Rust engine first):

```bash
./gradlew bundleRelease
```

The signed bundle lands in `app/build/outputs/bundle/release/`. Signing reads
`keystore.properties` in this folder; without it the build is unsigned.

**Windows path limit.** If this folder sits under a very long path, set
`MZ_BUILD_ROOT` to a short folder (for example `C:\mzbuild`) before building.
Build output then goes there instead of into the project.

## Test the engine without a device

```bash
cd rust && cargo test
```

```bash
cd rust && cargo run --example crawl
```

`crawl` runs the same engine against the live site: it downloads every public
page, reports block statistics, and runs sample searches.

## Developer options

In the app: **Info → Developer options**, or the last item in the slide-out
drawer. It shows engine and device diagnostics, the engine log, a self-test,
cache tools, and switches for forcing network loads, simulating offline, and
outlining content blocks.

## Signing key

`keystore/upload-keystore.jks` is the Play **upload** key and
`keystore.properties` holds its password. Both are excluded from git. Back them
up somewhere private: you need them for every future update. (With Play App
Signing, Google can reset a lost upload key, but it takes a support request.)

## Store submission

See `store/play-listing.md` for listing text, the data safety answers, the
content-rating answers and the release checklist.
