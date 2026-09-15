# FrameSketch

Desktop app for video playback and sports play analysis — draw, write, and annotate directly on the frame (with jitter, undo, and more).

## Requirements

- **Java 25** (JDK or JRE)
- **VLC** installed on the system (libVLC), **same architecture as the JDK** (on Apple Silicon use arm64 VLC, not the Intel/x86_64 build). FrameSketch uses [VLCJ](https://github.com/caprica/vlcj); the fat JAR bundles Java dependencies, not VLC native binaries.
- **FFmpeg** (reversed-clip cache for smooth reverse). On macOS: `brew install ffmpeg`. Optional: `FRAMESKETCH_FFMPEG=/path/to/ffmpeg`.

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

## Run in development

```bash
./gradlew run
```

## Fat JAR

Build an executable JAR with all Java dependencies:

```bash
./gradlew fatJar
```

Output: `build/libs/FrameSketch-1.0.0-all.jar`

Run:

```bash
java --enable-native-access=ALL-UNNAMED -jar build/libs/FrameSketch-1.0.0-all.jar
```

You can copy the JAR to another machine **with the same OS/architecture for the JavaFX natives from this build**, plus Java 25, VLC, and FFmpeg installed.

## Features

- Playback of popular formats via VLC (MP4, MKV, AVI, MOV, WebM, and more)
- On **add to playlist** / open, FFmpeg queues **fully reversed clips**; progress shows next to controls as `name  time=HH:MM:SS.xx`
- **M** / **N** switch direction with a shared time anchor (pressing the same mode again is a no-op)
- **V** / **B** slow motion (0.25x) in reverse / forward
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

- Smooth reverse uses an FFmpeg-generated reversed clip; first preparation can take a while. Cache: `framesketch-proxy-cache` in the system temp directory (cleared when the app closes, or when a clip is removed from the playlist).
- Switching clips in the playlist clears annotations, keyframes, and markers.
