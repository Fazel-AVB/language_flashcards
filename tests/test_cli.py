"""The `flashcards` command and the data folder (cli.py, paths.py)."""

import json

import pytest

from flashcards import cli, config, paths


def test_use_where_due(workbook_path, capsys):
    cli.main(["use", str(workbook_path)])
    assert config.get_reference_path() == str(workbook_path)
    cli.main(["where"])
    out = capsys.readouterr().out
    assert str(workbook_path) in out and str(paths.home()) in out
    cli.main(["due"])
    assert "0 due now, 4 new" in capsys.readouterr().out


def test_use_bad_file_exits(tmp_path, capsys):
    with pytest.raises(SystemExit):
        cli.main(["use", str(tmp_path / "missing.xlsx")])
    assert "Not a file" in capsys.readouterr().err


def test_due_without_workbook_exits(capsys):
    with pytest.raises(SystemExit):
        cli.main(["due"])


def test_version(capsys):
    with pytest.raises(SystemExit):
        cli.main(["--version"])
    assert capsys.readouterr().out.startswith("flashcards ")


def test_adopt_legacy_config_copies_once(tmp_path, monkeypatch):
    clone = tmp_path / "clone"
    clone.mkdir()
    (clone / "config.json").write_text(json.dumps({"reference_path": "x.xlsx", "prefs": {}}))
    monkeypatch.setattr(paths, "clone_dir", lambda: clone)

    assert paths.adopt_legacy_config() == clone / "config.json"
    assert config.load_config()["reference_path"] == "x.xlsx"
    # Never overwrites settings that already exist in the data folder.
    (clone / "config.json").write_text(json.dumps({"reference_path": "other.xlsx"}))
    assert paths.adopt_legacy_config() is None
    assert config.load_config()["reference_path"] == "x.xlsx"
