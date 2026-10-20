"""Safe Firebase Realtime Database remote-control CLI for SleepTrackerSimulator."""

from __future__ import annotations

import argparse
import os
import re
import sys
import time
from dataclasses import dataclass
from typing import Any, Callable, Mapping, Protocol, Sequence
from urllib.parse import urlparse

DEFAULT_PROJECT_ID = "sleeptrackersimulator"
DEFAULT_DATABASE_URL = "https://sleeptrackersimulator-default-rtdb.europe-west1.firebasedatabase.app"
DEVICE_ID = "sleep-tracker-simulator"
UID_ENV = "SLEEPTRACKER_TARGET_UID"
PROJECT_ENV = "SLEEPTRACKER_FIREBASE_PROJECT_ID"
DATABASE_URL_ENV = "SLEEPTRACKER_FIREBASE_DATABASE_URL"
UID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
FIELDS = {"flashlightEnabled", "vibrationEnabled"}

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_AUTH = 3
EXIT_FIREBASE = 4


class CliError(Exception):
    def __init__(self, message: str, exit_code: int = EXIT_FIREBASE) -> None:
        super().__init__(message)
        self.exit_code = exit_code


class ConfigurationConflict(CliError):
    pass


class DatabaseBackend(Protocol):
    detected_project_id: str | None

    def get(self, path: str) -> Any: ...
    def update(self, path: str, values: Mapping[str, bool]) -> None: ...
    def transaction(self, path: str, updater: Callable[[Any], Any]) -> Any: ...


@dataclass(frozen=True)
class Config:
    project_id: str
    database_url: str
    uid: str | None
    verbose: bool = False

    @property
    def control_path(self) -> str:
        if self.uid is None:
            raise CliError(
                f"Target UID is required. Set {UID_ENV} or pass --uid.",
                EXIT_USAGE,
            )
        return build_control_path(self.uid)


def validate_uid(uid: str) -> str:
    candidate = uid.strip()
    if not UID_PATTERN.fullmatch(candidate):
        raise CliError(
            "Invalid target UID. Expected 1-128 characters containing only letters, digits, '_' or '-'.",
            EXIT_USAGE,
        )
    return candidate


def validate_database_url(database_url: str) -> str:
    value = database_url.strip().rstrip("/")
    parsed = urlparse(value)
    allowed_host = parsed.hostname and (
        parsed.hostname.endswith(".firebasedatabase.app")
        or parsed.hostname.endswith(".firebaseio.com")
    )
    if parsed.scheme != "https" or not allowed_host or parsed.path or parsed.query or parsed.fragment:
        raise CliError("Invalid Firebase Realtime Database URL.", EXIT_USAGE)
    return value


def build_control_path(uid: str) -> str:
    safe_uid = validate_uid(uid)
    path = f"/users/{safe_uid}/control/{DEVICE_ID}"
    if path.count("/") != 4 or not path.startswith("/users/"):
        raise CliError("Refusing to use an unsafe Firebase path.", EXIT_USAGE)
    return path


def parse_switch(value: str) -> bool:
    return value == "on"


def validate_control_node(node: Any, *, allow_missing: bool = False) -> dict[str, Any] | None:
    if node is None and allow_missing:
        return None
    if node is None:
        raise ConfigurationConflict("Control node does not exist. Run the init command first.")
    if not isinstance(node, dict):
        raise ConfigurationConflict("Control node has an invalid type; expected an object. No data was changed.")
    for field in FIELDS:
        if field in node and type(node[field]) is not bool:
            raise ConfigurationConflict(
                f"Field {field} has an invalid type; expected Boolean. No data was changed."
            )
    return node


def require_complete_control_node(node: Any) -> dict[str, Any]:
    validated = validate_control_node(node)
    assert validated is not None
    missing = sorted(FIELDS.difference(validated))
    if missing:
        raise ConfigurationConflict(
            f"Control node is incomplete (missing: {', '.join(missing)}). Run init first."
        )
    return validated


class FirebaseAdminBackend:
    def __init__(self, config: Config) -> None:
        try:
            import firebase_admin
            from firebase_admin import credentials, db
            import google.auth
        except ImportError as error:
            raise CliError(
                "firebase-admin is not installed. Run: py -m pip install -r tools\\requirements-firebase-control.txt",
                EXIT_AUTH,
            ) from error

        try:
            _, detected_project = google.auth.default()
            self.detected_project_id = detected_project
            try:
                app = firebase_admin.get_app("sleeptracker-control-cli")
            except ValueError:
                app = firebase_admin.initialize_app(
                    credentials.ApplicationDefault(),
                    {"databaseURL": config.database_url, "projectId": config.project_id},
                    name="sleeptracker-control-cli",
                )
            self._db = db
            self._app = app
        except Exception as error:
            raise map_firebase_error(error, "Firebase initialization") from error

    def _reference(self, path: str):
        return self._db.reference(path, app=self._app)

    def get(self, path: str) -> Any:
        try:
            return self._reference(path).get()
        except Exception as error:
            raise map_firebase_error(error, "Firebase read") from error

    def update(self, path: str, values: Mapping[str, bool]) -> None:
        try:
            self._reference(path).update(dict(values))
        except Exception as error:
            raise map_firebase_error(error, "Firebase write") from error

    def transaction(self, path: str, updater: Callable[[Any], Any]) -> Any:
        try:
            return self._reference(path).transaction(updater)
        except ConfigurationConflict:
            raise
        except Exception as error:
            cause = error.__cause__
            if isinstance(cause, ConfigurationConflict):
                raise cause
            raise map_firebase_error(error, "Firebase transaction") from error


def map_firebase_error(error: Exception, operation: str) -> CliError:
    text = str(error).lower()
    kind = error.__class__.__name__
    if "credential" in text or "default credentials" in text or "unauthorized" in text:
        return CliError(
            f"{operation} failed: credentials are unavailable or invalid. Configure Application Default Credentials.",
            EXIT_AUTH,
        )
    if "permission" in text or "forbidden" in text or "403" in text:
        return CliError(
            f"{operation} failed: permission denied. Verify the service account IAM role and target project.",
            EXIT_AUTH,
        )
    if "timeout" in text or "timed out" in text:
        return CliError(f"{operation} timed out. Check the network and retry once.")
    return CliError(f"{operation} failed ({kind}). Check credentials, project ID, database URL and network.")


def emit_verbose(config: Config, **details: Any) -> None:
    if not config.verbose:
        return
    print("Diagnostics:")
    print(f"  Project ID: {config.project_id}")
    print(f"  Database URL: {config.database_url}")
    print(f"  Target UID: {config.uid or 'NOT SET'}")
    if config.uid:
        print(f"  Control path: {config.control_path}")
    for key, value in details.items():
        print(f"  {key.replace('_', ' ').title()}: {value}")


def command_status(config: Config, backend: DatabaseBackend) -> int:
    started = time.perf_counter()
    node = validate_control_node(backend.get(config.control_path))
    assert node is not None
    print("Firebase Remote Control")
    print(f"Project: {config.project_id}")
    print("Connection: OK\n")
    print(f"Flashlight: {format_state(node.get('flashlightEnabled'))}")
    print(f"Vibration: {format_state(node.get('vibrationEnabled'))}\n")
    print("Database read: SUCCESS")
    print("This is the desired state stored in Firebase, not verified physical actuator state.")
    emit_verbose(config, elapsed_ms=f"{(time.perf_counter() - started) * 1000:.1f}")
    return EXIT_OK


def command_switch(config: Config, backend: DatabaseBackend, actuator: str, enabled: bool) -> int:
    field = "flashlightEnabled" if actuator == "flashlight" else "vibrationEnabled"
    started = time.perf_counter()
    require_complete_control_node(backend.get(config.control_path))
    backend.update(config.control_path, {field: enabled})
    readback = backend.get(f"{config.control_path}/{field}")
    if type(readback) is not bool or readback is not enabled:
        raise CliError(f"Firebase readback mismatch for {field}; requested {str(enabled).lower()}.")
    print(f"Command: {actuator} {'on' if enabled else 'off'}")
    print(f"Target: {DEVICE_ID}\n")
    print("Firebase write: SUCCESS")
    print(f"Firebase readback: {str(readback).lower()}\n")
    print("Remote command stored successfully.")
    print("Physical device execution: NOT VERIFIED")
    emit_verbose(
        config,
        field=field,
        requested_value=str(enabled).lower(),
        write_result="SUCCESS",
        readback=str(readback).lower(),
        elapsed_ms=f"{(time.perf_counter() - started) * 1000:.1f}",
    )
    return EXIT_OK


def command_reset(config: Config, backend: DatabaseBackend) -> int:
    started = time.perf_counter()
    values = {"flashlightEnabled": False, "vibrationEnabled": False}
    require_complete_control_node(backend.get(config.control_path))
    backend.update(config.control_path, values)
    node = validate_control_node(backend.get(config.control_path))
    if node is None or any(node.get(field) is not False for field in FIELDS):
        raise CliError("Firebase reset readback mismatch.")
    print("Command: reset")
    print("Firebase write: SUCCESS")
    print("Firebase readback: flashlight=false, vibration=false")
    print("Physical device execution: NOT VERIFIED")
    emit_verbose(config, fields="flashlightEnabled,vibrationEnabled", elapsed_ms=f"{(time.perf_counter() - started) * 1000:.1f}")
    return EXIT_OK


def command_init(
    config: Config,
    backend: DatabaseBackend,
    assume_yes: bool,
    input_fn: Callable[[str], str] = input,
) -> int:
    path = config.control_path
    current = validate_control_node(backend.get(path), allow_missing=True)
    missing = FIELDS if current is None else FIELDS.difference(current)
    if not missing:
        print("Control node is already initialized. No data was changed.")
        return EXIT_OK
    print(f"Project ID: {config.project_id}")
    print(f"Database URL: {config.database_url}")
    print(f"Target UID: {config.uid}")
    print(f"Fields to initialize: {', '.join(sorted(missing))}")
    if not assume_yes and input_fn("Create missing fields with false? Type YES to continue: ").strip() != "YES":
        raise CliError("Initialization cancelled. No data was changed.", EXIT_USAGE)

    def initialize(node: Any) -> dict[str, Any]:
        validated = validate_control_node(node, allow_missing=True)
        result = {} if validated is None else dict(validated)
        for field in FIELDS:
            result.setdefault(field, False)
        return result

    result = validate_control_node(backend.transaction(path, initialize))
    if result is None or not FIELDS.issubset(result):
        raise CliError("Initialization readback failed.")
    print("Firebase initialization: SUCCESS")
    print("Existing valid values were preserved.")
    print("Physical device execution: NOT VERIFIED")
    return EXIT_OK


def command_doctor(config: Config, backend: DatabaseBackend) -> int:
    failures = 0
    print("Firebase Remote Control Doctor")
    print(f"Python: {sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro} " + ("OK" if sys.version_info >= (3, 11) else "FAILED"))
    if sys.version_info < (3, 11):
        failures += 1
    print("firebase-admin: OK")
    print("Credentials: OK")
    detected = backend.detected_project_id
    if detected and detected != config.project_id:
        print(f"Project ID: FAILED (credentials resolve to {detected}, expected {config.project_id})")
        failures += 1
    else:
        print(f"Project ID: OK ({config.project_id})")
    print(f"Database URL: OK ({config.database_url})")
    if config.uid is None:
        print(f"Target UID: FAILED (set {UID_ENV} or pass --uid)")
        return EXIT_USAGE
    print("Target UID: OK")
    try:
        node = validate_control_node(backend.get(config.control_path), allow_missing=True)
        print("Realtime Database read: SUCCESS")
        if node is None:
            print("Control node: MISSING")
            print("Safe next step: run init; it creates only missing Boolean fields after confirmation.")
            failures += 1
        else:
            missing = sorted(FIELDS.difference(node))
            if missing:
                print(f"Control fields: FAILED (missing: {', '.join(missing)}); run init")
                failures += 1
            else:
                print("Control fields: OK (both values are Boolean)")
    except CliError as error:
        print(f"Realtime Database read: FAILED ({error})")
        failures += 1
    print("Doctor mode: READ ONLY")
    return EXIT_OK if failures == 0 else EXIT_FIREBASE


def unavailable_doctor(config: Config, error: CliError) -> int:
    print("Firebase Remote Control Doctor")
    print(f"Python: {sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro} " + ("OK" if sys.version_info >= (3, 11) else "FAILED"))
    dependency_missing = "not installed" in str(error)
    print(f"firebase-admin: {'FAILED' if dependency_missing else 'OK'}")
    print(f"Credentials/Firebase initialization: FAILED ({error})")
    print(f"Project ID: NOT VERIFIED ({config.project_id})")
    print(f"Database URL: NOT VERIFIED ({config.database_url})")
    print("Realtime Database read: NOT ATTEMPTED")
    print("Control node: NOT VERIFIED")
    print("Doctor mode: READ ONLY")
    return error.exit_code


def format_state(value: Any) -> str:
    if value is True:
        return "ON"
    if value is False:
        return "OFF"
    return "MISSING"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Control SleepTrackerSimulator actuators through Firebase RTDB.")
    parser.add_argument("--uid", default=os.getenv(UID_ENV), help=f"Target anonymous Firebase UID (or {UID_ENV}).")
    parser.add_argument("--project-id", default=os.getenv(PROJECT_ENV, DEFAULT_PROJECT_ID), help=f"Firebase project ID (or {PROJECT_ENV}).")
    parser.add_argument("--database-url", default=os.getenv(DATABASE_URL_ENV, DEFAULT_DATABASE_URL), help=f"Realtime Database URL (or {DATABASE_URL_ENV}).")
    parser.add_argument("--verbose", action="store_true", help="Print non-secret diagnostics.")
    parser.add_argument("--yes", action="store_true", help="Confirm init non-interactively.")
    subparsers = parser.add_subparsers(dest="command", required=True)
    for actuator in ("flashlight", "vibration"):
        command = subparsers.add_parser(actuator)
        command.add_argument("state", choices=("on", "off"))
    subparsers.add_parser("status")
    subparsers.add_parser("reset")
    subparsers.add_parser("doctor")
    subparsers.add_parser("init")
    return parser


def run(
    argv: Sequence[str] | None = None,
    backend_factory: Callable[[Config], DatabaseBackend] = FirebaseAdminBackend,
    input_fn: Callable[[str], str] = input,
) -> int:
    args = build_parser().parse_args(argv)
    config = Config(
        project_id=args.project_id.strip(),
        database_url=validate_database_url(args.database_url),
        uid=validate_uid(args.uid) if args.uid is not None else None,
        verbose=args.verbose,
    )
    if not config.project_id or "/" in config.project_id:
        raise CliError("Invalid Firebase Project ID.", EXIT_USAGE)
    if args.command != "doctor" and config.uid is None:
        raise CliError(f"Target UID is required. Set {UID_ENV} or pass --uid.", EXIT_USAGE)
    try:
        backend = backend_factory(config)
    except CliError as error:
        if args.command == "doctor":
            return unavailable_doctor(config, error)
        raise
    if args.command != "doctor" and backend.detected_project_id and backend.detected_project_id != config.project_id:
        raise CliError(
            f"Credentials resolve to project {backend.detected_project_id}, expected {config.project_id}. Refusing the operation.",
            EXIT_AUTH,
        )
    if args.command in ("flashlight", "vibration"):
        return command_switch(config, backend, args.command, parse_switch(args.state))
    if args.command == "status":
        return command_status(config, backend)
    if args.command == "reset":
        return command_reset(config, backend)
    if args.command == "init":
        return command_init(config, backend, args.yes, input_fn)
    return command_doctor(config, backend)


def main() -> int:
    try:
        return run()
    except KeyboardInterrupt:
        print("Interrupted. No further operation was attempted.", file=sys.stderr)
        return 130
    except CliError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return error.exit_code


if __name__ == "__main__":
    raise SystemExit(main())
