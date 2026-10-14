# Vendored CDISC Define-XML schema packages

- `define-2-1/` — Define-XML 2.1.0 schema (entry point `cdisc-define-2.1/define2-1-0.xsd`)
- `define-2-0/` — Define-XML 2.0.0 schema (entry point `cdisc-define-2.0/define2-0-0.xsd`)

Both include the ODM 1.3.2 foundation schemas, the Analysis Results Metadata
(ARM) 1.0 extension, and the core schemas (`xml.xsd`, `xlink.xsd`,
`xmldsig-core-schema.xsd`).

This file is the provenance and licence record for every file under `xsd/`.
The licence texts it names ship in this module's jar as
`META-INF/NOTICE-xsd.txt`, and the module's `licenses.xml` lists them for
applications that aggregate those files (the data browser's About dialog).
Terms read and identities measured on 2026-10-14.

## Where the files come from

The files were mirrored on 2026-07-03 from Lex Jansen's stylesheet
repositories (https://github.com/lexjansen/define-xml-2.1-stylesheets and
https://github.com/lexjansen/define-xml-2.0-stylesheets, `schema/`). They are
**unmodified apart from one change** (see "Changes we made" below).

CDISC itself publishes the same files, under the MIT License, in its own
GitHub organisation. That CDISC-hosted copy is the footing this record relies
on. Measured on 2026-10-14 (`md5sum` / `cmp` against files fetched from
`raw.githubusercontent.com` at the commits named):

| our directory | CDISC repository, path, commit | identity |
|---|---|---|
| `define-2-0/**` (11 files) | `cdisc-org/define-xml-2.0-stylesheets`, `schema/`, `e94be4ec64c3360e86d756f54533b8f4f94f8a50` (branch `master`) | byte-identical, all 11 |
| `define-2-1/**` (12 files) | `cdisc-org/define-xml-2.1-stylesheets`, `schema/`, `e54eb4821ce5dfa34598b95a6bb4c58b3618662b` (branch `master`) | byte-identical, 11 of 12; see below |
| `define-2-{0,1}/core/{xml,xlink,xmldsig-core-schema}.xsd` | `cdisc-org/DataExchange-ODM`, `archive/ODM1-3-2/`, `6cb00a9cb38f245069758a7c5a6823cdf3ea48be` (branch `main`) | byte-identical, all 3 |

The one exception is `define-2-1/cdisc-define-2.1/define-enumerations.xsd`
(ours md5 `6facc9e39089be543f99e0142d489f8f`, upstream
`f1b1b200138ef8633d5f167b6355458f`): CDISC updated it upstream after our
mirror. The newer file is **deliberately not pulled in**: updating the schema
content is out of scope of the licence work and would change validation
behaviour.

The two `define-xml-*-stylesheets` repositories in `cdisc-org` are GitHub
forks of Lex Jansen's; `DataExchange-ODM` is CDISC's own repository (not a
fork). The identity rows above are measured before our change to
`xmldsig-core-schema.xsd`; that change is the only difference today.

## Licence, per family

All CDISC repositories named above carry the MIT License. Their `LICENSE`
files read, verbatim:

- `cdisc-org/define-xml-2.0-stylesheets/LICENSE.md`: "The MIT License (MIT)" /
  "Copyright (c) 2020 Lex Jansen"
- `cdisc-org/define-xml-2.1-stylesheets/LICENSE.md`: "The MIT License (MIT)" /
  "Copyright (c) 2022 Lex Jansen"
- `cdisc-org/DataExchange-ODM/LICENSE`: "MIT License" / "Copyright (c) 2022 cdisc"

The MIT permission and its one condition, quoted from those files:
*"Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions: The above copyright
notice and this permission notice shall be included in all copies or
substantial portions of the Software."* The full texts are in
`META-INF/NOTICE-xsd.txt`.

| family | files | authored by | source we rely on | licence |
|---|---|---|---|---|
| CDISC Define-XML 2.0.0 | `define-2-0/cdisc-define-2.0/*.xsd` | CDISC XML Technologies Team | `cdisc-org/define-xml-2.0-stylesheets` `schema/` | MIT (repository licence) |
| CDISC Define-XML 2.1.0 | `define-2-1/cdisc-define-2.1/*.xsd` | CDISC XML Technologies Team | `cdisc-org/define-xml-2.1-stylesheets` `schema/` | MIT (repository licence) |
| CDISC ODM 1.3.2 | `define-2-{0,1}/cdisc-odm-1.3.2/*.xsd` | CDISC | both stylesheet repositories, `schema/cdisc-odm-1.3.2/` | MIT (repository licence) |
| CDISC ARM 1.0 | `define-2-{0,1}/cdisc-arm-1.0/*.xsd` (the 2.0 and 2.1 sets differ) | CDISC ADaM Metadata Sub-Team | the stylesheet repository of the same Define-XML version | MIT (repository licence) |
| W3C XML namespace schema | `define-2-{0,1}/core/xml.xsd` | W3C | `cdisc-org/DataExchange-ODM` `archive/ODM1-3-2/` | MIT (repository licence); the file carries no notice of its own, and neither does the W3C original |
| `xlink.xsd` | `define-2-{0,1}/core/xlink.xsd` | not stated in the file | `cdisc-org/DataExchange-ODM` `archive/ODM1-3-2/` | MIT (repository licence) |
| W3C XML Signature schema | `define-2-{0,1}/core/xmldsig-core-schema.xsd` | The Internet Society and W3C | `cdisc-org/DataExchange-ODM` `archive/ODM1-3-2/`; canonical https://www.w3.org/TR/xmldsig-core/xmldsig-core-schema.xsd | W3C Software Notice and License (1998-07-20), named by the file itself; also MIT as hosted by CDISC |

Notes on the table:

- **Inference, not text:** that the MIT grant of the CDISC repositories
  covers the CDISC-authored schemas in them. No text in those repositories
  says so or says otherwise; the schema files carry no licence statement
  (only, for example, *"Define-XML 2.1.0 schema as developed by the CDISC XML
  Technologies Team"*), and the copyright line in the two stylesheet
  repositories names Lex Jansen, not CDISC. We rely on the fact that CDISC
  publishes the files, in its own organisation, under that licence.
- **CDISC's website terms.** https://www.cdisc.org/terms-and-conditions
  excludes distribution, but is worded for standards in document format:
  *"CDISC standards in document format are open and freely available for use
  in clinical research pursuant to the terms below. User agrees to limit
  access to this download for use within your organization only."* …
  *"the foregoing license grant does not include the right to (i) translate
  or create derivative works of the Material, or (ii) internally or
  externally copy, distribute, or post to a Web page."* The page does not say
  whether it governs the XML schemas. The licence files inside CDISC's
  login-gated standard packages were not read.
- **Decision.** On 2026-10-14 the project owner ruled to attribute on the
  CDISC-hosted footing: redistribute the files with the MIT notice of the
  CDISC repositories and the W3C notice, rather than stop shipping them.
- **`xlink.xsd` is a stub, not the W3C XLink schema** (1 642 bytes; the W3C
  files at https://www.w3.org/1999/xlink.xsd and
  https://www.w3.org/XML/2008/06/xlink.xsd are different and larger). It is
  byte-identical to CDISC's copy in `DataExchange-ODM`; its author is not
  stated.
- **`xml.xsd` differs from the W3C original** (https://www.w3.org/2001/03/xml.xsd):
  no DOCTYPE, whitespace, and the `specialAttrs` attribute group commented
  out. That is how CDISC ships it; we did not change it.

## Changes we made

**`xmldsig-core-schema.xsd`, both copies, 2026-10-14.** The copies as CDISC
publishes them lack the canonical W3C file's DOCTYPE and its copyright and
licence comment. The W3C Software Notice and License requires *"Any
pre-existing intellectual property disclaimers, notices, or terms and
conditions"* and *"Notice of any changes or modifications to the W3C files,
including the date changes were made"*. We restored the comment verbatim from
https://www.w3.org/TR/xmldsig-core/xmldsig-core-schema.xsd, at its canonical
place after the XML declaration. The DOCTYPE stays out: it names an external
DTD (`http://www.w3.org/2001/XMLSchema.dtd`) that a parser may then try to
fetch. Apart from the missing DOCTYPE the file is now identical to the W3C
original; the schema content is unchanged.

No other file under `xsd/` was changed.
