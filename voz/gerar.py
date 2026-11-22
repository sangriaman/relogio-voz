"""Gera um WAV por linha de frases.txt com a voz Piper pt_PT tugão (corre no GitHub Actions)."""
import subprocess, sys, unicodedata, re, wave, os
from pathlib import Path
from piper import PiperVoice

def slug(s: str) -> str:
    s = unicodedata.normalize("NFD", s.lower())
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    s = re.sub(r"[^a-z0-9 ]", " ", s)
    return re.sub(r"\s+", " ", s).strip().replace(" ", "_")

model, out = sys.argv[1], Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
voice = PiperVoice.load(model)
lines = [l.strip() for l in open(Path(__file__).with_name("frases.txt"), encoding="utf-8")]
lines = [l for l in lines if l and not l.startswith("#")]
seen = set()
for text in lines:
    name = slug(text)
    if not name or name in seen:
        continue
    seen.add(name)
    raw = out / f"{name}.raw.wav"
    with wave.open(str(raw), "wb") as w:
        voice.synthesize_wav(text, w)
    # cortar silêncio no início e no fim, mono 16 kHz 16 bits
    subprocess.run([
        "ffmpeg", "-loglevel", "error", "-y", "-i", str(raw),
        "-af", "silenceremove=start_periods=1:start_threshold=-50dB,areverse,"
               "silenceremove=start_periods=1:start_threshold=-50dB,areverse,aresample=16000",
        "-ac", "1", "-sample_fmt", "s16", "-map_metadata", "-1", "-fflags", "+bitexact",
        str(out / f"{name}.wav"),
    ], check=True)
    raw.unlink()
print(f"{len(seen)} gravações geradas")
