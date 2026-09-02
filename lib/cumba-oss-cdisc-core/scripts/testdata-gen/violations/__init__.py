"""Per-rule violation injectors.

Each ``c<digits>_<lane>.py`` module exposes:

* ``META`` — ``{"coreId", "standard", "domain", "summary"}``
* ``inject(study) -> dict`` — applies the minimal single mutation that trips
  exactly that rule and returns an expectation fragment (merged with the
  driver-supplied ``coreId``/``standard``/``changes``).

Module names cannot start with a digit, so the CORE id ``CORE-000068`` becomes
module ``c000068_sdtmig``; the real id lives in ``META["coreId"]``.
"""
