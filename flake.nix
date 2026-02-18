{
  description = "workshop - shared workspace for a small trusted mesh of agents";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (
      system: let
        pkgs = nixpkgs.legacyPackages.${system};

        babashka = pkgs.babashka;

        workshop = pkgs.stdenv.mkDerivation {
          pname = "workshop";
          version = "0.1.0";

          src = ./.;

          nativeBuildInputs = [pkgs.makeWrapper];
          buildInputs = [babashka];

          installPhase = ''
            mkdir -p $out/bin $out/share/workshop

            cp -r . $out/share/workshop/

            makeWrapper ${babashka}/bin/bb $out/bin/workshop \
              --prefix PATH : ${babashka}/bin \
              --run "cd $out/share/workshop && exec bb workshop.bb" \
              --set WORKSHOP_DIR "$out/share/workshop"
          '';

          meta = with pkgs.lib; {
            description = "Shared workspace for a small trusted mesh of agents";
            homepage = "https://github.com/noblepayne/workshop";
            license = licenses.mit;
            maintainers = [];
            platforms = platforms.unix;
          };
        };
      in {
        formatter = pkgs.alejandra;
        packages = {
          default = workshop;
        };

        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            babashka
            clojure
            clj-kondo
            cljfmt
            mdformat
            shellcheck
          ];

          shellHook = ''
            echo "workshop Dev Environment"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "Babashka version: $(bb --version)"
            echo ""
            echo "Quick start:"
            echo "  bb workshop.bb - Start the server (port 4242)"
            echo "  bb test.bb    - Run tests"
            echo "  bb -m repl    - Start REPL"
            echo ""
            echo "  nix build     - Build the package"
            echo "  nix run       - Run the built package"
            echo ""
          '';
        };

        apps = {
          default = {
            type = "app";
            program = "${workshop}/bin/workshop";
            meta = {
              description = "Start workshop server";
            };
          };
        };
      }
    )
    // {
      nixosModules.default = {
        config,
        lib,
        pkgs,
        ...
      }:
        with lib; let
          cfg = config.services.workshop;

          workshop-pkg = self.packages.${pkgs.system}.default;
        in {
          options.services.workshop = {
            enable = mkEnableOption "workshop agent workspace server";

            port = mkOption {
              type = types.port;
              default = 4242;
              description = "Port for the workshop HTTP server";
            };

            host = mkOption {
              type = types.str;
              default = "0.0.0.0";
              description = "Host address to bind to";
            };

            verbose = mkOption {
              type = types.bool;
              default = false;
              description = "Enable verbose logging";
            };

            dbPath = mkOption {
              type = types.str;
              default = "workshop.db";
              description = "Path to SQLite database file";
            };

            blobsDir = mkOption {
              type = types.str;
              default = "blobs";
              description = "Directory for file blobs";
            };

            retentionDays = mkOption {
              type = types.int;
              default = 30;
              description = "Number of days to retain messages";
            };

            user = mkOption {
              type = types.str;
              default = "workshop";
              description = "User to run the service as";
            };

            group = mkOption {
              type = types.str;
              default = "workshop";
              description = "Group to run the service as";
            };

            openFirewall = mkOption {
              type = types.bool;
              default = false;
              description = "Open firewall port for workshop";
            };
          };

          config = mkIf cfg.enable {
            users.users.${cfg.user} = {
              isSystemUser = true;
              group = cfg.group;
              description = "workshop service user";
            };

            users.groups.${cfg.group} = {};

            systemd.services.workshop = {
              description = "workshop agent workspace server";
              wantedBy = ["multi-user.target"];
              after = ["network.target"];

              environment = {
                HOME = "/var/lib/workshop";
                PORT = toString cfg.port;
                HOST = cfg.host;
                DB_PATH = "/var/lib/workshop/workshop.db";
                BLOBS_DIR = "/var/lib/workshop/blobs";
                WORKSHOP_VERBOSE =
                  if cfg.verbose
                  then "true"
                  else "false";
                WORKSHOP_RETENTION_DAYS = toString cfg.retentionDays;
              };

              serviceConfig = {
                Type = "simple";
                User = cfg.user;
                Group = cfg.group;
                ExecStart = "${workshop-pkg}/bin/workshop";
                Restart = "on-failure";
                RestartSec = "5s";
                StateDirectory = "workshop";

                NoNewPrivileges = true;
                PrivateTmp = true;
                ProtectSystem = "strict";
                ProtectHome = true;

                MemoryMax = "1G";
                TasksMax = 50;
              };
            };

            networking.firewall.allowedTCPPorts = mkIf cfg.openFirewall [cfg.port];
          };
        };
    };
}
