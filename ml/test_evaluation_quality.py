import unittest
import numpy as np

from ml.evaluation_quality import assert_no_recording_leakage, expected_calibration_error, subject_wise_split


class EvaluationQualityTest(unittest.TestCase):
    def test_subject_split_has_no_subject_overlap(self):
        subjects = np.repeat(np.arange(10), 4)
        split = subject_wise_split(subjects, seed=7)
        self.assertFalse(set(subjects[split.train_indices]) & set(subjects[split.test_indices]))

    def test_recording_leakage_is_rejected(self):
        with self.assertRaises(ValueError):
            assert_no_recording_leakage(["a", "b"], ["b", "c"])

    def test_calibration_error_is_bounded(self):
        value = expected_calibration_error(np.array([[0.9, 0.1], [0.4, 0.6]]), np.array([0, 1]))
        self.assertGreaterEqual(value, 0.0)
        self.assertLessEqual(value, 1.0)

    def test_subject_split_rejects_empty_and_non_vector_input(self):
        for invalid in ([], [["a"], ["b"]]):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                subject_wise_split(invalid)

    def test_subject_split_rejects_invalid_fraction(self):
        for fraction in (0.0, 1.0, -0.1, 1.1):
            with self.subTest(fraction=fraction), self.assertRaises(ValueError):
                subject_wise_split(["a", "b"], test_fraction=fraction)

    def test_leakage_rejects_null_recording_ids(self):
        with self.assertRaises(ValueError):
            assert_no_recording_leakage(["a", None], ["b"])

    def test_calibration_rejects_malformed_inputs(self):
        invalid_cases = [
            ([], [], 10),
            ([[0.4, 0.4]], [0], 10),
            ([[1.1, -0.1]], [0], 10),
            ([[0.5, 0.5]], [2], 10),
            ([[0.5, 0.5]], [0.0], 10),
            ([[0.5, 0.5]], [0], 0),
        ]
        for probabilities, labels, bins in invalid_cases:
            with self.subTest(probabilities=probabilities, labels=labels, bins=bins), self.assertRaises(ValueError):
                expected_calibration_error(probabilities, labels, bins)

    def test_calibration_accepts_zero_confidence_boundary(self):
        value = expected_calibration_error([[0.5, 0.5]], np.array([0]), bins=2)
        self.assertAlmostEqual(0.5, value)


if __name__ == "__main__":
    unittest.main()
