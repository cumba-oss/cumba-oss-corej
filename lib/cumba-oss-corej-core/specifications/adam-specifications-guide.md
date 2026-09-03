# ADaM Conformance Rules — Specification Sources

This document describes where to find CDISC ADaM (Analysis Data Model) conformance rules
and the related specification documents that define them.

## ADaM Conformance Rules

### CDISC Library API (Primary Source)

ADaM conformance rules are served via the same CDISC Library API used for SDTMIG rules.
The existing `CdiscLibraryClient` in `net.cumba.cdisc.library` already supports this.

**API Endpoint:** `GET /mdr/rules/{standard}/{version}`

Expected ADaM rule packages (standard = `adamig`):

| Standard | Version | API Path | Notes |
|----------|---------|----------|-------|
| `adamig` | `1-3` | `/mdr/rules/adamig/1-3` | Latest, corresponds to ADaMIG v1.3 |
| `adamig` | `1-2` | `/mdr/rules/adamig/1-2` | Corresponds to ADaMIG v1.2 |
| `adamig` | `1-1` | `/mdr/rules/adamig/1-1` | Corresponds to ADaMIG v1.1 |
| `adamig` | `1-0` | `/mdr/rules/adamig/1-0` | Corresponds to ADaMIG v1.0 |

**How to fetch:** Requires a CDISC Library API key (set via `CDISC_LIBRARY_API_KEY` environment variable).

```java
// Using the existing CdiscLibraryClient
CdiscLibraryClient client = CdiscBuilder.withApiKey("your-api-key").build();

// List all available rule catalogs (includes ADaM)
ApiResource catalogs = client.getRuleCatalogs();

// Fetch the ADaM rules package
RulePackage adamRules = client.getRules("adamig", "1-3");

// The returned RulePackage uses the same model as SDTMIG rules
// and can be loaded by RulePackageLoader
```

To save the fetched rules locally (like the existing `rules-sdtmig-3-4.json`):
```java
ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
mapper.writeValue(Path.of("rules/rules-adamig-1-3.json").toFile(), adamRules);
```

### CDISC Rules Engine (CORE) — Open Source

The [CDISC Rules Engine](https://github.com/cdisc-org/cdisc-rules-engine) (MIT License)
supports ADaM validation. Rules are fetched from the CDISC Library API at runtime and cached.

**CLI Usage:**
```bash
# Direct ADaM validation
core validate -rp cdisc-adamig-1-3 -d /path/to/adam/data

# Via TIG with ADaM substandard
core validate -s tig -v 1-0 -ss adam -uc ANALYSIS -d /path/to/data
```

The CORE engine caches **ADaM Controlled Terminology** packages locally (21 versions available,
from `adamct-2014-09-26` through `adamct-2025-09-26`).

### CDISC Rule Editor (Web UI)

Browse and inspect ADaM rules interactively at: https://rule-editor.cdisc.org  
Requires CDISC membership login.

---

## ADaM Specification Documents

All specification documents below are **proprietary** and require CDISC membership to download.
Register at https://www.cdisc.org to obtain access.

### Foundational Model

| Document | Version | URL | Description |
|----------|---------|-----|-------------|
| ADaM | v2.1 | https://www.cdisc.org/standards/foundational/adam/adam-v2-1 | Current foundational model supporting all ADaMIG versions (1.0–1.3) |

### Implementation Guides

| Document | Version | Publication Date | URL |
|----------|---------|-----------------|-----|
| ADaMIG | v1.3 | 29 November 2021 | https://www.cdisc.org/standards/foundational/adam/adamig-v1-3 |
| ADaMIG | v1.2 | 3 October 2019 | https://www.cdisc.org/standards/foundational/adam/adamig-v1-2 |
| ADaMIG | v1.1 | 12 February 2016 | https://www.cdisc.org/standards/foundational/adam/adamig-v1-1 |
| ADaMIG | v1.0 | 17 December 2009 | https://www.cdisc.org/standards/foundational/adam/adamig-v1-0 |

### Conformance Rules Documents

| Document | Version | URL |
|----------|---------|-----|
| ADaM Conformance Rules | v5.0 (current) | https://www.cdisc.org/standards/foundational/adam/adam-conformance-rules-v5-0 |
| ADaM Conformance Rules | v4.0 (legacy) | https://www.cdisc.org/standards/foundational/adam/adam-conformance-rules-v4-0 |

### Specialized Implementation Guides

| Document | URL |
|----------|-----|
| ADaMIG for Medical Devices v1.0 | https://www.cdisc.org/standards/foundational/adam |
| ADaMIG for Non-compartmental Analysis Input Data v1.0 | https://www.cdisc.org/standards/foundational/adam |
| ADaM Structure for Occurrence Data Implementation Guide v1.1 | https://www.cdisc.org/standards/foundational/adam |
| Basic Data Structure for ADaM popPK Implementation Guide v1.0 | https://www.cdisc.org/standards/foundational/adam |

---

## ADaM Metadata via CDISC Library API

The `net.cumba.cdisc.library` module already has model classes for ADaM metadata:

| Class | API Path | Description |
|-------|----------|-------------|
| `AdamProduct` | `/mdr/adam/{product}` | ADaM product version (e.g., `adam-2-1`) |
| `AdamDataStructure` | `/mdr/adam/{product}/datastructures/{ds}` | Data structure (ADSL, BDS, OCCDS, etc.) |
| `AdamVariableSet` | `/mdr/adam/{product}/datastructures/{ds}/varsets/{vs}` | Variable set within a data structure |
| `AdamVariable` | `/mdr/adam/{product}/datastructures/{ds}/variables/{var}` | Individual variable metadata |

**Usage for enriching ADaM rules:**
```java
CdiscLibraryClient client = CdiscBuilder.withApiKey("your-api-key").build();

// List ADaM products
Products products = client.getProducts();
List<Link> adamProducts = products.adamLinks();  // e.g., "ADaM 2.1"

// Get ADaM product details
AdamProduct adam21 = client.getAdamProduct("adam-2-1");

// List data structures (ADSL, BDS, OCCDS, ...)
List<Link> structures = client.getAdamDataStructureLinks("adam-2-1");
```

---

## Controlled Terminology

ADaM Controlled Terminology is published separately and versioned by date.
Available via the CDISC Library API at `/mdr/ct/packages` (filter for `adamct-*`).

Known versions: `adamct-2014-09-26` through `adamct-2025-09-26` (21 versions).

---

## Integration Notes for This Module

The existing `Test1.java` already loads and executes ADaM rules from `rules/rules-adam.json`.
To add ADaM rules to this module:

1. **Fetch rules** from the CDISC Library API using `CdiscLibraryClient.getRules("adamig", "1-3")`
2. **Save** the result as `rules/rules-adamig-1-3.json`
3. **Load** with `RulePackageLoader.load(Path.of("rules/rules-adamig-1-3.json"))` — the same
   `RulePackage` / `Rule` model classes apply since SDTMIG and ADaM rules share the same JSON schema
4. **Execute** with `RuleRunner.execute(rule, dataTable, datasetResolver)` — same execution engine

The rule JSON schema (`specifications/CORE-base.json`) is standard-agnostic and applies to both
SDTMIG and ADaM rules.

---

## Public Resources (No Membership Required)

| Resource | URL | Content |
|----------|-----|---------|
| CDISC Rules Engine | https://github.com/cdisc-org/cdisc-rules-engine | Open source engine (MIT), ADaM CT cache |
| CDISC Conformance Rules Editor | https://github.com/cdisc-org/conformance-rules-editor | TypeScript UI for editing rules |
| SDTM-ADaM Pilot Project | https://github.com/cdisc-org/sdtm-adam-pilot-project | Sample SDTM and ADaM datasets |
| CORE Rule JSON Schema | `specifications/CORE-base.json` (local) | Schema for rule definitions |
