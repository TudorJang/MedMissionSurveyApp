# Philippine Address Hierarchy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the free-text Address/City/State-Province/ZIP fields in Patient Information with a PSA PSGC-backed Region → Province → City → Barangay cascading selector (plus auto-filled ZIP and a free-text Street/Subdivision/Landmark field), eliminating the typo/inconsistency/missing-hierarchy problems found in real sample data.

**Architecture:** Two bundled JSON assets (PSGC hierarchy, PHLPost ZIP-by-name) are parsed once into in-memory lookup structures by a `PsgcRepository`. A single reusable `GeoSelectField` Compose component (tap-to-open searchable dialog, with a "Not listed" free-text fallback) is instantiated four times in `FormScreen`, chained by a small piece of hoisted dialog-step state that drives auto-advance on first fill only.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization (already a dependency), JUnit + Robolectric (existing test stack, no new test infra).

**Spec:** `docs/superpowers/specs/2026-08-14-philippine-address-hierarchy-design.md`

## Global Constraints

- No blocking validation anywhere — every field stays optional, matching every other field in this form (spec §1, and the app's established philosophy documented throughout `FormScreen.kt`).
- PSGC hierarchy data source: `https://raw.githubusercontent.com/xemasiv/psgc2/master/tree.json` (CC BY 4.0 — requires attribution, spec §3).
- ZIP-by-name data source: `https://raw.githubusercontent.com/arnellebalane/zipcodes-ph/master/source/zipcodes.json` (MIT license).
- PSGC codes are never stored, only PSA official name strings (spec §4.1).
- `stateProvince` is renamed to `province` everywhere (model, DTO, wire-contract.md) — no back-compat shim, since no bridge consumes the wire format yet (spec §4.1).
- No Compose UI test infrastructure exists in this project; Compose-integration behavior (dialogs, auto-advance) is verified manually on the emulator, not with automated UI tests (established project convention, reconfirmed in spec §9).

---

## Task 1: Acquire the PSGC hierarchy and ZIP datasets as bundled assets

**Files:**
- Create: `app/src/main/assets/psgc/hierarchy.json`
- Create: `app/src/main/assets/psgc/zipcodes.json`
- Create: `docs/reference/psgc-notice.md`

**Interfaces:**
- Produces: two asset files later tasks parse by path `"psgc/hierarchy.json"` and `"psgc/zipcodes.json"` via `Context.assets.open(...)`.

- [ ] **Step 1: Download the two dataset files**

```bash
mkdir -p app/src/main/assets/psgc
curl -sL "https://raw.githubusercontent.com/xemasiv/psgc2/master/tree.json" -o app/src/main/assets/psgc/hierarchy.json
curl -sL "https://raw.githubusercontent.com/arnellebalane/zipcodes-ph/master/source/zipcodes.json" -o app/src/main/assets/psgc/zipcodes.json
```

- [ ] **Step 2: Verify both files are non-trivial and contain expected content**

```bash
wc -c app/src/main/assets/psgc/hierarchy.json app/src/main/assets/psgc/zipcodes.json
grep -o '"NATIONAL CAPITAL REGION (NCR)"' app/src/main/assets/psgc/hierarchy.json
grep -o '"1920":"Taytay"' app/src/main/assets/psgc/zipcodes.json
```

Expected: `hierarchy.json` around 1.5MB, `zipcodes.json` around 45KB, both greps find one match. If either file is empty or the greps find nothing, the download failed (network issue, or the upstream repo restructured) — stop and re-investigate before continuing; every later task depends on these files having this exact shape.

- [ ] **Step 3: Add the required CC BY 4.0 attribution notice**

Create `docs/reference/psgc-notice.md`:

```markdown
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
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/psgc/ docs/reference/psgc-notice.md
git commit -m "chore: bundle PSGC hierarchy and ZIP datasets as app assets"
```

---

## Task 2: Pure PSGC hierarchy parser

**Files:**
- Create: `app/src/main/java/com/medmission/survey/data/psgc/PsgcModels.kt`
- Create: `app/src/main/java/com/medmission/survey/data/psgc/PsgcParser.kt`
- Test: `app/src/test/java/com/medmission/survey/data/psgc/PsgcParserTest.kt`

**Interfaces:**
- Produces: `data class PsgcPath(region: String, province: String?, city: String? = null, barangay: String? = null)`, `data class PsgcHierarchy(regions: List<String>, provincesByRegion: Map<String, List<String>>, citiesByParent: Map<PsgcPath, List<String>>, barangaysByCity: Map<PsgcPath, List<String>>)`, `fun parsePsgcHierarchy(root: JsonObject): PsgcHierarchy`. Task 4 (`PsgcRepository`) consumes these directly.

**Background for the implementer:** `hierarchy.json` nests region → province → city → barangay as JSON objects keyed by name, with metadata (`population`, `class`, `cityClass`, `subMunicipality`) mixed in as sibling keys at every level — e.g. `{"REGION I (ILOCOS REGION)":{"population":5026128,"Ilocos Norte":{"population":593081,"Batac":{"class":"City","population":20,"Ablan":{"population":5}}}}}`. **National Capital Region (NCR) has no province level** — its immediate children are city objects (they carry a `"class"` key), not province objects (which never have `"class"`). That distinction — "does at least one child object carry a `class` key" — is the general, name-independent way to detect "this region has no provinces," used for every region, not just NCR by name-matching.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/medmission/survey/data/psgc/PsgcParserTest.kt`:

```kotlin
package com.medmission.survey.data.psgc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

private val SAMPLE_HIERARCHY = """
{
  "REGION I (ILOCOS REGION)": {
    "population": 100,
    "Ilocos Norte": {
      "population": 50,
      "Batac": {
        "class": "City",
        "population": 20,
        "Ablan": {"population": 5},
        "Bagnos": {"population": 3}
      }
    }
  },
  "NATIONAL CAPITAL REGION (NCR)": {
    "population": 200,
    "City Of Manila": {
      "class": "City",
      "cityClass": "Highly Urbanized City",
      "population": 100,
      "Ermita": {"population": 10},
      "Malate": {"population": 15}
    }
  }
}
""".trimIndent()

class PsgcParserTest {
    private fun parse() = parsePsgcHierarchy(Json.parseToJsonElement(SAMPLE_HIERARCHY).jsonObject)

    @Test
    fun `lists every region`() {
        assertEquals(
            setOf("REGION I (ILOCOS REGION)", "NATIONAL CAPITAL REGION (NCR)"),
            parse().regions.toSet(),
        )
    }

    @Test
    fun `a region with provinces lists them`() {
        assertEquals(listOf("Ilocos Norte"), parse().provincesByRegion["REGION I (ILOCOS REGION)"])
    }

    @Test
    fun `NCR has no provinces`() {
        assertEquals(emptyList<String>(), parse().provincesByRegion["NATIONAL CAPITAL REGION (NCR)"])
    }

    @Test
    fun `cities are listed under their province`() {
        val path = PsgcPath(region = "REGION I (ILOCOS REGION)", province = "Ilocos Norte")
        assertEquals(listOf("Batac"), parse().citiesByParent[path])
    }

    @Test
    fun `NCR cities are listed directly under the region, with a null province`() {
        val path = PsgcPath(region = "NATIONAL CAPITAL REGION (NCR)", province = null)
        assertEquals(listOf("City Of Manila"), parse().citiesByParent[path])
    }

    @Test
    fun `barangays are listed under their city`() {
        val path = PsgcPath(region = "REGION I (ILOCOS REGION)", province = "Ilocos Norte", city = "Batac")
        assertEquals(setOf("Ablan", "Bagnos"), parse().barangaysByCity[path]?.toSet())
    }

    @Test
    fun `NCR barangays are listed under the city with a null province`() {
        val path = PsgcPath(region = "NATIONAL CAPITAL REGION (NCR)", province = null, city = "City Of Manila")
        assertEquals(setOf("Ermita", "Malate"), parse().barangaysByCity[path]?.toSet())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.psgc.PsgcParserTest" --console=plain --no-daemon`
Expected: FAIL to compile — `parsePsgcHierarchy`, `PsgcPath`, `PsgcHierarchy` are unresolved references.

- [ ] **Step 3: Write the models**

Create `app/src/main/java/com/medmission/survey/data/psgc/PsgcModels.kt`:

```kotlin
package com.medmission.survey.data.psgc

/**
 * A fully-qualified path through the PSGC hierarchy. [province] is null when the region has
 * no province level (currently only NCR) — never a placeholder string like "NCR".
 */
data class PsgcPath(
    val region: String,
    val province: String?,
    val city: String? = null,
    val barangay: String? = null,
)

data class PsgcHierarchy(
    val regions: List<String>,
    /** Empty list means the region has no province level (e.g. NCR) — cities sit directly under it. */
    val provincesByRegion: Map<String, List<String>>,
    /** Keyed by a path with city and barangay null. */
    val citiesByParent: Map<PsgcPath, List<String>>,
    /** Keyed by a path with barangay null. */
    val barangaysByCity: Map<PsgcPath, List<String>>,
)
```

- [ ] **Step 4: Write the parser**

Create `app/src/main/java/com/medmission/survey/data/psgc/PsgcParser.kt`:

```kotlin
package com.medmission.survey.data.psgc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val METADATA_KEYS = setOf("population", "class", "cityClass", "subMunicipality")

/**
 * True when [node]'s children are city/municipality objects rather than province objects —
 * i.e. at least one child carries the PSGC `"class"` key, which every city/municipality node
 * has and no province node ever has. NCR is the only region where this is currently true,
 * but the check itself doesn't hardcode "NCR" — it generalizes to any future region PSA
 * structures the same way.
 */
private fun childrenAreCities(node: JsonObject): Boolean =
    locationChildren(node).any { (_, child) -> child.containsKey("class") }

private fun locationChildren(node: JsonObject): List<Pair<String, JsonObject>> =
    node.entries
        .filter { (key, value) -> key !in METADATA_KEYS && value is JsonObject }
        .map { (key, value) -> key to value.jsonObject }

fun parsePsgcHierarchy(root: JsonObject): PsgcHierarchy {
    val regions = mutableListOf<String>()
    val provincesByRegion = mutableMapOf<String, List<String>>()
    val citiesByParent = mutableMapOf<PsgcPath, List<String>>()
    val barangaysByCity = mutableMapOf<PsgcPath, List<String>>()

    fun addCity(region: String, province: String?, cityName: String, cityNode: JsonObject) {
        val path = PsgcPath(region = region, province = province, city = cityName)
        barangaysByCity[path] = locationChildren(cityNode).map { it.first }
    }

    for ((regionName, regionNode) in locationChildren(root)) {
        regions += regionName
        if (childrenAreCities(regionNode)) {
            provincesByRegion[regionName] = emptyList()
            val citiesPath = PsgcPath(region = regionName, province = null)
            val cities = locationChildren(regionNode)
            citiesByParent[citiesPath] = cities.map { it.first }
            for ((cityName, cityNode) in cities) {
                addCity(regionName, province = null, cityName, cityNode)
            }
        } else {
            val provinces = locationChildren(regionNode)
            provincesByRegion[regionName] = provinces.map { it.first }
            for ((provinceName, provinceNode) in provinces) {
                val citiesPath = PsgcPath(region = regionName, province = provinceName)
                val cities = locationChildren(provinceNode)
                citiesByParent[citiesPath] = cities.map { it.first }
                for ((cityName, cityNode) in cities) {
                    addCity(regionName, provinceName, cityName, cityNode)
                }
            }
        }
    }

    return PsgcHierarchy(regions, provincesByRegion, citiesByParent, barangaysByCity)
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.psgc.PsgcParserTest" --console=plain --no-daemon`
Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/medmission/survey/data/psgc/PsgcModels.kt \
        app/src/main/java/com/medmission/survey/data/psgc/PsgcParser.kt \
        app/src/test/java/com/medmission/survey/data/psgc/PsgcParserTest.kt
git commit -m "feat: parse the PSGC Region/Province/City/Barangay hierarchy"
```

---

## Task 3: Pure ZIP-by-name parser and lookup

**Files:**
- Modify: `app/src/main/java/com/medmission/survey/data/psgc/PsgcParser.kt`
- Test: `app/src/test/java/com/medmission/survey/data/psgc/ZipParserTest.kt`

**Interfaces:**
- Consumes: nothing from Task 2.
- Produces: `fun parseZipByName(root: JsonObject): Map<String, String>`, `fun findZip(zipByName: Map<String, String>, city: String, barangay: String?): String?`. Task 4 consumes both.

**Background for the implementer:** `zipcodes.json` maps zip code → a location name, or an array of names sharing that zip, with **no city/province context attached to each name** — e.g. `{"1920":"Taytay","1000":"Manila CPO - Ermita","1100":["Central","Piñahan","Project 6"]}`. There's no structured link from a name back to which city it belongs to. `findZip` therefore does the best available match: an exact barangay-name hit wins if present, otherwise fall back to an exact city-name hit, otherwise `null`. A same-named barangay in two different cities is a known, accepted approximation for this display-only, non-blocking field (spec §7, §10) — not something to solve here.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/medmission/survey/data/psgc/ZipParserTest.kt`:

```kotlin
package com.medmission.survey.data.psgc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val SAMPLE_ZIPS = """
{
  "1920": "Taytay",
  "1000": "Manila CPO - Ermita",
  "1100": ["Central", "Piñahan", "Project 6"]
}
""".trimIndent()

class ZipParserTest {
    private fun parse() = parseZipByName(Json.parseToJsonElement(SAMPLE_ZIPS).jsonObject)

    @Test
    fun `a plain string entry maps its name to its zip`() {
        assertEquals("1920", parse()["Taytay"])
    }

    @Test
    fun `an array entry maps every name in it to the same zip`() {
        val zipByName = parse()
        assertEquals("1100", zipByName["Central"])
        assertEquals("1100", zipByName["Piñahan"])
        assertEquals("1100", zipByName["Project 6"])
    }

    @Test
    fun `findZip prefers a barangay match over a city match`() {
        val zipByName = mapOf("Ermita" to "1000", "Manila" to "1099")
        assertEquals("1000", findZip(zipByName, city = "Manila", barangay = "Ermita"))
    }

    @Test
    fun `findZip falls back to the city name when the barangay has no entry`() {
        val zipByName = parse()
        assertEquals("1920", findZip(zipByName, city = "Taytay", barangay = "San Isidro"))
    }

    @Test
    fun `findZip returns null when neither barangay nor city is found`() {
        val zipByName = parse()
        assertNull(findZip(zipByName, city = "Unknown City", barangay = "Unknown Barangay"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.psgc.ZipParserTest" --console=plain --no-daemon`
Expected: FAIL to compile — `parseZipByName` and `findZip` are unresolved references.

- [ ] **Step 3: Add the ZIP parser and lookup to `PsgcParser.kt`**

Append to `app/src/main/java/com/medmission/survey/data/psgc/PsgcParser.kt`:

```kotlin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

fun parseZipByName(root: JsonObject): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for ((zip, value) in root) {
        val names = when (value) {
            is JsonArray -> value.map { it.jsonPrimitive.content }
            else -> listOf(value.jsonPrimitive.content)
        }
        for (name in names) {
            result.putIfAbsent(name, zip)
        }
    }
    return result
}

fun findZip(zipByName: Map<String, String>, city: String, barangay: String?): String? =
    barangay?.let { zipByName[it] } ?: zipByName[city]
```

(Add the two new imports to the top of the file alongside the existing `kotlinx.serialization.json.JsonObject` / `jsonObject` imports.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.psgc.ZipParserTest" --console=plain --no-daemon`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/medmission/survey/data/psgc/PsgcParser.kt \
        app/src/test/java/com/medmission/survey/data/psgc/ZipParserTest.kt
git commit -m "feat: parse the PHLPost ZIP-by-name lookup"
```

---

## Task 4: `PsgcRepository` (asset loading)

**Files:**
- Create: `app/src/main/java/com/medmission/survey/data/psgc/PsgcRepository.kt`
- Test: `app/src/test/java/com/medmission/survey/data/psgc/PsgcRepositoryTest.kt`

**Interfaces:**
- Consumes: `parsePsgcHierarchy`, `parseZipByName`, `findZip` (Tasks 2–3); the two bundled assets (Task 1).
- Produces: `class PsgcRepository(context: Context)` with `regions(): List<String>`, `provinces(region: String): List<String>`, `cities(region: String, province: String?): List<String>`, `barangays(region: String, province: String?, city: String): List<String>`, `zip(city: String, barangay: String?): String?`. Task 5 (`SurveyApplication`) and Task 9 (`FormScreen`) consume this directly.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/medmission/survey/data/psgc/PsgcRepositoryTest.kt`:

```kotlin
package com.medmission.survey.data.psgc

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PsgcRepositoryTest {
    private val repository = PsgcRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun `lists all 17 regions from the bundled dataset`() {
        assertEquals(17, repository.regions().size)
    }

    @Test
    fun `Rizal is a province of the CALABARZON region`() {
        val calabarzon = repository.regions().first { it.contains("CALABARZON") }
        assertTrue(repository.provinces(calabarzon).contains("Rizal"))
    }

    @Test
    fun `NCR has no provinces`() {
        val ncr = repository.regions().first { it.contains("NATIONAL CAPITAL REGION") }
        assertTrue(repository.provinces(ncr).isEmpty())
    }

    @Test
    fun `Taytay resolves to ZIP 1920`() {
        assertEquals("1920", repository.zip(city = "Taytay", barangay = null))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.psgc.PsgcRepositoryTest" --console=plain --no-daemon`
Expected: FAIL to compile — `PsgcRepository` is an unresolved reference.

- [ ] **Step 3: Write the repository**

Create `app/src/main/java/com/medmission/survey/data/psgc/PsgcRepository.kt`:

```kotlin
package com.medmission.survey.data.psgc

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class PsgcRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private val hierarchy: PsgcHierarchy by lazy {
        parsePsgcHierarchy(json.parseToJsonElement(readAsset("psgc/hierarchy.json")).jsonObject)
    }

    private val zipByName: Map<String, String> by lazy {
        parseZipByName(json.parseToJsonElement(readAsset("psgc/zipcodes.json")).jsonObject)
    }

    fun regions(): List<String> = hierarchy.regions

    fun provinces(region: String): List<String> = hierarchy.provincesByRegion[region].orEmpty()

    fun cities(region: String, province: String?): List<String> =
        hierarchy.citiesByParent[PsgcPath(region, province)].orEmpty()

    fun barangays(region: String, province: String?, city: String): List<String> =
        hierarchy.barangaysByCity[PsgcPath(region, province, city)].orEmpty()

    fun zip(city: String, barangay: String?): String? = findZip(zipByName, city, barangay)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.psgc.PsgcRepositoryTest" --console=plain --no-daemon`
Expected: PASS, 4 tests. (This test reads the real bundled assets via Robolectric, so it also re-verifies Task 1's downloads are intact.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/medmission/survey/data/psgc/PsgcRepository.kt \
        app/src/test/java/com/medmission/survey/data/psgc/PsgcRepositoryTest.kt
git commit -m "feat: add PsgcRepository for querying the bundled geographic data"
```

---

## Task 5: Expose `PsgcRepository` from `SurveyApplication`

**Files:**
- Modify: `app/src/main/java/com/medmission/survey/SurveyApplication.kt`

**Interfaces:**
- Consumes: `PsgcRepository` (Task 4).
- Produces: `SurveyApplication.psgcRepository: PsgcRepository` property. Task 9 (`SurveyNavGraph`) consumes this.

This mirrors the existing `devicePrefix` and `nsdDiscoveryService` lazy properties already on this class — no test needed, same as those (thin DI wiring, not independently testable without instrumentation, consistent with existing precedent in this file).

- [ ] **Step 1: Add the property**

In `app/src/main/java/com/medmission/survey/SurveyApplication.kt`, add the import and property next to `nsdDiscoveryService`:

```kotlin
import com.medmission.survey.data.psgc.PsgcRepository
```

```kotlin
    val psgcRepository: PsgcRepository by lazy {
        PsgcRepository(this)
    }
```

- [ ] **Step 2: Build to confirm it compiles**

Run: `./gradlew assembleDebug --console=plain --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/medmission/survey/SurveyApplication.kt
git commit -m "feat: expose PsgcRepository from SurveyApplication"
```

---

## Task 6: `SurveyRecord` model changes

**Files:**
- Modify: `app/src/main/java/com/medmission/survey/data/model/SurveyRecord.kt`
- Generated: `app/schemas/com.medmission.survey.data.local.AppDatabase/1.json` (regenerated by KSP on build, not hand-edited)

**Interfaces:**
- Produces: `SurveyRecord.region: String?`, `SurveyRecord.barangay: String?`, `SurveyRecord.province: String?` (renamed from `stateProvince`). Task 7 (DTO/Mapper) and Task 9 (`FormScreen`) consume these fields.

This is a field rename plus two additions on a `data class` — there's no new logic to TDD here (Room persists whatever fields exist; this is covered indirectly by the existing `SurveyDaoTest` round-trip test once it compiles again). The step-by-step below is: make the change, then fix every call site that breaks.

- [ ] **Step 1: Rename and add fields**

In `app/src/main/java/com/medmission/survey/data/model/SurveyRecord.kt`, find:

```kotlin
    val address: String? = null,
    val city: String? = null,
    val stateProvince: String? = null,
    val zip: String? = null,
```

Replace with:

```kotlin
    val address: String? = null,
    val region: String? = null,
    val province: String? = null,
    val city: String? = null,
    val barangay: String? = null,
    val zip: String? = null,
```

- [ ] **Step 2: Rebuild and confirm only the expected files break**

Run: `./gradlew compileDebugKotlin compileDebugUnitTestKotlin --console=plain --no-daemon`

Expected: FAIL. Every remaining reference to `stateProvince` in the codebase lives in exactly three files, all handled by later tasks — **do not fix them here**:
- `app/src/main/java/com/medmission/survey/data/network/SurveyPayloadDto.kt` and `SurveyPayloadMapper.kt`, and the test `app/src/test/java/com/medmission/survey/data/network/SurveyPayloadMapperTest.kt` — fixed in Task 7.
- `app/src/main/java/com/medmission/survey/ui/form/FormScreen.kt` — fixed in Task 9.

If the compiler reports an error in any *other* file, stop and investigate — that would mean a `stateProvince` reference exists somewhere this plan didn't account for.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/medmission/survey/data/model/SurveyRecord.kt
git commit -m "feat: add region/barangay fields and rename stateProvince to province"
```

(This commit intentionally leaves the build red in `FormScreen.kt` between now and Task 9 landing in the same working session — do not push or consider the branch done until Task 9's commit lands too.)

---

## Task 7: Wire payload (`SurveyPayloadDto`, `SurveyPayloadMapper`, `wire-contract.md`)

**Files:**
- Modify: `app/src/main/java/com/medmission/survey/data/network/SurveyPayloadDto.kt`
- Modify: `app/src/main/java/com/medmission/survey/data/network/SurveyPayloadMapper.kt`
- Modify: `app/src/test/java/com/medmission/survey/data/network/SurveyPayloadMapperTest.kt`
- Modify: `docs/reference/wire-contract.md`

**Interfaces:**
- Consumes: `SurveyRecord.region/province/city/barangay` (Task 6).
- Produces: `PatientDto.region: String?`, `.province: String?` (renamed from `.stateProvince`), `.barangay: String?`.

This follows the exact same pattern as today's `maritalStatusOther` addition (`SurveyPayloadMapperTest.kt` already exercises this DTO round-trip; extend the existing fully-populated-record test rather than adding a new one).

- [ ] **Step 1: Extend the failing test**

In `app/src/test/java/com/medmission/survey/data/network/SurveyPayloadMapperTest.kt`, in the fully-populated-record test, change:

```kotlin
            address = "12 Mabini St",
            city = "Manila",
            stateProvince = "NCR",
            zip = "1000",
```

to:

```kotlin
            address = "12 Mabini St",
            region = "NATIONAL CAPITAL REGION (NCR)",
            province = null,
            city = "Manila",
            barangay = "Ermita",
            zip = "1000",
```

And in the same test's JSON-shape assertions, change:

```kotlin
        assertEquals("MARRIED", patient.getValue("maritalStatus").jsonPrimitive.content)
```

to also assert (add these two lines right after that one):

```kotlin
        assertEquals("NATIONAL CAPITAL REGION (NCR)", patient.getValue("region").jsonPrimitive.content)
        assertEquals("Ermita", patient.getValue("barangay").jsonPrimitive.content)
        assertEquals(false, patient.containsKey("province")) // null fields are absent, not null (see wire-contract.md §2)
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.network.SurveyPayloadMapperTest" --console=plain --no-daemon`
Expected: FAIL to compile. `SurveyRecord` already has `region`/`province`/`barangay` from Task 6, so the `SurveyRecord(...)` construction in the test compiles fine — the failure is in `SurveyPayloadMapper.kt`, which still writes `stateProvince = record.stateProvince` and has no `region`/`barangay` mapping, and in the test's own assertions referencing `patient.getValue("region")` / `"barangay"`, which don't exist on `PatientDto` yet. Confirm the reported errors point at `SurveyPayloadDto.kt` / `SurveyPayloadMapper.kt` / the test file — not at `SurveyRecord.kt`.

- [ ] **Step 3: Update the DTO**

In `app/src/main/java/com/medmission/survey/data/network/SurveyPayloadDto.kt`, find:

```kotlin
    val address: String? = null,
    val city: String? = null,
    val stateProvince: String? = null,
    val zip: String? = null,
```

Replace with:

```kotlin
    val address: String? = null,
    val region: String? = null,
    val province: String? = null,
    val city: String? = null,
    val barangay: String? = null,
    val zip: String? = null,
```

- [ ] **Step 4: Update the mapper**

In `app/src/main/java/com/medmission/survey/data/network/SurveyPayloadMapper.kt`, find:

```kotlin
            address = record.address,
            city = record.city,
            stateProvince = record.stateProvince,
            zip = record.zip,
```

Replace with:

```kotlin
            address = record.address,
            region = record.region,
            province = record.province,
            city = record.city,
            barangay = record.barangay,
            zip = record.zip,
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.medmission.survey.data.network.SurveyPayloadMapperTest" --console=plain --no-daemon`
Expected: PASS, all tests in the file green.

- [ ] **Step 6: Update `wire-contract.md`**

In `docs/reference/wire-contract.md`, apply exactly the diff shown in the spec's §8:

```diff
   "patient": {
     "firstName": "Juan",
     "lastName": "Dela Cruz",
     "birthDate": "1980-03-04",
     "gender": "MALE",
     "age": 46,
-    "address": "12 Mabini St",
-    "city": "Manila",
-    "stateProvince": "NCR",
-    "zip": "1000",
+    "address": "12 Mabini St, Simona Subd.",
+    "region": "NCR",
+    "province": null,
+    "city": "Manila",
+    "barangay": "Ermita",
+    "zip": "1000",
     "email": "juan@example.com",
     "cellPhone": "+63-900-000-0000",
     "maritalStatus": "MARRIED"
   }
```

(Note: the sample payload for illustration purposes can keep the short "NCR" label; the actual wire values used by the app are the PSA official region names as they appear in `hierarchy.json`, e.g. `"NATIONAL CAPITAL REGION (NCR)"` — add a footnote saying so next to the sample.)

Also update the §4 field-types table row for `patient.firstName/.lastName/.address/.city/.stateProvince/.zip/.email/.cellPhone`, removing `.stateProvince` and adding a new row:

```markdown
| `patient.firstName`, `.lastName`, `.address`, `.city`, `.email`, `.cellPhone` | String |
| `patient.region`, `.province`, `.barangay` | String — PSA official name from the bundled PSGC dataset, or an arbitrary free-text string if "Not listed" was chosen on the tablet. `.province` is absent for addresses in NCR (no province level exists there). |
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/medmission/survey/data/network/SurveyPayloadDto.kt \
        app/src/main/java/com/medmission/survey/data/network/SurveyPayloadMapper.kt \
        app/src/test/java/com/medmission/survey/data/network/SurveyPayloadMapperTest.kt \
        docs/reference/wire-contract.md
git commit -m "feat: add region/barangay to the wire payload, rename stateProvince to province"
```

---

## Task 8: `GeoSelectField` composable

**Files:**
- Create: `app/src/main/java/com/medmission/survey/ui/form/GeoSelectField.kt`

**Interfaces:**
- Produces: `@Composable fun GeoSelectField(label: String, value: String?, options: List<String>, isDialogOpen: Boolean, onOpenDialog: () -> Unit, onDismissDialog: () -> Unit, onSelect: (String) -> Unit, onFreeText: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true)`. Task 9 (`FormScreen`) consumes this, four times.

No automated test for this file — it's a Compose UI component, and this project has no Compose UI test infrastructure (Global Constraints). Verified manually in Task 10.

- [ ] **Step 1: Write the component**

Create `app/src/main/java/com/medmission/survey/ui/form/GeoSelectField.kt`:

```kotlin
package com.medmission.survey.ui.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * A field backed by a fixed [options] list, chosen through a tap-to-open searchable dialog
 * rather than typed. If [value] is non-null but not present in [options], the field renders
 * as a plain free-text field instead — this is how "Not listed" (picked once, in the dialog)
 * stays represented: as data, not as a separate flag to keep in sync.
 *
 * Dialog visibility is hoisted to the caller ([isDialogOpen]/[onOpenDialog]/[onDismissDialog])
 * rather than held locally, so a parent composable holding several of these fields can chain
 * them — closing one field's dialog and opening the next's on selection (see `FormScreen`'s
 * Patient Information section for the auto-advance chain).
 */
@Composable
fun GeoSelectField(
    label: String,
    value: String?,
    options: List<String>,
    isDialogOpen: Boolean,
    onOpenDialog: () -> Unit,
    onDismissDialog: () -> Unit,
    onSelect: (String) -> Unit,
    onFreeText: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val isFreeText = value != null && value !in options
    if (isFreeText) {
        OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = onFreeText,
            label = { Text(label) },
            singleLine = true,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
        )
        return
    }

    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        modifier = if (enabled) {
            modifier.fillMaxWidth().clickable(onClick = onOpenDialog)
        } else {
            modifier.fillMaxWidth()
        },
        enabled = enabled,
    )

    if (isDialogOpen) {
        GeoSelectDialog(
            title = label,
            options = options,
            onDismiss = onDismissDialog,
            onSelect = onSelect,
            onNotListed = { onFreeText("") },
        )
    }
}

@Composable
private fun GeoSelectDialog(
    title: String,
    options: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onNotListed: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, options) {
        if (query.isBlank()) options else options.filter { it.contains(query, ignoreCase = true) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                LazyColumn {
                    items(filtered) { option ->
                        Text(
                            text = option,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option) }
                                .padding(vertical = 12.dp),
                        )
                    }
                    item {
                        Text(
                            text = "Not listed",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNotListed() }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Build to confirm it compiles**

Run: `./gradlew compileDebugKotlin --console=plain --no-daemon`
Expected: `GeoSelectField.kt` compiles without error (the rest of the module may still show the pre-existing `FormScreen.kt` errors from Task 6 — that's expected until Task 9).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/medmission/survey/ui/form/GeoSelectField.kt
git commit -m "feat: add GeoSelectField, a searchable dropdown with a free-text fallback"
```

---

## Task 9: Wire the cascade into `FormScreen`

**Files:**
- Modify: `app/src/main/java/com/medmission/survey/ui/form/FormScreen.kt`
- Modify: `app/src/main/java/com/medmission/survey/ui/nav/SurveyNavGraph.kt`

**Interfaces:**
- Consumes: `GeoSelectField` (Task 8), `PsgcRepository` (Task 4), `SurveyApplication.psgcRepository` (Task 5), `SurveyRecord.region/province/city/barangay` (Task 6).

This task has no unit test of its own (Compose UI wiring, per Global Constraints) — Task 10 verifies it on the emulator. Work through the steps in order; the build stays red until Step 2 is done.

- [ ] **Step 1: Thread `PsgcRepository` through to `FormScreen`**

In `app/src/main/java/com/medmission/survey/ui/nav/SurveyNavGraph.kt`, find the `FormScreen(...)` call inside the `"form?recordId={recordId}"` composable and add one parameter:

```kotlin
            FormScreen(
                record = record,
                onFieldChange = { viewModel.updateField(it) },
                onToggleMedicalHistory = { viewModel.toggleMedicalHistory(it) },
                onToggleSymptom = { viewModel.toggleSymptom(it) },
                onDone = { navController.navigate("laptopSelect/${record.recordId}") },
                psgcRepository = app.psgcRepository,
            )
```

- [ ] **Step 2: Replace the Address/City/Province/ZIP block in `FormScreen.kt`**

In `app/src/main/java/com/medmission/survey/ui/form/FormScreen.kt`:

1. Add `psgcRepository: com.medmission.survey.data.psgc.PsgcRepository` as a new parameter of the top-level `FormScreen` composable (alongside `onDone`).
2. Just inside the `SectionCard(title = "Patient Information") { ... }` block, before the row containing `TextFieldRow(label = "Address", ...)`, add:

```kotlin
                var activeGeoDialog by remember { mutableStateOf<GeoDialogStep?>(null) }
```

3. Delete the existing block that reads:

```kotlin
                TextFieldRow(
                    label = "Address",
                    value = record.address,
                    onValueChange = { v -> onFieldChange { it.copy(address = v) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextFieldRow(
                        label = "City",
                        value = record.city,
                        onValueChange = { v -> onFieldChange { it.copy(city = v) } },
                        modifier = Modifier.weight(1f),
                    )
                    TextFieldRow(
                        label = "State/Province",
                        value = record.stateProvince,
                        onValueChange = { v -> onFieldChange { it.copy(stateProvince = v) } },
                        modifier = Modifier.weight(1f),
                    )
                    MaskedTextFieldRow(
                        label = "ZIP",
                        externalValue = record.zip.orEmpty(),
                        mask = ::formatZipInput,
                        onValueChange = { v -> onFieldChange { it.copy(zip = v) } },
                        modifier = Modifier.weight(0.7f),
                    )
                }
```

4. Replace it with:

```kotlin
                GeoSelectField(
                    label = "Region",
                    value = record.region,
                    options = psgcRepository.regions(),
                    isDialogOpen = activeGeoDialog == GeoDialogStep.REGION,
                    onOpenDialog = { activeGeoDialog = GeoDialogStep.REGION },
                    onDismissDialog = { activeGeoDialog = null },
                    onSelect = { picked ->
                        val wasEmpty = record.region == null
                        onFieldChange {
                            it.copy(region = picked, province = null, city = null, barangay = null, zip = null)
                        }
                        activeGeoDialog = when {
                            !wasEmpty -> null
                            psgcRepository.provinces(picked).isEmpty() -> GeoDialogStep.CITY
                            else -> GeoDialogStep.PROVINCE
                        }
                    },
                    onFreeText = { text -> onFieldChange { it.copy(region = text) } },
                )
                GeoSelectField(
                    label = "Province",
                    value = record.province,
                    options = record.region?.let { psgcRepository.provinces(it) }.orEmpty(),
                    isDialogOpen = activeGeoDialog == GeoDialogStep.PROVINCE,
                    onOpenDialog = { activeGeoDialog = GeoDialogStep.PROVINCE },
                    onDismissDialog = { activeGeoDialog = null },
                    onSelect = { picked ->
                        val wasEmpty = record.province == null
                        onFieldChange { it.copy(province = picked, city = null, barangay = null, zip = null) }
                        activeGeoDialog = if (wasEmpty) GeoDialogStep.CITY else null
                    },
                    onFreeText = { text -> onFieldChange { it.copy(province = text) } },
                    enabled = record.region != null,
                )
                GeoSelectField(
                    label = "City / Municipality",
                    value = record.city,
                    options = record.region?.let { psgcRepository.cities(it, record.province) }.orEmpty(),
                    isDialogOpen = activeGeoDialog == GeoDialogStep.CITY,
                    onOpenDialog = { activeGeoDialog = GeoDialogStep.CITY },
                    onDismissDialog = { activeGeoDialog = null },
                    onSelect = { picked ->
                        val wasEmpty = record.city == null
                        val zip = psgcRepository.zip(picked, null)
                        onFieldChange { it.copy(city = picked, barangay = null, zip = zip) }
                        activeGeoDialog = if (wasEmpty) GeoDialogStep.BARANGAY else null
                    },
                    onFreeText = { text -> onFieldChange { it.copy(city = text) } },
                    enabled = record.region != null,
                )
                GeoSelectField(
                    label = "Barangay",
                    value = record.barangay,
                    options = record.city?.let { city ->
                        psgcRepository.barangays(record.region.orEmpty(), record.province, city)
                    }.orEmpty(),
                    isDialogOpen = activeGeoDialog == GeoDialogStep.BARANGAY,
                    onOpenDialog = { activeGeoDialog = GeoDialogStep.BARANGAY },
                    onDismissDialog = { activeGeoDialog = null },
                    onSelect = { picked ->
                        val zip = psgcRepository.zip(record.city.orEmpty(), picked) ?: record.zip
                        onFieldChange { it.copy(barangay = picked, zip = zip) }
                        activeGeoDialog = null
                    },
                    onFreeText = { text -> onFieldChange { it.copy(barangay = text) } },
                    enabled = record.city != null,
                )
                TextFieldRow(
                    label = "ZIP",
                    value = record.zip,
                    onValueChange = {},
                    enabled = false,
                )
                TextFieldRow(
                    label = "Street / Subdivision / Landmark",
                    value = record.address,
                    onValueChange = { v -> onFieldChange { it.copy(address = v) } },
                )
```

5. Add the step enum as a private top-level declaration near the other private helpers in the file (e.g. just above `NumericFieldRow`):

```kotlin
private enum class GeoDialogStep { REGION, PROVINCE, CITY, BARANGAY }
```

6. Delete the now-unused `formatZipInput` import (`import com.medmission.survey.util.formatZipInput`, currently line 65). Its only call site was inside the ZIP `MaskedTextFieldRow` block deleted in step 4 above, so after that deletion this import is dead — Kotlin will fail the build on an unresolved-usage warning-as-error only if the project enables that lint; either way an unused import should be removed for cleanliness.

- [ ] **Step 3: Full rebuild and full test suite**

Run: `./gradlew assembleDebug testDebugUnitTest --console=plain --no-daemon`
Expected: BUILD SUCCESSFUL, and every existing test still passes (this is a UI-layer change; no test in the suite should have needed to change here beyond what Tasks 6–7 already updated).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/medmission/survey/ui/form/FormScreen.kt \
        app/src/main/java/com/medmission/survey/ui/nav/SurveyNavGraph.kt
git commit -m "feat: replace free-text address fields with the PSGC cascading selector"
```

---

## Task 10: Manual emulator verification

**Files:** none (verification only).

Follow the project's established emulator workflow (headless AVD `MedMissionTablet`, `adb`, `pm clear` before a clean run — see any of today's earlier sessions for the exact commands). Walk through these checks on a fresh record, taking a screenshot after each:

- [ ] Tapping the Region field opens a dialog listing all 17 regions with a working search box.
- [ ] Selecting a non-NCR region (e.g. one containing "CALABARZON") auto-closes that dialog and opens the Province dialog; selecting "Rizal" auto-advances to City; selecting "Taytay" auto-advances to Barangay; selecting a barangay closes the chain and the ZIP field shows `1920`.
- [ ] Selecting "NATIONAL CAPITAL REGION (NCR)" as Region skips straight to the City dialog (no Province dialog appears), and the Province field stays empty/disabled.
- [ ] Picking "Not listed" in any dialog turns that field into a free-text box instead.
- [ ] Re-opening an already-answered field (e.g. tapping Province again after it's filled) does **not** auto-advance into City afterward — selecting a different Province just updates that field and closes its own dialog.
- [ ] Changing Region after Province/City/Barangay were already set clears all three (and ZIP) rather than leaving stale, mismatched values on screen.
- [ ] The Street/Subdivision/Landmark field at the bottom accepts free text as before.

- [ ] **If any check fails:** fix the underlying code (not the checklist), rebuild, reinstall, and re-verify — do not mark this task done with a known-broken interaction.

---

## Task 11: Final full-suite verification

**Files:** none.

- [ ] **Step 1: Run the complete test suite**

Run: `./gradlew assembleDebug testDebugUnitTest --console=plain --no-daemon`
Expected: BUILD SUCCESSFUL, all tests pass, pristine output (no new warnings from files this plan touched).

- [ ] **Step 2: Confirm nothing was left uncommitted**

Run: `git status --short`
Expected: empty (everything from Tasks 1–9 was committed at the end of its own task).

---

## Self-review notes (for whoever executes this plan)

- **Spec coverage:** §3 (data sources) → Task 1; §4.1/§4.2 (model) → Tasks 6–7; §4.3 removed by inlining the storage decision into Task 4 directly (no separate Room path was built, matching the spec's decision); §5 (UI order, component, auto-advance) → Tasks 8–9; §6 (NCR) → Task 2's `childrenAreCities` + Task 9's `provinces(picked).isEmpty()` check; §7 (ZIP) → Task 3 + Task 9's `onSelect` handlers; §8 (wire contract) → Task 7; §9 (test plan) → Tasks 2–4 unit tests + Task 10 manual pass; §10.1 (dataset sourcing) → resolved concretely in Task 1 rather than left open; §10.2 (app size) → not separately tasked, since Task 1's Step 2 size check (`wc -c`, ~1.5MB total) already answers it at a level adequate for this pilot; §10.3 (migration) — explicitly out of scope per the spec, no task needed.
