# HerlysByte

Java desktop application for inspecting `.jar` structure and bytecode metadata. **HerlysByte never loads or executes classes from an analyzed archive.**

## Features

- JavaFX dark desktop interface with drag-and-drop JAR opening
- Compact custom title bar with minimize/maximize/restore/close, window dragging, edge resizing and screen-fitted startup sizing
- Hierarchical package explorer with expandable/collapsible folders and class entries
- Live filtering across classes, methods and strings
- CFR-powered Java decompiler with Source, Structure, Strings and References tabs
- ASM bytecode disassembly for the selected class
- Resource browser with path/size sorting, live search and single-file extraction
- Copy decompiled source and save it as `.java`
- Separate background analysis/decompilation workers with a busy indicator; stale selections are cancelled/ignored
- Keyboard shortcuts for opening, searching, exporting and copying source
- JAR manifest, package, class, method, field and resource overview
- ASM-based class metadata, string-constant and observed dependency extraction
- Search across class names, methods and strings
- Corrupt/unreadable class warnings; background analysis to keep the UI responsive

### Keyboard shortcuts

- `Ctrl+O` — open a JAR
- `Ctrl+F` — focus class/resource search
- `Ctrl+S` — save the selected decompiled class as Java source
- `Ctrl+Shift+C` — copy decompiled source

## Requirements

- JDK 21+ để chạy ứng dụng
- Maven 3.9+ chỉ cần khi tự build từ mã nguồn

## Run

Windows x64: double-click `dist/HerlysByte.exe`. It is a single-file launcher with the Windows JAR embedded; it extracts the app into the current user's Local AppData cache on first run. **Java 21+ still needs to be installed** (the EXE does not bundle a Java runtime). If Java is not detected, install a Java 21+ JDK/JRE and run the EXE again. The self-contained `dist/HerlysByte-Windows.jar` remains available as an alternative and also requires Java 21+.

On Linux, build/run the Linux-native JAR with Maven (`mvn javafx:run`). Do not use the Windows JAR on Linux; JavaFX bundles native libraries per operating system. To build a Windows JAR from source, run `mvn -Dtarget.javafx=windows clean package`; for macOS use `mvn -Dtarget.javafx=macos clean package`.

From source, you can also run:

```bash
mvn javafx:run
```

## Test and package

```bash
mvn test
mvn package
```

The Windows JAR is assembled at `dist/HerlysByte-Windows.jar` and includes its JavaFX Windows native libraries. The one-file launcher is `dist/HerlysByte.exe`; on Linux it can be rebuilt with MinGW-w64 using `windows/build-exe.sh` after rebuilding the Windows JAR.

## Scope notes

HerlysByte borrows the JAR/class navigation and source-inspection workflow of tools such as Recaf. CFR reconstructs readable Java source from the selected class; this is a best-effort decompilation, not the original source, and v1 does not edit bytecode. Dependencies are references observed in class signatures and bytecode instructions; they do not represent a complete dependency-resolution graph. Analysis is local and no archive classes are executed.
