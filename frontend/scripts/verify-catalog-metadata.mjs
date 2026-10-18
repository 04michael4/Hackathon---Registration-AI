// End-to-end production check with synthetic instructors only. Requires npm run build and Chrome.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const artifactDir = await mkdtemp(path.join(tmpdir(), "syllo-catalog-check-"));
const children = [];
let socket;
let checks = 0;
const check = (value, message) => { assert.ok(value, message); checks++; };
const fixture = {
  courseId: "CSE-099", subject: "CSE", number: "099", sectionCount: 2, title: "Synthetic Catalog Course", credits: 3, description: "Synthetic test data only.",
  predictedRating: { quality: 3.66, difficulty: 2.95, confidence: "LOW", basis: "PREDICTED", sectionsPredicted: 1, sections: 2 },
  conditions: [{ code: "PREREQUISITE", message: "Fixture prerequisite is unverified", evidenceLevel: "TEAM_POLICY", outcome: "UNVERIFIED" }],
  sections: [{ crn: "99001", sectionCode: "010", componentType: "LECTURE", lectureLink: null, credits: 3, status: "UNKNOWN", meetings: [],
    instructors: ["Fixture, Alpha", "Fixture, Beta"], instructorRatings: [
      { name: "Fixture, Alpha", predictedQuality: 3.66, predictedDifficulty: 2.95, confidence: "LOW", ratingCount: 12, courseSpecific: true },
      { name: "Fixture, Beta", predictedQuality: null, predictedDifficulty: null, confidence: null, ratingCount: null, courseSpecific: false },
    ] },
    { crn: "99002", sectionCode: "060", componentType: "LAB", lectureLink: "99001", credits: 0, status: "CLOSED", meetings: [],
      instructors: ["Fixture, Gamma"], instructorRatings: [{ name: "Fixture, Gamma", predictedQuality: 4.1, predictedDifficulty: 3.1, confidence: "HIGH", ratingCount: 8, courseSpecific: false }] },
  ],
};
const courses = Array.from({ length: 21 }, (_, index) => ({ ...fixture,
  courseId: `CSE-${String(99 - index).padStart(3, "0")}`,
  title: index ? `Synthetic Course ${index}` : fixture.title,
  predictedRating: index === 1 ? null : fixture.predictedRating,
  ...(index === 20 ? { courseId: "CSE-398-042", title: "Synthetic Topic Course", conditions: [
    { code: "COMMON_EXAMS", message: "Fixture common exam is unverified", evidenceLevel: "TEAM_POLICY", outcome: "UNVERIFIED" },
  ] } : {}),
}));
let failDetail = false;
const backend = createServer((request, response) => {
  const url = new URL(request.url, "http://fixture");
  response.setHeader("content-type", "application/json");
  if (url.pathname === "/api/courses") {
    const query = url.searchParams.get("q") ?? "";
    if (query === "failure") { response.writeHead(503).end('{"detail":"fixture unavailable"}'); return; }
    const page = Number(url.searchParams.get("page") ?? 0);
    const matches = courses.filter(course => `${course.courseId} ${course.title}`.toLowerCase().includes(query.toLowerCase()));
    response.end(JSON.stringify({ items: matches.slice(page * 20, (page + 1) * 20), page, size: 20, totalItems: matches.length, totalPages: Math.ceil(matches.length / 20) }));
  } else {
    const course = courses.find(item => `/api/courses/${item.courseId}` === url.pathname);
    if (!course) { response.writeHead(404).end('{}'); return; }
    if (failDetail) { response.writeHead(503).end('{}'); return; }
    setTimeout(() => response.end(JSON.stringify(course.courseId === "CSE-098" ? { ...course, conditions: [], sections: [] } : course)), course.courseId === "CSE-097" ? 300 : 0);
  }
});
await new Promise(resolve => backend.listen(0, "127.0.0.1", resolve));
const backendPort = backend.address().port;

async function availablePort() {
  const server = createServer();
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

async function startApp(enabled) {
  const port = await availablePort();
  const child = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "--hostname", "127.0.0.1", "--port", String(port)], {
    cwd: process.cwd(), env: { ...process.env, FRONTEND_MODE: "demo", BACKEND_URL: enabled ? `http://127.0.0.1:${backendPort}` : "" }, windowsHide: true, stdio: "ignore",
  });
  children.push(child);
  for (let attempt = 0; attempt < 120; attempt++) {
    try { if ((await fetch(`http://127.0.0.1:${port}`)).ok) return `http://127.0.0.1:${port}`; } catch { /* Starting. */ }
    await pause(100);
  }
  throw new Error("Production frontend failed to start");
}

const pending = new Map();
const errors = [];
let nextId = 0;
function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++nextId; pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const result = await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? "Browser evaluation failed");
  return result.result.value;
}
async function waitFor(expression, label) {
  for (let attempt = 0; attempt < 200; attempt++) { if (await evaluate(`Boolean(${expression})`)) return; await pause(50); }
  throw new Error(`Timed out: ${label}`);
}
async function screenshot(name, width, height) {
  await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: width < 600 });
  await pause(200);
  const { data } = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
  await writeFile(path.join(artifactDir, name), Buffer.from(data, "base64"));
}
async function search(text) {
  await evaluate(`(() => {const input=document.querySelector('#live-catalog-query');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,${JSON.stringify(text)});input.dispatchEvent(new Event('input',{bubbles:true}));})()`);
  await pause(50);
  await evaluate("document.querySelector('.live-catalog-browser form').requestSubmit()");
}

try {
  const developmentUrl = process.env.SYLLO_CATALOG_DEV_URL;
  const app = developmentUrl ?? await startApp(true);
  const debugPort = await availablePort();
  children.push(spawn(process.env.CHROME_PATH ?? "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", [
    "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
    `--remote-debugging-port=${debugPort}`, `--user-data-dir=${path.join(artifactDir, "chrome-profile")}`, "about:blank",
  ], { stdio: "ignore", windowsHide: true }));
  let debuggerUrl;
  for (let attempt = 0; attempt < 100; attempt++) {
    try { debuggerUrl = (await fetch(`http://127.0.0.1:${debugPort}/json/list`).then(result => result.json())).find(item => item.type === "page")?.webSocketDebuggerUrl; } catch { /* Starting. */ }
    if (debuggerUrl) break; await pause(50);
  }
  assert.ok(debuggerUrl, "Chrome did not start");
  socket = new WebSocket(debuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, { once: true }); socket.addEventListener("error", reject, { once: true }); });
  socket.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    if (message.id) { const job = pending.get(message.id); pending.delete(message.id); if (message.error) job.reject(new Error(message.error.message)); else job.resolve(message.result); }
    else if (message.method === "Runtime.exceptionThrown") errors.push(message.params.exceptionDetails.text);
    else if (message.method === "Runtime.consoleAPICalled" && message.params.type === "error") errors.push("Browser console error");
  });
  await send("Runtime.enable"); await send("Page.enable");
  await send("Page.navigate", { url: app });
  await waitFor("document.documentElement.dataset.sylloRuntime==='loaded'", "runtime");
  await evaluate("document.querySelector('[data-action=login]').click()");
  if (developmentUrl) {
    check((await fetch(app)).status === 200, "Development page returns HTTP 200");
    check(await evaluate("document.body.dataset.activeScreen==='planner'"), "Development login opens planner");
    const apiMode = await evaluate("document.documentElement.dataset.sylloMode==='api'");
    if (apiMode) {
      await waitFor("document.querySelector('[data-api-planner]')", "development API planner");
      check(await evaluate("getComputedStyle(document.querySelector('.api-planner-columns')).display==='grid'"), "API planner stylesheet loaded in development");
      check(await evaluate("getComputedStyle(document.querySelector('.planner-layout > .manual-planner')).display==='none'"), "API mode hides the prototype planner");
      check(await evaluate("document.querySelector('.api-plan-panel').hidden"), "Planning preferences start collapsed");
      check(await evaluate("document.querySelector('#syllo-ai-toggle').checkVisibility()"), "Original header AI button remains visible");
      for (const [width, height] of [[1440,900], [800,700], [390,844]]) {
        await screenshot(`semester-api-${width}.png`, width, height);
        check(await evaluate("document.documentElement.scrollWidth<=innerWidth+1"), `${width}: restored semester layout fits viewport`);
        check(await evaluate("document.querySelector('.api-week').checkVisibility()"), `${width}: calendar remains in the main workspace`);
        if (width === 1440) check(await evaluate("document.querySelector('.api-calendar-column').getBoundingClientRect().right<=document.querySelector('.api-course-sidebar').getBoundingClientRect().left"), "Calendar is left of the course list");
      }
      await evaluate("document.querySelector('#syllo-ai-toggle').click()");
      check(await evaluate("!document.querySelector('.api-plan-panel').hidden&&document.querySelector('#syllo-ai-toggle').getAttribute('aria-expanded')==='true'"), "Header button opens actual API preferences");
      await evaluate("document.querySelector('[aria-label=\"Close Intelligent Planning\"]').click()");
      check(await evaluate("document.querySelector('.api-plan-panel').hidden"), "Planning assistant closes again");
    } else {
      check(await evaluate("document.querySelectorAll('.course-list-card').length>0"), "Development planner renders courses");
    }
    check(await evaluate("(()=>{const probe=document.createElement('div');probe.className='catalog-prediction';document.body.append(probe);const loaded=getComputedStyle(probe).borderTopStyle==='dashed';probe.remove();return loaded})()"), "Catalog stylesheet loaded in development");
    check(errors.length === 0, "No development browser exceptions or console errors");
    console.log(`PASS: ${checks} running-development-server checks`);
  } else {
  await waitFor("document.querySelectorAll('.live-catalog-results > li').length===20", "live API search");
  check(await evaluate("document.querySelector('.live-catalog-browser').textContent.includes('Predicted quality: 3.66 / 5')"), "Prediction label");
  check(await evaluate("document.querySelector('.live-catalog-browser').textContent.includes('Low confidence')"), "Confidence on course cards");
  check(await evaluate("document.querySelectorAll('.live-catalog-results > li')[1].textContent.includes('No prediction available')"), "Null prediction");
  await screenshot("live-catalog-desktop.png", 1440, 1000);
  await evaluate("[...document.querySelectorAll('.live-catalog-pages button')].find(button=>button.textContent==='Next').click()");
  await waitFor("document.querySelectorAll('.live-catalog-results > li').length===1", "next page"); checks++;
  await search("CSE-099");
  await waitFor("document.querySelector('.live-catalog-results')?.textContent.includes('CSE-099')", "code search"); checks++;
  await evaluate("document.querySelector('.live-catalog-results button').click()");
  await waitFor("document.querySelector('.catalog-course-details')?.textContent.includes('Fixture, Gamma')", "section ratings");
  const body = await evaluate("document.querySelector('.catalog-course-details').textContent");
  for (const text of ["Fixture, Alpha", "Fixture, Beta", "Fixture, Gamma", "UNVERIFIED", "Fixture prerequisite is unverified", "High confidence", "Coverage: 1 of 2", "Partial coverage", "No prediction available"]) check(body.includes(text), `Detail contains ${text}`);
  check(!body.includes("0.00 / 5"), "Missing predictions do not become zero");
  check(await evaluate("!localStorage.getItem('syllo-planning-v3').includes('Fixture,') && !JSON.stringify(window.Syllo.state).includes('Fixture,')"), "Instructor API data never persisted in demo state");
  await screenshot("live-course-desktop.png", 1440, 1000);
  await screenshot("live-course-mobile.png", 390, 844);
  check(await evaluate("document.documentElement.scrollWidth<=innerWidth"), "Mobile page has no horizontal overflow");
  await evaluate("document.querySelector('[data-action=close-drawer]').click();document.dispatchEvent(new CustomEvent('syllo:catalog-open',{detail:{code:'CSE-097',term:'2027-SPRING'}}));document.dispatchEvent(new CustomEvent('syllo:catalog-open',{detail:{code:'CSE-098',term:'2027-SPRING'}}));");
  await waitFor("document.querySelector('.catalog-course-details')?.textContent.includes('CSE-098')", "rapid switch");
  await pause(400);
  check(await evaluate("document.querySelector('.catalog-course-details').textContent.includes('CSE-098')"), "Late response does not overwrite current course");
  check(await evaluate("document.querySelector('.catalog-course-details').textContent.includes('No conditions listed. This does not confirm eligibility.')"), "Empty conditions are not eligibility");
  await evaluate("document.querySelector('[data-action=close-drawer]').click()");
  failDetail = true;
  await evaluate("document.dispatchEvent(new CustomEvent('syllo:catalog-open',{detail:{code:'CSE-096',term:'2027-SPRING'}}))");
  await waitFor("document.querySelector('.catalog-unavailable')", "detail failure"); checks++;
  failDetail = false;
  await evaluate("document.querySelector('.catalog-unavailable button').click()");
  await waitFor("document.querySelector('.catalog-course-details')?.textContent.includes('CSE-096')", "detail retry"); checks++;
  await evaluate("document.querySelector('[data-action=close-drawer]').click()");
  await search("CSE-398-042");
  await waitFor("document.querySelectorAll('.live-catalog-results > li').length===1&&document.querySelector('.live-catalog-results').textContent.includes('CSE-398-042')", "special-topic search"); checks++;
  await evaluate("document.querySelector('.live-catalog-results button').click()");
  await waitFor("document.querySelector('.catalog-course-details')?.textContent.includes('CSE-398-042')", "special-topic details");
  check(await evaluate("document.querySelector('.catalog-course-details').textContent.includes('Fixture common exam is unverified')&&document.querySelector('.catalog-course-details').textContent.includes('Fixture, Gamma')"), "Special-topic details retain conditions and instructor predictions");
  await evaluate("document.querySelector('[data-action=close-drawer]').click()");
  await search("failure");
  await waitFor("document.querySelector('.live-catalog-browser')?.textContent.includes('Live catalog unavailable')", "search failure"); checks++;
  const api = await fetch(`${app}/api/catalog/courses/CSE-099?term=2027-SPRING`);
  check(api.headers.get("cache-control").includes("private, no-store"), "HTTP responses are private and uncached");
  check(!(await fetch(app).then(response => response.text())).includes("Fixture, Alpha"), "Instructor names absent from server HTML");
  check(await evaluate("(()=>{const before=JSON.stringify(window.Syllo.state.planner);window.SylloPlanner.add('art003');window.Syllo.undo();return before===JSON.stringify(window.Syllo.state.planner)})()"), "Demo planner add/undo still works");
  const offline = await startApp(false);
  check((await fetch(`${offline}/api/catalog/courses`)).status === 503, "Live API disabled without configuration");
  await send("Page.navigate", { url: offline });
  await waitFor("document.documentElement.dataset.sylloRuntime==='loaded'", "offline runtime");
  await evaluate("document.querySelector('[data-action=login]').click()");
  check(await evaluate("!document.querySelector('.live-catalog-browser')"), "Default demo does not request live metadata");
  check(errors.length === 0, "No browser JavaScript exceptions");
  console.log(`PASS: ${checks} catalog browser checks; screenshots: ${artifactDir}`);
  }
} finally {
  socket?.close();
  for (const child of children.reverse()) child.kill();
  backend.closeAllConnections(); backend.close();
}
