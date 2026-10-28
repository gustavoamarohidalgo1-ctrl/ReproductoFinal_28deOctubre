#!/usr/bin/env python3
"""Sample background playback through ADB; does not control playback or measure sound.

ADB polling itself adds load. A passing result describes sampled conditions only.
"""
import argparse
import json
import os
import re
import signal
import subprocess
import sys
import time
from pathlib import Path

TRACK = re.compile(r'^\s*(?:[A-Za-z]\s+)?(\d+)\s+yes\s+(\d+)\s*/\s*(\d+)\s+(\d+)\s+(\d+)\s+\S\s+0x[0-9a-fA-F]+\s+[0-9a-fA-F]+\s+[0-9a-fA-F]+\s+(\d+)\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+(?:true|false)\s+([0-9A-Fa-f]{8})\s+\d+\s+\d+\s+\S+\s+(\d+)\b')


def parse_track(dump, pid, uid):
    # Rows are accepted only in the live track table, never Local log history.
    active = False
    history = False
    found = []
    for line in dump.splitlines():
        if re.match(r'^Output thread\b', line):
            history = False
        if 'Local log:' in line:
            history = True
        if history or 'Effect Chains' in line:
            active = False
        if history:
            continue
        if 'Client(pid/uid)' in line and 'Underruns' in line and 'Server' in line:
            active = True
            continue
        if active:
            match = TRACK.match(line)
            if match and int(match[2]) == pid and int(match[3]) == uid:
                found.append(dict(id=int(match[1]), pid=pid, uid=uid, session=int(match[4]),
                                  port=int(match[5]), rate=int(match[6]),
                                  server=int(match[7], 16), underruns=int(match[8])))
    return found[0] if len(found) == 1 else None


def counter_delta(old, new, seconds, rate):
    if seconds <= 0 or rate <= 0:
        return None
    delta = (new - old) & 0xffffffff
    # Permit scheduling/buffering jitter without treating reset as a wrap.
    return delta if delta <= rate * (seconds * 1.5 + 3) else None


def sufficient_progress(delta, seconds, rate):
    # Three seconds of jitter is a plausibility allowance, not proof of sound.
    return delta is not None and delta > 0 and delta >= rate * max(0, seconds - 3)


def interval_ok(delta, seconds, rate, previous_underruns, current_underruns):
    return (sufficient_progress(delta, seconds, rate) and
            current_underruns == previous_underruns)


def has_own_binding(service_block, package):
    pattern = r'\bClient AppBindRecord\{[^\n]*ProcessRecord\{[^\n]*\b\d+:' + re.escape(package) + r'(?:[:/])'
    return bool(re.search(pattern, service_block))


def principal_wakefulness(power):
    match = re.search(r'^\s{2}mWakefulness=(\w+)\s*$', power, re.M)
    return match.group(1) if match else None


def memory_totals(mem):
    pss = re.search(r'^\s*TOTAL PSS:\s*(\d+)\b', mem, re.M)
    rss = re.search(r'\bTOTAL RSS:\s*(\d+)\b', mem)
    return (int(pss[1]) if pss else None, int(rss[1]) if rss else None)


def same_track(a, b):
    return all(a[k] == b[k] for k in ('pid', 'id', 'session', 'port', 'rate'))


def coverage_ok(gap, interval):
    return gap <= 2 * interval + 5


def output_available(out):
    return not any((out / name).exists() for name in ('summary.json', 'samples.jsonl'))


def valid_package(package):
    return bool(re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+', package))


def session_matches(block, package):
    return bool(re.search(r'^\s*package=' + re.escape(package) + r'\s*$', block, re.M))


def has_instrumentation(processes, package):
    blocks = re.split(r'(?=^\s*\*APP\* UID )', processes, flags=re.M)
    for block in blocks:
        header = block.split('\n', 1)[0]
        if not re.search(r'\d+:' + re.escape(package) + r'(?:[:/])', header):
            continue
        if re.search(r'\bActiveInstrumentation\{', block):
            return True
        if re.search(r'\bm(?:Active)?Instr(?:umentation)?\s*=\s*(?!null\b|none\b)[^\s]+', block, re.I):
            return True
    return False


def snapshot_valid(sample, boundary=False):
    return (sample['fgs'] and sample['playing'] and sample['asleep'] and
            sample['track'] is not None and not sample['own_binding'] and
            (not boundary or not sample['instrumentation']))


def evaluate(samples, duration, elapsed, invalid=False):
    return (not invalid and len(samples) >= 2 and elapsed >= duration and
            snapshot_valid(samples[0], True) and snapshot_valid(samples[-1], True) and
            all(snapshot_valid(s) for s in samples) and
            any(s.get('advanced', False) for s in samples[1:]))


def atomic_json(path, value):
    tmp = path.with_suffix(path.suffix + '.tmp')
    with tmp.open('w', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(tmp, path)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--package', required=True)
    p.add_argument('--duration', type=int, required=True)
    p.add_argument('--interval', type=int, default=30)
    p.add_argument('--output', required=True)
    p.add_argument('--timeout', type=float, default=20)
    args = p.parse_args()
    if not valid_package(args.package):
        p.error('package must be an Android package identifier')
    if not 10 <= args.duration <= 604800 or not 5 <= args.interval <= 60 or args.timeout <= 0:
        p.error('duration must be 10..604800, interval 5..60, timeout positive')
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    if not output_available(out):
        p.error('output already contains a run; choose a new directory')
    stopped = False

    def stop(_signum, _frame):
        nonlocal stopped
        stopped = True
    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    def adb(*command):
        return subprocess.run([args.adb, '-s', args.serial, 'shell', *command],
                              capture_output=True, text=True, timeout=args.timeout, check=True).stdout

    def take_sample(metrics):
        pid_text = adb('pidof', args.package + ':playback').strip().split()
        if len(pid_text) != 1 or not pid_text[0].isdigit():
            raise RuntimeError('playback process unavailable or ambiguous')
        pid = int(pid_text[0])
        packages = adb('cmd', 'package', 'list', 'packages', '-U', args.package)
        uid_matches = re.findall(r'^package:' + re.escape(args.package) + r'\s+uid:(\d+)\s*$', packages, re.M)
        if len(uid_matches) != 1:
            raise RuntimeError('playback UID unavailable')
        uid = int(uid_matches[0])
        service = adb('dumpsys', 'activity', 'services', args.package)
        audio = adb('dumpsys', 'media.audio_flinger')
        audio_observed_at = time.monotonic()
        session = adb('dumpsys', 'media_session')
        power = adb('dumpsys', 'power')
        processes = adb('dumpsys', 'activity', 'processes')
        blocks = re.split(r'(?=^\s*\* ServiceRecord\{)', service, flags=re.M)
        service_block = next((b for b in blocks if args.package + '/.PlaybackService' in b), '')
        session_blocks = re.split(r'(?=^\s*[^\n]*\(userId=\d+\)\s*$)', session, flags=re.M)
        playing = any(session_matches(b, args.package) and re.search(r'state=PLAYING\(3\)', b) for b in session_blocks)
        own_binding = has_own_binding(service_block, args.package)
        instrumentation = has_instrumentation(processes, args.package)
        fgs_match = re.search(r'isForeground=true[^\n]*types=0x([0-9a-fA-F]+)', service_block)
        sample = dict(pid=pid, uid=uid, track=parse_track(audio, pid, uid), playing=playing,
                      audio_observed_at=audio_observed_at,
                      fgs=bool(fgs_match and int(fgs_match[1], 16) & 2),
                      asleep=principal_wakefulness(power) in ('Asleep', 'Dozing'),
                      own_binding=own_binding, instrumentation=instrumentation)
        if metrics:
            mem = adb('dumpsys', 'meminfo', str(pid))
            pss, rss = memory_totals(mem)
            sample.update(pss_kb=pss, rss_kb=rss)
            cpu = adb('dumpsys', 'cpuinfo')
            match = re.search(r'^\s*([\d.]+)%\s+' + str(pid) + r'/', cpu, re.M)
            sample['cpu_percent'] = float(match[1]) if match else None
        # Save only app-specific rows and relevant system indicators.
        raw = {'audio': '\n'.join(line for line in audio.splitlines() if str(pid) + '/' in line or 'Client(pid/uid)' in line),
               'service': service_block, 'session': '\n'.join(b for b in session_blocks if session_matches(b, args.package)),
               'power': '\n'.join(line for line in power.splitlines() if 'mWakefulness=' in line),
               'processes': '\n'.join(line for line in processes.splitlines() if args.package in line)}
        return sample, raw

    samples_count = 0
    previous = None
    first = None
    last = None
    last_raw = None
    changes = 0
    advance_count = 0
    invalid = False
    failure = None
    start = time.monotonic()
    next_metrics = start
    report = dict(status='running', serial=args.serial, package=args.package,
                  requested_seconds=args.duration, interval_seconds=args.interval,
                  caveat='Sampling and ADB add load; physical sound is not measured.')
    atomic_json(out / 'summary.json', report)
    try:
        with (out / 'samples.jsonl').open('w', encoding='utf-8') as log:
            while not stopped:
                sample, raw = take_sample(time.monotonic() >= next_metrics)
                now = time.monotonic()
                sample['elapsed_seconds'] = now - start
                if now >= next_metrics:
                    next_metrics = now + 300
                if previous and sample['track'] and previous['track']:
                    a, b = previous['track'], sample['track']
                    gap = sample['audio_observed_at'] - previous['audio_observed_at']
                    if not coverage_ok(gap, args.interval):
                        invalid = True
                        sample['coverage_gap'] = True
                        failure = 'sampling coverage gap exceeded limit'
                    if not same_track(a, b):
                        changes += 1
                        invalid = True
                        sample['track_changed'] = True
                        failure = failure or 'playback track, process, or format changed'
                    else:
                        delta = counter_delta(a['server'], b['server'], gap, b['rate'])
                        sample['frame_delta'] = delta
                        sample['advanced'] = sufficient_progress(delta, gap, b['rate'])
                        sample['underrun_delta'] = b['underruns'] - a['underruns']
                        if not interval_ok(delta, gap, b['rate'], a['underruns'], b['underruns']):
                            invalid = True
                            failure = failure or ('track underrun counter changed' if sample['underrun_delta'] != 0
                                                  else 'insufficient or implausible audio frame progress')
                        else:
                            advance_count += 1
                samples_count += 1
                first = first or sample
                last, last_raw = sample, raw
                if samples_count == 1:
                    atomic_json(out / 'initial.json', raw)
                if not snapshot_valid(sample, samples_count == 1):
                    invalid = True
                    failure = failure or 'invalid snapshot: ' + ', '.join(
                        name for name, ok in (
                            ('foreground media service', sample['fgs']), ('playing session', sample['playing']),
                            ('screen asleep', sample['asleep']), ('active audio track', sample['track'] is not None),
                            ('no app binding', not sample['own_binding']),
                            ('no instrumentation', not sample['instrumentation'])
                        ) if not ok)
                log.write(json.dumps(sample) + '\n')
                log.flush()
                previous = sample
                observed = sample['elapsed_seconds'] - first['elapsed_seconds']
                if invalid or observed >= args.duration:
                    break
                remaining = min(args.interval, args.duration - observed)
                end = time.monotonic() + remaining
                while not stopped and time.monotonic() < end:
                    time.sleep(min(0.25, end - time.monotonic()))
    except (OSError, subprocess.SubprocessError, RuntimeError, ValueError) as exc:
        failure = str(exc)
        invalid = True
    finally:
        elapsed = last['elapsed_seconds'] - first['elapsed_seconds'] if last and first else 0
        if last is not None and not snapshot_valid(last, True):
            invalid = True
            failure = failure or 'invalid final snapshot: app binding or instrumentation present'
        if last_raw is not None:
            atomic_json(out / ('failure.json' if invalid or stopped else 'final.json'), last_raw)
        passed = not stopped and first is not None and last is not None and evaluate(
            [first, last], args.duration, elapsed, invalid or advance_count == 0)
        report.update(status='passed' if passed else 'interrupted' if stopped else 'failed',
                      elapsed_seconds=elapsed, samples=samples_count, advancing_samples=advance_count,
                      track_changes=changes, failure=failure, first=first, last=last)
        atomic_json(out / 'summary.json', report)
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
