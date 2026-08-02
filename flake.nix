{
  description = "Development tools for Harness Android";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs, ... }:
    let
      forAllSystems = nixpkgs.lib.genAttrs [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
    in
    {
      packages = forAllSystems (
        system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };
          androidSdk = pkgs.androidenv.composeAndroidPackages {
            buildToolsVersions = [ "34.0.0" "35.0.0" ];
            platformVersions = [ "35" ];
            ndkVersions = [ "27.2.12479018" ];
            cmakeVersions = [ "3.22.1" ];
            includeNDK = true;
          };
          # Downloads the Android SDK/NDK used both for the native whisper
          # prebuild and, as a fallback, for the in-tree CMake build.
          ndk = "${androidSdk.ndk-bundle}/libexec/android-sdk/ndk/27.2.12479018";
          cmakeBin = "${builtins.head androidSdk.cmake}/libexec/android-sdk/cmake/3.22.1/bin/cmake";

          # Reusable prebuilt whisper.cpp native libraries for every Android ABI.
          # Its store path is keyed only on the C++ sources under
          # native-android/app/src/main/cpp (plus the pinned SDK/NDK), so a
          # Kotlin-only change does not rebuild, download, or recompile the
          # native code. Gradle packages the resulting .so files directly.
          whisperNative = pkgs.stdenv.mkDerivation {
            pname = "harness-whisper-android";
            version = "native";
            src = ./native-android/app/src/main/cpp;
            nativeBuildInputs = [ pkgs.ninja pkgs.git ];
            dontUseCmakeConfigure = true;
            dontStrip = true;
            dontPatchELF = true;
            buildPhase = ''
              runHook preBuild
              mkdir -p "$out/jniLibs"
              # Same toolchain, output layout, and flags as the in-tree CMake
              # fallback used by the Android Gradle plugin.
              for abi in arm64-v8a armeabi-v7a x86 x86_64; do
                ${cmakeBin} -S . -B "build-$abi" -G Ninja \
                  -DCMAKE_TOOLCHAIN_FILE="${ndk}/build/cmake/android.toolchain.cmake" \
                  -DANDROID_ABI="$abi" \
                  -DANDROID_PLATFORM=android-26 \
                  -DANDROID_NDK="${ndk}" \
                  -DANDROID_STL=c++_static \
                  -DCMAKE_BUILD_TYPE=Release \
                  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$out/jniLibs/$abi" \
                  -DCMAKE_RUNTIME_OUTPUT_DIRECTORY="$out/jniLibs/$abi"
                ${cmakeBin} --build "build-$abi" -j "$NIX_BUILD_CORES"
              done
              runHook postBuild
            '';
            installPhase = "true";
          };
        in
        {
          inherit whisperNative;

          gradle = pkgs.writeShellApplication {
            name = "gradle";
            runtimeInputs = [ pkgs.aapt pkgs.jdk17 androidSdk.androidsdk whisperNative ];
            text = ''
              export ANDROID_HOME=${androidSdk.androidsdk}/libexec/android-sdk
              export ANDROID_SDK_ROOT="$ANDROID_HOME"
              export JAVA_TOOL_OPTIONS="-Duser.home=$HOME"
              export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${pkgs.aapt}/bin/aapt2''${GRADLE_OPTS:+ $GRADLE_OPTS}"
              # Point Gradle at the Nix-prebuild native libraries. A Gradle
              # property is used (not an env var) so it is forwarded to a
              # long-lived Gradle daemon every invocation.
              exec ./native-android/gradlew -p native-android -PwhisperNativeDir="${whisperNative}" "$@"
            '';
          };
          default = self.packages.${system}.gradle;
        }
      );
    };
}
