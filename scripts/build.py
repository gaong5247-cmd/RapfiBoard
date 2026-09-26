#!/usr/bin/env python3
"""Portable NDK + Gradle build. Python 3.10+, JDK 17, Android SDK required."""
import os,pathlib,subprocess,shutil,sys,json,hashlib
root=pathlib.Path(__file__).resolve().parents[1]
choice=sys.argv[1] if len(sys.argv)>1 else 'all'
if choice not in ('arm64','armv7','universal','all'): raise SystemExit('Use arm64 / armv7 / universal / all')
sdk=pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
if not (sdk/'platforms/android-35/android.jar').is_file(): raise SystemExit('Set ANDROID_HOME to an SDK with platform 35, build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1.')
ndk=pathlib.Path(os.environ.get('ANDROID_NDK_HOME',str(sdk/'ndk/27.2.12479018')))
if (ndk/'android-ndk-r27c/build').is_dir(): ndk=ndk/'android-ndk-r27c'
suffix='.exe' if os.name=='nt' else ''
cmake=sdk/f'cmake/3.22.1/bin/cmake{suffix}'; ninja=sdk/f'cmake/3.22.1/bin/ninja{suffix}'
def run(args): subprocess.run(list(map(str,args)),cwd=root,check=True)
for abi in ['arm64-v8a','armeabi-v7a']:
    out=root/'build'/abi
    run([cmake,'-S',root/'native','-B',out,'-G','Ninja',f'-DCMAKE_MAKE_PROGRAM={ninja}',f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',f'-DANDROID_ABI={abi}','-DANDROID_PLATFORM=android-26','-DANDROID_STL=c++_static','-DCMAKE_BUILD_TYPE=Release'])
    run([cmake,'--build',out,'-j',str(min(4,os.cpu_count() or 2))])
    dest=root/'app/src/main/jniLibs'/abi;dest.mkdir(parents=True,exist_ok=True);shutil.copy2(out/'rapfi/librapfi.so',dest/'librapfi.so')
# Release binaries are tied to a user-controlled signing key. A local key is generated only if absent.
key=root/'signing/local-release.jks';key.parent.mkdir(exist_ok=True)
if not key.exists():
    run(['keytool','-genkeypair','-keystore',key,'-storepass',os.environ.get('RAPFI_STORE_PASSWORD','rapfiboard-local'),'-keypass',os.environ.get('RAPFI_KEY_PASSWORD','rapfiboard-local'),'-alias',os.environ.get('RAPFI_KEY_ALIAS','rapfiboard'),'-keyalg','RSA','-keysize','3072','-validity','3650','-dname','CN=RapfiBoard Local Build'])
(root/'local.properties').write_text('sdk.dir='+str(sdk.resolve()).replace('\\','/')+'\n')
gradle=root/('gradlew.bat' if os.name=='nt' else 'gradlew')
run([gradle,'testDebugUnitTest','assembleRelease','bundleRelease','--no-daemon'])
dist=root/'dist';dist.mkdir(exist_ok=True)
for abi,label in [('arm64-v8a','arm64'),('armeabi-v7a','armv7'),('universal','universal')]:
    if choice=='all' or choice==label:
        shutil.copy2(root/f'app/build/outputs/apk/release/app-{abi}-release.apk',dist/f'rapfiboard-{label}-release.apk')
if choice in ('all','universal'): shutil.copy2(root/'app/build/outputs/bundle/release/app-release.aab',dist/'rapfiboard-release.aab')
(dist/'SHA256SUMS').write_text('\n'.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}' for p in sorted(dist.glob('*')) if p.suffix in ('.apk','.aab'))+'\n')
print('Artifacts:',dist)
