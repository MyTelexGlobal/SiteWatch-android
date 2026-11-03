#!/usr/bin/env python3
"""Inventory project artifacts and ALL external JARs in the patched Gradle ZIP.

Only public Maven package names and versions are sent to OSV. No source files,
signing material, local paths or user URLs are sent. A catalog match does not
by itself prove exploitability; a clean result is not a safety guarantee.
"""
import argparse
import io
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


def properties(data):
    result = {}
    for line in data.decode("utf-8", errors="replace").splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def inventory(gradle_zip, metadata):
    packages = {}

    def add(group, name, version, source):
        packages.setdefault((group + ":" + name, version), set()).add(source)

    ns = {"v": "https://schema.gradle.org/dependency-verification"}
    tree = ET.parse(metadata)
    for component in tree.findall("v:components/v:component", ns):
        if any(a.get("name", "").endswith((".jar", ".aar"))
               for a in component.findall("v:artifact", ns)):
            add(component.get("group"), component.get("name"), component.get("version"), "project")
    unmatched = []
    jar_count = 0
    with zipfile.ZipFile(gradle_zip) as outer:
        aliases = {}
        for name in outer.namelist():
            if name.endswith(".properties") and "/lib/" in name:
                data = properties(outer.read(name))
                if all(k in data for k in ("alias.group", "alias.name", "alias.version", "jarFile")):
                    aliases[data["jarFile"]] = data
        for name in outer.namelist():
            base = name.rsplit("/", 1)[-1]
            if not name.endswith(".jar") or "/lib/" not in name or base.startswith("gradle-"):
                continue
            jar_count += 1
            found = False
            if base in aliases:
                data = aliases[base]
                add(data["alias.group"], data["alias.name"], data["alias.version"], "gradle-bundled")
                found = True
            with zipfile.ZipFile(io.BytesIO(outer.read(name))) as jar:
                for member in jar.namelist():
                    if member.startswith("META-INF/maven/") and member.endswith("/pom.properties"):
                        data = properties(jar.read(member))
                        if all(k in data for k in ("groupId", "artifactId", "version")):
                            add(data["groupId"], data["artifactId"], data["version"], "gradle-bundled")
                            found = True
            if not found:
                unmatched.append(base)
    add("org.gradle", "gradle-core", "9.8.2", "gradle-core")
    return [{"name": key[0], "version": key[1], "sources": sorted(sources)}
            for key, sources in sorted(packages.items())], unmatched, jar_count


def query_osv(packages):
    queries = [{"package": {"ecosystem": "Maven", "name": p["name"]},
                "version": p["version"]} for p in packages]
    request = urllib.request.Request("https://api.osv.dev/v1/querybatch",
            data=json.dumps({"queries": queries}).encode(),
            headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=90) as response:
        results = json.load(response)["results"]
    assert len(results) == len(packages)
    matches = []
    for package, result in zip(packages, results):
        if result.get("next_page_token"):
            raise RuntimeError("OSV pagination requires further requests; refusing an incomplete result")
        if result.get("vulns"):
            matches.append({**package, "advisories": [v["id"] for v in result["vulns"]]})
    return matches


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-zip", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, default=Path("gradle/verification-metadata.xml"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--inventory-only", action="store_true")
    args = parser.parse_args()
    packages, unmatched, jar_count = inventory(args.gradle_zip, args.metadata)
    result = {"packages": packages, "external_gradle_jars": jar_count,
              "unmapped_external_jars": unmatched, "osv_checked": not args.inventory_only}
    if not args.inventory_only:
        result["matches"] = query_osv(packages)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"packages": len(packages), "external_gradle_jars": jar_count,
                      "unmapped_external_jars": unmatched,
                      "matches": result.get("matches", "not checked")}, indent=2))
    if unmatched or result.get("matches"):
        raise SystemExit(1)
