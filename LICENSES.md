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

## Solar system

Sun, Moon, and planet positions are computed at runtime from published orbital theory
(see `astro/ephemeris/` for the specific sources cited in code comments) — no bundled data file.

---

This same attribution is shown in-app under **Settings → Data sources & licenses**.
