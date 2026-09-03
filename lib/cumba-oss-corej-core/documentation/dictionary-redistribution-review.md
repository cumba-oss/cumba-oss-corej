# Dictionary Redistribution Review

**Counsel review request — coreJ**

**We have decided to ship no terminology data at all until these questions
are answered.** Every dictionary is instead downloaded or read from a local
distribution on the user's own machine.

This brief therefore asks a narrower question than it originally did: *may
we ever bundle?* We would like to, because it is the difference between a
tool that works on installation and one that answers nothing until the
operator acts. Three of the four candidate terminologies have no published
licence at all. This brief sets out exactly what we would ship if cleared,
the basis we believe supports it, and seven questions we cannot answer
ourselves.

| | |
|---|---|
| **Product** | coreJ — CDISC conformance validator |
| **Product licence** | AGPL-3.0-only |
| **Distribution** | Source, zip bundle, Docker images, hosted REST API |
| **Prepared** | 30 August 2026 |
| **Status** | Pre-implementation. **Current decision: bundle nothing; users install locally** |
| **Commercial use** | Yes |

> **How to read this.** Every licence quotation below was transcribed from
> the live source on 30 August 2026 and should be checked against the
> current text before you rely on it — the LOINC licence page carries a
> "last updated" date four days earlier, and the SNOMED Global Patient Set
> licence changed in March 2026. Nothing here is a legal conclusion; the
> verdicts are our engineering reading, offered so you can correct them.

---

## 1. What we would distribute, if cleared

None of this is shipped today. The validator reads dictionaries in a house
JSON format that no authority publishes, so every file is a **converted
extract**, not a redistributed release. In each case we take substantially
fewer fields than the source provides.

| File | Content extracted | Dropped | Size |
|---|---|---|---|
| `medrt.json` | 3,695 concept names and their NUI codes | All relationships, properties, synonyms, hierarchy | 686 KB |
| `unii.json.gz` | 171,912 UNII codes and display names (columns 0–1 of 25) | 23 columns, incl. every third-party identifier (NCIt, RxCUI, PubChem, WHO INN, Kew MPNS) | 6.6 MB |
| `loinc.json` | ~104,000 LOINC codes with their long common names | ~39 of 41 table columns | ~3 MB |
| `neoplasm.json` | 310 CDISC submission values from one codelist (C88025), each classified benign or malignant | Definitions, NCIt concept codes, synonyms, all other codelists | 35 KB |

Each file would ship alongside its licence or terms-of-use document and a
provenance record (source URL, artefact name, release version, retrieval
date, checksum).

## 2. What we do today, and why the channels still matter

Today the software ships with an installer and no data. On the operator's
machine it downloads MED-RT, UNII and the CDISC subset directly from the
publishing authorities; LOINC via the operator's own free account; and it
reads SNOMED, MedDRA and WHODrug from a local distribution the operator
already holds. It writes each licence document alongside the data it
fetches and shows the terms at install time.

We believe that posture raises none of the questions below, since we
redistribute nothing and the operator obtains each terminology directly
from its publisher. **If you disagree with that premise, it is the most
important thing you could tell us** — everything else here is secondary.

The channels below would matter if bundling were permitted, and one of
them (the hosted API) may matter regardless:

- **A downloadable zip bundle.** Engages LOINC's fixed-medium and "same
  Internet page" notice clauses.
- **Docker images** pulled from a registry, where there is no conventional
  "page" the notice sits on.
- **A hosted REST API and web interface.** Engages LOINC's online-resource
  clause, and — because the product is AGPL-3.0 — §13 of that licence,
  which treats network interaction as a distribution event.

---

## 3. Per-terminology register

### LOINC — *two clauses to confirm*

| | |
|---|---|
| **Licensor** | Regenstrief Institute, Inc. and the LOINC Committee |
| **Licence** | Published, detailed, and expressly permits commercial redistribution — <https://loinc.org/license/> |
| **Our reading** | Bundling is clearly contemplated. The open points are *what must accompany the codes* and *where the notice must appear*. |

> Permission is hereby granted in perpetuity, without payment of license
> fees or royalties, to use, copy, or distribute the Licensed Materials
> for any commercial or non-commercial purpose, subject to the following
> terms and conditions…
>
> — *LOINC Copyright Notice and License, grant clause*

Extraction into a product is expressly permitted, conditionally:

> **Subject to Section 1 and the other restrictions hereof**, portions of
> the Group 1 Artifacts or Group 3 Artifacts may be incorporated into
> other products and services, such as laboratory test compendiums, data
> dictionaries, online terminology services, software programs including
> mobile device applications… **provided that:**
>
> — *LOINC licence, §10 (incorporation)*

One of those provisos drove a design change. We had originally planned to
ship **codes only**, reasoning that carrying less content reduced
exposure. We now read §10.c as requiring the opposite:

> Any information that is extracted from the Licensed Material must always
> be associated with the corresponding identifier from LOINC… **and
> include the corresponding LOINC display name**: For LOINC codes, one of
> the following LOINC display names… **must be included**: The
> fully-specified name…; The LOINC short name…; The LOINC long common
> name…; or The LOINC Display name.
>
> — *LOINC licence, §10.c*

We have therefore changed the design to include long common names. That in
turn raises a second issue we do not know how to resolve, because adding
names is what pulls third-party-owned text into our bundle:

> When LOINC terms with third-party copyright are included in any products
> or services as described in Section 10 above, the copyright notice
> contained in the **EXTERNAL_COPYRIGHT_NOTICE** field **must be included
> in addition to** the LOINC copyright notice.
>
> — *LOINC licence, Notice of Third-Party Content and Copyright Terms*

We would ship the required short notice verbatim as
`LOINC_short_license.txt`. We note that under §10 the version number is
*"strongly encouraged, but not required"* (the mandatory-version language
sits in §9, which governs distributing whole copies) — we intend to
include it regardless.

One further clause sits oddly against the rest and we have not been able
to reconcile it: *"no other right to create a derivative work of any of
the Licensed Materials is hereby granted"*, in a licence that elsewhere
plainly contemplates reformatting and partial extraction.

---

### MED-RT (Medication Reference Terminology) — *no licence exists*

| | |
|---|---|
| **Producer** | US Department of Veterans Affairs / Veterans Health Administration, developed with Apelon, Inc. as contractor |
| **Host** | NCI Enterprise Vocabulary Services (host only, not the rights holder) |
| **Licence** | **None published.** We enumerated all 8 distribution files and all 507 archive files and found no licence, terms-of-use, or copyright section. |
| **Our basis** | Absence of restriction, not an affirmative grant: 17 U.S.C. §105, plus NLM UMLS source-restriction-level **0** ("general terms of the License apply with no additional restrictions", with no category-restriction line, unlike neighbouring entries). |

The only copyright string anywhere in the distribution covers Apelon's XML
import schema, not the content:

> Copyright (c) 1997 - 2014 Apelon, Inc. All rights reserved.
>
> — *MED-RT_Schema_v1.xsd — schema file only*

**A correction we made during review, which may be instructive.** We had
planned to ship a CC BY 4.0 licence file with MED-RT, reasoning that NCI
content is CC BY 4.0. That was wrong. The NCI statement is
product-specific:

> The NCI Thesaurus™ is released under the Creative Commons Attribution
> 4.0 International license (CC BY 4.0)… The name "NCI Thesaurus" is
> trademarked. **Only the NCI Thesaurus™ published by the NCI can be
> released under this name.**
>
> — *NCI Thesaurus Terms of Use (evs.nci.nih.gov/ftp1/NCI_Thesaurus/)*

MED-RT is a different artefact from a different producer, with no terms
file. We have dropped that licence file rather than assert a grant we
cannot convey. We would instead record the absence of terms, the §105
basis, the UMLS restriction level, and the MED-RT™ / NDF-RT™ trademarks.

Relevant to Q5 below: our extract carries only names and NUI codes, all in
the MED-RT namespace. It contains no RxNorm, MeSH or SNOMED CT content,
which is what would otherwise pull in a separate licence.

---

### UNII (Unique Ingredient Identifiers) — *no licence exists*

| | |
|---|---|
| **Producer** | US Food and Drug Administration |
| **Licence** | **None published.** No UNII-specific terms page exists; the site footer carries only "© 2026 U.S. Food and Drug Administration. All Rights Reserved" boilerplate. |
| **Our basis** | 17 U.S.C. §105 plus FDA's general open-data posture. We flag that FDA's explicit CC0 dedication covers *openFDA*, a different service — citing it for UNII is an inference, not a grant. |

A second correction worth recording: we had intended to ship the file
included in the UNII download as the licence document. On reading it in
full, it is a 25-field column glossary containing no copyright, licence,
terms or disclaimer language of any kind. Presenting it as a licence would
have misrepresented it.

Mitigating fact: the third-party material in the UNII release lives in the
columns we discard. Our extract takes 2 of 25 columns, dropping every
external identifier and the WHO disclaimer attached to the INN column:

> This adaptation was not created by WHO. WHO is not responsible for the
> content or accuracy of this adaptation. The original edition shall be
> the binding and authentic edition.
>
> — *READ ME UNII Lists.txt — attached to the INN_ID column, which we do not ship*

---

### CDISC Controlled Terminology — neoplasm subset — *grant unclear*

| | |
|---|---|
| **Producer** | CDISC, developed and published with NCI EVS |
| **What we take** | 310 submission values from a single codelist (C88025, "Neoplasm Type") — short tumour-type strings such as `ADENOMA, BENIGN`. No definitions, no concept codes. |
| **Licence** | The CT packages ship no licence text at any level. CDISC's general terms assert rights and grant only internal use. |

> Copyright (c) 2014, CDISC All rights reserved.… You are hereby granted a
> nonexclusive, worldwide, perpetual, compensation-free, non-transferable,
> non-sublicensable license to reproduce, publish, display, and use this
> standard **solely within Your Organization**… the foregoing license
> grant **does not include the right to** (i) translate or create
> derivative works of the Material, or (ii) internally or externally copy,
> distribute, or post to a Web page.
>
> — *cdisc.org/terms-and-conditions*

Those terms are scoped to "standards in document format", which arguably
excludes the EVS-hosted terminology files — but that leaves the
terminology with no published grant at all. The only supporting statement
is a knowledge-base line about intent, not a licence:

> CDISC also wants to ensure that its terminology standards remain open
> and free, without licensing restrictions.
>
> — *CDISC Controlled Terminology FAQs*

We would describe this as *published free of charge with no terms
attached*, rather than "free of licence restriction", which converts
silence into permission and passes it downstream.

**A third-party hazard we found, and the control we adopted.** The wider CT
publications embed instrument copyrights from 50+ rights holders — EORTC,
AJCC, CAPS, CDR, CDRS-R — carrying notices such as:

> No part of the EORTC system of measures may be reproduced, distributed,
> or transmitted in any form or by any means… without the prior written
> permission of the European Organisation for the Research and Treatment
> of Cancer (EORTC). All rights reserved.
>
> — *SDTM Controlled Terminology, definition column of codelist C183596*

Our original mitigation was to take codes and submission values while
dropping definitions. Testing that rule falsified it: questionnaire
codelists place instrument content *in the submission value itself*
(codelist C101818 carries the EQ-5D-5L's five dimensions as submission
values), and that codelist carries no copyright notice, because the notice
sits on a different codelist. Column filtering would therefore have
shipped instrument content stripped of its notice. We replaced it with a
**codelist allowlist**. C88025 is on it, verified to carry no third-party
notice on the codelist or any of its 310 terms.

---

### SNOMED CT — *not bundled*

| | |
|---|---|
| **Licensor** | SNOMED International (registered England and Wales) |
| **Free tier** | Global Patient Set — **CC BY-ND 4.0** since approximately March 2026 |
| **Our decision** | Ship nothing; convert locally on the user's machine only. |

We raise this only because the reasoning may generalise. Two points we
would want confirmed if we ever revisit it: the GPS was **CC BY 4.0 until
early 2026**, and that grant is irrevocable, so the release version
determines the answer; and the clause that actually gives us pause is not
the no-derivatives term itself but §4(b), under which including
substantially all of a database's contents in one's own database produces
Adapted Material. We also note we are passing up an option the licence
plainly allows — shipping the source file verbatim with attribution.

---

### MedDRA and WHODrug — *no exposure*

Commercial subscription terminologies. We ship no data and never will;
licence holders point the software at their own files. Included here only
for completeness.

---

## 4. Questions for counsel

None of these now block shipping — they determine whether we may ever
bundle, and so whether the product can work without an install step. Q1–Q3
gate a LOINC bundle; Q7 gates bundling at all.

**Q0 — the premise question.** Is our current posture sound: an installer
that fetches terminology from the publisher onto the operator's machine,
converts it locally for that operator's own use, and writes the licence
alongside? We assume this is materially different from redistribution and
needs no clearance. If that assumption is wrong, we would want to know
before anything else.

**Q1 — Does §10.c bind a code-only extract, and do long common names
discharge it?**
We reversed our design on this reading — from codes only to codes plus
names. If the reading is wrong, we would prefer to carry less content. If
it is right, we want to be sure long common names are a sufficient choice
among the four permitted display names.

**Q2 — How do we satisfy §10.b once display names are included?**
Adding names is precisely what pulls in terms bearing
`EXTERNAL_COPYRIGHT_NOTICE` content. Is the compliant combination to carry
those notices alongside, or to exclude the affected records? Excluding
them makes the validator silently wrong for those codes, so we would
rather carry them if that works.

**Q3 — Does LOINC's no-derivative-works sentence bar a JSON reformatting?**
The licence elsewhere permits incorporating "portions" into software and
data dictionaries, which is exactly what we do. We suspect the sentence
targets something else, but it is written flatly.

**Q4 — Is redistributing 310 CDISC submission values lawful?**
CDISC asserts "All rights reserved" and grants no redistribution right,
while NCI's Creative Commons statement names only the NCI Thesaurus. A
sub-question that may dispose of it entirely: do 310 short tumour-type
strings clear the originality threshold at all?

**Q5 — Does 17 U.S.C. §105 cover MED-RT given contractor authorship?**
§105 addresses works of federal *employees*. MED-RT is a VA product
developed with Apelon as contractor, and contractor-authored works can be
copyrighted and assigned. The same question applies in weaker form to
UNII.

**Q6 — Would a converted SNOMED GPS become Adapted Material under §4(b)?**
Sui generis database rights are live for a licensor registered in England
and Wales. Our reading is that mechanical reformatting does not create
such a right in us, but this is UK/EU database law and we are guessing.

**Q7 — Is mere aggregation sound for restrictively-licensed data inside an
AGPL product?**
The AGPL tells recipients they may modify and redistribute everything they
received. LOINC forbids altering field contents. We intend to state
explicitly, in the shipped documentation and naming each dictionary, that
the AGPL grant does not extend to the bundled data and that each remains
under its own terms. Is that sufficient? And does §13 network use change
our LOINC notice obligations for the hosted API?

---

## 5. What we have not verified

Stated plainly so the gaps are not mistaken for clean findings:

- **The LOINC release package itself has never been downloaded** — it is
  account-gated. Any notices shipped *inside* the package, beyond the web
  licence quoted here, are unread. This is the most material gap in the
  LOINC analysis.
- **The SNOMED GPS package has never been downloaded** — registration-gated.
  Its in-package notices are likewise unread, so we cannot say CC BY-ND is
  the whole of its terms.
- Only SDTM and SEND controlled terminology were examined for third-party
  notices. ADaM, CDASH, Define-XML, DDF and Glossary CT were not.
- MedDRA and WHODrug commercial terms were taken as given, on the basis
  that we ship nothing.
- All quotations were transcribed from live sources on 30 August 2026. Two
  of these licences changed within the preceding six months.

---

*Prepared by the coreJ engineering team as a compliance analysis, not legal
advice. Verdicts reflect our engineering reading and are offered for
correction.*

*No dictionary data has been bundled or distributed, and none will be
pending this review. Implementation of the local-install path proceeds.*
