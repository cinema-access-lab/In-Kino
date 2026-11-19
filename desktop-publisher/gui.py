# SPDX-License-Identifier: Apache-2.0
"""Windows-friendly Tkinter publisher. Native accessibility testing remains pending."""

import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, ttk

from publish import upload

root = tk.Tk()
VERSION_FILE = Path(__file__).resolve().parent.parent / "VERSION"
version = VERSION_FILE.read_text(encoding="utf-8").strip() if VERSION_FILE.exists() else ""
root.title(f"In-Kino Publisher {version}".strip())
root.geometry("760x840")
root.configure(bg="#10141b")
style = ttk.Style(root)
style.theme_use("clam")
style.configure("TLabel", font=("Segoe UI", 14), background="#10141b", foreground="white")
style.configure("TEntry", font=("Segoe UI", 14), padding=10)
style.configure("TButton", font=("Segoe UI", 14, "bold"), padding=14)
style.configure("TCheckbutton", font=("Segoe UI", 13), background="#10141b", foreground="white")
frame = ttk.Frame(root, padding=24, style="TFrame")
frame.pack(fill="both", expand=True)
style.configure("TFrame", background="#10141b")
fields = {}


def line(label, default="", secret=False):
    ttk.Label(frame, text=label).pack(anchor="w", pady=(12, 2))
    v = tk.StringVar(value=default)
    e = ttk.Entry(frame, textvariable=v, show="*" if secret else "")
    e.pack(fill="x")
    fields[label] = v
    return v


server = line("Server origin", "http://127.0.0.1:8765")
token = line("Publisher token", secret=True)
title = line("Film title")
version = line("Presentation version", "Theatrical 24 fps")
fps = line("Frame rate", "24")
language = line("Language", "de")
existing = line("Existing film ID (optional)")
paths = {}


def chooser(label, types):
    row = ttk.Frame(frame)
    row.pack(fill="x", pady=8)
    v = tk.StringVar()
    paths[label] = v
    ttk.Label(row, text=label).pack(anchor="w")
    ttk.Entry(row, textvariable=v).pack(side="left", fill="x", expand=True)
    ttk.Button(
        row,
        text="Browse…",
        command=lambda: v.set(filedialog.askopenfilename(title=label, filetypes=types)),
    ).pack(side="right")


chooser("Reference film WAV — stays on this computer", [("WAV PCM", "*.wav")])
chooser("Audiodescription (optional)", [("Audio", "*.m4a *.wav")])
chooser("SDH (optional)", [("Subtitles", "*.srt *.vtt")])
post = tk.BooleanVar(value=False)
ttk.Checkbutton(
    frame, text="Publish automatically after successful validation", variable=post
).pack(anchor="w", pady=10)
status = tk.StringVar(value="Ready. No reference WAV is transmitted.")
ttk.Label(frame, textvariable=status, wraplength=690).pack(fill="x", pady=12)


def run():
    btn.configure(state="disabled")
    status.set("Generating fingerprint locally, then uploading derived assets…")

    def task():
        try:
            result = upload(
                server.get(),
                token.get(),
                title.get(),
                version.get(),
                float(fps.get()),
                language.get(),
                paths["Reference film WAV — stays on this computer"].get(),
                paths["Audiodescription (optional)"].get() or None,
                paths["SDH (optional)"].get() or None,
                existing.get().strip() or None,
                post.get(),
            )
            root.after(
                0,
                lambda: status.set(
                    "SUCCESS  Film ID: "
                    + result["film_id"]
                    + "  Version ID: "
                    + result["version_id"]
                    + "  Status: "
                    + result["state"]
                ),
            )
        except Exception as e:
            message = str(e)
            root.after(0, lambda: status.set("ERROR: " + message))
        finally:
            root.after(0, lambda: btn.configure(state="normal"))

    threading.Thread(target=task, daemon=True).start()


btn = ttk.Button(frame, text="GENERATE LOCALLY AND UPLOAD", command=run)
btn.pack(fill="x", pady=15)
root.mainloop()
