#!/usr/bin/env python3
"""
명세 ↔ 구현 대조 감사 (openapi.yaml 정본 vs Spring 컨트롤러).

왜 필요한가
-----------
완성의 정의가 "명세의 [미구현] 0건"인데, 그 표시는 손으로 관리된다.
구현하고 표시를 안 지우면 명세가 실제보다 뒤처지고(과소평가), 스텁을
완료로 세면 실제보다 앞선다(과대평가). 2026-08-02 실측에서 낡은 표시가
6건 나왔다 — 아무도 몰랐다. 이 스크립트는 그 드리프트를 기계로 잡는다.

판정 기준 2축 (둘 다 0이어야 완성)
----------------------------------
  x-implementation-status: not-implemented   미구현
  x-auth-phase:            WEEK4-STUB        4주차까지 501 E-AUTH-011 반환 (D-16)
                           DEV-STUB-ACTIVE   스텁 기간에도 dev 사용자로 동작

WEEK4-STUB 를 함께 세지 않으면 501 을 반환하는 엔드포인트를 남겨두고도
"[미구현] 0건"이 성립한다. 표시가 있는 것과 동작하는 것은 다르다.

사용법
------
  python3 tools/spec-audit.py            # 감사 (드리프트 있으면 exit 1)
  python3 tools/spec-audit.py --fix      # 낡은 표시 제거 후 재감사
  python3 tools/spec-audit.py --json     # 기계 판독용

의존성 없음 (표준 라이브러리만). PyYAML 을 쓰지 않는 이유: 라운드트립에서
주석·인용부호·키 순서가 뭉개진다. 이 파일은 사람이 읽는 계약 문서다.
"""

import argparse
import glob
import json
import os
import re
import sys

SPEC = "src/main/resources/static/openapi.yaml"
CONTROLLERS = "src/main/java/**/*Controller.java"

HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options"}
SPRING_ANN = {
    "GetMapping": "GET", "PostMapping": "POST", "PutMapping": "PUT",
    "PatchMapping": "PATCH", "DeleteMapping": "DELETE",
}
UNIMPL_TAG = "[미구현]"


def normalize(path):
    """경로 변수명 차이를 흡수 — /x/{taskId} 와 /x/{id} 를 같게 본다."""
    return re.sub(r"\{[^}]+\}", "{}", path)


def parse_spec(lines):
    """오퍼레이션 단위로 확장 필드와 summary 위치를 수집."""
    ops = {}
    path = None
    cur = None
    in_paths = False
    for i, line in enumerate(lines):
        if re.match(r"^paths:", line):
            in_paths = True
            continue
        if in_paths and re.match(r"^[a-zA-Z]", line):
            in_paths = False
        if not in_paths:
            continue

        m = re.match(r"^  (/\S*):\s*$", line)
        if m:
            path, cur = m.group(1), None
            continue

        m = re.match(r"^    ([a-z]+):\s*$", line)
        if m and m.group(1) in HTTP_METHODS and path:
            cur = (m.group(1).upper(), normalize(path))
            ops[cur] = {
                "raw_path": path, "line": i + 1, "status": None, "status_line": None,
                "auth_phase": None, "lane": None, "summary_lines": [],
            }
            continue

        if not cur:
            continue

        m = re.match(r"^      x-implementation-status:\s*(\S+)", line)
        if m:
            ops[cur]["status"] = m.group(1)
            ops[cur]["status_line"] = i
        m = re.match(r"^      x-auth-phase:\s*(\S+)", line)
        if m:
            ops[cur]["auth_phase"] = m.group(1)
        m = re.match(r"^      x-lane:\s*(\S+)", line)
        if m:
            ops[cur]["lane"] = m.group(1)
        # summary 는 한 줄일 수도, 접힌 블록(`summary: >`)일 수도 있다
        if re.match(r"^      summary:", line):
            ops[cur]["summary_lines"] = [i]
            if re.match(r"^      summary:\s*[>|]", line):
                for j in range(i + 1, len(lines)):
                    if re.match(r"^        \S", lines[j]):
                        ops[cur]["summary_lines"].append(j)
                    else:
                        break
    return ops


def parse_controllers(root):
    """@RequestMapping 베이스 + @*Mapping 서브경로를 합쳐 실제 엔드포인트를 뽑는다."""
    impl = {}
    pattern = r"@(" + "|".join(SPRING_ANN) + r")(?:\(\s*(?:value\s*=\s*)?\"([^\"]*)\")?"
    for f in glob.glob(os.path.join(root, CONTROLLERS), recursive=True):
        src = open(f, encoding="utf-8").read()
        base_m = re.search(r"@RequestMapping\(\s*\"([^\"]*)\"", src)
        base = base_m.group(1) if base_m else ""
        for m in re.finditer(pattern, src):
            full = re.sub(r"/+", "/", (base + (m.group(2) or "")) or "/")
            impl[(SPRING_ANN[m.group(1)], normalize(full))] = os.path.basename(f)
    return impl


def audit(root):
    lines = open(os.path.join(root, SPEC), encoding="utf-8").read().split("\n")
    spec = parse_spec(lines)
    impl = parse_controllers(root)

    spec_keys, impl_keys = set(spec), set(impl)
    both = spec_keys & impl_keys

    stale = sorted(k for k in both if spec[k]["status"] == "not-implemented")
    missing = sorted(spec_keys - impl_keys)
    unmarked = sorted(k for k in missing if spec[k]["status"] != "not-implemented")
    ghost = sorted(impl_keys - spec_keys)
    stub = sorted(k for k in spec if spec[k]["auth_phase"] == "WEEK4-STUB")
    dev_stub = sorted(k for k in spec if spec[k]["auth_phase"] == "DEV-STUB-ACTIVE")
    # 실동작 = 구현 & 스텁 아님.
    # DEV-STUB-ACTIVE 도 뺀다 — dev 사용자로 돌 뿐이고, 4주차에 스텁 컨트롤러가
    # 통째로 제거/승격되므로(명세 머리말 D-16) 출품 기준의 완성이 아니다.
    staged = set(stub) | set(dev_stub)
    real = sorted(k for k in both if k not in staged)

    return {
        "lines": lines, "spec": spec, "impl": impl,
        "total": len(spec), "implemented": len(both), "real": len(real),
        "stale": stale, "missing": missing, "unmarked": unmarked,
        "ghost": ghost, "stub": stub, "dev_stub": dev_stub,
    }


def fix(root, r):
    """낡은 표시만 제거한다. 새 표시를 붙이지는 않는다 — 그건 구현자의 판단이다."""
    lines = r["lines"]
    drop = set()
    for k in r["stale"]:
        op = r["spec"][k]
        if op["status_line"] is not None:
            drop.add(op["status_line"])
        for idx in op["summary_lines"]:
            if UNIMPL_TAG in lines[idx]:
                lines[idx] = lines[idx].replace(UNIMPL_TAG + " ", "").replace(UNIMPL_TAG, "")
    out = [l for i, l in enumerate(lines) if i not in drop]
    open(os.path.join(root, SPEC), "w", encoding="utf-8").write("\n".join(out))
    return len(r["stale"]), len(drop)


def report(r):
    s, impl = r["spec"], r["impl"]
    print(f"명세 오퍼레이션        : {r['total']}")
    print(f"구현 엔드포인트        : {len(impl)}  (명세 내 {r['implemented']} + 명세 외 {len(r['ghost'])})")
    print(f"실동작 구현            : {r['real']}  ← 스텁 제외")
    print()
    print(f"🔴 구현됐는데 not-implemented : {len(r['stale'])}")
    print(f"⚠️  미구현인데 표시 없음       : {len(r['unmarked'])}")
    print(f"🟡 WEEK4-STUB (501 반환)      : {len(r['stub'])}")
    print(f"🟢 DEV-STUB-ACTIVE (dev 동작) : {len(r['dev_stub'])}")
    print(f"⬜ 미구현                     : {len(r['missing'])}")
    print(f"👻 명세에 없는 구현           : {len(r['ghost'])}")

    for title, keys, extra in (
        ("🔴 낡은 표시 — 구현 완료인데 not-implemented (지워야 함)", r["stale"], True),
        ("⚠️ 미구현인데 표시 없음 (붙여야 함)", r["unmarked"], False),
        ("👻 명세에 없는 구현", r["ghost"], True),
    ):
        if not keys:
            continue
        print(f"\n── {title} ──")
        for k in keys:
            if k in s:
                src = f"  ← {impl[k]}" if extra and k in impl else ""
                print(f"  L{s[k]['line']:>5}  {k[0]:6} {s[k]['raw_path']:44} lane={s[k]['lane']}{src}")
            else:
                print(f"         {k[0]:6} {k[1]:44}  ← {impl[k]}")

    lanes = {}
    for k in r["missing"]:
        lanes[s[k]["lane"]] = lanes.get(s[k]["lane"], 0) + 1
    if lanes:
        print(f"\n── 미구현 레인별 ──\n  " +
              " · ".join(f"{k}: {v}" for k, v in sorted(lanes.items())))

    done = r["real"]
    print(f"\n{'='*56}")
    print(f"완성도: {done}/{r['total']}  ({done * 100 // r['total']}%)  — 스텁 제외 실동작 기준")
    print(f"완성 조건: not-implemented 0 그리고 x-auth-phase 0 (WEEK4-STUB·DEV-STUB-ACTIVE 모두 소멸)")
    print(f"{'='*56}")


def main():
    ap = argparse.ArgumentParser(description="명세 ↔ 구현 대조 감사")
    ap.add_argument("--fix", action="store_true", help="낡은 [미구현] 표시를 제거한다")
    ap.add_argument("--json", action="store_true", help="기계 판독용 출력")
    ap.add_argument("--root", default=".", help="저장소 루트 (기본: 현재 디렉터리)")
    a = ap.parse_args()

    if not os.path.exists(os.path.join(a.root, SPEC)):
        sys.exit(f"명세를 찾을 수 없습니다: {os.path.join(a.root, SPEC)}\n"
                 f"저장소 루트에서 실행하거나 --root 를 주십시오.")

    r = audit(a.root)

    if a.fix:
        if not r["stale"]:
            print("낡은 표시 없음 — 변경하지 않았습니다.")
        else:
            n, dropped = fix(a.root, r)
            print(f"낡은 표시 {n}건 제거 (삭제 라인 {dropped}). 재감사합니다.\n")
            r = audit(a.root)

    if a.json:
        print(json.dumps({
            "total": r["total"], "real": r["real"],
            "stale": [list(k) for k in r["stale"]],
            "missing": [list(k) for k in r["missing"]],
            "unmarked": [list(k) for k in r["unmarked"]],
            "stub": [list(k) for k in r["stub"]],
            "ghost": [list(k) for k in r["ghost"]],
        }, ensure_ascii=False, indent=2))
    else:
        report(r)

    # CI 연결 대비 — 드리프트가 있으면 실패시킨다.
    # WEEK4-STUB 는 4주차까지 정상 상태이므로 실패 사유가 아니다.
    sys.exit(1 if (r["stale"] or r["unmarked"]) else 0)


if __name__ == "__main__":
    main()
