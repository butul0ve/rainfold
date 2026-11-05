#!/usr/bin/env python3
"""Prepare the bundled CC0 field recording from a local, verified Ronumi Ogg.

This development tool never downloads audio. See docs/audio/RAIN.md for provenance.
Requires ffmpeg; the shipped bytes were produced with 7.1.5-0+deb13u1.
"""
import argparse
import hashlib
from pathlib import Path
import subprocess

SOURCE_SHA256 = "1efaad3144350e0cfc2fc7cb537e7f6aa1e5341136e5af935a884e9e5ce73ded"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Local verified rain.ogg")
    parser.add_argument("output", type=Path, help="Output rain.wav")
    args = parser.parse_args()
    if hashlib.sha256(args.source.read_bytes()).hexdigest() != SOURCE_SHA256:
        parser.error("Source SHA-256 differs from the documented recording")
    subprocess.run([
        "ffmpeg", "-v", "error", "-y", "-i", str(args.source), "-t", "30",
        "-map_metadata", "-1", "-af", "volume=2", "-ar", "48000", "-ac", "1",
        "-c:a", "pcm_s16le", "-fflags", "+bitexact", "-flags:a", "+bitexact", str(args.output),
    ], check=True)
    print(hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()