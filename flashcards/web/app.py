"""The app's web server: one page (static/index.html) plus a small JSON API.

Each /api route parses the request and calls one method of service.Service;
no rules about the data live here.

It listens on 127.0.0.1 only (see launcher.py), so no other computer can
reach it. Two more checks keep *websites* open in your browser from using it
(any page can send requests to localhost):

- Host header must be 127.0.0.1:<port> or localhost:<port>. This blocks "DNS
  rebinding", where a website points its own domain name at 127.0.0.1.
- Every /api call must carry the X-FC-Token header. The token is random per
  launch and only written into the page this server serves; other websites
  can't read that page, and a custom header can't be sent cross-site without
  the server's permission (which it never gives).

Do not remove either check: without them any website could read your
vocabulary or overwrite your workbook while the app is open.
Same design as time_tracker's tracker/web/app.py.
"""

from __future__ import annotations

import os
import sys
import threading
import time
from collections.abc import Callable
from importlib import resources

from flask import Flask, Response, abort, jsonify, request

from .. import __version__, paths
from ..service import Service, UserError, browse_for_file

TOKEN_HEADER = "X-FC-Token"
TOKEN_PLACEHOLDER = "__FC_TOKEN__"


def create_app(
    service: Service,
    token: str,
    allowed_hosts: set[str],
    on_quit: Callable[[], None] | None = None,
    notice: str | None = None,
    browse: Callable[[], str | None] = browse_for_file,
) -> Flask:
    """notice: a one-time message for the page (e.g. "your settings were moved to ...").
    browse: the file picker; replaced in tests."""
    app = Flask(__name__, static_folder="static", static_url_path="/static")
    # Read by the launcher's idle watchdog (launcher.py) to exit after the page is closed.
    app.extensions["flashcards"] = {"last_seen": time.time(), "notice": notice}

    def body() -> dict:
        return request.get_json(force=True, silent=True) or {}

    @app.before_request
    def guard():
        if request.host not in allowed_hosts:
            abort(403)
        if request.path.startswith("/api/") and request.path != "/api/ping":
            if request.headers.get(TOKEN_HEADER) != token:
                abort(403)
        app.extensions["flashcards"]["last_seen"] = time.time()

    @app.after_request
    def headers(resp):
        # No other site may show this page in a frame (tricking you into
        # clicking its buttons), and the page loads nothing from the internet.
        resp.headers["X-Frame-Options"] = "DENY"
        resp.headers["Content-Security-Policy"] = (
            "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
            "frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        )
        return resp

    @app.errorhandler(UserError)
    def user_error(e):
        return jsonify(error=str(e)), 400

    @app.get("/")
    def index():
        html = resources.files("flashcards.web").joinpath("static/index.html").read_text(encoding="utf-8")
        resp = Response(html.replace(TOKEN_PLACEHOLDER, token), mimetype="text/html")
        resp.headers["Cache-Control"] = "no-store"  # the token changes every launch
        return resp

    @app.get("/favicon.ico")
    def favicon():
        return app.send_static_file("flashcards.ico")

    @app.get("/api/ping")
    def ping():
        # No token needed: the launcher uses it to find an already-running app,
        # and the page as a heartbeat (keeps the idle watchdog from stopping it).
        return jsonify(app="flashcards", version=__version__)

    @app.get("/api/info")
    def info():
        state = app.extensions["flashcards"]
        msg, state["notice"] = state.get("notice"), None  # shown once
        return jsonify(version=__version__, data_folder=str(paths.home()), notice=msg,
                       can_open_folder=sys.platform == "win32")

    # --- workbook ---------------------------------------------------------

    @app.get("/api/overview")
    def overview():
        return jsonify(service.overview())

    @app.put("/api/reference")
    def set_reference():
        return jsonify(service.set_reference(str(body().get("path", ""))))

    @app.post("/api/browse")
    def browse_():
        return jsonify(path=browse())

    # --- session ----------------------------------------------------------

    @app.post("/api/scope")
    def scope():
        return jsonify(service.scope(body()))

    @app.get("/api/session")
    def session_view():
        return jsonify(service.view())

    @app.post("/api/session")
    def start():
        return jsonify(service.start(body()))

    @app.post("/api/session/grade")
    def grade():
        return jsonify(service.grade(body().get("grade")))

    @app.post("/api/session/skip")
    def skip():
        return jsonify(service.skip())

    @app.post("/api/session/end")
    def end():
        return jsonify(service.end())

    @app.post("/api/session/save")
    def save():
        return jsonify(service.write())

    @app.delete("/api/session")
    def discard():
        return jsonify(service.discard())

    # --- app ----------------------------------------------------------------

    @app.post("/api/open-backups")
    def open_backups():
        # Windows only (os.startfile): opens the folder in Explorer.
        folder = paths.backup_dir()
        if sys.platform != "win32":
            raise UserError(f"Open this folder yourself: {folder}")
        folder.mkdir(parents=True, exist_ok=True)
        os.startfile(folder)  # noqa: S606 - a fixed local folder, never user input
        return jsonify(ok=True)

    @app.post("/api/quit")
    def quit_():
        if on_quit is not None:
            # After a moment, so this response still reaches the page.
            threading.Timer(0.3, on_quit).start()
        return jsonify(ok=True)

    return app
