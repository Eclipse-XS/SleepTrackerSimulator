# ML evaluation

The production task is coarse movement classification (`REST`, `RESTLESS`, `ACTIVE`), not clinical sleep staging. Existing reported metrics come from synthetic evaluation and cannot establish real-world or medical performance. Existing local ML result files were already modified before this audit and are not attributed to it.

No representative independent real-world dataset, subject-wise held-out test, placement-stratified result, calibration analysis, battery experiment, or confidence interval was produced in this run. The aspirational Macro F1 target is therefore unverified.

Status: runtime model contract tests are part of the existing suite; real-world performance claim is `BLOCKED`.
