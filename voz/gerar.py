"""Gera as gravações do Tricky com a voz Piper pt_PT tugão (corre no GitHub Actions).

Em vez de palavras soltas, gera bocados grandes e naturais ("Em Ronfe estão agora",
"cinco graus", "São dez horas", "e vinte e cinco da tarde"…), para que cada resposta
tenha poucas junções. A app junta-os sem pausas.
"""
import re, subprocess, sys, unicodedata, wave
from pathlib import Path
from piper import PiperVoice

UNITS = ["zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove",
         "dez", "onze", "doze", "treze", "catorze", "quinze", "dezasseis", "dezassete", "dezoito", "dezanove"]
TENS = ["", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa"]


def w(n: int, fem: bool = False) -> str:
    """Igual a Words.of() na app."""
    if n < 0:
        return "menos " + w(-n, fem)
    if n < 20:
        s = UNITS[n]
    elif n < 100:
        s = TENS[n // 10] + (" e " + UNITS[n % 10] if n % 10 else "")
    elif n == 100:
        s = "cem"
    else:
        s = str(n)
    if fem:
        s = re.sub(r"\bum$", "uma", s)
        s = re.sub(r"\bdois$", "duas", s)
    return s


def slug(s: str) -> str:
    s = unicodedata.normalize("NFD", s.lower())
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    s = re.sub(r"[^a-z0-9 ]", " ", s)
    return re.sub(r"\s+", " ", s).strip().replace(" ", "_")


CITIES = ["Ronfe", "Guimarães", "Braga", "Porto", "Lisboa", "Coimbra", "Aveiro", "Faro", "Viseu",
          "Leiria", "Évora", "Setúbal", "Fafe", "Vizela", "Barcelos", "Vila Nova de Famalicão",
          "Póvoa de Varzim", "Vila do Conde", "Viana do Castelo", "Santo Tirso", "Felgueiras",
          "Amarante", "Funchal", "Ponta Delgada", "Madrid", "Paris", "Londres"]
DAYS = ["segunda-feira", "terça-feira", "quarta-feira", "quinta-feira", "sexta-feira", "sábado", "domingo"]
MONTHS = ["janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto",
          "setembro", "outubro", "novembro", "dezembro"]
PERIODS = ["", " da manhã", " da tarde", " da noite"]


def phrases() -> list[str]:
    out = []
    # frases fixas e palavras soltas (reserva) de frases.txt
    for l in open(Path(__file__).with_name("frases.txt"), encoding="utf-8"):
        l = l.strip()
        if l and not l.startswith("#"):
            out.append(l)
    # --- tempo
    for c in CITIES:
        out += [f"Em {c} estão agora", f"Em {c} está agora", f"Amanhã em {c}", f"Depois de amanhã em {c}"]
    for t in range(-15, 46):
        out.append(f"{w(t)} {'grau' if abs(t) == 1 else 'graus'}")
        out += [f"Hoje a máxima é de {w(t)}", f"A máxima é de {w(t)}", f"e a mínima de {w(t)}"]
    for r in range(20, 101, 5):
        out.append(f"Com {w(r)} por cento de probabilidade de chuva")
    # --- horas
    out += ["É meia-noite", "É meio-dia", "É uma hora"]
    for h in range(2, 13):
        out.append(f"São {w(h, True)} horas")
    for p in PERIODS:
        if p:
            out.append(p.strip())
        for m in range(1, 60):
            mm = "e um minuto" if m == 1 else "e meia" if m == 30 else f"e {w(m)}"
            out.append(mm + p)
    # --- data
    for d in DAYS:
        out.append(f"Hoje é {d}")
    for n in range(1, 32):
        out.append(f"{w(n)} de")
    # --- alarme
    for h in range(1, 13):
        out.append(f"Alarme marcado para {'a' if h == 1 else 'as'} {w(h, True)}")
    # --- temporizador
    for n in range(1, 61):
        out.append(f"Temporizador de {'um minuto' if n == 1 else w(n) + ' minutos'} a contar.")
    for n in range(2, 13):
        out.append(f"Temporizador de {w(n, True)} horas a contar.")
    out.append("Temporizador de uma hora a contar.")
    return out


def main():
    model, out = sys.argv[1], Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    voice = PiperVoice.load(model)
    todo, seen = [], set()
    for text in phrases():
        name = slug(text)
        if name and name not in seen:
            seen.add(name)
            todo.append((text, name))

    def synth(item):
        text, name = item
        raw = out / f"{name}.raw.wav"
        with wave.open(str(raw), "wb") as f:
            voice.synthesize_wav(text, f)
        # cortar silêncio no início e no fim; mono 16 kHz 16 bits
        subprocess.run([
            "ffmpeg", "-loglevel", "error", "-y", "-i", str(raw),
            "-af", "silenceremove=start_periods=1:start_threshold=-50dB,areverse,"
                   "silenceremove=start_periods=1:start_threshold=-50dB,areverse,aresample=16000",
            "-ac", "1", "-sample_fmt", "s16", "-map_metadata", "-1", "-fflags", "+bitexact",
            str(out / f"{name}.wav"),
        ], check=True)
        raw.unlink()

    for item in todo:
        synth(item)
    print(f"{len(todo)} gravações geradas")


if __name__ == "__main__":
    main()
