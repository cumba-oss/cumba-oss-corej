"""Deterministic subject + trial-design model shared by all domains.

Built once per generation run from the CLI parameters; every domain draws its
subjects, arms, epochs, visits and reference dates from here so cross-dataset
values line up (DM.ARMCD in TA, EPOCH in TE, VISIT/VISITNUM in TV/SV, exposure
within the treatment window, etc.).

Dates are computed from a fixed base date (not "today") so output is stable.
Phase 3 enriches the per-domain date placement; this module owns the skeleton.
"""

from __future__ import annotations

import datetime as _dt
from dataclasses import dataclass

_BASE = _dt.date(2020, 1, 6)  # study day 1 of subject 0 (a Monday)

# Three arms: two active treatments + placebo.
ARMS = [
    ("A", "Drug A 54 mg", "Drug A", 54),
    ("B", "Drug B 81 mg", "Drug B", 81),
    ("P", "Placebo", "Placebo", 0),
]

def iso(d: _dt.date) -> str:
    return d.isoformat()


@dataclass
class Visit:
    num: float
    name: str
    day: int  # study day (>=1; no day 0)
    epoch: str


@dataclass
class Subject:
    index: int
    usubjid: str
    subjid: str
    siteid: str
    arm_index: int
    sex: str
    age: int
    race: str
    country: str
    # reference dates (date objects)
    rficdtc: _dt.date
    rfstdtc: _dt.date
    rfxstdtc: _dt.date
    rfxendtc: _dt.date
    rfendtc: _dt.date
    rfpendtc: _dt.date

    @property
    def armcd(self) -> str:
        return ARMS[self.arm_index][0]

    @property
    def arm(self) -> str:
        return ARMS[self.arm_index][1]

    @property
    def trt(self) -> str:
        return ARMS[self.arm_index][2]

    @property
    def dose(self) -> int:
        return ARMS[self.arm_index][3]

    def visit_date(self, day: int) -> _dt.date:
        """Calendar date of a study day for this subject (day 1 == rfstdtc)."""
        return self.rfstdtc + _dt.timedelta(days=day - 1)

    def study_day(self, d: _dt.date) -> int:
        """SDTM study day relative to RFSTDTC (no day 0: <start is negative)."""
        delta = (d - self.rfstdtc).days
        return delta + 1 if delta >= 0 else delta


@dataclass
class Study:
    studyid: str
    standard: str
    version: str
    subjects: list[Subject]
    visits: list[Visit]
    seed: int = 0
    _SEX = ("M", "F")
    _RACE = ("WHITE", "BLACK OR AFRICAN AMERICAN", "ASIAN")
    _COUNTRY = ("USA", "CAN", "GBR")

    def treatment_visits(self) -> list[Visit]:
        return [v for v in self.visits if v.epoch == "TREATMENT"]


def build_study(
    standard: str,
    version: str,
    n_subjects: int = 20,
    n_visits: int = 10,
    studyid: str = "SYNTH01",
    seed: int = 0,
) -> Study:
    """Construct the deterministic study skeleton."""
    # Visit schedule: 1 screening + (n_visits-2) treatment + 1 follow-up.
    visits: list[Visit] = []
    visits.append(Visit(1.0, "SCREENING", -14, "SCREENING"))
    n_trt = max(1, n_visits - 2)
    for k in range(n_trt):
        day = 1 + k * 14
        visits.append(Visit(float(k + 2), f"WEEK {k * 2}" if k else "BASELINE", day, "TREATMENT"))
    last_trt_day = 1 + (n_trt - 1) * 14
    # Follow-up VISITNUM sits one above the last treatment visit (num n_trt + 1).
    # For the default n_visits >= 3 this equals n_visits (so output is unchanged);
    # for n_visits < 3 (where n_trt is clamped to 1) it avoids colliding with the
    # treatment visit's number — a duplicate VISITNUM would break TV/SV coherence.
    visits.append(Visit(float(n_trt + 2), "FOLLOW-UP", last_trt_day + 28, "FOLLOW-UP"))

    subjects: list[Subject] = []
    for i in range(n_subjects):
        base = _BASE + _dt.timedelta(days=i * 2)  # stagger enrolment
        rfst = base
        rfxen = rfst + _dt.timedelta(days=last_trt_day - 1)
        rfen = rfst + _dt.timedelta(days=(last_trt_day + 28) - 1)
        subjects.append(
            Subject(
                index=i,
                usubjid=f"{studyid}-{i + 1:03d}",
                subjid=f"{i + 1:03d}",
                siteid=f"{701 + (i % 3)}",
                arm_index=i % len(ARMS),
                sex=Study._SEX[i % 2],
                age=45 + (i * 3) % 40,
                race=Study._RACE[i % len(Study._RACE)],
                country=Study._COUNTRY[i % len(Study._COUNTRY)],
                rficdtc=rfst - _dt.timedelta(days=14),
                rfstdtc=rfst,
                rfxstdtc=rfst,
                rfxendtc=rfxen,
                rfendtc=rfen,
                rfpendtc=rfen,
            )
        )
    return Study(studyid, standard, version, subjects, visits, seed)
