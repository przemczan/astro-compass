# Data licenses

AstroCompass bundles three third-party datasets. The app aggregates these into compact binary
blobs for on-device search, alignment, and the sky map
(`tools/build-catalogs.mjs` -> `stars.bin` / `dso.bin` / `constellations.bin`); the underlying
data remains under its original license and must be credited on redistribution.

## Stars — HYG Database

- Source: [astronexus/HYG-Database](https://github.com/astronexus/HYG-Database)
- Author: Astronomy Nexus / David Nash, compiled from the Hipparcos, Yale Bright Star, and
  Gliese catalogs
- Version used: v4.1 (`hygdata_v41.csv`)
- License: [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)

## Deep sky — OpenNGC

- Source: [mattiaverga/OpenNGC](https://github.com/mattiaverga/OpenNGC)
- Author: Mattia Verga
- Files used: `NGC.csv`, `addendum.csv`
- License: [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)

## Constellation lines — d3-celestial

- Source: [ofrohn/d3-celestial](https://github.com/ofrohn/d3-celestial)
- Author: Olaf Frohn
- File used: `data/constellations.lines.json`
- License: BSD-3-Clause

## Deep-sky object photos — Digitized Sky Survey (DSS2) via CDS hips2fits

- Source: [CDS hips2fits](https://alasky.u-strasbg.fr/hips-image-services/hips2fits), survey
  `CDS/P/DSS2/color`
- Credit: Digitized Sky Survey, Space Telescope Science Institute (STScI) / Anglo-Australian
  Observatory / UK Schmidt Telescope (AAO-UKST)
- Files used: `composeApp/src/commonMain/composeResources/drawable/<id>.jpg`, one per bundled
  object, fetched by `tools/fetch-object-images.mjs`
- License/usage terms: [archive.stsci.edu/dss/copyright.html](https://archive.stsci.edu/dss/copyright.html)
  — **restricts commercial use**; confirm compatibility with distribution before shipping a build
  that includes these photos

## Solar system

Sun, Moon, and planet positions are computed at runtime from published orbital theory
(see `astro/ephemeris/` for the specific sources cited in code comments) — no bundled data file.

---

This same attribution is shown in-app under **Settings → Data sources & licenses**.
