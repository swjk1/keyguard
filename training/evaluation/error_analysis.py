"""§24 — failure buckets. Thin wrapper over `evaluation.evaluate`.

All four evaluation entry points run the same single pass and print the same report,
because splitting them would mean four forward passes over the same data to produce
numbers that must agree with each other anyway. They exist as separate names because
§28's directory layout names them and because `--out` lets each write its own file.
"""
from evaluation.evaluate import main

if __name__ == "__main__":
    main()
