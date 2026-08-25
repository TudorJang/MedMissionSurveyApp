# Geographic data attribution

The Region/Province/City/Municipality/Barangay hierarchy bundled in
`app/src/main/assets/psgc/hierarchy.json` is the official Philippine
Standard Geographic Code (PSGC), published by the Philippine Statistics
Authority (PSA), **2nd Quarter 2026 release**. It is built by
`tools/psgc/build_hierarchy.py` from the release tables carried in the
CRAN package `psgc` (https://github.com/yng-me/psgc, MIT), which is where
PSA's quarterly publication can actually be fetched — see "Refreshing the
datasets" below. Earlier releases of this file came from
https://github.com/xemasiv/psgc2 (CC BY 4.0), a 2019-era snapshot.

Geographic data © Philippine Statistics Authority (PSA), licensed
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

The ZIP code lookup in `app/src/main/assets/psgc/zipcodes.json` is from
https://github.com/arnellebalane/zipcodes-ph (MIT license).

## Known limitations of the ZIP lookup

The lookup matches PSA official names against the ZIP dataset's names,
city first, then barangay (`PsgcParser.findZip`). Two measured gaps, kept
deliberately unfixed — the ZIP field in the form is editable precisely so
an operator can fill these in by hand:

- **Caloocan and Pasay resolve no ZIP at all.** Their barangays are
  numbered ("Barangay 1", …), which matches nothing, and their PSGC city
  names don't match the ZIP dataset's naming either. Neither city carries
  sub-municipality data, so the Manila route below is not available to
  them.
- **Manila is now partly covered** (`MANILA_DISTRICT_ZIPS` in
  `PsgcParser`). PSA publishes a postal district for each of its 897
  barangays and the ZIP dataset ships one entry per district, so the two
  are joined by a hand-written table of ten districts — Ermita 1000,
  Quiapo 1001, Intramuros 1002, Malate 1004, San Miguel 1005, Binondo
  1006, Paco 1007, Sta. Ana 1009, San Nicolas 1010, Pandacan 1011 — each
  quoted from the dataset entry it came from. 308 barangays resolve where
  none did before.
  Four districts are deliberately left out because the post office splits
  them and PSA does not: Sampaloc (1008 east / 1015 west), Sta. Cruz
  (1014 north / 1003 south), Tondo I/II (1013 north / 1012 south) and
  Port Area (the dataset's only entry is "Port Area (South)"). Picking one
  half would be right about half the time and wrong invisibly the rest;
  those barangays stay blank for the operator to fill.
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

PSA publishes the PSGC quarterly. To move to a newer release:

    pip install rdata
    curl -L -o sysdata.rda https://raw.githubusercontent.com/yng-me/psgc/main/R/sysdata.rda
    python tools/psgc/build_hierarchy.py sysdata.rda --release Q3_2026 \
        --out app/src/main/assets/psgc/hierarchy.json

Then run `PsgcRepositoryTest`, which asserts against real dataset content
(region count, NIR, a highly urbanised city's placement, Taytay's ZIP) and
will catch shape changes. The converter itself refuses to write a tree in
which one region holds both provinces and cities, because the app's parser
decides between the two per region and the province list would silently
vanish in the picker.

**Why not psa.gov.ph directly.** The authoritative Excel publication is at
psa.gov.ph, but that host answers every non-browser request with HTTP 403 —
tried with and without a browser user agent, from two clients. The CRAN
package above carries the same PSA tables (area names, geographic levels
including Sub-Municipality, and 2015/2020/2024 census populations) for every
release since Q1 2023, which is why the converter reads it instead. If PSA
ever becomes reachable, converting its Excel into the same shape is the
better source.

**What the 2026-08-18 refresh changed** (from the 2019-era snapshot):

- 17 regions -> 18: BARMM replaces ARMM, and the Negros Island Region
  (re-established 2024) appears with Negros Occidental, Negros Oriental and
  Siquijor moved into it.
- 42,044 -> 42,010 barangays, 1,634 -> 1,642 cities and municipalities,
  81 -> 84 province-level nodes. The two extra province-level nodes are
  PSA's own: the BARMM "Special Geographic Area" and "City of Isabela (Not
  a Province)", both of which hold municipalities and would otherwise have
  to sit beside provinces under their region.
- Names lose the parenthetical aliases and gain PSA's current spelling
  ("Anini Y" -> "Anini-Y", "Alabel (Capital)" -> "Alabel", "City Of Manila"
  -> "City of Manila"). Region names are now title case as PSA writes them
  ("National Capital Region (NCR)", not "NATIONAL CAPITAL REGION (NCR)") —
  visible on the wire, see `wire-contract.md`.
- ZIP matching improved on its own as a result, measured against the same
  `zipcodes.json`: cities resolving a ZIP went from 1,202/1,634 (73%) to
  1,265/1,642 (77%), barangays from 6,288 (14%) to 7,105 (16%). The Manila
  and Caloocan gaps described above are unchanged.
