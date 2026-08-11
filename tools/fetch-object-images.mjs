#!/usr/bin/env node
// Fetches a lead image + license metadata for each Messier object, for offline bundling into the
// sky map. Run manually: `node tools/fetch-object-images.mjs`
//
// This step only downloads full-resolution originals and license metadata into
// tools/image-staging/ (gitignored) for review -- resizing, plate-solving for real apparent
// size/orientation, and committing the approved set into composeResources happens in later steps
// once the picks here are approved. Nothing here is committed to the app.
//
// Sourcing: NASA's public image library (images-api.nasa.gov, no auth, effectively no rate limit,
// content is public domain unless an item explicitly carries a `copyright` field) is tried first --
// but a simple "Messier N" search only matches ~half the catalog (many NASA archive entries for a
// given object are titled by mission/common name, not its Messier number). Objects NASA doesn't
// match fall back to Wikipedia's REST summary API for the "Messier N" article's lead image, plus
// Wikimedia Commons for that file's license -- much more complete coverage, but far more
// aggressively rate-limited for anonymous clients, hence trying NASA first.

import { writeFileSync, readFileSync, mkdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const STAGING_DIR = join(__dirname, 'image-staging');
const MANIFEST_PATH = join(STAGING_DIR, 'manifest.json');

// M102 is excluded -- a disputed/duplicate-of-M101 entry that OpenNGC itself doesn't carry either
// (see the dso.bin decode: 109 of the catalog's DSOs have a Messier number, covering M1-M110 minus
// M102).
const MESSIER_NUMBERS = Array.from({ length: 110 }, (_, i) => i + 1).filter((n) => n !== 102);

const USER_AGENT = 'AstroGuider-ImageTool/1.0 (offline sky-map image bundling, personal project)';

// Only Wikimedia's anonymous API tier needs this -- NASA's image API took 109 back-to-back
// requests with no throttling and never returned a 429 during testing.
const WIKIMEDIA_MIN_REQUEST_INTERVAL_MS = 700;
const WIKIMEDIA_MAX_RETRIES = 6;
let lastWikimediaRequestTime = 0;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function politeWikimediaFetch(url) {
  for (let attempt = 0; attempt <= WIKIMEDIA_MAX_RETRIES; attempt++) {
    const wait = lastWikimediaRequestTime + WIKIMEDIA_MIN_REQUEST_INTERVAL_MS - Date.now();
    if (wait > 0) await sleep(wait);
    lastWikimediaRequestTime = Date.now();

    const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
    if (res.status === 429) {
      const retryAfterHeader = res.headers.get('retry-after');
      const backoffMs = retryAfterHeader ? parseInt(retryAfterHeader, 10) * 1000 : 2 ** attempt * 1000;
      console.log(`    (429, backing off ${(backoffMs / 1000).toFixed(0)}s -- attempt ${attempt + 1}/${WIKIMEDIA_MAX_RETRIES})`);
      await sleep(backoffMs);
      continue;
    }
    return res;
  }
  throw new Error(`429 Too Many Requests after ${WIKIMEDIA_MAX_RETRIES} retries for ${url}`);
}

function stripHtml(value) {
  return value ? value.replace(/<[^>]+>/g, '').trim() : null;
}

async function downloadBytes(url) {
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} for ${url}`);
  return Buffer.from(await res.arrayBuffer());
}

// --- NASA (images-api.nasa.gov) -------------------------------------------------------------

// NASA's search does loose keyword matching, not phrase matching -- "Messier 3" surfaced
// "Pillars of Creation" (M16) as its top hit, and "Messier 1"/"Messier 4" both top-hit the exact
// same unrelated galaxy ("A mysterious hermit" / UGC 4879), neither mentioning M1, M4, or Messier
// anywhere. A hit existing is not evidence it's *about* the queried object -- every candidate is
// checked against its own title/description/keywords for an unambiguous M{n} or Messier {n}
// token before being trusted; word-boundary anchored so M1 doesn't match text about M104 or M12.
function isRelevantNasaMatch(n, info) {
  const text = [info.title, info.description, ...(info.keywords ?? [])].join(' ');
  return new RegExp(`\\bm\\s?${n}\\b`, 'i').test(text) || new RegExp(`\\bmessier\\s?${n}\\b`, 'i').test(text);
}

async function fetchFromNasa(n) {
  const searchRes = await fetch(`https://images-api.nasa.gov/search?q=${encodeURIComponent(`Messier ${n}`)}&media_type=image`);
  if (!searchRes.ok) throw new Error(`NASA search ${searchRes.status} for M${n}`);
  const searchData = await searchRes.json();
  const item = searchData.collection.items.find((candidate) => isRelevantNasaMatch(n, candidate.data[0]));
  if (!item) return null; // no confidently-relevant NASA hit -- caller falls back to Wikipedia

  const info = item.data[0];
  // Largest available rendition -- `links` is present directly on the search result, no extra
  // per-item API call needed. `~orig` isn't always listed; `~large` (1920px) is the next best.
  const best =
    item.links.find((l) => l.href.includes('~orig')) ?? item.links.find((l) => l.href.includes('~large')) ?? item.links[0];
  if (!best) return null;

  const ext = best.href.split('.').pop().toLowerCase();
  const bytes = await downloadBytes(best.href);
  return {
    messier: n,
    source: 'nasa',
    title: info.title,
    file: `M${n}.${ext}`,
    bytes: bytes.length,
    sourceUrl: best.href,
    nasaId: info.nasa_id,
    artist: info.secondary_creator ?? info.center ?? 'NASA',
    // NASA's media library policy: content is public domain unless the item explicitly carries a
    // `copyright` field (rare, and never seen across any of this catalog's search results).
    license: info.copyright ? `COPYRIGHT: ${info.copyright} -- NEEDS MANUAL REVIEW` : 'Public domain (NASA)',
    licenseUrl: 'https://www.nasa.gov/nasa-brand-center/images-and-media/',
    bufferForWrite: bytes,
  };
}

// --- Wikipedia / Wikimedia Commons -----------------------------------------------------------

// originalimage.source is sometimes a direct file URL and sometimes a capped-width thumbnail
// rendition of the original (e.g. ".../commons/thumb/0/05/Real_Name.png/3840px-Real_Name.png",
// plus a tracking query string) -- the real Commons file name is the second-to-last path segment
// in the thumb case, not the last one.
function extractCommonsFileName(sourceUrl) {
  const segments = sourceUrl.split('?')[0].split('/');
  const last = segments[segments.length - 1];
  const isThumbRendition = /^\d+px-/.test(last);
  return decodeURIComponent(isThumbRendition ? segments[segments.length - 2] : last);
}

async function fetchLicense(fileName) {
  const url = `https://commons.wikimedia.org/w/api.php?action=query&titles=${encodeURIComponent(`File:${fileName}`)}&prop=imageinfo&iiprop=extmetadata&format=json`;
  const res = await politeWikimediaFetch(url);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} for ${url}`);
  const data = await res.json();
  const page = Object.values(data.query?.pages ?? {})[0];
  const meta = page?.imageinfo?.[0]?.extmetadata;
  if (!meta) return null;
  return {
    artist: stripHtml(meta.Artist?.value),
    licenseShortName: meta.LicenseShortName?.value ?? null,
    licenseUrl: meta.LicenseUrl?.value ?? null,
  };
}

async function fetchFromWikipedia(n) {
  const title = `Messier_${n}`;
  const res = await politeWikimediaFetch(`https://en.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(title)}`);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} for Wikipedia summary of ${title}`);
  const summary = await res.json();
  const original = summary.originalimage;
  if (!original) throw new Error('no lead image on the Wikipedia page');

  const fileName = extractCommonsFileName(original.source);
  const license = await fetchLicense(fileName);
  const ext = fileName.split('.').pop().toLowerCase();

  const imgRes = await politeWikimediaFetch(original.source);
  if (!imgRes.ok) throw new Error(`${imgRes.status} ${imgRes.statusText} for ${original.source}`);
  const bytes = Buffer.from(await imgRes.arrayBuffer());

  return {
    messier: n,
    source: 'wikipedia',
    title: summary.titles?.canonical ?? title,
    file: `M${n}.${ext}`,
    width: original.width,
    height: original.height,
    bytes: bytes.length,
    sourceUrl: original.source,
    commonsFile: fileName,
    artist: license?.artist ?? null,
    license: license?.licenseShortName ?? null,
    licenseUrl: license?.licenseUrl ?? null,
    bufferForWrite: bytes,
  };
}

// --- Driver ------------------------------------------------------------------------------------

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
  if (state.manifest.length > 0) console.log(`Resuming: ${state.manifest.length} object(s) already fetched.`);
  const alreadyFetched = new Set(state.manifest.map((entry) => entry.messier));

  let nasaCount = 0;
  let wikipediaCount = 0;

  for (const n of MESSIER_NUMBERS) {
    if (alreadyFetched.has(n)) continue;

    let result = null;
    try {
      result = await fetchFromNasa(n);
    } catch (err) {
      console.log(`M${n}: NASA lookup failed (${err.message}), trying Wikipedia`);
    }

    if (result) {
      nasaCount++;
    } else {
      try {
        result = await fetchFromWikipedia(n);
        wikipediaCount++;
      } catch (err) {
        state.failures.push({ messier: n, error: err.message });
        console.log(`M${n}: FAILED (both sources) -- ${err.message}`);
        saveManifest(state);
        continue;
      }
    }

    writeFileSync(join(STAGING_DIR, result.file), result.bufferForWrite);
    delete result.bufferForWrite;
    state.manifest.push(result);
    // Flushed after every object, not just at the end -- a prior run got interrupted mid-way and
    // lost 19 objects' worth of metadata (the image files were already on disk, but nothing
    // tracked their source/license without this).
    saveManifest(state);

    const sizeNote = result.width ? `${result.width}x${result.height}` : '';
    console.log(`M${n}: OK [${result.source}] ${sizeNote} ${(result.bytes / 1024).toFixed(0)} KB  ${result.license ?? 'UNKNOWN LICENSE'}  (${result.artist ?? 'unknown artist'})`);
  }

  console.log(`\n${state.manifest.length}/${MESSIER_NUMBERS.length} succeeded (${nasaCount} via NASA, ${wikipediaCount} via Wikipedia), ${state.failures.length} failed.`);
  console.log(`Staged in ${STAGING_DIR}`);
}

main();
