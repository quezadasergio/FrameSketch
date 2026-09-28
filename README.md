# FrameSketch

Desktop app for video playback and sports play analysis — draw, write, and annotate directly on the frame (with jitter, undo, and more).

## Requirements

- **Java 25** (JDK) for development and for building installers
- **VLC** installed on the system (libVLC), **same architecture as the app** (on Apple Silicon use arm64 VLC, not the Intel/x86_64 build). FrameSketch uses [VLCJ](https://github.com/caprica/vlcj). Native installers **do not bundle VLC**; the app asks you to install it if it is missing.
- **FFmpeg** (and **ffprobe**) for development. Native installers **bundle a portable FFmpeg**, so end users do not need to install it. Optional override: `FRAMESKETCH_FFMPEG=/path/to/ffmpeg`.

### macOS (Homebrew)

```bash
brew install openjdk@25
brew install --cask vlc
brew install ffmpeg
# If you already had Intel (x86_64) VLC on Apple Silicon:
# brew reinstall --cask vlc --force
```

Make sure `java -version` reports 25, or export:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || echo /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home)"
export PATH="$JAVA_HOME/bin:$PATH"
```

Confirm libVLC is arm64 on Apple Silicon:

```bash
file /Applications/VLC.app/Contents/MacOS/lib/libvlccore.dylib
```

### Windows

Install **Java 25**, **VLC**, and **FFmpeg** (FFmpeg is only required for `gradlew run` / development; the `.exe` installer already includes it).

With [winget](https://learn.microsoft.com/windows/package-manager/winget/):

```bat
winget install Microsoft.OpenJDK.25
winget install VideoLAN.VLC
winget install Gyan.FFmpeg
```

Alternatively with Chocolatey:

```bat
choco install temurin25
choco install vlc
choco install ffmpeg
```

Or download manually:

- Java 25: https://adoptium.net/ or https://learn.microsoft.com/java/openjdk/download
- VLC: https://www.videolan.org/vlc/ (match x64 vs ARM with your JDK)
- FFmpeg: https://www.gyan.dev/ffmpeg/builds/ or https://github.com/BtbN/FFmpeg-Builds/releases — add `ffmpeg.exe` and `ffprobe.exe` to `PATH`

After installing, open a **new** terminal and check:

```bat
java -version
vlc --version
ffmpeg -version
```

## Run in development

```bash
./gradlew run
```

Windows:

```bat
gradlew.bat run
```

## Fat JAR

Build an executable JAR with all Java dependencies:

```bash
./gradlew fatJar
```

Output: `build/libs/FrameSketch-1.0.0-all.jar`

Run:

```bash
java --enable-native-access=ALL-UNNAMED --enable-native-access=javafx.graphics --sun-misc-unsafe-memory-access=allow -jar build/libs/FrameSketch-1.0.0-all.jar
```

You can copy the JAR to another machine **with the same OS/architecture for the JavaFX natives from this build**, plus Java 25, VLC, and FFmpeg installed.

## Native installers (Mac and Windows)

These tasks call `jpackage` (JDK 25) and **copy a portable FFmpeg/ffprobe into the app**. **VLC is not copied**; users must install VLC themselves.

Build on the **target OS** (a Mac `.dmg` cannot be built on Windows, and a Windows `.exe` cannot be built on a Mac).

```bash
# Current OS:
./gradlew packageApp

# macOS only (creates a .dmg):
./gradlew packageMac

# Windows only (creates a .exe installer; needs [WiX Toolset 3](https://wixtoolset.org/)):
gradlew.bat packageWin
```

Output goes to `build/dist/`.

Optional: put your own `ffmpeg` / `ffprobe` (or `ffmpeg.exe` / `ffprobe.exe`) in `packaging/ffmpeg-prebuilt/` to skip the download from [BtbN FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds/releases).

The packaged app still shows a message if VLC is missing.

## Features

- Playback of popular formats via VLC (MP4, MKV, AVI, MOV, WebM, and more)
- On **add to playlist** / open, FFmpeg queues **forward and reversed H.264 proxies**; progress shows next to controls as `name  time=HH:MM:SS.xx`
- **M** / **N** switch direction with a shared time anchor (pressing the same mode again is a no-op)
- **V** / **B** slow motion (0.50x) in reverse / forward
- Playlist hover tooltip: total duration + reverse conversion status
- Controls with Material Design 2 icons (Ikonli) and tooltips
- Timeline **keyframes**, **A/B markers**, annotations on video

### Keyboard shortcuts

| Key | Action |
|-----|--------|
| `Z` | Play / Pause |
| `X` | Stop |
| `C` | Go to start |
| `V` | Slow motion reverse |
| `B` | Slow motion forward |
| `N` | Forward 1x (switch from reverse) |
| `M` | Reverse 1x (switch from forward) |
| `W` | Undo last annotation |
| `Q` | Clear all annotations |
| `A` / `S` | Previous / next keyframe |
| `K` | Add keyframe |
| `1` / `2` | Marker A / B |
| `Delete` / `Backspace` | Delete selected keyframe |

## Notes

- Smooth reverse (and reliable forward after reverse) uses FFmpeg-generated clips; first preparation can take a while. Cache: `framesketch-proxy-cache` in the system temp directory (cleared when the app closes, or when a clip is removed from the playlist).
- Switching clips in the playlist clears annotations, keyframes, and markers.
