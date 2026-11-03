#!/usr/bin/env python3
"""Exercise the signed APK through the Android UI on an isolated emulator.

This script resets SiteWatch test data ONLY when --reset-test-app is supplied.
It refuses physical devices. Fixtures contain synthetic URLs and no real secrets.
"""
import argparse
import hashlib
import http.server
import json
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time
import traceback
import urllib.parse
import xml.etree.ElementTree as ET

PACKAGE = "ru.sitewatch.monitor"
SECRET = "sitewatch-test-secret"


class Emulator:
    def __init__(self, adb, serial, output):
        if not serial.startswith("emulator-"):
            raise ValueError("Only an emulator serial is allowed")
        self.command = [adb, "-s", serial]
        self.output = output
        self.output.mkdir(parents=True, exist_ok=True)
        assert self.shell("getprop", "ro.kernel.qemu").strip() == "1"
        assert self.shell("getprop", "sys.boot_completed").strip() == "1"

    def adb(self, *args, timeout=40, check=True):
        result = subprocess.run(self.command + list(args), capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(result.stderr.decode(errors="replace") + result.stdout.decode(errors="replace"))
        return result.stdout.decode("utf-8", errors="replace")

    def shell(self, *args, **kwargs):
        return self.adb("shell", *args, **kwargs)

    def tree(self):
        for _ in range(4):
            self.shell("uiautomator", "dump", "/sdcard/sitewatch-window.xml", check=False)
            text = self.adb("exec-out", "cat", "/sdcard/sitewatch-window.xml")
            try:
                return ET.fromstring(text)
            except ET.ParseError:
                time.sleep(0.3)
        raise RuntimeError("Could not obtain an Android UI hierarchy")

    def find(self, *, text=None, resource=None, class_name=None, contains=None, tree=None):
        tree = self.tree() if tree is None else tree
        for node in tree.iter("node"):
            if text is not None and node.get("text", "").casefold() != text.casefold():
                continue
            if resource is not None and node.get("resource-id") != resource:
                continue
            if class_name is not None and node.get("class") != class_name:
                continue
            if contains is not None and contains.casefold() not in node.get("text", "").casefold():
                continue
            return node
        return None

    def tap(self, **selector):
        node = self.find(**selector)
        if node is None:
            raise AssertionError("UI control missing: " + repr(selector))
        bounds = list(map(int, re.findall(r"\d+", node.get("bounds"))))
        x1, y1, x2, y2 = bounds
        assert x2 > x1 and y2 > y1, selector
        self.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def wait(self, **selector):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            node = self.find(**selector)
            if node is not None:
                return node
            time.sleep(0.4)
        raise AssertionError("Expected UI text/control: " + repr(selector))

    def screenshot(self, name):
        result = subprocess.run(self.command + ["exec-out", "screencap", "-p"],
                                capture_output=True, check=True, timeout=20)
        (self.output / (name + ".png")).write_bytes(result.stdout)
        ET.ElementTree(self.tree()).write(self.output / (name + ".xml"), encoding="utf-8")

    def add_url(self, url, expected_error=None):
        self.tap(resource=PACKAGE + ":id/addButton")
        self.tap(class_name="android.widget.EditText")
        self.shell("input text " + shlex.quote(url))
        self.tap(text="Добавить")
        if expected_error:
            self.wait(contains=expected_error)
            self.tap(text="Отмена")
        else:
            self.wait(resource=PACKAGE + ":id/summary")

    def settings(self):
        self.tap(resource=PACKAGE + ":id/settingsButton")
        self.wait(text="Настройки")

    def card(self, url):
        # Return to the top, then inspect visible cards while scrolling down.
        for _ in range(5):
            self.shell("input", "swipe", "540", "1000", "540", "2000", "180")
        for _ in range(9):
            tree = self.tree()
            node = self.find(text=url, tree=tree)
            if node is not None:
                parents = {child: parent for parent in tree.iter() for child in parent}
                parent = node
                while parent in parents:
                    parent = parents[parent]
                    labels = [n.get("text", "") for n in parent]
                    if any(t.startswith(("Сайт доступен", "Сайт недоступен", "Проверка заблокирована"))
                           for t in labels):
                        return parent
            self.shell("input", "swipe", "540", "1950", "540", "900", "220")
        raise AssertionError("Card not found: " + url)

    def assert_card(self, url, expected):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            card = self.card(url)
            if any(expected in n.get("text", "") for n in card.iter()):
                return
            time.sleep(0.5)
        raise AssertionError("Card status missing: " + expected)


def run(args):
    output = args.output
    device = Emulator(args.adb, args.serial, output)
    report = {"android": device.shell("getprop", "ro.build.version.release").strip(),
              "api": device.shell("getprop", "ro.build.version.sdk").strip(),
              "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(), "checks": []}

    def passed(name, **details):
        report["checks"].append({"name": name, "status": "PASS", **details})
        print("PASS:", name, flush=True)
        (output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")

    requests = []
    fixture_state = {"up": False}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            path = urllib.parse.urlsplit(self.path).path
            requests.append({"path": path, "at": time.monotonic()})
            status, location = 200, None
            if path == "/down" or path == "/switch" and not fixture_state["up"]:
                status = 502
            if path in ("/loop", "/redirect"):
                status, location = 302, "/loop" if path == "/loop" else "/up"
            if path == "/credentials":
                status, location = 302, "http://user:password@10.0.2.2:" + str(args.port) + "/up"
            self.send_response(status)
            if location:
                self.send_header("Location", location)
            self.send_header("Content-Length", "0")
            self.send_header("Connection", "close")
            self.end_headers()

        def log_message(self, *_):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    args.port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = "http://10.0.2.2:" + str(args.port)
    urls = [base + suffix for suffix in ("/up", "/down", "/switch?token=" + SECRET,
                                        "/redirect", "/loop", "/credentials")]
    restore_clock = None
    try:
        assert args.reset_test_app, "Explicit --reset-test-app is required on this disposable emulator"
        device.adb("uninstall", PACKAGE, check=False)
        device.adb("install", "--no-incremental", str(args.baseline_apk), timeout=60)
        device.shell("am", "start", "-n", PACKAGE + "/.MainActivity")
        for url in urls:
            device.add_url(url)
        device.wait(contains="Всего: 6")
        device.assert_card(urls[0], "Сайт доступен")
        device.assert_card(urls[1], "502")
        device.settings()
        device.tap(class_name="android.widget.Spinner")
        device.tap(text="1 минута")
        device.tap(text="Готово")
        device.screenshot("01-baseline-six-sites")
        passed("baseline_1_3_six_sites_and_http_results")

        device.shell("am", "force-stop", PACKAGE)
        old_count = len(requests)
        device.adb("install", "--no-incremental", "-r", str(args.apk), timeout=60)
        device.shell("am", "start", "-n", PACKAGE + "/.MainActivity")
        device.wait(contains="Заблокировано: 6")
        assert len(requests) == old_count, "HTTP request escaped before consent"
        device.settings()
        assert device.find(class_name="android.widget.Spinner") is not None
        assert device.find(text="1 минута") is not None, "Interval was lost during upgrade"
        device.tap(text="Разрешить незащищенный HTTP")
        device.tap(text="Отмена")
        assert device.find(text="Разрешить незащищенный HTTP").get("checked") == "false"
        assert len(requests) == old_count
        passed("upgrade_preserves_six_sites_and_interval_http_default_off")
        device.tap(text="Разрешить незащищенный HTTP")
        device.tap(text="Разрешить")
        device.tap(text="Готово")
        device.assert_card(urls[0], "Сайт доступен")
        device.assert_card(urls[1], "502")
        device.assert_card(urls[4], "POLICY")
        device.assert_card(urls[5], "POLICY")
        passed("http_consent_codes_and_redirect_policy_on_android")
        device.screenshot("02-secure-http-policy")

        device.add_url(base + "/up", "Этот сайт уже добавлен")
        device.add_url("http://user:password@10.0.2.2:" + str(args.port), "логином и паролем")
        passed("duplicate_and_credential_urls_rejected")

        device.settings()
        device.tap(text="Уведомлять о восстановлении")
        allow = "com.android.permissioncontroller:id/permission_allow_button"
        device.wait(resource=allow)
        device.tap(resource=allow)
        device.tap(text="Готово")
        fixture_state["up"] = True
        device.tap(resource=PACKAGE + ":id/checkNowButton")
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            notifications = device.shell("dumpsys", "notification", "--noredact")
            if "Сайт снова доступен" in notifications and "10.0.2.2" in notifications:
                break
            time.sleep(0.5)
        else:
            raise AssertionError("Recovery notification did not arrive")
        assert SECRET not in notifications and "/switch" not in notifications
        (output / "notifications.txt").write_text(notifications)
        passed("recovery_notification_hides_path_and_secret")

        # An external HTTPS check verifies the emulator's normal system trust path.
        device.add_url("https://example.org/")
        device.assert_card("https://example.org/", "Сайт доступен")
        passed("public_https_with_system_certificate_validation")

        device.shell("input", "keyevent", "3")
        time.sleep(5)  # Let already-authorized foreground requests finish.
        before = len(requests)
        if args.advance_emulator_clock:
            auto_time = device.shell("settings", "get", "global", "auto_time").strip()
            clock_offset = int(device.shell("date", "+%s").strip()) - int(time.time())
            was_root = device.shell("id", "-u").strip() == "0"
            device.adb("root")
            device.adb("wait-for-device")
            assert device.shell("id", "-u").strip() == "0", "A rootable emulator is required for clock simulation"
            restore_clock = (auto_time, clock_offset, was_root)
            device.shell("settings", "put", "global", "auto_time", "0")
            device.shell("date", "-s", "@" + str(int(time.time()) + clock_offset + 16 * 60))
        else:
            # Observe the actual periodic run without modifying the guest clock.
            deadline = time.monotonic() + 16 * 60
            while time.monotonic() < deadline and len(requests) == before:
                time.sleep(0.5)
        jobs = device.shell("dumpsys", "jobscheduler")
        candidates = []
        for match in re.finditer(r"^\s*JOB\s+(\S+)/(\d+):[^\n]*" +
                                 re.escape(PACKAGE + "/androidx.work"), jobs, re.MULTILINE):
            identifier = match.group(1).lstrip("#")
            namespace = identifier.rsplit(":", 1)[0] if ":" in identifier else None
            candidates.append((namespace, match.group(2)))
        assert candidates, "WorkManager job is not scheduled"
        namespace, job_id = candidates[0]
        command = ["cmd", "jobscheduler", "run", "-f"]
        if namespace:
            command += ["-n", namespace]
        forced = len(requests) == before
        if forced:
            device.shell(*(command + [PACKAGE, job_id]))
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline and len(requests) == before:
            time.sleep(0.5)
        assert len(requests) > before, "Forced background worker made no request"
        passed("background_workmanager_job_runs_with_activity_hidden", job_id=job_id,
               namespace=namespace, forced=forced,
               guest_clock_advance_seconds=960 if args.advance_emulator_clock else 0)

        device.shell("am", "force-stop", PACKAGE)
        device.shell("am", "start", "-n", PACKAGE + "/.MainActivity")
        device.wait(contains="Всего: 7")
        device.assert_card("https://example.org/", "Сайт доступен")
        card = device.card("https://example.org/")
        delete = next(n for n in card.iter() if n.get("text") == "×")
        bounds = list(map(int, re.findall(r"\d+", delete.get("bounds"))))
        device.shell("input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
        device.tap(text="Удалить")
        device.wait(contains="Всего: 6")
        device.screenshot("03-final-installed-release")
        passed("restart_persistence_and_site_removal")
        crash = device.shell("logcat", "-d", "-v", "brief", "-s", "AndroidRuntime:E")
        assert "Process: " + PACKAGE not in crash, "App crash found in logcat"
        report["result"] = "PASS"
        passed("no_sitewatch_crash_in_android_runtime_log")
    except Exception:
        report["result"] = "FAIL"
        report["error"] = traceback.format_exc()
        try:
            device.screenshot("failure")
        except Exception:
            pass
        raise
    finally:
        if restore_clock:
            auto_time, clock_offset, was_root = restore_clock
            device.shell("date", "-s", "@" + str(int(time.time()) + clock_offset))
            device.shell("settings", "put", "global", "auto_time", auto_time)
            if not was_root:
                device.adb("unroot")
                device.adb("wait-for-device")
        server.shutdown()
        (output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--baseline-apk", type=Path, required=True)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--output", type=Path, default=Path("test-results/emulator"))
    parser.add_argument("--reset-test-app", action="store_true")
    parser.add_argument("--advance-emulator-clock", action="store_true",
                        help="Simulate the period by changing only a rootable emulator's clock; restored afterwards")
    run(parser.parse_args())
