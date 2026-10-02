"""Tests for flashcards/web/app.py -- the local web API and its security checks (Flask test client)."""

import threading
from datetime import timezone

import pytest
from flask.testing import FlaskClient

from flashcards.service import Service
from flashcards.web.app import TOKEN_HEADER, create_app

TOKEN = "test-token"
HOST = "127.0.0.1:8767"


class LocalClient(FlaskClient):
    """Sends requests as the browser does, to http://127.0.0.1:8767 (the Host the app accepts)."""

    def open(self, *args, **kwargs):
        kwargs.setdefault("base_url", f"http://{HOST}")
        return super().open(*args, **kwargs)


def make_client(app):
    app.test_client_class = LocalClient
    return app.test_client()


@pytest.fixture
def picked():
    return {"path": None}


@pytest.fixture
def client(picked):
    app = create_app(Service(local_tz=timezone.utc, fuzz=False), TOKEN, {HOST, "localhost:8767"},
                     notice="moved", browse=lambda: picked["path"])
    return make_client(app)


def call(client, method, path, json=None):
    return client.open(path, method=method, json=json, headers={TOKEN_HEADER: TOKEN})


class TestSecurity:
    def test_api_needs_token(self, client):
        assert client.get("/api/overview").status_code == 403
        assert client.get("/api/overview", headers={TOKEN_HEADER: "wrong"}).status_code == 403

    def test_foreign_host_rejected(self, client):
        # DNS rebinding: a website's own name pointing at 127.0.0.1.
        assert client.get("/api/ping", base_url="http://evil.example:8767").status_code == 403

    def test_ping_without_token(self, client):
        assert client.get("/api/ping").get_json()["app"] == "flashcards"

    def test_page_carries_token_and_is_not_cached(self, client):
        r = client.get("/")
        assert TOKEN in r.get_data(as_text=True)
        assert r.headers["Cache-Control"] == "no-store"
        assert "frame-ancestors 'none'" in r.headers["Content-Security-Policy"]

    def test_static_files_served(self, client):
        for path in ["/static/app.js", "/static/style.css", "/static/logo.svg", "/favicon.ico"]:
            assert client.get(path).status_code == 200, path


def test_info_notice_shown_once(client):
    assert call(client, "GET", "/api/info").get_json()["notice"] == "moved"
    assert call(client, "GET", "/api/info").get_json()["notice"] is None


def test_browse_then_review_and_save(client, picked, workbook_path):
    assert call(client, "GET", "/api/overview").get_json()["reference"] is None
    picked["path"] = str(workbook_path)
    path = call(client, "POST", "/api/browse").get_json()["path"]
    o = call(client, "PUT", "/api/reference", {"path": path}).get_json()
    assert o["words"] == 4

    assert call(client, "POST", "/api/scope", {"mode": "mix", "size": 2}).get_json()["drawn"] == 2
    view = call(client, "POST", "/api/session", {"mode": "mix", "size": 2}).get_json()
    assert view["stage"] == "review"
    view = call(client, "POST", "/api/session/grade", {"grade": 5}).get_json()
    view = call(client, "POST", "/api/session/skip").get_json()
    assert view["stage"] == "summary" and len(view["rows"]) == 1
    saved = call(client, "POST", "/api/session/save").get_json()
    assert saved["stage"] == "saved" and saved["words"] == 1
    assert call(client, "DELETE", "/api/session").get_json()["stage"] == "setup"


def test_user_error_is_400_with_message(client, workbook_path):
    r = call(client, "PUT", "/api/reference", {"path": "/no/such/file.xlsx"})
    assert r.status_code == 400 and "Not a file" in r.get_json()["error"]
    call(client, "PUT", "/api/reference", {"path": str(workbook_path)})
    r = call(client, "POST", "/api/session/grade", {"grade": 3})
    assert r.status_code == 400 and "No session" in r.get_json()["error"]


def test_quit_calls_back():
    called = threading.Event()
    c = make_client(create_app(Service(), TOKEN, {HOST}, on_quit=called.set))
    assert c.post("/api/quit", headers={TOKEN_HEADER: TOKEN}).status_code == 200
    assert called.wait(2)
