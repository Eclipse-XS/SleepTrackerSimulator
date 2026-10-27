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


if __name__ == "__main__":
    unittest.main()
