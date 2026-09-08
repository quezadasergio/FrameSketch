# FrameSketch

Desktop app for video playback and sports play analysis — draw, write, and annotate directly on the frame (with jitter, undo, and more).

## Requirements

- **Java 25** (JDK or JRE)
- **VLC** installed on the system (libVLC), **same architecture as the JDK** (on Apple Silicon use arm64 VLC, not the Intel/x86_64 build). FrameSketch uses [VLCJ](https://github.com/caprica/vlcj); the fat JAR bundles Java dependencies, not VLC native binaries.
- **FFmpeg** (for smooth reverse playback). On macOS: `brew install ffmpeg`. Optional: `FRAMESKETCH_FFMPEG=/path/to/ffmpeg`.

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

You can copy the JAR to another machine **with the same OS/architecture for the JavaFX natives from this build**, plus Java 25 and VLC installed.

## Features

- Playback of popular formats via VLC (MP4, MKV, AVI, MOV, WebM, and more)
- Controls with Material Design 2 icons (Ikonli) and tooltips (player and playlist)
- Timeline **keyframes** (add with button, `K`, Shift+click, or double-click; drag; delete with right-click / Delete / Backspace; jump with `A` / `S`)
- **A/B markers** (buttons or keys `1` / `2`), draggable, with Δt and speed calculation `v = distance / Δt`
- Immediate rate control. On **open**, FFmpeg prepares a reversed clip in the background (status text below). A loading ring on the video appears **only if you request reverse before the cache is ready**; when done, reverse starts at the same timeline point (`duration − t`)
- Volume + mute
- 5-second splash screen with credit `Application made by quezadasergio`
- Playlist modes: single, sequence, repeat one, repeat all
- On-video annotation: draw, text, color, stroke width, jitter, undo, clear all (no confirmation)
- Annotations are tied to the video time at which they were created

### Keyboard shortcuts

| Key | Action |
|-----|--------|
| `Z` | Play / Pause |
| `X` | Stop |
| `C` | Go to start |
| `V` | Slow motion reverse |
| `B` | Slow motion forward |
| `N` | 1x |
| `M` | Reverse playback |
| `A` / `S` | Previous / next keyframe |
| `K` | Add keyframe |
| `1` / `2` | Marker A / B |
| `Delete` / `Backspace` | Delete selected keyframe |

## Notes

- Smooth reverse uses an FFmpeg-generated cached clip; the first preparation for a file can take a while on long videos.
- Switching clips in the playlist clears annotations, keyframes, and markers.
