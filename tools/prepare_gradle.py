#!/usr/bin/env python3
"""Build the project's Gradle distribution from two checksum-pinned inputs.

The official Wrapper is unchanged. Gradle 9.8.2's vulnerable Guava JAR is
replaced by upstream Guava 33.7.2-jre. Two classpath metadata records are updated.
ZIP_STORED makes the archive independent of the local zlib version.
"""
import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import urllib.parse
import urllib.request
import zipfile

GRADLE_URL = "https://services.gradle.org/distributions/gradle-9.8.2-bin.zip"
GRADLE_SHA256 = "21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607"
GUAVA_URL = "https://repo.maven.apache.org/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar"
GUAVA_SHA256 = "b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c"
OLD_MEMBER = "gradle-9.8.2/lib/guava-33.4.6-jre.jar"
NEW_MEMBER = "gradle-9.8.2/lib/guava-33.7.2-jre.jar"
PROVENANCE_MEMBER = "gradle-9.8.2/SITEWATCH-SECURITY-PATCH.json"
GUAVA_PROPERTIES = "gradle-9.8.2/lib/guava.properties"
CLI_JAR = "gradle-9.8.2/lib/gradle-gradle-cli-main-9.8.2.jar"
OLD_NAME = b"guava-33.4.6-jre.jar"
NEW_NAME = b"guava-33.7.2-jre.jar"
PROPERTY_NOTICE = b"# Modified for SiteWatch on 2026-11-03: Guava security update.\n"
MANIFEST_NOTICE = b"SiteWatch-Security-Patch: Guava 33.7.2-jre; modified 2026-11-03\r\n"


def patch_cli_manifest(data):
    buffer = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as original, zipfile.ZipFile(buffer, "w") as patched:
        for member in original.infolist():
            info = copy.copy(member)
            info.compress_type = zipfile.ZIP_STORED
            content = original.read(member)
            if member.filename == "META-INF/MANIFEST.MF":
                assert content.count(OLD_NAME) == 1
                # The replacement has the same length, preserving manifest folding.
                content = content.replace(OLD_NAME, NEW_NAME)
                assert content.endswith(b"\r\n\r\n")
                content = content[:-2] + MANIFEST_NOTICE + b"\r\n"
            patched.writestr(info, content)
    return buffer.getvalue()


def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise ValueError("Refusing a non-HTTPS download redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def verified_input(path, url, expected):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(path.suffix + ".part")
        opener = urllib.request.build_opener(HttpsRedirects())
        try:
            with opener.open(url, timeout=120) as response, temporary.open("wb") as output:
                for block in iter(lambda: response.read(1024 * 1024), b""):
                    output.write(block)
            if sha256(temporary) != expected:
                raise ValueError("Download SHA-256 mismatch: " + url)
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)
    if sha256(path) != expected:
        raise ValueError("Input SHA-256 mismatch: " + str(path))


def prepare(gradle_zip, guava_jar, output):
    verified_input(gradle_zip, GRADLE_URL, GRADLE_SHA256)
    verified_input(guava_jar, GUAVA_URL, GUAVA_SHA256)
    assert output.resolve() not in (gradle_zip.resolve(), guava_jar.resolve())
    provenance = {
        "base": {"url": GRADLE_URL, "sha256": GRADLE_SHA256},
        "replacement": {"url": GUAVA_URL, "sha256": GUAVA_SHA256,
                        "removed": OLD_MEMBER, "added": NEW_MEMBER},
        "patch_version": "sitewatch.1",
        "modified_on": "2026-11-03",
        "metadata_updates": [GUAVA_PROPERTIES, CLI_JAR + ":META-INF/MANIFEST.MF"],
        "notice": "Modified Gradle distribution for SiteWatch; not an official Gradle release.",
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(gradle_zip) as original, zipfile.ZipFile(output, "w") as patched:
        assert original.namelist().count(OLD_MEMBER) == 1
        assert NEW_MEMBER not in original.namelist()
        for member in original.infolist():
            info = copy.copy(member)
            info.compress_type = zipfile.ZIP_STORED
            if member.filename == OLD_MEMBER:
                info.filename = NEW_MEMBER
                data = guava_jar.read_bytes()
            elif member.filename == GUAVA_PROPERTIES:
                data = original.read(member)
                assert data.count(OLD_NAME) == 1
                assert data.count(b"33.4.6-jre") == 2
                data = PROPERTY_NOTICE + data.replace(b"33.4.6-jre", b"33.7.2-jre")
            elif member.filename == CLI_JAR:
                data = patch_cli_manifest(original.read(member))
            else:
                data = original.read(member)
            patched.writestr(info, data)
        info = zipfile.ZipInfo(PROVENANCE_MEMBER, date_time=(2026, 11, 3, 0, 0, 0))
        info.create_system = 3
        info.external_attr = 0o100644 << 16
        patched.writestr(info, json.dumps(provenance, sort_keys=True, indent=2) + "\n")

    # Verify against the input archive, not a list inferred from the output.
    with zipfile.ZipFile(gradle_zip) as original, zipfile.ZipFile(output) as patched:
        expected_names = set(original.namelist()) - {OLD_MEMBER} | {NEW_MEMBER, PROVENANCE_MEMBER}
        assert set(patched.namelist()) == expected_names
        assert len(patched.namelist()) == len(expected_names)
        assert hashlib.sha256(patched.read(NEW_MEMBER)).hexdigest() == GUAVA_SHA256
        for name in original.namelist():
            if name not in (OLD_MEMBER, GUAVA_PROPERTIES, CLI_JAR):
                assert patched.read(name) == original.read(name), name
        assert patched.read(GUAVA_PROPERTIES) == PROPERTY_NOTICE + original.read(GUAVA_PROPERTIES).replace(
            b"33.4.6-jre", b"33.7.2-jre")
        with zipfile.ZipFile(io.BytesIO(original.read(CLI_JAR))) as before, \
                zipfile.ZipFile(io.BytesIO(patched.read(CLI_JAR))) as after:
            assert before.namelist() == after.namelist()
            for name in before.namelist():
                expected = before.read(name)
                if name == "META-INF/MANIFEST.MF":
                    expected = expected.replace(OLD_NAME, NEW_NAME)
                    expected = expected[:-2] + MANIFEST_NOTICE + b"\r\n"
                assert after.read(name) == expected, name
        assert patched.testzip() is None
    return sha256(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-zip", type=Path, default=Path(".toolchain-cache/gradle-9.8.2-bin.zip"))
    parser.add_argument("--guava-jar", type=Path, default=Path(".toolchain-cache/guava-33.7.2-jre.jar"))
    parser.add_argument("--output", type=Path,
                        default=Path(".toolchain-cache/gradle-9.8.2-sitewatch.1-bin.zip"))
    parser.add_argument("--expected-sha256", help="Fail if the result differs from this checksum")
    args = parser.parse_args()
    digest = prepare(args.gradle_zip, args.guava_jar, args.output)
    if args.expected_sha256 and digest != args.expected_sha256:
        raise SystemExit("Output SHA-256 mismatch")
    print(digest, args.output)
