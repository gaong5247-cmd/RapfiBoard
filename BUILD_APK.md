# APK 빌드

Python 3.10 이상과 JDK 17이 필요합니다. Android SDK에는 다음 패키지가 필요합니다.

- Android Platform 35
- Android Build Tools 35.0.0
- Android NDK 27.2.12479018
- CMake 3.22.1
- Ninja
- Gradle 8.13 또는 Android Studio Gradle wrapper

## Windows PowerShell

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
python scripts\build_apk.py --release all --aab
```

## Linux / macOS

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
python3 scripts/build_apk.py --release all --aab
```

생성 결과는 `dist/`에 저장됩니다.

```text
dist/rapfiboard-arm64-release.apk
dist/rapfiboard-armv7-release.apk
dist/rapfiboard-universal-release.apk
dist/rapfiboard-release.aab
```

이미 `app/src/main/jniLibs/`에 네이티브 Rapfi 라이브러리가 있다면 `--skip-native`를 붙여 CMake 단계를 건너뛸 수 있습니다. `--debug`를 사용하면 디버그 APK를 만들고, `--run-tests`를 추가하면 JVM 테스트도 먼저 실행합니다.
