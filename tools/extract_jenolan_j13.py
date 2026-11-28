#!/usr/bin/env python3
"""Extract the measured J13 main traverse as local grid metres (not game blocks)."""
import csv
import math
import sys
from pathlib import Path


def extract(text):
    block = text.split('*begin ent\n', 1)[1].split('*data passage', 1)[0]
    if '*calibrate declination -10.7 ; For Grid North.' not in block:
        raise ValueError('Unexpected grid-north calibration')
    graph = {}
    for line in block.splitlines():
        parts = line.partition(';')[0].split()
        if not parts or parts[0].startswith('*'):
            continue
        a, b, length, bearing, inclination = parts
        length = float(length)
        az = math.radians(float(bearing) + 10.7)
        slope = math.radians(float(inclination))
        horizontal = length * math.cos(slope)
        v = (horizontal * math.sin(az), horizontal * math.cos(az), length * math.sin(slope))
        graph.setdefault(a, []).append((b, v))
        graph.setdefault(b, []).append((a, tuple(-x for x in v)))
    stack = [[('J13', (0., 0., 0.))]]
    while stack:
        route = stack.pop()
        station, point = route[-1]
        if station == 'MFC101':
            return route
        seen = {name for name, _ in route}
        for nxt, delta in graph[station]:
            if nxt not in seen:
                stack.append(route + [(nxt, tuple(a + b for a, b in zip(point, delta)))])
    raise ValueError('No measured J13–MFC101 connection')


if __name__ == '__main__':
    route = extract(Path(sys.argv[1]).read_text())
    writer = csv.writer(sys.stdout, lineterminator='\n')
    writer.writerow(('station', 'grid_east_m', 'grid_north_m', 'up_m'))
    for name, point in route:
        writer.writerow((name, *(f'{x:.4f}' for x in point)))
