"""Create a desktop shortcut to the app (flashcards-gui.exe) with its icon. Windows only.

pip makes .venv\\Scripts\\flashcards-gui.exe with a generic icon, and an
.exe's own icon can't be changed without rebuilding it. A shortcut can point
at the .exe and carry any icon, so that is what `flashcards shortcut` makes.

Windows has no Python-standard way to write a .lnk file; its own scripting
object (WScript.Shell, via PowerShell) does, and needs no extra package.
Same approach as tracker/shortcut.py in the time_tracker project.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from importlib import resources
from pathlib import Path

SHORTCUT_NAME = "Flashcards.lnk"

# Paths are handed over in environment variables, not pasted into the script
# text, so quotes or spaces in a folder name can't break the PowerShell code.
PS_SCRIPT = r"""
$desktop = [Environment]::GetFolderPath('Desktop')
$path = Join-Path $desktop $env:FC_NAME
$s = (New-Object -ComObject WScript.Shell).CreateShortcut($path)
$s.TargetPath = $env:FC_TARGET
$s.WorkingDirectory = Split-Path $env:FC_TARGET
$s.IconLocation = $env:FC_ICON + ',0'
$s.Description = 'Flashcards: review your vocabulary workbook'
$s.Save()
Write-Output $path
"""


def gui_exe() -> Path:
    # sys.executable is .venv\Scripts\python.exe when the command runs from
    # its venv; pip puts flashcards-gui.exe next to it.
    exe = Path(sys.executable).with_name("flashcards-gui.exe")
    if exe.exists():
        return exe
    found = shutil.which("flashcards-gui")
    if found:
        return Path(found)
    raise RuntimeError("flashcards-gui.exe not found. Run `python -m pip install -e .` in the clone first.")


def icon_path() -> Path:
    # A real file on disk with a normal or editable install, which is what the shortcut needs.
    return Path(str(resources.files("flashcards.web").joinpath("static", "flashcards.ico")))


def create_desktop_shortcut() -> Path:
    """Create (or replace) 'Flashcards.lnk' on the desktop. Returns its path."""
    if sys.platform != "win32":
        raise RuntimeError("`flashcards shortcut` works on Windows only. Elsewhere, start the app with `flashcards`.")
    env = {**os.environ, "FC_NAME": SHORTCUT_NAME, "FC_TARGET": str(gui_exe()), "FC_ICON": str(icon_path())}
    result = subprocess.run(
        ["powershell", "-NoProfile", "-NonInteractive", "-Command", PS_SCRIPT],
        env=env, capture_output=True, text=True, errors="replace",
    )
    if result.returncode != 0:
        raise RuntimeError(f"PowerShell couldn't create the shortcut: {result.stderr.strip()}")
    return Path(result.stdout.strip())
