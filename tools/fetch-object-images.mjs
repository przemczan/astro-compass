#!/usr/bin/env node
// Fetches a sky-survey cutout image for every deep-sky object at or brighter than a magnitude
// limit, via CDS's hips2fits service, for offline bundling into the sky map. Run manually:
// `node tools/fetch-object-images.mjs [--mag-limit 11] [--size 1024] [--concurrency 3] [--overwrite]`
//
// hips2fits (https://alasky.u-strasbg.fr/hips-image-services/hips2fits) renders an arbitrary
// RA/Dec/field-of-view cutout from a HiPS survey on demand -- every object gets a properly
// centered, uniformly-styled image instead of relying on someone having already published a
// captioned photo of it, and coverage isn't limited to the Messier catalog.
//
// Object selection/positions/sizes come from OpenNGC (same source and filter rules as
// `tools/build-catalogs.mjs`'s `buildDeepSky`, so this matches `dso.bin` exactly), so each cutout
// is centered on the exact coordinates the app itself uses and keyed by the same
// `catalogDesignation` string `DeepSkyObject.id` uses at runtime (e.g. "NGC0224").
//
// Output goes to tools/image-staging-hips2fits/ (gitignored). This is the download half of a
// two-step pipeline -- `tools/build-object-images.java` is the separate, offline "build" step
// that resizes/bundles whatever is currently staged here. That split is deliberate: to replace a
// specific object's photo, just overwrite its staged file (same filename) and rerun this script --
// an id with an already-staged file is left untouched (skipped, no re-fetch) by default, so a
// manual replacement survives a rerun. Pass --overwrite to force every selected object to be
// re-fetched regardless of what's already staged.
//
// CAUTION: DSS2 (this script's survey) is not blanket public domain like NASA's image library --
// STScI's usage terms (https://archive.stsci.edu/dss/copyright.html) restrict commercial use.
// Confirm those terms are compatible with shipping before bundling any of this into a released
// build.

import { writeFileSync, readFileSync, mkdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const STAGING_DIR = join(__dirname, 'image-staging-hips2fits');
const MANIFEST_PATH = join(STAGING_DIR, 'manifest.json');

const NGC_URL = 'https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/NGC.csv';
const ADDENDUM_URL = 'https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/addendum.csv';

const HIPS2FITS_ENDPOINT = 'https://alasky.u-strasbg.fr/hips-image-services/hips2fits';
// Full-sky coverage (merged POSS-II north + SERC/ESO south), unlike e.g. PanSTARRS DR1
// (dec >= -30 deg, which would clip southern objects). Swap for a higher-resolution survey once
// this pipeline covers only the northern sky on purpose.
const HIPS_SURVEY_ID = 'CDS/P/DSS2/color';

// Padding margin applied to an object's real north/east-aligned footprint (see
// computeSkyFootprintArcmin/computeCutoutPlan), clamped -- enough headroom for a bit of
// surrounding starfield without shrinking the object itself to a speck once SkyMap scales the
// image's longest edge to the object's on-screen size (see SkyMap.kt's drawObjectPhoto doc
// comment: the image is scaled as a whole, so a loosely-framed cutout makes the object render
// smaller than intended).
const FOV_MARGIN_FACTOR = 1.4;
const MIN_FOV_ARCMIN = 8;
const MAX_FOV_DEGREES = 5;
// Some entries (mostly asterisms/clusters) carry no MajAx in OpenNGC at all.
const DEFAULT_MAJ_AXIS_ARCMIN = 15;

// OpenNGC "Type" column values excluded from the app's own catalog (dso.bin) -- kept identical to
// build-catalogs.mjs's NGC_EXCLUDED_TYPES so this script fetches images for exactly the objects
// the app can actually display.
const NGC_EXCLUDED_TYPES = new Set(['NonEx', 'Dup']);

const DEFAULT_MAG_LIMIT = 11;
const DEFAULT_IMAGE_SIZE_PX = 1024;
// hips2fits has no documented concurrency limit -- kept modest by default since this is a shared
// CDS service, not something to hammer just because Node's event loop can juggle it.
const DEFAULT_CONCURRENCY = 3;

const USER_AGENT = 'AstroGuider-ImageTool/1.0 (offline sky-map image bundling, personal project)';

// hips2fits has no documented public rate limit either, but stay polite -- this gate is shared
// across every concurrent worker (see main's worker pool), so raising CONCURRENCY doesn't raise
// how often a *new* request can start, only how many can be in flight (awaiting their response)
// at once. That's the point: per-request latency, not this pacing floor, is main's actual
// bottleneck, so overlapping the waiting time is what concurrency buys here.
const MIN_REQUEST_INTERVAL_MS = 300;
const MAX_RETRIES = 4;
let lastRequestTime = 0;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

const args = parseArgs(process.argv.slice(2));
const MAG_LIMIT = args['mag-limit'] ? parseFloat(args['mag-limit']) : DEFAULT_MAG_LIMIT;
const IMAGE_SIZE_PX = args.size ? parseInt(args.size, 10) : DEFAULT_IMAGE_SIZE_PX;
const CONCURRENCY = args.concurrency ? parseInt(args.concurrency, 10) : DEFAULT_CONCURRENCY;
const OVERWRITE = 'overwrite' in args;

// `--flag value` sets a string value; a bare `--flag` (nothing after it, or immediately followed
// by another `--flag`) sets it to `true` -- needed for boolean flags like `--overwrite` that take
// no value, so they don't accidentally swallow the next flag's name as their own value.
function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) {
      const key = argv[i].slice(2);
      const next = argv[i + 1];
      if (next !== undefined && !next.startsWith('--')) {
        out[key] = next;
        i++;
      } else {
        out[key] = true;
      }
    }
  }
  return out;
}

async function loadText(url, overridePath) {
  if (overridePath) return readFileSync(overridePath, 'utf8');
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Failed to fetch ${url}: ${res.status}`);
  return res.text();
}

// RFC4180-ish CSV parsing: handles quoted fields (with escaped "" quotes) for an arbitrary delimiter.
// Copied from build-catalogs.mjs -- both scripts are standalone `node tools/x.mjs` entry points,
// not modules meant to share imports.
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

// Must produce a valid Kotlin identifier (Compose Multiplatform turns each drawable filename into
// a `Res.drawable.<name>` accessor) -- covers OpenNGC's embedded spaces ("IC0080 NED01") and
// hyphens (ESO-catalog ids like "ESO056-115") alike, rather than only the space case.
function sanitizeId(id) {
  return id.toLowerCase().replace(/[^a-z0-9]/g, '_');
}

// --- OpenNGC lookup ---------------------------------------------------------------------------

// Same selection as build-catalogs.mjs's buildDeepSky (exclude NonEx/Dup, require RA/Dec,
// V-Mag falling back to B-Mag), plus a magnitude cutoff -- so this fetches images for exactly the
// subset of dso.bin's objects that qualify.
async function loadCandidatePositions() {
  const byId = new Map();
  for (const [url, overridePath] of [[NGC_URL, args.ngc], [ADDENDUM_URL, args.addendum]]) {
    const text = await loadText(url, overridePath);
    const rows = parseCsv(text, ';');
    const idx = indexHeader(rows[0]);
    for (const r of rows.slice(1)) {
      const type = r[idx.Type];
      if (NGC_EXCLUDED_TYPES.has(type)) continue;

      const raStr = r[idx.RA];
      const decStr = r[idx.Dec];
      if (!raStr || !decStr) continue;

      const vMag = parseFloat(r[idx['V-Mag']]);
      const bMag = parseFloat(r[idx['B-Mag']]);
      const mag = !isNaN(vMag) ? vMag : (!isNaN(bMag) ? bMag : NaN);
      if (isNaN(mag) || mag > MAG_LIMIT) continue;

      const id = r[idx.Name];
      const messierStr = r[idx.M];

      byId.set(id, {
        id,
        messier: messierStr ? parseInt(messierStr, 10) : 0,
        commonName: (r[idx['Common names']] || '').split(',')[0].trim() || null,
        raDeg: parseRaSexagesimal(raStr),
        decDeg: parseDecSexagesimal(decStr),
        magnitude: mag,
        majAxisArcmin: parseFloat(r[idx.MajAx]),
        minAxisArcmin: parseFloat(r[idx.MinAx]),
        positionAngleDegrees: parseFloat(r[idx.PosAng]),
      });
    }
  }
  return byId;
}

// --- hips2fits ---------------------------------------------------------------------------------

// The real north/east-aligned angular footprint (arcmin) of an object's major/minor axis tilted
// by its position angle (degrees east of north -- OpenNGC's convention, same as
// build-catalogs.mjs). Cutouts stay north-up (see buildCutoutUrl's rotation_angle=0), so a tilted
// ellipse's *axis-aligned* bounding box -- not its raw (major, minor) size -- is what the cutout
// actually needs to fully contain it without clipping the corners. Falls back to a circular
// footprint (majAxisArcmin square) when minAxisArcmin or positionAngleDegrees is unknown, which
// covers most non-galaxy DSOs (star clusters etc.) -- OpenNGC rarely measures orientation for
// those, and a circular fallback reproduces this pipeline's original square-crop behavior exactly.
function computeSkyFootprintArcmin(majAxisArcmin, minAxisArcmin, positionAngleDegrees) {
  const majArcmin = Number.isFinite(majAxisArcmin) && majAxisArcmin > 0 ? majAxisArcmin : DEFAULT_MAJ_AXIS_ARCMIN;
  const hasShape = Number.isFinite(minAxisArcmin) && minAxisArcmin > 0 && Number.isFinite(positionAngleDegrees);
  if (!hasShape) return { widthArcmin: majArcmin, heightArcmin: majArcmin };

  const a = majArcmin / 2;
  const b = minAxisArcmin / 2;
  const theta = (positionAngleDegrees * Math.PI) / 180;
  // PA=0 -> major axis north-south (all in "height"); PA=90 -> major axis east-west (all in
  // "width"). Standard ellipse-bounding-box-under-rotation identity for the two in between.
  const widthArcmin = 2 * Math.sqrt((a * Math.sin(theta)) ** 2 + (b * Math.cos(theta)) ** 2);
  const heightArcmin = 2 * Math.sqrt((a * Math.cos(theta)) ** 2 + (b * Math.sin(theta)) ** 2);
  return { widthArcmin, heightArcmin };
}

// Pads/clamps computeSkyFootprintArcmin's raw footprint by its LONGER dimension (same margin/clamp
// values the original square-only version used), then scales the shorter dimension by that same
// factor so the true aspect ratio survives the clamp untouched. width/height in pixels stay
// proportional to the degree footprint, so the pixel scale (degrees/px) is uniform in both
// directions -- matching hips2fits's own convention of `fov` applying to the larger dimension.
function computeCutoutPlan(widthArcmin, heightArcmin) {
  const longerArcmin = Math.max(widthArcmin, heightArcmin);
  const paddedLongerArcmin = longerArcmin * FOV_MARGIN_FACTOR;
  const clampedLongerArcmin = Math.min(MAX_FOV_DEGREES * 60, Math.max(MIN_FOV_ARCMIN, paddedLongerArcmin));
  const scale = clampedLongerArcmin / longerArcmin;

  const widthDegrees = (widthArcmin * scale) / 60;
  const heightDegrees = (heightArcmin * scale) / 60;
  const widthPx = widthDegrees >= heightDegrees ? IMAGE_SIZE_PX : Math.round(IMAGE_SIZE_PX * (widthDegrees / heightDegrees));
  const heightPx = heightDegrees >= widthDegrees ? IMAGE_SIZE_PX : Math.round(IMAGE_SIZE_PX * (heightDegrees / widthDegrees));

  return { fovDegrees: Math.max(widthDegrees, heightDegrees), widthPx, heightPx };
}

function buildCutoutUrl(position, plan) {
  const params = new URLSearchParams({
    hips: HIPS_SURVEY_ID,
    ra: position.raDeg.toFixed(6),
    dec: position.decDeg.toFixed(6),
    fov: plan.fovDegrees.toFixed(6),
    width: String(plan.widthPx),
    height: String(plan.heightPx),
    projection: 'TAN',
    coordsys: 'icrs',
    rotation_angle: '0', // north-up, matching SkyMap's northOffsetDirections rotation convention
    format: 'jpg',
  });
  return `${HIPS2FITS_ENDPOINT}?${params}`;
}

async function politeFetch(url) {
  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    const wait = lastRequestTime + MIN_REQUEST_INTERVAL_MS - Date.now();
    if (wait > 0) await sleep(wait);
    lastRequestTime = Date.now();

    const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
    if (res.status === 429 || res.status >= 500) {
      const retryAfterHeader = res.headers.get('retry-after');
      const backoffMs = retryAfterHeader ? parseInt(retryAfterHeader, 10) * 1000 : 2 ** attempt * 1000;
      console.log(`    (${res.status}, backing off ${(backoffMs / 1000).toFixed(0)}s -- attempt ${attempt + 1}/${MAX_RETRIES})`);
      await sleep(backoffMs);
      continue;
    }
    return res;
  }
  throw new Error(`Failed after ${MAX_RETRIES} retries for ${url}`);
}

async function fetchCutout(position) {
  const { widthArcmin, heightArcmin } = computeSkyFootprintArcmin(
    position.majAxisArcmin,
    position.minAxisArcmin,
    position.positionAngleDegrees,
  );
  const plan = computeCutoutPlan(widthArcmin, heightArcmin);
  const url = buildCutoutUrl(position, plan);

  const res = await politeFetch(url);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} for ${url}`);
  const bytes = Buffer.from(await res.arrayBuffer());

  return {
    id: position.id,
    messier: position.messier,
    commonName: position.commonName,
    raDeg: position.raDeg,
    decDeg: position.decDeg,
    magnitude: position.magnitude,
    majAxisArcmin: Number.isFinite(position.majAxisArcmin) ? position.majAxisArcmin : null,
    minAxisArcmin: Number.isFinite(position.minAxisArcmin) ? position.minAxisArcmin : null,
    positionAngleDegrees: Number.isFinite(position.positionAngleDegrees) ? position.positionAngleDegrees : null,
    fovDegrees: plan.fovDegrees,
    requestedWidthPx: plan.widthPx,
    requestedHeightPx: plan.heightPx,
    hips: HIPS_SURVEY_ID,
    file: `${sanitizeId(position.id)}.jpg`,
    bytes: bytes.length,
    sourceUrl: url,
    // DSS2 usage terms need manual review before this is bundled into a shipped build -- see file
    // header caveat.
    license: 'Digitized Sky Survey (STScI / AAO-UKST) via CDS hips2fits -- NEEDS MANUAL REVIEW before shipping',
    licenseUrl: 'https://archive.stsci.edu/dss/copyright.html',
    bufferForWrite: bytes,
  };
}

// --- Driver --------------------------------------------------------------------------------

function loadManifest() {
  if (!existsSync(MANIFEST_PATH)) return { manifest: [], failures: [] };
  return JSON.parse(readFileSync(MANIFEST_PATH, 'utf8'));
}

function saveManifest(state) {
  writeFileSync(MANIFEST_PATH, JSON.stringify(state, null, 2));
}

async function main() {
  mkdirSync(STAGING_DIR, { recursive: true });
  const state = loadManifest();

  console.log(`Loading OpenNGC and selecting objects at mag <= ${MAG_LIMIT}...`);
  const positions = await loadCandidatePositions();
  console.log(`${positions.size} object(s) qualify. Fetching with ${CONCURRENCY} concurrent worker(s).${OVERWRITE ? ' Overwriting existing files.' : ''}`);

  let fetched = 0;
  let skipped = 0;

  // A shared-queue worker pool, not Promise.all over fixed-size batches -- batching would idle
  // every worker down to the slowest request each round; pulling from one shared cursor keeps all
  // CONCURRENCY workers busy until the queue itself is empty. Safe without locking: every mutation
  // of `queue`/`state` below happens synchronously between `await` points, and Node's
  // single-threaded event loop never interleaves two workers mid-synchronous-block, only at an
  // `await` -- the same reasoning that already made this pipeline's resumability safe across
  // separate runs applies here across concurrent workers within one run.
  const queue = Array.from(positions.values());
  let nextIndex = 0;

  async function worker() {
    while (nextIndex < queue.length) {
      const position = queue[nextIndex++];
      const id = position.id;

      // Keyed on the file itself, not the manifest -- a manually-replaced photo (dropped in under
      // the same filename, no manifest entry needed) is left alone same as a normal previous
      // fetch, unless --overwrite forces a re-fetch of everything selected.
      const expectedFile = `${sanitizeId(id)}.jpg`;
      if (!OVERWRITE && existsSync(join(STAGING_DIR, expectedFile))) {
        skipped++;
        continue;
      }

      let result;
      try {
        result = await fetchCutout(position);
      } catch (err) {
        state.failures = state.failures.filter((f) => f.id !== id);
        state.failures.push({ id, error: err.message });
        console.log(`${id}: FAILED -- ${err.message}`);
        saveManifest(state);
        continue;
      }

      writeFileSync(join(STAGING_DIR, result.file), result.bufferForWrite);
      delete result.bufferForWrite;

      state.manifest = state.manifest.filter((entry) => entry.id !== id);
      state.failures = state.failures.filter((f) => f.id !== id);
      state.manifest.push(result);
      // Flushed after every object so an interrupted run doesn't lose already-fetched metadata.
      saveManifest(state);
      fetched++;

      console.log(`${id}: OK  fov=${result.fovDegrees.toFixed(3)}deg  ${(result.bytes / 1024).toFixed(0)}KB${result.commonName ? `  (${result.commonName})` : ''}`);
    }
  }

  await Promise.all(Array.from({ length: CONCURRENCY }, worker));

  console.log(`\n${fetched} fetched, ${skipped} already staged, ${state.failures.length} failed. ${positions.size} total qualifying.`);
  console.log(`Staged in ${STAGING_DIR}`);
}

main();
