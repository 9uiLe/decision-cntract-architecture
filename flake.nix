{
  description = "Development environments for the DCA examples and mobile product";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/d3b13ad75cc492690728f9307e443dec9ef9a20e";

  outputs = { nixpkgs, ... }: {
    devShells = nixpkgs.lib.genAttrs [
      "aarch64-darwin"
      "aarch64-linux"
      "x86_64-linux"
    ] (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        androidPkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };
        androidSdk = (androidPkgs.androidenv.composeAndroidPackages {
          cmdLineToolsVersion = "latest";
          platformToolsVersion = "37.0.1";
          buildToolsVersions = [ "36.0.0" "37.0.0" ];
          platformVersions = [ "37" "36" ];
          includeEmulator = true;
          emulatorVersion = "37.1.11";
          includeSystemImages = true;
          systemImageTypes = [ "google_apis" ];
          abiVersions = [ "arm64-v8a" ];
          includeCmake = false;
          includeNDK = false;
          includeSources = false;
        }).androidsdk;
        androidSdkRoot = "${androidSdk}/libexec/android-sdk";
      in {
        default = pkgs.mkShell {
          packages = [ pkgs.jdk21 pkgs.kotlin ];
        };
        dca-plugin = pkgs.mkShell {
          packages = [ pkgs.python312 pkgs.git ];
        };
      } // nixpkgs.lib.optionalAttrs (system == "aarch64-darwin") {
        android = pkgs.mkShell {
          packages = [ pkgs.jdk21 pkgs.python3 androidSdk ];
          ANDROID_HOME = androidSdkRoot;
          ANDROID_SDK_ROOT = androidSdkRoot;
        };
        ios = pkgs.mkShell {
          packages = [ pkgs.jdk21 pkgs.xcodegen ];
        };
      });
  };
}
