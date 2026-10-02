"""The `flashcards` command.

  flashcards                   open the app in your browser (same as `gui`)
  flashcards gui               the same, with --port / --no-browser
  flashcards shortcut          put a Flashcards shortcut with its icon on the desktop (Windows)
  flashcards where             show where the workbook, settings and backups are
  flashcards use FILE          set the vocabulary workbook to review
  flashcards due               how many words are due now
  flashcards --version

argparse, not click: the command is this small and the standard library
keeps the dependency list to what the app itself needs.
"""

from __future__ import annotations

import argparse
import sys

from . import __version__, config, paths


def _gui(args) -> None:
    from .web.launcher import main as launch

    launch(port=args.port, open_browser=not args.no_browser)


def _shortcut(_args) -> None:
    from .shortcut import create_desktop_shortcut, icon_path

    try:
        path = create_desktop_shortcut()
    except RuntimeError as e:
        print(e, file=sys.stderr)
        print(f"To set the icon by hand: right-click a shortcut → Properties → Change Icon → {icon_path()}",
              file=sys.stderr)
        sys.exit(1)
    print(f"Created {path}. Double-click it to open Flashcards.")


def _where(_args) -> None:
    paths.adopt_legacy_config()  # so a fresh install reports the settings it will use
    ref = config.load_config()["reference_path"]
    print(f"Workbook:       {ref or '(not set: run `flashcards use FILE` or pick it in the app)'}"
          f"{'' if not ref or config.get_reference_path() else '  (missing!)'}")
    print(f"Data folder:    {paths.home()}")
    print(f"Backups:        {paths.backup_dir()}")
    print(f"Review log:     {paths.reviews_path()}")


def _use(args) -> None:
    try:
        path = config.set_reference_path(args.file)
    except ValueError as e:
        print(e, file=sys.stderr)
        sys.exit(1)
    print(f"Reviewing {path} from now on. Restart the app if it is open.")


def _due(_args) -> None:
    from .service import Service

    paths.adopt_legacy_config()
    info = Service().overview()
    if not info.get("reference"):
        print("No workbook set. Run `flashcards use FILE` first.", file=sys.stderr)
        sys.exit(1)
    if info.get("error"):
        print(info["error"], file=sys.stderr)
        sys.exit(1)
    later = f", {info['later']} scheduled later (next {info['next_due']})" if info["later"] else ""
    print(f"{info['due']} due now, {info['new']} new{later}.")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="flashcards", description="Review an Excel vocabulary workbook with spaced repetition.")
    parser.add_argument("--version", action="version", version=f"flashcards {__version__}")
    sub = parser.add_subparsers(dest="command")

    from .web.launcher import DEFAULT_PORT

    p = sub.add_parser("gui", help="open the app in your browser (the default)")
    p.add_argument("--port", type=int, default=DEFAULT_PORT,
                   help=f"port on 127.0.0.1 (default {DEFAULT_PORT}); another free one is used if taken")
    p.add_argument("--no-browser", action="store_true", help="don't open the browser")
    p.set_defaults(func=_gui)

    sub.add_parser("shortcut", help="put a shortcut with the app icon on the desktop (Windows)").set_defaults(func=_shortcut)
    sub.add_parser("where", help="show where the workbook, settings and backups are").set_defaults(func=_where)

    p = sub.add_parser("use", help="set the vocabulary workbook to review")
    p.add_argument("file", help="path to an .xlsx workbook")
    p.set_defaults(func=_use)

    sub.add_parser("due", help="how many words are due now").set_defaults(func=_due)

    args = parser.parse_args(argv)
    if args.command is None:
        args = parser.parse_args(["gui", *(argv or [])])
    args.func(args)


if __name__ == "__main__":
    main()
