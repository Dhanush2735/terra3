#!/usr/bin/env python3
"""Check a verified WGS84 survey entrance against bundled public cave records."""
import argparse
import gzip
import math
import struct

from import_cave_survey import RESOURCE


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('lat', type=float)
    parser.add_argument('lon', type=float)
    args = parser.parse_args()
    if not (-90 <= args.lat <= 90 and -180 <= args.lon <= 180):
        parser.error('Expected latitude and longitude in decimal degrees')
    data = gzip.decompress(RESOURCE.read_bytes())
    if data[:4] != b'TCV1':
        parser.error('Unexpected CaveData format')
    count = struct.unpack_from('<I', data, 4)[0]
    lat, lon = map(math.radians, (args.lat, args.lon))
    target = round(args.lat * 1e6), round(args.lon * 1e6)
    exact = []
    nearest = math.inf
    for a, b, _, _, kind, flags, _ in struct.iter_unpack('<iiHIBBI', data[8:8 + 20 * count]):
        eligible = kind == 0 and not flags & 4
        if (a, b) == target:
            exact.append(eligible)
        if eligible:
            y, x = map(math.radians, (a / 1e6, b / 1e6))
            h = math.sin((y - lat) / 2)**2 + math.cos(lat) * math.cos(y) * math.sin((x - lon) / 2)**2
            nearest = min(nearest, 12_742_000 * math.asin(math.sqrt(min(1, h))))
    if exact == [True]:
        print('Unique eligible entrance key. Verify the survey and record identify the same cave before import.')
        return 0
    print('No unique eligible entrance key.' + (f' Nearest eligible record: {nearest:.1f} m away.' if math.isfinite(nearest) else ''))
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
