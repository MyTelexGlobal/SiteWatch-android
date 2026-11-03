#!/usr/bin/env python3
"""Explicit Play Protect device scan on an isolated Google Play emulator.

This is not Google Play review or a promise about other devices. No Google
account is imported and no protection settings are changed. English system
UI is required. The signed APK must already be suitable for this test device.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time

from emulator_smoke import Emulator, PACKAGE


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    device = Emulator(args.adb, args.serial, args.output)
    report = {'apk_sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              'scope': 'One explicit Play Protect device scan on a Google Play emulator',
              'google_play_approval': False, 'warning_removal_on_other_devices_proven': False,
              'observations': [], 'result': 'INCOMPLETE'}
    try:
        device.adb('install', '--no-incremental', '-r', str(args.apk), timeout=90)
        info = device.shell('dumpsys', 'package', PACKAGE)
        report['installed_version'] = next(line.strip() for line in info.splitlines()
                                           if 'versionName=' in line)
        device.shell('am', 'start', '-a', 'com.google.android.gms.settings.SECURITY_SETTINGS')
        device.wait(text='Google Play Protect')
        device.tap(text='Google Play Protect')
        device.wait(text='Scan')
        device.tap(text='Scan')
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            texts = [node.get('text') or node.get('content-desc')
                     for node in device.tree().iter()
                     if node.get('text') or node.get('content-desc')]
            report['observations'].append(texts)
            print(texts[:6], flush=True)
            fresh = any('moments ago' in text.lower() or 'just now' in text.lower()
                        for text in texts)
            if 'No harmful apps found' in texts and fresh and 'СайтКонтроль' in texts:
                device.screenshot('play-protect-no-harmful-apps')
                report['result'] = 'PASS'
                report['message'] = 'No harmful apps found'
                report['sitewatch_in_recently_scanned_apps'] = True
                break
            time.sleep(1)
        if report['result'] != 'PASS':
            raise AssertionError('A fresh completed scan including SiteWatch was not observed')
    except Exception as error:
        report['error'] = str(error)
        device.screenshot('play-protect-incomplete')
        raise
    finally:
        (args.output / 'play-protect-result.json').write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
