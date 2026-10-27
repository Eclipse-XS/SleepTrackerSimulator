import http from 'http';

function createMockJwt(uid) {
  const header = Buffer.from(JSON.stringify({ alg: "none", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(JSON.stringify({
    iss: "https://securetoken.google.com/sleep-tracker-simulator-dev",
    aud: "sleep-tracker-simulator-dev",
    auth_time: Math.floor(Date.now() / 1000),
    user_id: uid,
    sub: uid,
    iat: Math.floor(Date.now() / 1000),
    exp: Math.floor(Date.now() / 1000) + 3600,
    firebase: {
      identities: {},
      sign_in_provider: "anonymous"
    }
  })).toString("base64url");
  return `${header}.${payload}.signature`;
}

let lastUid = "e2e-user-" + Math.random().toString(36).substring(2, 8);

const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', chunk => { body += chunk; });
  req.on('end', () => {
    console.log(`[AUTH] ${req.method} ${req.url} - body: ${body}`);

    if (req.url.includes("getAccountInfo")) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        kind: "identitytoolkit#GetAccountInfoResponse",
        users: [
          {
            localId: lastUid,
            lastLoginAt: Date.now().toString(),
            createdAt: Date.now().toString(),
            disabled: false
          }
        ]
      }));
      return;
    }

    lastUid = "e2e-user-" + Math.random().toString(36).substring(2, 8);
    const token = createMockJwt(lastUid);

    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      kind: "identitytoolkit#SignupNewUserResponse",
      idToken: token,
      refreshToken: "fake-refresh-token-" + lastUid,
      expiresIn: "3600",
      localId: lastUid,
      isNewUser: true
    }));
  });
});

server.listen(9099, '0.0.0.0', () => {
  console.log("Mock Firebase Auth Emulator listening on 0.0.0.0:9099");
});
