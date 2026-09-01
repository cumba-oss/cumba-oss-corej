#!/usr/bin/env python3
"""Generate an HTML diff report comparing two versions of rules-sdtmig-3-4.json.

Input:  org/rules-sdtmig-3-4.json   (original)
        rules/rules-sdtmig-3-4.json (updated)
Output: rules-sdtmig-3-4-diff.html  (self-contained, offline-viewable)
"""
import json
import os
import difflib
import html
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
ORIG_PATH = os.path.join(HERE, "org", "rules-sdtmig-3-4.json")
UPD_PATH = os.path.join(HERE, "rules", "rules-sdtmig-3-4.json")
OUT_PATH = os.path.join(HERE, "rules-sdtmig-3-4-diff.html")

# Header text (overridable via the CLI). The labels appear in the report's <meta> line;
# TITLE is used for both the browser <title> and the <h1>.
TITLE = "CDISC CORE — SDTMIG 3.4 Rules Diff"
ORIG_LABEL = "org/rules-sdtmig-3-4.json"
UPD_LABEL = "rules/rules-sdtmig-3-4.json"

# Column order for the overview table. Core is merged into first "Rule" col.
COLUMNS = [
    "Authorities",
    "Rule_Type",
    "Sensitivity",
    "Scope",
    # Top-level since PLAN-scope-requirements-split retired `Scope.Variables`: the
    # variable / dataset requirement and the Library/Define/Dictionary booleans. This
    # list is CLOSED — a field missing from it is invisible in the report.
    "Requirements",
    "Description",
    "Check",
    "Outcome",
    "Executability",
    "Operations",
    "Grouping_Variables",
    "Match_Datasets",
    "Core",  # Status field can change; keep last
]

# Keys dropped recursively on load so they are completely ignored on compare
# (never surface in any column or in the full-rule diff).
IGNORED_KEYS = {"_links", "id"}


def _strip_ignored(o):
    """Recursively drop every ignored key (see IGNORED_KEYS)."""
    if isinstance(o, dict):
        return {k: _strip_ignored(v) for k, v in o.items() if k not in IGNORED_KEYS}
    if isinstance(o, list):
        return [_strip_ignored(x) for x in o]
    return o


def load(path):
    with open(path, encoding="utf-8") as f:
        return _strip_ignored(json.load(f))


def key_of(rule):
    c = rule.get("Core") or {}
    return (c.get("Id", ""), c.get("Version", ""))


def norm(v):
    """Canonicalize for equality comparison (stable key order)."""
    return json.dumps(v, sort_keys=True, ensure_ascii=False)


def pretty(v):
    if v is None:
        return "(absent)"
    return json.dumps(v, indent=2, ensure_ascii=False, sort_keys=False)


def unified_diff_html(old, new):
    old_s = pretty(old).splitlines()
    new_s = pretty(new).splitlines()
    diff = difflib.unified_diff(old_s, new_s, fromfile="original", tofile="updated", lineterm="")
    out = []
    for line in diff:
        esc = html.escape(line)
        if line.startswith("+++") or line.startswith("---"):
            out.append(f'<span class="diff-hdr">{esc}</span>')
        elif line.startswith("@@"):
            out.append(f'<span class="diff-hunk">{esc}</span>')
        elif line.startswith("+"):
            out.append(f'<span class="diff-add">{esc}</span>')
        elif line.startswith("-"):
            out.append(f'<span class="diff-del">{esc}</span>')
        else:
            out.append(f'<span class="diff-ctx">{esc}</span>')
    return "\n".join(out)


def side_by_side_html(old, new):
    """Build line-aligned old/new HTML using difflib opcodes. Shorter side is
    padded with blank lines so corresponding lines land on the same visual row."""
    old_lines = pretty(old).splitlines() or [""]
    new_lines = pretty(new).splitlines() or [""]
    sm = difflib.SequenceMatcher(a=old_lines, b=new_lines, autojunk=False)
    old_rows = []  # list of (cls, text)
    new_rows = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            for k in range(i2 - i1):
                old_rows.append(("ctx", old_lines[i1 + k]))
                new_rows.append(("ctx", new_lines[j1 + k]))
        elif tag == "replace":
            n_old, n_new = i2 - i1, j2 - j1
            for k in range(max(n_old, n_new)):
                old_rows.append(("del", old_lines[i1 + k]) if k < n_old else ("pad", ""))
                new_rows.append(("add", new_lines[j1 + k]) if k < n_new else ("pad", ""))
        elif tag == "delete":
            for k in range(i2 - i1):
                old_rows.append(("del", old_lines[i1 + k]))
                new_rows.append(("pad", ""))
        elif tag == "insert":
            for k in range(j2 - j1):
                old_rows.append(("pad", ""))
                new_rows.append(("add", new_lines[j1 + k]))

    def render(rows):
        parts = []
        for cls, text in rows:
            esc = html.escape(text) if text else "&nbsp;"
            parts.append(f'<div class="sbs-line sbs-{cls}">{esc}</div>')
        return "".join(parts)

    return render(old_rows), render(new_rows)


def short_preview(v, maxlen=80):
    if v is None:
        return "(absent)"
    s = json.dumps(v, ensure_ascii=False)
    if len(s) > maxlen:
        s = s[: maxlen - 1] + "…"
    return s


def cell_class(orig_v, upd_v):
    if orig_v is None and upd_v is not None:
        return "added"
    if orig_v is not None and upd_v is None:
        return "removed"
    if norm(orig_v) != norm(upd_v):
        return "changed"
    return "same"


def main():
    orig = load(ORIG_PATH)
    upd = load(UPD_PATH)

    orig_rules = {key_of(r): r for r in orig["rules"].values()}
    upd_rules = {key_of(r): r for r in upd["rules"].values()}

    all_keys = sorted(set(orig_rules) | set(upd_rules))

    # Per-column change counts across all rules.
    col_change_count = {c: 0 for c in COLUMNS}
    row_new = set()
    row_removed = set()
    rows = []
    # Detail blob: only include fields that are different, to keep HTML small.
    detail = OrderedDict()

    for rk in all_keys:
        orig_r = orig_rules.get(rk)
        upd_r = upd_rules.get(rk)
        row_key = f"{rk[0]}__v{rk[1]}"

        if orig_r is None:
            row_new.add(row_key)
        if upd_r is None:
            row_removed.add(row_key)

        row_detail = {}
        cells = {}
        for c in COLUMNS:
            ov = orig_r.get(c) if orig_r else None
            nv = upd_r.get(c) if upd_r else None
            cls = cell_class(ov, nv)
            if cls in ("changed", "added", "removed"):
                col_change_count[c] += 1
                sbs_o, sbs_n = side_by_side_html(ov, nv)
                row_detail[c] = {
                    "cls": cls,
                    "diff_html": unified_diff_html(ov, nv),
                    "sbs_old_html": sbs_o,
                    "sbs_new_html": sbs_n,
                    "preview_new": short_preview(nv),
                    "preview_old": short_preview(ov),
                }
            cells[c] = cls

        # Full-rule view for changed rows: attach under special key "__full__".
        row_has_change = bool(row_detail) or orig_r is None or upd_r is None
        if row_has_change:
            sbs_o, sbs_n = side_by_side_html(orig_r, upd_r)
            row_detail["__full__"] = {
                "cls": "new" if orig_r is None else ("removed" if upd_r is None else "changed"),
                "diff_html": unified_diff_html(orig_r, upd_r),
                "sbs_old_html": sbs_o,
                "sbs_new_html": sbs_n,
                "preview_new": "",
                "preview_old": "",
            }

        n_changed = sum(1 for c in COLUMNS if cells[c] in ("changed", "added", "removed"))
        rows.append(
            {
                "key": row_key,
                "core_id": rk[0],
                "version": rk[1],
                "orig_status": (orig_r or {}).get("Core", {}).get("Status", ""),
                "upd_status": (upd_r or {}).get("Core", {}).get("Status", ""),
                "sensitivity": (upd_r or orig_r or {}).get("Sensitivity", ""),
                "cells": cells,
                "n_changed": n_changed,
                "is_new": orig_r is None,
                "is_removed": upd_r is None,
            }
        )
        if row_detail:
            detail[row_key] = row_detail

    total = len(rows)
    changed_rows = sum(1 for r in rows if r["n_changed"] > 0 and not r["is_new"] and not r["is_removed"])

    emit_html(rows, detail, col_change_count, total, changed_rows,
              len(row_new), len(row_removed))
    print(f"Wrote {OUT_PATH}")
    print(f"  total={total}  changed={changed_rows}  "
          f"new={len(row_new)}  removed={len(row_removed)}")
    for c in COLUMNS:
        if col_change_count[c]:
            print(f"  {c}: {col_change_count[c]} changes")


def emit_html(rows, detail, col_change_count, total, changed,
              new, removed):
    # Pre-serialize detail blob as JSON for embedding. Escape </ so the embedded
    # JSON never accidentally closes the surrounding <script> tag.
    detail_json = json.dumps(detail, ensure_ascii=False).replace("</", "<\\/")

    col_headers = []
    for c in COLUMNS:
        cnt = col_change_count[c]
        badge = f' <span class="col-badge">{cnt}</span>' if cnt else ""
        tip = f"Click to filter: show only rules where {c} changed"
        col_headers.append(
            f'<th class="filterable" data-col="{c}" title="{html.escape(tip)}">'
            f'{html.escape(c)}{badge}</th>'
        )

    tbody_rows = []
    for r in rows:
        classes = ["data-row"]
        if r["is_new"]:
            classes.append("row-new")
        elif r["is_removed"]:
            classes.append("row-removed")
        elif r["n_changed"] > 0:
            classes.append("row-changed")
        else:
            classes.append("row-same")

        search_hay = (
            r["core_id"] + " " + r["version"] + " " + r["sensitivity"]
        ).lower()

        tds = []
        # First two columns: core id + version (sticky). Core.Id is clickable for changed rows.
        anchor = html.escape(r["key"])
        row_has_change = r["n_changed"] > 0 or r["is_new"] or r["is_removed"]
        core_cls = "sticky-col core-id" + (" clickable" if row_has_change else "")
        click_attrs = (
            f' data-row="{anchor}" data-col="__full__" title="Click to view complete rule diff"'
            if row_has_change else ""
        )
        tds.append(
            f'<td class="{core_cls}"{click_attrs}>'
            f'<a id="{anchor}" href="#{anchor}">{html.escape(r["core_id"])}</a></td>'
        )
        tds.append(f'<td class="version">{html.escape(r["version"])}</td>')

        for c in COLUMNS:
            cls = r["cells"][c]
            cell_cls = f"cell cell-{cls}"
            if cls in ("changed", "added", "removed"):
                dv = detail.get(r["key"], {}).get(c, {})
                tip = "OLD: " + dv.get("preview_old", "") + "\nNEW: " + dv.get("preview_new", "")
                title = html.escape(tip)
                tds.append(
                    f'<td class="{cell_cls}" data-col="{c}" data-row="{html.escape(r["key"])}"'
                    f' title="{title}">'
                    f'<span class="cell-label">{cls}</span></td>'
                )
            else:
                tds.append(f'<td class="{cell_cls}"></td>')

        tbody_rows.append(
            f'<tr class="{" ".join(classes)}" data-key="{html.escape(r["key"])}" '
            f'data-search="{html.escape(search_hay)}" data-nchanged="{r["n_changed"]}">'
            f'{"".join(tds)}</tr>'
        )

    # Assemble.
    html_out = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{html.escape(TITLE)}</title>
<style>
  :root {{
    --bg: #f5f5f7;
    --fg: #1d1d1f;
    --muted: #6e6e73;
    --yellow: #fff3b0;
    --yellow-dark: #e6c200;
    --green: #c8f0c8;
    --green-dark: #2e7d32;
    --red: #f5c6cb;
    --red-dark: #8a1c22;
    --border: #d2d2d7;
    --accent: #0066cc;
  }}
  * {{ box-sizing: border-box; }}
  body {{ margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
         background: var(--bg); color: var(--fg); font-size: 13px; }}
  header {{ position: sticky; top: 0; z-index: 20; background: #fff; border-bottom: 1px solid var(--border);
            padding: 12px 20px; box-shadow: 0 1px 3px rgba(0,0,0,0.05); }}
  h1 {{ margin: 0 0 8px 0; font-size: 18px; }}
  .meta {{ color: var(--muted); font-size: 12px; margin-bottom: 10px; }}
  .summary {{ display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }}
  .stat {{ background: #f0f0f0; padding: 4px 10px; border-radius: 4px; font-size: 12px; }}
  .stat strong {{ color: var(--accent); }}
  .stat.changed-stat {{ background: var(--yellow); }}
  .stat.new-stat {{ background: var(--green); }}
  .stat.removed-stat {{ background: var(--red); }}
  .controls {{ display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }}
  .controls input[type=text] {{ padding: 5px 8px; border: 1px solid var(--border); border-radius: 4px;
                                 font-size: 13px; width: 260px; }}
  .controls label {{ font-size: 12px; display: flex; align-items: center; gap: 4px; cursor: pointer; }}
  .legend {{ display: flex; gap: 8px; margin-left: auto; font-size: 11px; color: var(--muted); }}
  .legend .sw {{ display: inline-block; width: 12px; height: 12px; border: 1px solid var(--border);
                  vertical-align: middle; margin-right: 3px; border-radius: 2px; }}
  .sw-changed {{ background: var(--yellow); }}
  .sw-new {{ background: var(--green); }}
  .sw-removed {{ background: var(--red); }}

  .table-wrap {{ overflow: auto; max-height: calc(100vh - 120px); }}
  table {{ border-collapse: separate; border-spacing: 0; width: 100%; background: #fff;
           font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; font-size: 11.5px; }}
  thead th {{ position: sticky; top: 0; z-index: 10; background: #eaeaef; border-bottom: 2px solid var(--border);
              padding: 6px 8px; text-align: left; white-space: nowrap; cursor: pointer; user-select: none; }}
  thead th.filterable:hover {{ background: #d8dbe0; }}
  thead th.filter-active {{ background: #cce7ff; color: #003a75; box-shadow: inset 0 -3px 0 var(--accent); }}
  thead th.sticky-col {{ z-index: 11; left: 0; cursor: default; }}
  thead th.sticky-col:hover {{ background: #eaeaef; }}
  .filter-chip {{ background: #cce7ff; color: #003a75; padding: 2px 10px; border-radius: 12px;
                   cursor: pointer; font-size: 11px; border: 1px solid #99c9ef;
                   display: inline-flex; align-items: center; gap: 4px; user-select: none; }}
  .filter-chip:hover {{ background: #b8dcfb; }}
  .filter-chip.hidden {{ display: none; }}
  tbody td {{ padding: 4px 8px; border-bottom: 1px solid #eee; white-space: nowrap;
              overflow: hidden; text-overflow: ellipsis; max-width: 200px; }}
  tbody td.sticky-col {{ position: sticky; left: 0; background: inherit; z-index: 5;
                          border-right: 1px solid var(--border); font-weight: 600; }}
  .core-id a {{ color: var(--accent); text-decoration: none; }}
  .core-id a:hover {{ text-decoration: underline; }}
  .core-id.clickable {{ cursor: pointer; }}
  .core-id.clickable:hover {{ background: #e6f0fa !important; }}
  .core-id.clickable a::after {{ content: " \\25BE"; color: var(--muted); font-size: 10px; }}
  .col-badge {{ background: var(--yellow-dark); color: #000; border-radius: 8px; padding: 1px 6px;
                 font-size: 10px; font-weight: 600; margin-left: 4px; }}

  tr.row-changed td {{ background: #fff; }}
  tr.row-same td {{ background: #fafafa; color: var(--muted); }}
  tr.row-new td {{ background: var(--green) !important; }}
  tr.row-removed td {{ background: var(--red) !important; }}

  .cell-same {{ color: #ccc; }}
  .cell-changed {{ background: var(--yellow); cursor: pointer; }}
  .cell-added {{ background: var(--green); cursor: pointer; }}
  .cell-removed {{ background: var(--red); cursor: pointer; }}
  .cell-label {{ font-size: 10px; color: #555; text-transform: uppercase; font-weight: 600; }}

  tr.hidden {{ display: none; }}
  tr:target td {{ background: #fffbcc !important; box-shadow: inset 0 0 0 2px var(--yellow-dark); }}

  #modal {{ position: fixed; inset: 0; background: rgba(0,0,0,0.4); z-index: 100;
            display: none; align-items: center; justify-content: center; padding: 20px; }}
  #modal.show {{ display: flex; }}
  .modal-content {{ background: #fff; border-radius: 8px; max-width: 1400px; width: 100%;
                     max-height: 90vh; display: flex; flex-direction: column;
                     box-shadow: 0 20px 40px rgba(0,0,0,0.3); }}
  .modal-head {{ padding: 12px 20px; border-bottom: 1px solid var(--border);
                  display: flex; justify-content: space-between; align-items: center; }}
  .modal-head h2 {{ margin: 0; font-size: 16px; }}
  .modal-close {{ background: none; border: none; font-size: 24px; cursor: pointer; color: var(--muted);
                   line-height: 1; }}
  .modal-tabs {{ display: flex; border-bottom: 1px solid var(--border); padding: 0 20px; }}
  .modal-tab {{ padding: 8px 14px; border: none; background: none; cursor: pointer;
                 border-bottom: 2px solid transparent; font-size: 13px; }}
  .modal-tab.active {{ border-bottom-color: var(--accent); color: var(--accent); font-weight: 600; }}
  .modal-body {{ padding: 16px 20px; overflow: auto; flex: 1; }}
  .pane {{ display: none; }}
  .pane.active {{ display: block; }}
  .pane.side-by-side {{ display: none; grid-template-columns: 1fr 1fr; gap: 16px; }}
  .pane.side-by-side.active {{ display: grid; }}
  pre {{ background: #f7f7f9; padding: 10px; border-radius: 4px; overflow: auto; font-size: 12px;
          margin: 0; max-height: 70vh; line-height: 1.4; }}
  .pane h3 {{ font-size: 13px; margin: 0 0 6px 0; color: var(--muted); }}
  .diff-hdr {{ color: var(--muted); display: block; }}
  .diff-hunk {{ color: #6f42c1; display: block; }}
  .diff-add {{ background: #e6ffed; color: #22863a; display: block; }}
  .diff-del {{ background: #ffeef0; color: #b31d28; display: block; }}
  .diff-ctx {{ color: #586069; display: block; }}

  /* Side-by-side diff panes: each pane is a stack of fixed-height lines that
     align row-for-row with its counterpart (both sides have equal line count). */
  .sbs-pane {{ background: #f7f7f9; border-radius: 4px; padding: 4px 0;
               font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
               font-size: 12px; line-height: 1.5; max-height: 70vh; overflow: auto;
               border: 1px solid var(--border); }}
  .sbs-line {{ white-space: pre; padding: 0 8px; min-height: 1.5em;
                border-left: 3px solid transparent; }}
  .sbs-ctx {{ color: #24292e; }}
  .sbs-del {{ background: #ffeef0; border-left-color: #d73a49; color: #b31d28; }}
  .sbs-add {{ background: #e6ffed; border-left-color: #22863a; color: #22863a; }}
  .sbs-pad {{ background: #f0f0f3; color: transparent; }}
</style>
</head>
<body>
<header>
  <h1>{html.escape(TITLE)}</h1>
  <div class="meta">
    Compares <code>{html.escape(ORIG_LABEL)}</code> (original) vs
    <code>{html.escape(UPD_LABEL)}</code> (updated). Row key: Core.Id + Core.Version.
  </div>
  <div class="summary">
    <span class="stat">Total rules: <strong>{total}</strong></span>
    <span class="stat changed-stat">Changed: <strong>{changed}</strong></span>
    <span class="stat new-stat">New: <strong>{new}</strong></span>
    <span class="stat removed-stat">Removed: <strong>{removed}</strong></span>
  </div>
  <div class="controls">
    <input type="text" id="search" placeholder="Filter by Core.Id, Sensitivity, …">
    <label><input type="checkbox" id="only-changed"> Only changed</label>
    <label><input type="checkbox" id="hide-same-col"> Hide unchanged columns</label>
    <span id="col-filter-chip" class="filter-chip hidden" title="Click to clear column filter">Filter: — ×</span>
    <span class="legend">
      <span><span class="sw sw-changed"></span>changed</span>
      <span><span class="sw sw-new"></span>new row/field</span>
      <span><span class="sw sw-removed"></span>removed row/field</span>
    </span>
  </div>
</header>

<div class="table-wrap">
<table id="diff-table">
  <thead><tr>
    <th class="sticky-col" data-col="core_id">Core.Id</th>
    <th data-col="version">Ver</th>
    {"".join(col_headers)}
  </tr></thead>
  <tbody>
    {"".join(tbody_rows)}
  </tbody>
</table>
</div>

<div id="modal" role="dialog" aria-modal="true">
  <div class="modal-content">
    <div class="modal-head">
      <h2 id="modal-title"></h2>
      <button class="modal-close" id="modal-close" aria-label="Close">×</button>
    </div>
    <div class="modal-tabs">
      <button class="modal-tab active" data-pane="diff">Unified diff</button>
      <button class="modal-tab" data-pane="side">Side by side</button>
    </div>
    <div class="modal-body">
      <div class="pane active" id="pane-diff"><pre id="diff-view"></pre></div>
      <div class="pane side-by-side" id="pane-side">
        <div><h3>Updated</h3><div id="new-view" class="sbs-pane"></div></div>
        <div><h3>Original</h3><div id="old-view" class="sbs-pane"></div></div>
      </div>
    </div>
  </div>
</div>

<script id="diff-data" type="application/json">{detail_json}</script>
<script>
  const DETAIL = JSON.parse(document.getElementById('diff-data').textContent);
  const tbody = document.querySelector('#diff-table tbody');
  const search = document.getElementById('search');
  const onlyChanged = document.getElementById('only-changed');
  const hideSameCol = document.getElementById('hide-same-col');

  let activeColIdx = null; // index into thead cells; null = no column filter
  const colFilterChip = document.getElementById('col-filter-chip');

  function applyFilter() {{
    const q = search.value.trim().toLowerCase();
    const oc = onlyChanged.checked;
    for (const tr of tbody.rows) {{
      const matches = !q || tr.dataset.search.includes(q);
      const effectiveChanges = parseInt(tr.dataset.nchanged, 10);
      const passChange = !oc || effectiveChanges > 0 ||
                         tr.classList.contains('row-new') || tr.classList.contains('row-removed');
      let passCol = true;
      if (activeColIdx !== null) {{
        const cell = tr.cells[activeColIdx];
        const isChange = cell && (cell.classList.contains('cell-changed') ||
                                   cell.classList.contains('cell-added') ||
                                   cell.classList.contains('cell-removed'));
        passCol = !!isChange;
      }}
      tr.classList.toggle('hidden', !(matches && passChange && passCol));
    }}
  }}

  function setColFilter(idx, colName) {{
    activeColIdx = (activeColIdx === idx) ? null : idx;
    document.querySelectorAll('#diff-table thead th').forEach((th, i) => {{
      th.classList.toggle('filter-active', i === activeColIdx);
    }});
    if (activeColIdx === null) {{
      colFilterChip.classList.add('hidden');
    }} else {{
      colFilterChip.classList.remove('hidden');
      colFilterChip.textContent = 'Filter: ' + colName + ' ×';
    }}
    applyFilter();
  }}
  colFilterChip.addEventListener('click', () => setColFilter(activeColIdx, ''));
  search.addEventListener('input', applyFilter);
  onlyChanged.addEventListener('change', applyFilter);

  hideSameCol.addEventListener('change', () => {{
    const headers = document.querySelectorAll('#diff-table thead th');
    const hide = hideSameCol.checked;
    headers.forEach((th, idx) => {{
      const col = th.dataset.col;
      if (!col || col === 'core_id' || col === 'version') return;
      const hasBadge = th.querySelector('.col-badge') !== null;
      const show = !hide || hasBadge;
      th.style.display = show ? '' : 'none';
      for (const tr of tbody.rows) {{
        if (tr.cells[idx]) tr.cells[idx].style.display = show ? '' : 'none';
      }}
    }});
  }});

  // Column header click = filter to rows that have a change in this column.
  document.querySelectorAll('#diff-table thead th.filterable').forEach((th, _i) => {{
    th.addEventListener('click', () => {{
      const all = Array.from(document.querySelectorAll('#diff-table thead th'));
      const idx = all.indexOf(th);
      setColFilter(idx, th.dataset.col);
    }});
  }});

  // Modal
  const modal = document.getElementById('modal');
  const modalTitle = document.getElementById('modal-title');
  const diffView = document.getElementById('diff-view');
  const oldView = document.getElementById('old-view');
  const newView = document.getElementById('new-view');

  tbody.addEventListener('click', (e) => {{
    const td = e.target.closest('td');
    if (!td) return;
    const isCellClick = td.classList.contains('cell-changed') ||
                        td.classList.contains('cell-added') ||
                        td.classList.contains('cell-removed');
    const isFullRuleClick = td.classList.contains('core-id') && td.classList.contains('clickable');
    if (!isCellClick && !isFullRuleClick) return;
    // Don't let the <a> anchor navigate when we're opening the modal.
    if (isFullRuleClick && e.target.tagName === 'A') e.preventDefault();
    const row = td.dataset.row;
    const col = td.dataset.col;
    if (!row || !col) return;
    const info = (DETAIL[row] || {{}})[col];
    if (!info) return;
    const label = col === '__full__' ? 'complete rule' : col;
    modalTitle.textContent = row.replace('__v', ' v') + ' — ' + label + ' (' + info.cls + ')';
    diffView.innerHTML = info.diff_html;
    oldView.innerHTML = info.sbs_old_html;
    newView.innerHTML = info.sbs_new_html;
    modal.classList.add('show');
  }});

  document.getElementById('modal-close').addEventListener('click', () => modal.classList.remove('show'));
  modal.addEventListener('click', (e) => {{ if (e.target === modal) modal.classList.remove('show'); }});
  document.addEventListener('keydown', (e) => {{ if (e.key === 'Escape') modal.classList.remove('show'); }});

  document.querySelectorAll('.modal-tab').forEach(btn => {{
    btn.addEventListener('click', () => {{
      document.querySelectorAll('.modal-tab').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      document.querySelectorAll('.pane').forEach(p => p.classList.remove('active'));
      document.getElementById('pane-' + btn.dataset.pane).classList.add('active');
    }});
  }});

  // Synchronize scrolling between old and new panes in side-by-side view.
  let syncing = false;
  function syncScroll(src, dst) {{
    if (syncing) return;
    syncing = true;
    dst.scrollTop = src.scrollTop;
    dst.scrollLeft = src.scrollLeft;
    requestAnimationFrame(() => {{ syncing = false; }});
  }}
  oldView.addEventListener('scroll', () => syncScroll(oldView, newView));
  newView.addEventListener('scroll', () => syncScroll(newView, oldView));
</script>
</body>
</html>
"""
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        f.write(html_out)


def _cli():
    import argparse

    ap = argparse.ArgumentParser(
        prog="build-rules-diff.py",
        description=(
            "Generate a self-contained, offline HTML diff report between two CDISC CORE "
            "rules JSON files. Rules are matched by Core.Id + Core.Version; each changed "
            "field is shown as a unified and side-by-side diff."
        ),
        epilog=(
            "examples:\n"
            "  # use built-in defaults (org/ vs rules/)\n"
            "  python3 build-rules-diff.py\n\n"
            "  # compare two explicit files\n"
            "  python3 build-rules-diff.py \\\n"
            "      --orig rules_org/rules-sdtmig-3-4.json \\\n"
            "      --upd  rules_org/rules-sdtmig-3-4-reworked-main.json \\\n"
            "      --out  rules_org/rules-sdtmig-3-4-reworked-main-diff-vs-base.html \\\n"
            "      --title 'reworked-main vs base'"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--orig", default=ORIG_PATH,
                    help="original / base rules JSON, shown on the left (default: %(default)s)")
    ap.add_argument("--upd", default=UPD_PATH,
                    help="updated rules JSON, shown on the right (default: %(default)s)")
    ap.add_argument("--out", default=OUT_PATH,
                    help="output HTML file (default: %(default)s)")
    ap.add_argument("--title", default=None, metavar="LABEL",
                    help="label appended to the report title/header, e.g. 'reworked-main vs base'")
    ap.add_argument("--orig-label", default=None, metavar="TEXT",
                    help="name shown for the original file in the header (default: the --orig path)")
    ap.add_argument("--upd-label", default=None, metavar="TEXT",
                    help="name shown for the updated file in the header (default: the --upd path)")
    return ap.parse_args()


if __name__ == "__main__":
    _args = _cli()
    ORIG_PATH = _args.orig
    UPD_PATH = _args.upd
    OUT_PATH = _args.out
    ORIG_LABEL = _args.orig_label or _args.orig
    UPD_LABEL = _args.upd_label or _args.upd
    if _args.title:
        TITLE = f"{TITLE} ({_args.title})"
    main()
