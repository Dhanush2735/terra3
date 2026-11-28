#!/usr/bin/env python3
"""Convert one approved, pre-projected survey centerline to a Phase 3 manifest row."""
import argparse, csv, gzip, math, struct
from pathlib import Path

RESOURCE = Path(__file__).resolve().parents[1] / 'src/main/resources/net/buildtheearth/terraplusplus/caves.bin.gz'


def convert(stations, lat, lon, source, resource=RESOURCE):
    if not (-90_000_000 <= lat <= 90_000_000 and -180_000_000 <= lon <= 180_000_000):
        raise ValueError('Invalid entrance coordinates')
    if not source.strip() or any(c in source for c in '\t\n\r'):
        raise ValueError('Supply a single-line source and attribution')
    raw = gzip.decompress(resource.read_bytes())
    if raw[:4] != b'TCV1':
        raise ValueError('Unexpected CaveData format')
    count = struct.unpack_from('<I', raw, 4)[0]
    matches = [r for r in struct.iter_unpack('<iiHIBBI', raw[8:8+20*count])
               if r[:2] == (lat, lon)]
    if len(matches) != 1 or matches[0][4] != 0 or matches[0][5] & 4:
        raise ValueError('Entrance must uniquely match a non-sensitive CaveData cave record')
    with stations.open(newline='', encoding='utf-8') as file:
        reader = csv.DictReader(file)
        if reader.fieldnames != ['x', 'y', 'z']:
            raise ValueError('Expected x,y,z header and offsets in projected block axes')
        points = []
        for row in reader:
            if len(points) >= 2048 or None in row:
                raise ValueError('Too many stations or extra fields')
            p = tuple(float(row[k]) for k in ('x', 'y', 'z'))
            if not all(map(math.isfinite, p)) or abs(p[0]) > 3500 or abs(p[2]) > 3500 or not -3500 <= p[1] <= -10:
                raise ValueError('Station outside pilot bounds')
            points.append(p)
    if len(points) < 2 or points[0][0] != 0 or points[0][2] != 0 or points[0][1] < -64:
        raise ValueError('Route must begin directly below the matched entrance')
    if not 0 < sum(math.dist(a, b) for a, b in zip(points, points[1:])) <= 5000:
        raise ValueError('Route length outside pilot bounds')
    return f'{lat}\t{lon}\t{source}\t' + ';'.join(','.join(repr(v) for v in p) for p in points)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('stations', type=Path)
    p.add_argument('--lat-e6', type=int, required=True)
    p.add_argument('--lon-e6', type=int, required=True)
    p.add_argument('--source', required=True, help='Survey credit, URL and license')
    p.add_argument('--rights-checked', action='store_true', required=True,
                   help='Confirm permission for this intended derivative and distribution')
    a = p.parse_args()
    try:
        print(convert(a.stations, a.lat_e6, a.lon_e6, a.source))
    except (ValueError, OSError, TypeError, struct.error) as e:
        p.error(str(e))


if __name__ == '__main__':
    main()
