#!/usr/bin/env python3
"""Stage public mapped road/rail tunnel centerlines from a saved Overpass JSON export."""
import argparse
import json
import math
from pathlib import Path

TUNNEL = {'yes', 'building_passage', 'avalanche_protector'}


def stage(doc):
    features = {}
    for way in doc['elements']:
        if way.get('type') != 'way':
            continue
        t = way.get('tags', {})
        if t.get('tunnel') not in TUNNEL or not (t.get('highway') or t.get('railway')):
            continue
        if t.get('access') in {'private', 'no'} or t.get('highway') in {'construction', 'proposed'} or t.get('railway') in {'abandoned', 'disused', 'construction', 'proposed'}:
            continue
        raw = way.get('geometry', [])
        if len(raw) < 2 or ('nodes' in way and len(way['nodes']) != len(raw)):
            continue
        coords = [[p.get('lon'), p.get('lat')] for p in raw]
        if any(not isinstance(v, (int, float)) or not math.isfinite(v) or abs(v) > limit
               for pair in coords for v, limit in zip(pair, (180, 90))):
            continue
        if len({tuple(p) for p in coords}) < 2 or any(abs(a[0]-b[0]) > 180 for a, b in zip(coords, coords[1:])):
            continue
        # Portal height, depth and cross section are not implied by OSM layer.
        ident = int(way['id'])
        props = {k: t[k] for k in ('name', 'highway', 'railway', 'tunnel', 'layer', 'ref') if k in t}
        props.update(osm_way_id=ident, vertical_geometry='unknown', ready_for_carving=False)
        feature = {'type':'Feature','id':ident,'properties':props,
                   'geometry':{'type':'LineString','coordinates':coords}}
        if ident in features and features[ident] != feature:
            raise ValueError(f'Conflicting copies of OSM way {ident}')
        features[ident] = feature
    return {'type':'FeatureCollection','attribution':'© OpenStreetMap contributors; ODbL',
            'features':[features[i] for i in sorted(features)]}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('overpass_json', type=Path)
    p.add_argument('output_geojson', type=Path)
    a = p.parse_args()
    result = stage(json.loads(a.overpass_json.read_text(encoding='utf-8')))
    with a.output_geojson.open('x', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2, allow_nan=False)
        f.write('\n')


if __name__ == '__main__':
    main()
