import fs from "node:fs";
import path from "node:path";
import test, { after, before, beforeEach } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";

const projectId = "demo-sleep-tracker-rules";
const rules = fs.readFileSync(path.resolve("../database.rules.json"), "utf8");
let env;

before(async () => {
  env = await initializeTestEnvironment({ projectId, database: { rules } });
});
beforeEach(async () => env.clearDatabase());
after(async () => env.cleanup());

const validSession = {
  schemaVersion: 2,
  deviceId: "sleep-tracker-simulator",
  startedAt: 1000,
  measurements: {
    m1: {
      measurementId: "m1",
      deviceId: "sleep-tracker-simulator",
      sensorType: "ACCELEROMETER",
      x: 1,
      y: 2,
      z: 9.8,
      value: 0.2,
      timestamp: 1000,
      movementState: "STILL",
      movementIntensity: 0.2,
      bleConnected: false,
      restState: "MONITORING"
    }
  }
};

const validRuleEvent = {
  eventId: "e1", ruleId: "r1", ruleName: "Movement alert", timestamp: 1001,
  source: "ML_CLASS", sourceValue: "ACTIVE", sensorTimestampNanos: 100,
  predictionId: "p1", mlClass: "ACTIVE", mlConfidence: 0.91,
  requestedAction: "MOCK_BLE_ALERT", actuatorResult: "NOT_REQUESTED",
  bleDeliveryResult: "ALERT_ACK:e1", schemaVersion: 1
};

test("owner can write and read a valid session", async () => {
  const db = env.authenticatedContext("owner").database();
  await assertSucceeds(db.ref("users/owner/sessions/s1").set(validSession));
  await assertSucceeds(db.ref("users/owner/sessions/s1").get());
});

test("unauthenticated and cross-user access is denied", async () => {
  await assertFails(env.unauthenticatedContext().database().ref("users/owner").get());
  await assertFails(env.authenticatedContext("attacker").database().ref("users/owner").get());
  await assertFails(env.authenticatedContext("attacker").database().ref("users/owner/sessions/s1").set(validSession));
});

test("unexpected properties and invalid physiological values are denied", async () => {
  const db = env.authenticatedContext("owner").database();
  await assertFails(db.ref("users/owner/sessions/s1").set({ ...validSession, unexpected: true }));
  const invalidHr = structuredClone(validSession);
  invalidHr.measurements.m1.heartRateBpm = 999;
  await assertFails(db.ref("users/owner/sessions/s2").set(invalidHr));
});

test("remote controls accept only booleans and known fields", async () => {
  const ref = env.authenticatedContext("owner").database().ref("users/owner/control/sleep-tracker-simulator");
  await assertSucceeds(ref.set({ vibrationEnabled: true, flashlightEnabled: false }));
  await assertFails(ref.set({ vibrationEnabled: "yes" }));
  await assertFails(ref.set({ vibrationEnabled: true, unknown: false }));
});

test("owner can write a schema-validated smart-rule event", async () => {
  const ref = env.authenticatedContext("owner").database().ref("users/owner/sessions/s1");
  await assertSucceeds(ref.set(validSession));
  await assertSucceeds(ref.child("ruleEvents/e1").set(validRuleEvent));
});

test("smart-rule validation rejects invalid action, confidence, timestamp, id and extra fields", async () => {
  const ref = env.authenticatedContext("owner").database().ref("users/owner/sessions/s1");
  await assertSucceeds(ref.set(validSession));
  await assertFails(ref.child("ruleEvents/e1").set({ ...validRuleEvent, requestedAction: "DELETE_DATA" }));
  await assertFails(ref.child("ruleEvents/e1").set({ ...validRuleEvent, mlConfidence: 1.01 }));
  await assertFails(ref.child("ruleEvents/e1").set({ ...validRuleEvent, timestamp: 0 }));
  await assertFails(ref.child("ruleEvents/e1").set({ ...validRuleEvent, eventId: "other" }));
  await assertFails(ref.child("ruleEvents/e1").set({ ...validRuleEvent, unexpected: true }));
});

test("same event UUID is idempotent; overwrite and cross-user delete are rejected", async () => {
  const ref = env.authenticatedContext("owner").database().ref("users/owner/sessions/s1");
  await assertSucceeds(ref.set(validSession));
  await assertSucceeds(ref.child("ruleEvents/e1").set(validRuleEvent));
  await assertSucceeds(ref.child("ruleEvents/e1").set(validRuleEvent));
  await assertFails(ref.child("ruleEvents/e1").set({ ...validRuleEvent, actuatorResult: "CHANGED" }));
  await assertFails(env.authenticatedContext("attacker").database()
    .ref("users/owner/sessions/s1/ruleEvents/e1").remove());
});

test("invalid and cross-user smart-rule events are denied", async () => {
  const owner = env.authenticatedContext("owner").database();
  await assertSucceeds(owner.ref("users/owner/sessions/s1").set(validSession));
  const invalid = { eventId: "wrong", ruleId: "r1", ruleName: "R", timestamp: 1001,
    source: "INVALID", sourceValue: "x", requestedAction: "MOCK_BLE_ALERT",
    actuatorResult: "NOT_REQUESTED", bleDeliveryResult: "NOT_CONNECTED", schemaVersion: 1 };
  await assertFails(owner.ref("users/owner/sessions/s1/ruleEvents/e1").set(invalid));
  await assertFails(env.authenticatedContext("attacker").database()
    .ref("users/owner/sessions/s1/ruleEvents/e2").set({ ...invalid, eventId: "e2", source: "ML_CLASS" }));
});

const validSummary = {
  sessionId: "summary-s1",
  startedAt: 1000,
  endedAt: 2000,
  observationDurationMs: 1000,
  lowMotionFraction: 0.85,
  restlessFraction: 0.10,
  activeFraction: 0.05,
  sourceMode: "PHONE_ONLY",
  placement: "MATTRESS",
  qualityStatus: "VALID",
  schemaVersion: 2
};

test("owner can write and read a valid SleepSense session summary", async () => {
  const db = env.authenticatedContext("owner").database();
  await assertSucceeds(db.ref("users/owner/sessionSummaries/summary-s1").set(validSummary));
  await assertSucceeds(db.ref("users/owner/sessionSummaries/summary-s1").get());
});

test("session summary validation rejects invalid fractions, mismatching id or extra properties", async () => {
  const db = env.authenticatedContext("owner").database();
  await assertFails(db.ref("users/owner/sessionSummaries/summary-s1").set({ ...validSummary, lowMotionFraction: 1.5 }));
  await assertFails(db.ref("users/owner/sessionSummaries/summary-s1").set({ ...validSummary, sessionId: "other-id" }));
  await assertFails(db.ref("users/owner/sessionSummaries/summary-s1").set({ ...validSummary, unexpectedProp: true }));
});

test("attacker cannot read or write owner session summaries", async () => {
  const attackerDb = env.authenticatedContext("attacker").database();
  await assertFails(attackerDb.ref("users/owner/sessionSummaries/summary-s1").set(validSummary));
  await assertFails(attackerDb.ref("users/owner/sessionSummaries/summary-s1").get());
});
