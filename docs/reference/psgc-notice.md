# Geographic data attribution

The Region/Province/City/Municipality/Barangay hierarchy bundled in
`app/src/main/assets/psgc/hierarchy.json` is derived from the official
Philippine Standard Geographic Code (PSGC), published by the Philippine
Statistics Authority (PSA), via https://github.com/xemasiv/psgc2
(Creative Commons Attribution 4.0 International).

Geographic data © Philippine Statistics Authority (PSA), licensed
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

The ZIP code lookup in `app/src/main/assets/psgc/zipcodes.json` is from
https://github.com/arnellebalane/zipcodes-ph (MIT license).

## Known limitations of the ZIP lookup

The lookup matches PSA official names against the ZIP dataset's names,
city first, then barangay (`PsgcParser.findZip`). Two measured gaps, kept
deliberately unfixed — the ZIP field in the form is editable precisely so
an operator can fill these in by hand:

- **Manila, Caloocan and Pasay resolve no ZIP at all.** Their barangays
  are numbered ("Barangay 1", …), which matches nothing, and their PSGC
  city names ("City Of Manila") don't match the ZIP dataset's naming
  ("Manila CPO - Ermita", per-district entries).
- **Do not "fix" this with a subMunicipality fallback.** It looks
  promising — Manila's 897 barangay nodes all carry a `subMunicipality`
  key (Ermita, Tondo, …) — but it was measured against the bundled
  datasets and rejected: exact-name matching resolves only 196 barangays
  correctly while assigning **wrong** ZIPs to 424 (e.g. Santa Cruz →
  1104 Quezon City, Sampaloc → 4329 Quezon province, Santa Ana → 2022),
  because those district names collide with entries elsewhere in the
  country, and the dataset's Manila entries use variant labels ("Sampaloc
  East"/"Sampaloc West", "Sta. Cruz South") that exact matching misses.
  Caloocan and Pasay carry no `subMunicipality` data at all. A correct
  fix needs a curated Manila-district → ZIP table or a better upstream
  dataset, not name matching.

## Refreshing the datasets

Both files were downloaded as-is from the repositories above (see the
plan document `docs/superpowers/plans/2026-08-14-philippine-address-hierarchy.md`,
Task 1, for the exact URLs). The bundled PSGC snapshot predates 2019: it
still names ARMM (renamed BARMM in 2019) and lacks the re-established
Negros Island Region (2024). All provinces, cities and barangays are
present under the older region names, so nothing is unreachable — but a
refresh should re-run the Task 1 verification greps and the
`PsgcRepositoryTest` suite, which asserts against real dataset content
(region count, Taytay ZIP precedence) and will catch shape changes.

**Refresh attempt, 2026-08-15 — no viable source yet.** Findings, so the
next attempt doesn't repeat the survey:

- The pinned upstream (`xemasiv/psgc2` `tree.json`) is byte-identical to
  the bundled file (SHA-256 verified) — re-downloading is a no-op.
- `psgc.gitlab.io` (community PSGC API) has the BARMM rename but still
  lists 17 regions with no Negros Island Region, uses a different naming
  style ("Ilocos Region" vs. the PSA official "REGION I (ILOCOS REGION)"
  this app records), and is a flat code-keyed API — adopting it means a
  format converter plus a wire-visible change to every region string.
- `flores-jacob/philippine-regions-…-barangays` tops out at a 2019
  dataset. No surveyed community JSON source carries NIR (June 2024) yet.
- The authoritative path is the PSA's quarterly PSGC publication (Excel)
  at psa.gov.ph, converted into this app's `tree.json` shape (`class` key
  on city/municipality nodes, `subMunicipality` on Manila barangays).
  That is a small standalone project — converter script, full-count
  verification against PSA's published totals, region-count test update
  (17 → 18), and a check that ZIP name-matching still behaves — not a
  drop-in refresh. psa.gov.ph was unreachable from the development
  network at the time of this attempt.
