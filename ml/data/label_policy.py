"""Strict supervised-label policy (ML-001).

Only ``REST``, ``RESTLESS`` and ``ACTIVE`` are valid supervised targets. Anything else (``UNLABELED``,
``UNCERTAIN``, ``None``, empty or unknown strings) must NEVER be mapped to a class implicitly.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Iterable
from typing import Any

CLASSES = ["REST", "RESTLESS", "ACTIVE"]
LABEL_TO_INT = {c: i for i, c in enumerate(CLASSES)}
INT_TO_LABEL = {i: c for i, c in enumerate(CLASSES)}
SUPERVISED_LABELS = frozenset(CLASSES)


class InvalidLabelError(ValueError):
    """Raised when a window carries a label that cannot be used as a supervised target."""


def is_supervised_label(label: Any) -> bool:
    return isinstance(label, str) and label in SUPERVISED_LABELS


def encode_label_strict(label: Any, *, session_id: str = "?", window_id: str = "?") -> int:
    """Return the class index or raise :class:`InvalidLabelError` naming session, window and label."""
    if is_supervised_label(label):
        return LABEL_TO_INT[label]
    raise InvalidLabelError(
        f"Invalid supervised label {label!r} for session_id={session_id!r}, window_id={window_id!r}; "
        f"expected one of {CLASSES}. Unlabeled/unknown windows are never mapped to REST."
    )


def audit_labels(labels: Iterable[Any]) -> dict[str, Any]:
    """Counts supervised vs. excluded labels for dataset audits."""
    counts: Counter[str] = Counter()
    for label in labels:
        counts[label if is_supervised_label(label) else f"EXCLUDED:{label!r}"] += 1
    supervised = {c: counts.get(c, 0) for c in CLASSES}
    excluded = {k: v for k, v in counts.items() if k.startswith("EXCLUDED:")}
    return {
        "supervised_total": sum(supervised.values()),
        "supervised_by_class": supervised,
        "excluded_total": sum(excluded.values()),
        "excluded_by_label": dict(sorted(excluded.items())),
    }
