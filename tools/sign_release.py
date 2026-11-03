#!/usr/bin/env python3
"""Sign an existing APK or AAB without putting passwords in command arguments."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--password-file", type=Path, required=True)
    parser.add_argument("--alias", required=True)
    parser.add_argument("--build-tools", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists; choose a new output path")
    if args.input.suffix.lower() not in (".apk", ".aab"):
        parser.error("Input must be an APK or AAB")
    for path in (args.input, args.keystore, args.password_file):
        if not path.is_file():
            parser.error("Missing file: " + str(path))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    password = str(args.password_file.resolve())
    if args.input.suffix.lower() == ".aab":
        java_home = os.environ.get("JAVA_HOME")
        jarsigner = str(Path(java_home) / "bin/jarsigner") if java_home else "jarsigner"
        shutil.copyfile(args.input, args.output)
        subprocess.run([jarsigner, "-keystore", str(args.keystore),
                        "-storepass:file", password,
                        "-sigalg", "SHA256withRSA", "-digestalg", "SHA-256",
                        str(args.output), args.alias], check=True)
        subprocess.run([jarsigner, "-verify", str(args.output)], check=True)
    else:
        if not args.build_tools:
            parser.error("--build-tools is required for APK signing")
        aligned = args.output.with_suffix(".aligned.tmp.apk")
        try:
            subprocess.run([str(args.build_tools / "zipalign"), "-p", "4",
                            str(args.input), str(aligned)], check=True)
            subprocess.run([str(args.build_tools / "apksigner"), "sign",
                            "--ks", str(args.keystore), "--ks-key-alias", args.alias,
                            "--ks-pass", "file:" + password,
                            "--out", str(args.output), str(aligned)], check=True)
            subprocess.run([str(args.build_tools / "apksigner"), "verify", "--verbose",
                            "--print-certs", str(args.output)], check=True)
        finally:
            aligned.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
