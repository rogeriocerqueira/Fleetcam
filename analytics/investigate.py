"""Generate the synthetic incident dataset and run every investigation query.

    python analytics/investigate.py           # print results
    python analytics/investigate.py --check   # also assert the findings quoted in the postmortem (used in CI)
"""
import argparse
import pathlib
import re

import duckdb

HERE = pathlib.Path(__file__).parent


def load_queries() -> dict[str, str]:
    text = (HERE / "investigation.sql").read_text()
    blocks = re.split(r"^-- name: (\w+)\s*$", text, flags=re.M)
    return {name: sql.strip() for name, sql in zip(blocks[1::2], blocks[2::2])}


def show(con: duckdb.DuckDBPyConnection, name: str, sql: str):
    rel = con.sql(sql)
    print(f"\n=== {name} ===")
    rel.show(max_width=200)
    return rel.fetchall()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="assert postmortem findings")
    args = parser.parse_args()

    data_dir = HERE / "data"
    data_dir.mkdir(exist_ok=True)
    con = duckdb.connect(str(data_dir / "incident.duckdb"))
    con.execute("set threads = 1")  # deterministic random()
    con.execute("set timezone = 'UTC'")
    con.execute((HERE / "generate_dataset.sql").read_text())

    results = {name: show(con, name, sql) for name, sql in load_queries().items()}

    if args.check:
        by_fw = {row[0]: row for row in results["q2_slo_by_firmware"]}
        assert by_fw["2.3.1"][2] == 0.0, "skewed firmware should fail the naive SLO completely"
        assert by_fw["2.2.0"][2] > 90, "healthy firmware should be fine"

        decomposed = {row[0]: row for row in results["q3_decompose_latency"]}
        assert decomposed["2.3.1"][1] < 10, "buffer delay is normal: not a connectivity problem"
        assert abs(decomposed["2.3.1"][3] - 3 * 3600) < 5, "offset is ~3h: clock bug"

        assert results["q4_offset_histogram"][0][0] == 180, "spike at exactly 180 min"

        robust = {row[0]: row[1] for row in results["q5_slo_robust_formula"]}
        assert abs(robust["2.3.1"] - robust["2.2.0"]) < 1.5, "robust formula treats both firmwares equally"
        print("\nAll postmortem findings reproduced ✔")


if __name__ == "__main__":
    main()
