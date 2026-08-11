#!/usr/bin/env node
// Generates composeApp/src/commonMain/composeResources/files/{stars,dso,constellations}.bin from
// the HYG star database, OpenNGC deep-sky catalog, and d3-celestial's constellation line data.
// Run manually/occasionally with `node tools/build-catalogs.mjs` -- this script fetches from the
// network, but its *output* is committed and the Gradle build never touches the network, so the
// app build stays reproducible offline.
//
// Sources (all verified 2026-08-08, credited in LICENSES.md):
//   HYG v4.1        https://github.com/astronexus/HYG-Database  (Astronomy Nexus / David Nash), CC-BY-SA-4.0
//   OpenNGC         https://github.com/mattiaverga/OpenNGC      (Mattia Verga), CC-BY-SA-4.0
//   d3-celestial    https://github.com/ofrohn/d3-celestial      (Olaf Frohn), BSD-3-Clause
//
// Usage: node tools/build-catalogs.mjs [--hyg <path>] [--ngc <path>] [--addendum <path>] [--constellations <path>]
//   Omit a flag to fetch that source fresh; pass a local path to reuse an already-downloaded copy.

import { writeFileSync, readFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = join(__dirname, '..', 'composeApp', 'src', 'commonMain', 'composeResources', 'files');

const HYG_URL = 'https://raw.githubusercontent.com/astronexus/HYG-Database/main/hyg/CURRENT/hygdata_v41.csv';
const NGC_URL = 'https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/NGC.csv';
const ADDENDUM_URL = 'https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/addendum.csv';
const CONSTELLATION_LINES_URL = 'https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/constellations.lines.json';

// Stars fainter than this are dropped even if named -- keeps the blob to genuinely
// identifiable/pointable stars rather than the ~12k anonymous mag<=7 field stars HYG also carries.
const STAR_MAG_LIMIT = 7.0;

const args = parseArgs(process.argv.slice(2));

async function loadText(url, overridePath) {
  if (overridePath) return readFileSync(overridePath, 'utf8');
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Failed to fetch ${url}: ${res.status}`);
  return res.text();
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) {
      out[argv[i].slice(2)] = argv[i + 1];
      i++;
    }
  }
  return out;
}

// RFC4180-ish CSV parsing: handles quoted fields (with escaped "" quotes) for an arbitrary delimiter.
function parseCsv(text, delimiter) {
  const rows = [];
  let row = [];
  let field = '';
  let inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inQuotes) {
      if (c === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; } else inQuotes = false;
      } else field += c;
    } else if (c === '"') {
      inQuotes = true;
    } else if (c === delimiter) {
      row.push(field);
      field = '';
    } else if (c === '\n') {
      row.push(field);
      field = '';
      if (row.length > 1 || row[0] !== '') rows.push(row);
      row = [];
    } else if (c === '\r') {
      // skip
    } else {
      field += c;
    }
  }
  if (field.length || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

function indexHeader(headerRow) {
  const idx = {};
  headerRow.forEach((h, i) => { idx[h] = i; });
  return idx;
}

class BinaryWriter {
  constructor() { this.chunks = []; }
  uint8(v) { this.chunks.push(Buffer.from([v & 0xFF])); }
  int32(v) { const b = Buffer.alloc(4); b.writeInt32LE(v, 0); this.chunks.push(b); }
  float32(v) { const b = Buffer.alloc(4); b.writeFloatLE(v, 0); this.chunks.push(b); }
  string(s) {
    const bytes = Buffer.from(s ?? '', 'utf8');
    if (bytes.length > 255) throw new Error(`String too long (${bytes.length} bytes): ${s}`);
    this.uint8(bytes.length);
    this.chunks.push(bytes);
  }
  toBuffer() { return Buffer.concat(this.chunks); }
}

// "HH:MM:SS.SS" -> degrees
function parseRaSexagesimal(s) {
  const [h, m, sec] = s.split(':').map(Number);
  return (h + m / 60 + sec / 3600) * 15;
}

// "+DD:MM:SS.S" -> degrees
function parseDecSexagesimal(s) {
  const sign = s.startsWith('-') ? -1 : 1;
  const [d, m, sec] = s.replace(/^[+-]/, '').split(':').map(Number);
  return sign * (d + m / 60 + sec / 3600);
}

const DEG_TO_RAD = Math.PI / 180;

async function buildStars() {
  const text = await loadText(HYG_URL, args.hyg);
  const rows = parseCsv(text, ',');
  const idx = indexHeader(rows[0]);

  const stars = [];
  for (const r of rows.slice(1)) {
    const id = parseInt(r[idx.id], 10);
    if (id === 0) continue; // HYG id=0 is "Sol" -- the Sun itself, computed via ephemeris instead
    const mag = parseFloat(r[idx.mag]);
    if (!(mag <= STAR_MAG_LIMIT)) continue;

    const proper = r[idx.proper] || '';
    const bayer = r[idx.bayer] || '';
    const flam = r[idx.flam] || '';
    if (!proper && !bayer && !flam) continue; // no usable label -- skip anonymous field stars

    stars.push({
      id,
      hip: r[idx.hip] ? parseInt(r[idx.hip], 10) : 0,
      raRad: parseFloat(r[idx.ra]) * 15.0 * DEG_TO_RAD, // HYG stores RA in decimal hours
      decRad: parseFloat(r[idx.dec]) * DEG_TO_RAD,
      mag,
      proper,
      bayer,
      flamsteed: flam ? parseInt(flam, 10) : 0,
      constellation: r[idx.con] || '',
    });
  }

  const w = new BinaryWriter();
  w.int32(stars.length);
  for (const s of stars) {
    w.int32(s.id);
    w.int32(s.hip);
    w.float32(s.raRad);
    w.float32(s.decRad);
    w.float32(s.mag);
    w.string(s.proper);
    w.string(s.bayer);
    w.uint8(s.flamsteed);
    w.string(s.constellation);
  }
  mkdirSync(OUT_DIR, { recursive: true });
  writeFileSync(join(OUT_DIR, 'stars.bin'), w.toBuffer());
  console.log(`stars.bin: ${stars.length} stars (mag <= ${STAR_MAG_LIMIT}, named or Bayer/Flamsteed only)`);
}

// OpenNGC "Type" column -> ordinal, matching catalog.SkyObjectType's declaration order.
const NGC_TYPE_TO_ORDINAL = {
  '*': 0, '**': 1, '*Ass': 2, 'OCl': 3, 'GCl': 4, 'Cl+N': 5, 'G': 6, 'GPair': 7, 'GTrpl': 8, 'GGroup': 9,
  'PN': 10, 'HII': 11, 'DrkN': 12, 'EmN': 13, 'Neb': 14, 'RfN': 15, 'SNR': 16, 'Nova': 17, 'Other': 18,
};
// Nonexistent-object and duplicate/alias entries add noise without value for a guider.
const NGC_EXCLUDED_TYPES = new Set(['NonEx', 'Dup']);

async function buildDeepSky() {
  const ngcText = await loadText(NGC_URL, args.ngc);
  const addendumText = await loadText(ADDENDUM_URL, args.addendum);

  const objects = [];
  for (const text of [ngcText, addendumText]) {
    const rows = parseCsv(text, ';');
    const idx = indexHeader(rows[0]);
    for (const r of rows.slice(1)) {
      const type = r[idx.Type];
      if (NGC_EXCLUDED_TYPES.has(type)) continue;
      const raStr = r[idx.RA];
      const decStr = r[idx.Dec];
      if (!raStr || !decStr) continue; // a handful of rows carry no coordinates at all

      const vMag = parseFloat(r[idx['V-Mag']]);
      const bMag = parseFloat(r[idx['B-Mag']]);
      const mag = !isNaN(vMag) ? vMag : (!isNaN(bMag) ? bMag : NaN); // NaN = "no magnitude" sentinel

      const messierStr = r[idx.M];

      // Apparent size/orientation on the sky -- MajAx/MinAx in arcmin, PosAng in degrees east of
      // north. Many objects (especially round or small ones) carry no MinAx/PosAng at all; NaN is
      // the sentinel here too, same convention as `mag` above -- interpretation (e.g. "no MinAx
      // means circular") lives in the Kotlin decode side, not baked into the stored bytes.
      const majAxisArcmin = parseFloat(r[idx.MajAx]);
      const minAxisArcmin = parseFloat(r[idx.MinAx]);
      const positionAngleDegrees = parseFloat(r[idx.PosAng]);

      objects.push({
        name: r[idx.Name],
        type: NGC_TYPE_TO_ORDINAL[type] ?? NGC_TYPE_TO_ORDINAL['Other'],
        raRad: parseRaSexagesimal(raStr) * DEG_TO_RAD,
        decRad: parseDecSexagesimal(decStr) * DEG_TO_RAD,
        mag,
        constellation: r[idx.Const] || '',
        commonName: (r[idx['Common names']] || '').split(',')[0].trim(),
        messier: messierStr ? parseInt(messierStr, 10) : 0,
        majAxisArcmin,
        minAxisArcmin,
        positionAngleDegrees,
      });
    }
  }

  const w = new BinaryWriter();
  w.int32(objects.length);
  for (const o of objects) {
    w.string(o.name);
    w.uint8(o.messier);
    w.uint8(o.type);
    w.float32(o.raRad);
    w.float32(o.decRad);
    w.float32(o.mag);
    w.string(o.constellation);
    w.string(o.commonName);
    w.float32(o.majAxisArcmin);
    w.float32(o.minAxisArcmin);
    w.float32(o.positionAngleDegrees);
  }
  mkdirSync(OUT_DIR, { recursive: true });
  writeFileSync(join(OUT_DIR, 'dso.bin'), w.toBuffer());
  console.log(`dso.bin: ${objects.length} deep-sky objects`);
}

// d3-celestial stores RA as signed degrees (west-positive, i.e. mirrored/negated versus normal
// east-positive RA -- see its own d3.geo-projection convention), not HIP star references: each
// vertex is its own RA/Dec coordinate, independent of (and not required to exactly coincide with)
// any star in stars.bin. That sidesteps the brittleness a HIP-keyed format would have (a bundled
// stars.bin that later drops a star would silently break a line referencing it) at the cost of a
// few arcminutes of mismatch between a line's endpoint and the star it schematically touches --
// invisible for a stick figure, which was never meant to hit star centers pixel-perfect.
function normalizeRaDegrees(signedDegrees) {
  return signedDegrees < 0 ? signedDegrees + 360 : signedDegrees;
}

async function buildConstellationLines() {
  const text = await loadText(CONSTELLATION_LINES_URL, args.constellations);
  const geoJson = JSON.parse(text);

  const w = new BinaryWriter();
  w.int32(geoJson.features.length);
  for (const feature of geoJson.features) {
    const polylines = feature.geometry.coordinates;
    w.string(feature.id);
    w.int32(polylines.length);
    for (const polyline of polylines) {
      w.int32(polyline.length);
      for (const [raDeg, decDeg] of polyline) {
        w.float32(normalizeRaDegrees(raDeg) * DEG_TO_RAD);
        w.float32(decDeg * DEG_TO_RAD);
      }
    }
  }
  mkdirSync(OUT_DIR, { recursive: true });
  writeFileSync(join(OUT_DIR, 'constellations.bin'), w.toBuffer());
  const segments = geoJson.features.flatMap((f) => f.geometry.coordinates).reduce((sum, line) => sum + line.length - 1, 0);
  console.log(`constellations.bin: ${geoJson.features.length} constellations, ${segments} line segments`);
}

await buildStars();
await buildDeepSky();
await buildConstellationLines();
