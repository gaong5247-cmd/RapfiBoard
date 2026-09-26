#!/usr/bin/env python3
"""RapfiBoard APK builder.

Usage:
  python scripts/build_apk.py --release all
  python scripts/build_apk.py --debug universal
  python scripts/build_apk.py --release arm64 --skip-native
"""
from __future__ import annotations
import argparse, hashlib, os, platform, shutil, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ABIS = {"arm64": "arm64-v8a", "armv7": "armeabi-v7a"}

def die(message: str):
    raise SystemExit(f"\n[RapfiBoard build error]\n{message}\n")

def which(name: str):
    value = shutil.which(name)
    return Path(value) if value else None

def run(args, cwd=ROOT):
    print("\n> " + " ".join(map(str, args)))
    subprocess.run([str(x) for x in args], cwd=cwd, check=True)

def sdk_candidates():
    values = [os.getenv("ANDROID_HOME"), os.getenv("ANDROID_SDK_ROOT")]
    if platform.system() == "Windows":
        values.append(str(Path(os.getenv("LOCALAPPDATA", "")) / "Android/Sdk"))
    elif platform.system() == "Darwin":
        values.append(str(Path.home() / "Library/Android/sdk"))
    else:
        values += [str(Path.home() / "Android/Sdk"), "/opt/android-sdk", "/opt/android"]
    values.append(str(ROOT.parent / "tools/android-sdk"))
    return [Path(v).expanduser() for v in values if v]

def find_sdk():
    for sdk in sdk_candidates():
        if (sdk / "platforms").is_dir() or (sdk / "platform-tools").is_dir():
            return sdk
    die("Android SDK를 찾지 못했어요. ANDROID_HOME 또는 ANDROID_SDK_ROOT를 설정하세요.")

def find_gradle():
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if wrapper.is_file():
        if os.name != "nt": wrapper.chmod(wrapper.stat().st_mode | 0o111)
        return wrapper
    bundled = ROOT.parent / "tools/gradle-8.13/bin" / ("gradle.bat" if os.name == "nt" else "gradle")
    if bundled.is_file(): return bundled
    found = which("gradle")
    if found: return found
    die("Gradle wrapper/Gradle를 찾지 못했어요. Android Studio 또는 Gradle 8.13을 설치하세요.")

def validate_sdk(sdk):
    exe = ".exe" if os.name == "nt" else ""
    cmake = sdk / f"cmake/3.22.1/bin/cmake{exe}"
    ninja = sdk / f"cmake/3.22.1/bin/ninja{exe}"
    ndk = sdk / "ndk/27.2.12479018"
    if not ndk.is_dir():
        nested = list((sdk / "ndk").glob("*/build/cmake/android.toolchain.cmake"))
        if nested: ndk = nested[0].parents[3]
    needed = [sdk / "platforms/android-35/android.jar", cmake, ninja, ndk / "build/cmake/android.toolchain.cmake"]
    missing = [str(p) for p in needed if not p.exists()]
    if missing:
        die("필수 SDK 패키지가 없습니다:\n  " + "\n  ".join(missing) + "\n필요: platform 35, CMake 3.22.1, NDK 27.2.12479018")
    return cmake, ninja, ndk

def build_native(cmake, ninja, ndk):
    jobs = str(max(1, min(os.cpu_count() or 2, 8)))
    for abi in ABIS.values():
        out = ROOT / "build" / abi
        run([cmake, "-S", ROOT / "native", "-B", out, "-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={ninja}", f"-DCMAKE_TOOLCHAIN_FILE={ndk/'build/cmake/android.toolchain.cmake'}", f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release"])
        run([cmake, "--build", out, "-j", jobs])
        src = out / "rapfi/librapfi.so"
        if not src.is_file(): die(f"{abi} Rapfi library가 생성되지 않았어요: {src}")
        dest = ROOT / "app/src/main/jniLibs" / abi
        dest.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dest / "librapfi.so")

def copy_artifacts(kind, target, aab):
    out = ROOT / "app/build/outputs/apk" / kind
    dist = ROOT / "dist"; dist.mkdir(exist_ok=True)
    labels = [target] if target in ABIS else ["arm64", "armv7", "universal"]
    for label in labels:
        token = "universal" if label == "universal" else ABIS[label]
        candidates = list(out.glob(f"*{token}*{kind}.apk"))
        if label == "universal": candidates += list(out.glob(f"*universal*{kind}.apk"))
        if not candidates: die(f"{label} APK를 찾지 못했어요: {out}")
        dest = dist / f"rapfiboard-{label}-{kind}.apk"
        shutil.copy2(candidates[0], dest)
        print(f"APK: {dest} ({dest.stat().st_size:,} bytes)")
    if aab:
        bundle = ROOT / "app/build/outputs/bundle" / kind / f"app-{kind}.aab"
        if bundle.is_file(): shutil.copy2(bundle, dist / f"rapfiboard-{kind}.aab")
    files = sorted(p for p in dist.iterdir() if p.suffix in (".apk", ".aab"))
    (dist / "SHA256SUMS").write_text("\n".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}" for p in files) + "\n")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("target", nargs="?", choices=["arm64", "armv7", "universal", "all"], default="all")
    ap.add_argument("--release", action="store_true")
    ap.add_argument("--debug", action="store_true")
    ap.add_argument("--aab", action="store_true")
    ap.add_argument("--skip-native", action="store_true")
    ap.add_argument("--run-tests", action="store_true")
    args = ap.parse_args()
    kind = "release" if args.release and not args.debug else "debug"
    sdk = find_sdk(); cmake, ninja, ndk = validate_sdk(sdk)
    (ROOT / "local.properties").write_text(f"sdk.dir={sdk.resolve().as_posix()}\n")
    if not args.skip_native: build_native(cmake, ninja, ndk)
    gradle = find_gradle()
    tasks = ([":app:testDebugUnitTest"] if args.run_tests else []) + [f":app:assemble{kind.title()}"]
    if args.aab: tasks.append(f":app:bundle{kind.title()}")
    run([gradle, *tasks, "--no-daemon"])
    copy_artifacts(kind, args.target, args.aab)
    print(f"\n완료: {ROOT/'dist'}")

if __name__ == "__main__": main()
