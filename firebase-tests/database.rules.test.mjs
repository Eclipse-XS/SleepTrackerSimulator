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
