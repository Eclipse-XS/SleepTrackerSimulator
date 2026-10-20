from __future__ import annotations

import contextlib
import io
import unittest
from copy import deepcopy
from unittest.mock import patch

from tools import firebase_control as cli


UID = "1awU9VJr6tXyWcN3LWmXT6MnbIC3"
PATH = f"/users/{UID}/control/{cli.DEVICE_ID}"


class FakeBackend:
    detected_project_id = cli.DEFAULT_PROJECT_ID

    def __init__(self, node=None):
        self.data = {PATH: deepcopy(node)} if node is not _ABSENT else {}
        self.updates = []
        self.transactions = []

    def get(self, path):
        if path in self.data:
            return deepcopy(self.data[path])
        parent, _, field = path.rpartition("/")
        node = self.data.get(parent)
        return deepcopy(node.get(field)) if isinstance(node, dict) else None

    def update(self, path, values):
        self.updates.append((path, dict(values)))
        node = self.data.setdefault(path, {})
        node.update(values)

    def transaction(self, path, updater):
        self.transactions.append(path)
        result = updater(deepcopy(self.data.get(path)))
        self.data[path] = deepcopy(result)
        return deepcopy(result)


_ABSENT = object()


def invoke(arguments, backend, answer="YES"):
    stdout = io.StringIO()
    with contextlib.redirect_stdout(stdout):
        code = cli.run(
            ["--uid", UID, *arguments],
            backend_factory=lambda _: backend,
            input_fn=lambda _: answer,
        )
    return code, stdout.getvalue()


class FirebaseControlTest(unittest.TestCase):
    def test_builds_exact_scoped_path(self):
        self.assertEqual(PATH, cli.build_control_path(UID))

    def test_rejects_empty_or_unsafe_uid(self):
        for uid in ("", "../sessions", "user/name", "a.b", "x#y", "a" * 129):
            with self.subTest(uid=uid), self.assertRaises(cli.CliError):
                cli.validate_uid(uid)

    def test_flashlight_on_updates_only_flashlight_and_verifies_readback(self):
        backend = FakeBackend({"flashlightEnabled": False, "vibrationEnabled": True})
        code, output = invoke(["flashlight", "on"], backend)
        self.assertEqual(cli.EXIT_OK, code)
        self.assertEqual([(PATH, {"flashlightEnabled": True})], backend.updates)
        self.assertTrue(backend.data[PATH]["vibrationEnabled"])
        self.assertIn("Physical device execution: NOT VERIFIED", output)

    def test_vibration_off_updates_only_vibration(self):
        backend = FakeBackend({"flashlightEnabled": True, "vibrationEnabled": True})
        invoke(["vibration", "off"], backend)
        self.assertEqual([(PATH, {"vibrationEnabled": False})], backend.updates)
        self.assertTrue(backend.data[PATH]["flashlightEnabled"])

    def test_reset_updates_both_fields_without_replacing_node(self):
        backend = FakeBackend({"flashlightEnabled": True, "vibrationEnabled": True, "preserved": 7})
        invoke(["reset"], backend)
        self.assertEqual({"flashlightEnabled": False, "vibrationEnabled": False}, backend.updates[0][1])
        self.assertEqual(7, backend.data[PATH]["preserved"])

    def test_status_is_read_only_and_explains_desired_state(self):
        backend = FakeBackend({"flashlightEnabled": True, "vibrationEnabled": False})
        code, output = invoke(["status"], backend)
        self.assertEqual(cli.EXIT_OK, code)
        self.assertFalse(backend.updates)
        self.assertIn("Flashlight: ON", output)
        self.assertIn("desired state", output)

    def test_status_rejects_missing_node(self):
        with self.assertRaisesRegex(cli.ConfigurationConflict, "does not exist"):
            invoke(["status"], FakeBackend(_ABSENT))

    def test_switch_rejects_missing_node_without_creating_it(self):
        backend = FakeBackend(_ABSENT)
        with self.assertRaises(cli.ConfigurationConflict):
            invoke(["flashlight", "on"], backend)
        self.assertFalse(backend.updates)

    def test_status_rejects_non_boolean_field(self):
        backend = FakeBackend({"flashlightEnabled": "true", "vibrationEnabled": False})
        with self.assertRaisesRegex(cli.ConfigurationConflict, "invalid type"):
            invoke(["status"], backend)

    def test_init_creates_missing_node_idempotently(self):
        backend = FakeBackend(_ABSENT)
        invoke(["init"], backend)
        self.assertEqual({"flashlightEnabled": False, "vibrationEnabled": False}, backend.data[PATH])
        invoke(["init"], backend)
        self.assertEqual([PATH], backend.transactions)

    def test_init_preserves_existing_value_and_adds_missing_field(self):
        backend = FakeBackend({"flashlightEnabled": True})
        invoke(["init"], backend)
        self.assertEqual({"flashlightEnabled": True, "vibrationEnabled": False}, backend.data[PATH])

    def test_init_rejects_conflict_without_writing(self):
        backend = FakeBackend({"flashlightEnabled": 1})
        with self.assertRaises(cli.ConfigurationConflict):
            invoke(["init"], backend)
        self.assertFalse(backend.transactions)

    def test_init_requires_confirmation(self):
        backend = FakeBackend(_ABSENT)
        with self.assertRaisesRegex(cli.CliError, "cancelled"):
            invoke(["init"], backend, answer="no")
        self.assertFalse(backend.transactions)

    def test_doctor_is_read_only_and_reports_valid_node(self):
        backend = FakeBackend({"flashlightEnabled": False, "vibrationEnabled": False})
        code, output = invoke(["doctor"], backend)
        self.assertEqual(cli.EXIT_OK, code)
        self.assertIn("Doctor mode: READ ONLY", output)
        self.assertFalse(backend.updates)
        self.assertFalse(backend.transactions)

    def test_doctor_reports_project_mismatch(self):
        backend = FakeBackend({"flashlightEnabled": False, "vibrationEnabled": False})
        backend.detected_project_id = "wrong-project"
        code, output = invoke(["doctor"], backend)
        self.assertEqual(cli.EXIT_FIREBASE, code)
        self.assertIn("Project ID: FAILED", output)

    def test_missing_uid_prevents_backend_creation_and_write(self):
        called = False

        def factory(_):
            nonlocal called
            called = True
            return FakeBackend(_ABSENT)

        with patch.dict("os.environ", {}, clear=True), self.assertRaises(cli.CliError):
            cli.run(["flashlight", "on"], backend_factory=factory)
        self.assertFalse(called)

    def test_missing_credentials_has_actionable_error(self):
        error = cli.map_firebase_error(Exception("default credentials not found"), "Firebase initialization")
        self.assertEqual(cli.EXIT_AUTH, error.exit_code)
        self.assertIn("Application Default Credentials", str(error))

    def test_doctor_reports_missing_credentials_without_throwing(self):
        auth_error = cli.CliError("credentials are unavailable", cli.EXIT_AUTH)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = cli.run(
                ["--uid", UID, "doctor"],
                backend_factory=lambda _: (_ for _ in ()).throw(auth_error),
            )
        self.assertEqual(cli.EXIT_AUTH, code)
        self.assertIn("Control node: NOT VERIFIED", output.getvalue())

    def test_project_mismatch_blocks_write(self):
        backend = FakeBackend({"flashlightEnabled": False, "vibrationEnabled": False})
        backend.detected_project_id = "wrong-project"
        with self.assertRaisesRegex(cli.CliError, "Refusing"):
            invoke(["flashlight", "on"], backend)
        self.assertFalse(backend.updates)

    def test_permission_denied_has_actionable_error(self):
        error = cli.map_firebase_error(Exception("403 permission denied"), "Firebase write")
        self.assertEqual(cli.EXIT_AUTH, error.exit_code)
        self.assertIn("IAM role", str(error))

    def test_timeout_has_actionable_error(self):
        error = cli.map_firebase_error(TimeoutError("timed out"), "Firebase read")
        self.assertIn("timed out", str(error))

    def test_all_writes_are_confined_to_control_node(self):
        backend = FakeBackend({"flashlightEnabled": False, "vibrationEnabled": False})
        for command in (["flashlight", "on"], ["vibration", "on"], ["reset"]):
            invoke(command, backend)
        self.assertTrue(all(path == PATH for path, _ in backend.updates))


if __name__ == "__main__":
    unittest.main()
