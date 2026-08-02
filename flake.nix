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
        in
        {
          gradle = pkgs.writeShellApplication {
            name = "gradle";
            runtimeInputs = [ pkgs.aapt pkgs.jdk17 androidSdk.androidsdk ];
            text = ''
              export ANDROID_HOME=${androidSdk.androidsdk}/libexec/android-sdk
              export ANDROID_SDK_ROOT="$ANDROID_HOME"
              export JAVA_TOOL_OPTIONS="-Duser.home=$HOME"
              export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${pkgs.aapt}/bin/aapt2''${GRADLE_OPTS:+ $GRADLE_OPTS}"
              exec ./native-android/gradlew -p native-android "$@"
            '';
          };
          default = self.packages.${system}.gradle;
        }
      );
    };
}
