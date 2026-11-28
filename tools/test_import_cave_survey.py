#!/usr/bin/env python3
"""Offline importer regression checks; fixtures are synthetic, never bundled."""
import gzip
import struct
import tempfile
import unittest
from pathlib import Path

from import_cave_survey import convert


class SurveyImportTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        self.index = root / 'caves.gz'
        self.csv = root / 'stations.csv'
        self.csv.write_text('x,y,z\n0,-12,0\n3,-14,4\n')
        self.rows((0, 0))

    def rows(self, *entries):
        self.index.write_bytes(gzip.compress(b'TCV1' + struct.pack('<I', len(entries)) + b''.join(
            struct.pack('<iiHIBBI', 100, 200, 0, 0, kind, flags, 0) for kind, flags in entries)))

    def test_accepts_unique_public_entrance(self):
        self.assertEqual(convert(self.csv, 100, 200, 'Fixture', self.index),
                         '100\t200\tFixture\t0.0,-12.0,0.0;3.0,-14.0,4.0')

    def test_rejects_missing_duplicate_sensitive_or_non_cave(self):
        for entries in ((), ((0, 0), (0, 0)), ((0, 4),), ((1, 0),)):
            with self.subTest(entries=entries), self.assertRaisesRegex(ValueError, 'uniquely match'):
                self.rows(*entries)
                convert(self.csv, 100, 200, 'Fixture', self.index)

    def test_rejects_shallow_and_displaced_routes(self):
        for route in ('0,-9,0\n3,-14,4\n', '1,-12,0\n3,-14,4\n'):
            with self.subTest(route=route), self.assertRaises(ValueError):
                self.csv.write_text('x,y,z\n' + route)
                convert(self.csv, 100, 200, 'Fixture', self.index)


if __name__ == '__main__':
    unittest.main()
