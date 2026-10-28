import importlib.util
from pathlib import Path
import unittest
import tempfile

spec = importlib.util.spec_from_file_location('monitor', Path(__file__).parents[1] / 'monitor-background-playback.py')
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)


class MonitorTest(unittest.TestCase):
    def test_track_table_excludes_local_history(self):
        header = 'Type Id Active Client(pid/uid) Session Port Id S Flags Format Chn mask SRate ST Usg CT G db L dB R dB VS dB PortVol dB PortMuted Server FrmCnt FrmRdy F Underruns Flushed BitPerfect InternalMute Latency'
        row = '  56 yes 4430/ 10213 145 16 A 0x000 00000001 00000003 44100 3 1 2 -33 0 0 0 -33 false 000B8022 33075 32075 A 0 0 false false 815.26 t'
        historic = '  57 yes 4430/ 10213 145 16 A 0x000 00000001 00000003 44100 3 1 2 -33 0 0 0 -33 false 000B8022 33075 32075 A 0 0 false false 815.26 t'
        track = monitor.parse_track(header + '\n' + row + '\n  0 Effect Chains\n  Local log:\n' + header + '\n' + historic, 4430, 10213)
        self.assertEqual(56, track['id'])
        self.assertEqual(0xB8022, track['server'])
        self.assertIsNone(monitor.parse_track('Local log:\n' + historic, 4430, 10213))

    def test_counter_freeze_rollover_and_reset(self):
        self.assertEqual(0, monitor.counter_delta(100, 100, 5, 44100))
        self.assertEqual(512, monitor.counter_delta(0xffffff00, 0x100, 5, 44100))
        self.assertIsNone(monitor.counter_delta(10_000_000, 100, 5, 44100))
        self.assertFalse(monitor.sufficient_progress(1, 30, 44100))
        self.assertFalse(monitor.sufficient_progress(0, 5, 44100))
        self.assertFalse(monitor.sufficient_progress(1_000_000, 30, 44100))
        self.assertTrue(monitor.sufficient_progress(1_200_000, 30, 44100))
        self.assertFalse(monitor.interval_ok(1_200_000, 30, 44100, 2, 3))
        self.assertFalse(monitor.interval_ok(1_200_000, 30, 44100, 2, 1))
        self.assertTrue(monitor.interval_ok(1_200_000, 30, 44100, 2, 2))

    def test_own_client_binding(self):
        package = 'com.reproductor.audiolibros'
        actual = '* Client AppBindRecord{fb267d6 ProcessRecord{d3bbdf8 4408:com.reproductor.audiolibros/u0a213}}'
        self.assertTrue(monitor.has_own_binding(actual, package))
        self.assertTrue(monitor.has_own_binding(actual.replace('/u0a213', ':other/u0a213'), package))
        self.assertFalse(monitor.has_own_binding(actual.replace(package, package + 'x'), package))
        self.assertFalse(monitor.has_own_binding('Bindings: none', package))

    def test_track_changes_and_coverage(self):
        track = dict(pid=1, id=2, session=3, port=4, rate=44100)
        self.assertTrue(monitor.same_track(track, dict(track)))
        for key in track:
            self.assertFalse(monitor.same_track(track, dict(track, **{key: track[key] + 1})))
        self.assertTrue(monitor.coverage_ok(65, 30))
        self.assertFalse(monitor.coverage_ok(65.1, 30))

    def test_primary_power_and_labeled_memory(self):
        self.assertEqual('Awake', monitor.principal_wakefulness('  mWakefulness=Awake\n    mWakefulness=Asleep\n'))
        self.assertIsNone(monitor.principal_wakefulness('    mWakefulness=Asleep\n'))
        self.assertEqual((120, 340), monitor.memory_totals('TOTAL PSS: 120 TOTAL RSS: 340\n'))
        self.assertEqual((None, None), monitor.memory_totals('TOTAL 120 340\n'))

    def test_existing_run_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            self.assertTrue(monitor.output_available(path))
            (path / 'samples.jsonl').write_text('evidence')
            self.assertFalse(monitor.output_available(path))

    def test_package_and_instrumentation_are_exact(self):
        package = 'com.reproductor.audiolibros'
        self.assertTrue(monitor.valid_package(package))
        self.assertFalse(monitor.valid_package(package + ';id'))
        self.assertTrue(monitor.session_matches('  package=' + package + '\n', package))
        self.assertFalse(monitor.session_matches('  package=' + package + '.validation\n', package))
        header = '  *APP* UID 10213 ProcessRecord{abc 4430:' + package + ':playback/u0a213}\n'
        self.assertTrue(monitor.has_instrumentation(header + '    mInstr=ActiveInstrumentation{def}\n', package))
        self.assertFalse(monitor.has_instrumentation(header + '    mInstr=null\n', package))
        other = '  *APP* UID 2 ProcessRecord{abc 1:other.app/u0a1}\n  mInstr=ActiveInstrumentation{def}\n'
        self.assertFalse(monitor.has_instrumentation(other + header + '  mInstr=null\n', package))

    def test_session_playing_alone_never_proves_progress(self):
        base = dict(fgs=True, playing=True, asleep=True, track={'server': 0},
                    own_binding=False, instrumentation=False, advanced=False)
        self.assertFalse(monitor.evaluate([base, dict(base)], 10, 10))
        self.assertTrue(monitor.evaluate([base, dict(base, advanced=True)], 10, 10))
        self.assertFalse(monitor.evaluate([base, dict(base, advanced=True)], 10, 9))
        self.assertFalse(monitor.evaluate([base, dict(base, advanced=True, own_binding=True)], 10, 10))


if __name__ == '__main__':
    unittest.main()
