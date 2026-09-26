# Upstream audit

Rapfi pinned source: https://github.com/dhbloo/rapfi/tree/3c94c2a976f24a0dd1c5517623e9ab6fffe66bd7
Source snapshot is included under vendor/rapfi. No dependency on a moving branch during build.
Networks: https://github.com/dhbloo/rapfi-networks (downloaded 2026-09-26 UTC).
Exact distributed bytes are fixed by app/src/main/assets/runtime/manifest.json.

## Audit findings

* command/command.cpp: config resolves cwd first, then executable directory; weights resolve cwd, config directory, binary directory. The app launches its packaged executable with an explicit app-private working directory and absolute network paths in generated config.toml.
* main.cpp: default Piskvork mode. NO_COMMAND_MODULES removes training/selfplay CLI tools; it does not disable protocol or search threads. C++17, LZ4 and header dependencies are vendored.
* command/gomocup.cpp: START returns OK. ABOUT identifies the engine. YXBOARD loads without searching. DONE terminates the board. Board side codes are engine-relative, not absolute black/white. Protocol coordinates are zero based with coord_conversion_mode="none".
* YXNBEST n starts MultiPV; the engine plays its best move internally after a search. Therefore each app request sends a fresh START/YXBOARD, never assumes the engine retained the GUI position unchanged.
* INFO THREAD_NUM, HASH_SIZE (KiB), MAX_NODE, MAX_DEPTH, TIMEOUT_TURN (ms), SHOW_DETAIL 2, PONDERING, USEDATABASE are confirmed in source. No UCI `go`, `isready`, `uci`, or `setoption` is sent.
* search/searchoutput.cpp prints **INFO** directly (not MESSAGE INFO) with PV zero-based index through PV DONE. Fields: DEPTH, SELDEPTH, TOTALNODES, TOTALTIME, SPEED, EVAL, WINRATE, BESTLINE. Different search implementations expose different fields. Alpha-beta does not emit DRAWRATE; MCTS has additional root statistics.
* EVAL stream encoding in core/iohelper.cpp includes numeric values, +M<n>, -M<n>, +M*, -M*, VAL_NONE and infinities. Unusable sentinel PV blocks are discarded. Mate values are never called centipawns.
* eval/scoretables.h valueToWinRate is logistic using model ScalingFactor. This is not a calibrated full W/D/L distribution. Default UI calls it score probability, leaves draw unavailable, and keeps raw eval visible.
* YXSHOWFORBID prints concatenated 4-digit xxyy coordinates terminated by a dot; only black is checked. Native Board::checkForbiddenPoint provides legality, not a reason string. The app does not fabricate 'double three' vs 'double four' reasons.
* STOP/YXSTOP requests stopping search. Cancellation in the app additionally terminates its dedicated OS process to prevent stale output contamination.
* Evaluator load can emit MESSAGE 'disabled: no compatible weight config found' and fall back internally. The app treats this as a visible NNUE failure rather than labeling Classical output NNUE.
* NNUE and Classical mode share the same binary. Classical config removes the evaluator table while retaining model210901.bin.

## Android port

NDK r27c, API 26. arm64 uses baseline NEON (no dotprod requirement). armv7 uses scalar kernels. static libc++ avoids additional deployment dependencies. PIE executable is packaged as librapfi.so in jniLibs and extracted by Android into nativeLibraryDir; it is not dlopen'd. Writable assets are not executed. 16 KiB ELF load alignment is requested.

Two changes relative to upstream:
1. Parent CMake removes standalone pthread library on Android (Bionic provides pthread symbols).
2. mix10nnue.cpp supplies scalar activation for its small linearBlock output, matching the original saturation/shift/ReLU sequence. This fixes compilation even though the bundled evaluator is mix9svq. Mix10 networks are not advertised as supported by the app.

## YixinBoard investigation

README: https://github.com/dhbloo/Yixin-Board/blob/master/README.md identifies the modified GTK3 GUI, Rapfi support and Simplified BSD license. No YixinBoard code or artwork is copied. Source protocol handlers provide database query/edit/merge/lib conversion commands. The app's Room game database is a distinct format and is not advertised as a Yixin position database. Native Yixin .db/.lib import/export remains unimplemented.

## Licenses

Rapfi source headers: GPL-3.0-or-later; Copying.txt is included. App authored code uses the same license. Network repository LICENSE is CC0 and is bundled. Every existing third-party source header/license under vendor is preserved. Corresponding modified source is distributed with APKs. AndroidX/Kotlin dependency notices must remain in redistributed dependency artifacts; the app notice screen currently includes Rapfi and network license texts.
