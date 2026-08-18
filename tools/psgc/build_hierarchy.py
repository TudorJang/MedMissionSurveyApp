"""Builds app/src/main/assets/psgc/hierarchy.json from a PSA PSGC release.

The PSA publishes the Philippine Standard Geographic Code quarterly, but
psa.gov.ph refuses non-browser requests, so the release tables are taken from
the CRAN package `psgc` (yng-me/psgc), which carries PSA's published area
names, geographic levels and census populations for every release since
Q1 2023 in one R data file:

    https://raw.githubusercontent.com/yng-me/psgc/main/R/sysdata.rda

Usage:

    pip install rdata
    python tools/psgc/build_hierarchy.py sysdata.rda --release Q2_2026 \
        --out app/src/main/assets/psgc/hierarchy.json

The shape it writes is the one the app parses (see PsgcParser):

    region -> province -> city/municipality -> barangay

with NCR's cities hanging directly off the region, `class` and `cityClass` on
every city/municipality, `population` on every node, and `subMunicipality` on
the barangays of a city that has sub-municipalities (Manila's districts).
"""

import argparse
import json
from collections import OrderedDict

import rdata

# A 10-digit PSGC code is region(2) province(3) city/municipality(2) barangay(3).
REGION, PROVINCE, CITY, BARANGAY = slice(0, 2), slice(2, 5), slice(5, 7), slice(7, 10)

CLASS_BY_LEVEL = {"City": "City", "Mun": "Municipality"}


def load_release(path, release, population_year):
    data = rdata.conversion.convert(rdata.parser.parse_file(path))
    rows = data["psgc_releases"][release]
    populations = data["psgc_population"][release]
    by_code = {}
    for code, group in populations.groupby("psgc_code"):
        # PSA leaves areas created since the last census without a count.
        counted = group.dropna(subset=["year", "population"])
        years = {int(y): int(p) for y, p in zip(counted["year"], counted["population"])}
        for year in (population_year, 2020, 2015):
            if year in years:
                by_code[code] = years[year]
                break
    return rows, by_code


def build(rows, population_by_code):
    tree = OrderedDict()
    # Everything is keyed by code so a child can find its parent regardless of
    # the order the release table happens to be in.
    nodes, names = {}, {}
    for row in rows.itertuples():
        nodes[row.psgc_code] = row
        names[row.psgc_code] = row.area_name.strip()

    def population(code):
        return population_by_code.get(code)

    def node_for(code, extra=None):
        out = OrderedDict(extra or {})
        if population(code) is not None:
            out["population"] = population(code)
        return out

    def region_code(code):
        return code[REGION] + "000" + "00" + "000"

    def province_code(code):
        return code[REGION] + code[PROVINCE] + "00" + "000"

    def city_code(code):
        return code[REGION] + code[PROVINCE] + code[CITY] + "000"

    skipped = []

    # Regions first, so provinces and NCR cities have somewhere to go.
    for code, row in sorted(nodes.items()):
        if row.geographic_level == "Reg":
            tree[names[code]] = node_for(code)

    # Provinces. PSA also parks two province-slot rows with no level of their own —
    # the BARMM Special Geographic Area and "City of Isabela (Not a Province)" — and
    # they hold municipalities, so they have to stand in as provinces or their towns
    # end up beside provinces under the region, which the app cannot show.
    provinces = {}
    provinces_by_correspondence = {}
    for code, row in sorted(nodes.items()):
        province_slot = code[CITY] == "00" and code[BARANGAY] == "000" and code[PROVINCE] != "000"
        if row.geographic_level != "Prov" and not (province_slot and not row.geographic_level):
            continue
        region = names.get(region_code(code))
        if region is None:
            skipped.append((code, row.area_name, "no region"))
            continue
        provinces[code] = tree[region].setdefault(names[code], node_for(code))
        correspondence = getattr(row, "correspondence_code", None)
        if isinstance(correspondence, str) and len(correspondence) == 9:
            provinces_by_correspondence[correspondence[:4]] = provinces[code]

    # Cities and municipalities. In NCR they sit at the province slot and hang
    # off the region directly, which is exactly what the app's parser expects.
    cities = {}
    for code, row in sorted(nodes.items()):
        klass = CLASS_BY_LEVEL.get(row.geographic_level)
        if klass is None:
            continue
        parent_code = province_code(code)
        if parent_code in provinces:
            parent = provinces[parent_code]
        else:
            # A highly urbanised city is coded at the province slot — Baguio, Cebu,
            # Davao — because it is independent of any province. It still belongs
            # under the province it sits in for anyone picking an address, and the
            # old nine-digit correspondence code is what names that province.
            correspondence = getattr(row, "correspondence_code", None)
            mother = (provinces_by_correspondence.get(correspondence[:4])
                      if isinstance(correspondence, str) and len(correspondence) == 9 else None)
            if mother is not None:
                parent = mother
            else:
                region = names.get(region_code(code))
                if region is None:
                    skipped.append((code, row.area_name, "no parent"))
                    continue
                parent = tree[region]
        extra = OrderedDict({"class": klass})
        city_class = getattr(row, "city_class", None)
        if isinstance(city_class, str) and city_class.strip():
            extra["cityClass"] = city_class.strip()
        cities[city_code(code)] = parent.setdefault(names[code], node_for(code, extra))

    # Sub-municipalities are Manila's districts. The app has no level for them:
    # their barangays hang off the city, each tagged with the district it is in.
    sub_municipalities = {
        code: names[code] for code, row in nodes.items() if row.geographic_level == "SubMun"
    }

    for code, row in sorted(nodes.items()):
        if row.geographic_level != "Bgy":
            continue
        parent_code = city_code(code)
        extra = None
        if parent_code in sub_municipalities:
            extra = OrderedDict({"subMunicipality": sub_municipalities[parent_code]})
            parent_code = code[REGION] + code[PROVINCE] + "00" + "000"
        city = cities.get(parent_code)
        if city is None:
            skipped.append((code, row.area_name, "no city"))
            continue
        city[names[code]] = node_for(code, extra)

    # The app decides whether a region holds provinces or cities by looking at its
    # first child, so a region may not hold both. Better to stop here than to ship a
    # tree whose province list silently disappears in the picker.
    metadata = {"population", "class", "cityClass", "subMunicipality"}
    for region_name, region in tree.items():
        children = [v for k, v in region.items() if k not in metadata and isinstance(v, dict)]
        classed = [("class" in child) for child in children]
        if any(classed) and not all(classed):
            raise SystemExit(
                f"{region_name} would hold both provinces and cities, which the app cannot show")

    return tree, skipped


def counts(tree):
    metadata = {"population", "class", "cityClass", "subMunicipality"}
    children = lambda node: {k: v for k, v in node.items() if k not in metadata and isinstance(v, dict)}
    regions = len(tree)
    provinces = cities = barangays = 0
    for region in tree.values():
        for name, node in children(region).items():
            if "class" in node:
                cities += 1
                barangays += len(children(node))
            else:
                provinces += 1
                for city in children(node).values():
                    cities += 1
                    barangays += len(children(city))
    return {"regions": regions, "provinces": provinces, "cities": cities, "barangays": barangays}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sysdata", help="path to the downloaded sysdata.rda")
    parser.add_argument("--release", default="Q2_2026")
    parser.add_argument("--population-year", type=int, default=2024)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    rows, population_by_code = load_release(args.sysdata, args.release, args.population_year)
    tree, skipped = build(rows, population_by_code)

    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(tree, handle, ensure_ascii=False, separators=(",", ":"))

    print(f"{args.release}: " + ", ".join(f"{k} {v}" for k, v in counts(tree).items()))
    if skipped:
        print(f"skipped {len(skipped)} rows the hierarchy has no place for:")
        for code, name, why in skipped:
            print(f"  {code} {name} — {why}")


if __name__ == "__main__":
    main()
