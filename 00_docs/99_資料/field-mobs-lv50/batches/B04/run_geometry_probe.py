"""B04だけを限定javacし地形診断を実行する。Maven/サーバーは起動しない。"""
import argparse
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-classes", type=Path, required=True, help="既存のPlugin target/classes（読取のみ）")
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--m2", type=Path, required=True)
    parser.add_argument("--temp-root", type=Path, required=True, help="書込を許可された一時ディレクトリ")
    args = parser.parse_args()
    batch = Path(__file__).resolve().parent
    root = batch.parents[4]
    paper = args.m2 / "io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar"
    assert paper.exists() and args.baseline_classes.is_dir()
    jars = [paper]
    for group in ["org/jetbrains/annotations", "net/kyori", "net/md-5/bungeecord-chat", "com/google/guava/guava", "org/joml/joml"]:
        jars.extend(p for p in (args.m2 / group).rglob("*.jar") if not p.name.endswith("-sources.jar"))
    output = Path(tempfile.mkdtemp(prefix="b04-geometry-", dir=args.temp_root))
    classpath = ";".join(str(p) for p in [output, args.baseline_classes] + jars)
    source_root = root / "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/skill/field"
    sources = [source_root / name for name in ["StonebackBisonMobSkillExecutor.java", "StormRaptorMobSkillExecutor.java"]]
    compile_cmd = [str(args.jdk / "bin/javac.exe"), "-J-Duser.language=en", "-proc:none", "-encoding", "UTF-8", "-cp", classpath, "-d", str(output)] + [str(p) for p in sources + [batch / "GeometryProbe.java"]]
    for label, command in [("limited javac", compile_cmd), ("geometry probe", [str(args.jdk / "bin/java.exe"), "-Dfile.encoding=UTF-8", "-cp", classpath, "GeometryProbe"])]:
        result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace", timeout=45)
        print(f"{label}: exit {result.returncode}")
        print(result.stdout, end="")
        print(result.stderr, end="")
        if result.returncode:
            raise SystemExit(result.returncode)
    print(f"Temporary compiled classes: {output}")
    print("This validates B04 against cached baseline classes, not a full source build.")


if __name__ == "__main__":
    main()
