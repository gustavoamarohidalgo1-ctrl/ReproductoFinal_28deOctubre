#!/usr/bin/env python3
"""Bound a host command while retaining its combined output in a file."""
import os
import selectors
import signal
import subprocess
import sys
import time


class Interrupted(Exception):
    pass


def interrupted(signum, _frame):
    raise Interrupted(signum)


def stop_child(process):
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=0.5)
    except subprocess.TimeoutExpired:
        process.kill()
        try:
            process.wait(timeout=0.5)
        except subprocess.TimeoutExpired:
            pass


def main():
    timeout, output, *command = sys.argv[1:]
    deadline = time.monotonic() + float(timeout)
    process = None
    signal.signal(signal.SIGINT, interrupted)
    signal.signal(signal.SIGTERM, interrupted)
    try:
        with open(output, "wb", buffering=0) as log:
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            with selectors.DefaultSelector() as selector:
                os.set_blocking(process.stdout.fileno(), False)
                selector.register(process.stdout, selectors.EVENT_READ)
                while selector.get_map() or process.poll() is None:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        stop_child(process)
                        print("Host timeout: instrumentation or playback may continue on the device.", file=sys.stderr)
                        return 124
                    for key, _ in selector.select(min(remaining, 0.2)):
                        block = os.read(key.fd, 65536)
                        if block:
                            view = memoryview(block)
                            while view:
                                written = log.write(view)
                                if not written:
                                    raise OSError("output write made no progress")
                                view = view[written:]
                        else:
                            selector.unregister(key.fileobj)
                return process.returncode if process.returncode >= 0 else 1
    except Interrupted as error:
        if process is not None:
            stop_child(process)
        print("Interrupted: instrumentation or playback may continue on the device.", file=sys.stderr)
        return 128 + int(error.args[0])
    except OSError as error:
        if process is not None:
            stop_child(process)
        print(f"Could not run or save instrumentation output: {error}", file=sys.stderr)
        return 1
    finally:
        if process is not None and process.stdout is not None:
            process.stdout.close()


if __name__ == "__main__":
    sys.exit(main())
