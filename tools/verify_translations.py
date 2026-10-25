#!/usr/bin/env python3
"""Verify complete locale catalogs and Java Formatter placeholders without Android SDK."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
EXPECTED = {'values', 'values-ru', 'values-zh', 'values-hi', 'values-es', 'values-ar',
            'values-fr', 'values-bn', 'values-pt', 'values-ur', 'values-in',
            'values-de', 'values-ja', 'values-b+pcm', 'values-ar-rEG', 'values-mr'}

def catalog(path):
    rows = ET.parse(path).getroot().findall('string')
    result = {row.attrib['name']: ''.join(row.itertext()) for row in rows}
    assert len(rows) == len(result), f'Duplicate keys: {path}'
    return result

base = catalog(RES / 'values/strings.xml')
paths = list(RES.glob('values*/strings.xml'))
assert {path.parent.name for path in paths} == EXPECTED
for path in paths:
    values = catalog(path)
    assert values.keys() == base.keys(), f'Incomplete catalog: {path}'
    for key, value in values.items():
        assert value.strip('"').strip(), f'Empty translation: {path}: {key}'
        assert sorted(re.findall(r'%\d+\$[ds]', value)) == sorted(re.findall(r'%\d+\$[ds]', base[key])), (path, key)
locales = ET.parse(RES / 'xml/locales_config.xml').getroot()
assert len(locales) == 16
assert len({row.attrib['{http://schemas.android.com/apk/res/android}name'].split('-')[0] for row in locales}) == 15
assert not re.search('[А-Яа-яЁё]', (ROOT / 'app/src/main/java/app/switter/MainActivity.java').read_text()), 'Hardcoded Russian UI'
print(f'OK: {len(paths)} complete language catalogs, {len(base)} keys per language; placeholders match')
