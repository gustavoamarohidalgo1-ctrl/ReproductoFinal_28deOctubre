import os
import resource
import signal
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

HELPER = Path(__file__).resolve().parents[1] / "run-instrumentation.py"


class RunInstrumentationTest(unittest.TestCase):
    def run_helper(self, timeout, output, code):
        return subprocess.run([sys.executable, str(HELPER), str(timeout), str(output),
                               sys.executable, "-c", code], capture_output=True, timeout=3)

    def test_success_and_partial_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "log"
            result = self.run_helper(1, output, "print('complete')")
            self.assertEqual(result.returncode, 0)
            self.assertEqual(output.read_text(), "complete\n")

    def test_timeout_when_grandchild_inherits_stdout(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "log"
            code = ("import subprocess,sys; print('partial', flush=True); "
                    "subprocess.Popen([sys.executable,'-c','import time; time.sleep(1.5)'])")
            start = time.monotonic()
            result = self.run_helper(0.3, output, code)
            self.assertEqual(result.returncode, 124)
            self.assertLess(time.monotonic() - start, 1.3)
            self.assertEqual(output.read_text(), "partial\n")

    def test_write_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            def limit_output():
                signal.signal(signal.SIGXFSZ, signal.SIG_IGN)
                resource.setrlimit(resource.RLIMIT_FSIZE, (1024, 1024))
            result = subprocess.run([sys.executable, str(HELPER), "1", str(Path(directory) / "log"),
                                     sys.executable, "-c", "print('x'*8192, flush=True)"],
                                    capture_output=True, timeout=3, preexec_fn=limit_output)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(b"save instrumentation output", result.stderr)


if __name__ == "__main__":
    unittest.main()
