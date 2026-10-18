// Real HTTP acceptance test. Start Spring Boot with an isolated AuthoritativeCatalogFixture synthetic database first.
// No fake API server, mocked solver, paid AI calls, or shared database. Screenshots contain synthetic courses only.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
const backend = process.env.SMARTCOURSE_TEST_BACKEND_URL;
assert.ok(backend && ["127.0.0.1", "localhost"].includes(new URL(backend).hostname), "Set SMARTCOURSE_TEST_BACKEND_URL to the isolated loopback Spring Boot test instance");
const fixture = await fetch(`${backend}/api/courses?term=2027-SPRING&subject=MATH`).then(r => r.json());
assert.equal(fixture.items?.[0]?.title, "Synthetic integration MATH-021", "Acceptance checks require the synthetic PostgreSQL catalog fixture");
const output = await mkdtemp(path.join(tmpdir(), "smartcourse-api-check-"));
const children = [], pending = new Map(), errors = [], requests = [], responses = [];
let socket, id = 0, checks = 0;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (value, label) => { assert.ok(value, label); checks++; };
async function availablePort() { const server = createServer(); await new Promise(resolve => server.listen(0, "127.0.0.1", resolve)); const port = server.address().port; await new Promise(resolve => server.close(resolve)); return port; }
function send(method, params = {}) { return new Promise((resolve, reject) => { const requestId = ++id; pending.set(requestId, { resolve, reject }); socket.send(JSON.stringify({ id: requestId, method, params })); }); }
async function evaluate(expression) { const result = await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true }); if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? "Browser evaluation failed"); return result.result.value; }
async function waitFor(expression, label) { for (let i = 0; i < 200; i++) { if (await evaluate(`Boolean(${expression})`)) return; await pause(50); } throw new Error(`Timed out: ${label}`); }
async function setInput(selector, value) {
  const inHiddenPanel = await evaluate(`document.querySelector(${JSON.stringify(selector)})?.closest('.api-plan-panel')?.hidden`);
  if(inHiddenPanel) await evaluate("document.querySelector('#syllo-ai-toggle').click()");
  const inHiddenSearch = await evaluate(`document.querySelector(${JSON.stringify(selector)})?.closest('.api-search-dropdown')?.hidden`);
  if(inHiddenSearch) { await evaluate("document.querySelector('[aria-label=\"Search courses and interests\"]').focus()"); await pause(50); }

  await evaluate(`(()=>{const node=document.querySelector(${JSON.stringify(selector)});const proto=node.tagName==='SELECT'?HTMLSelectElement.prototype:node.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(node,${JSON.stringify(value)});node.dispatchEvent(new Event(node.tagName==='SELECT'?'change':'input',{bubbles:true}));})()`); await pause(50);
}
async function clickText(text, scope = ".api-planner") { const target = `[...document.querySelectorAll(${JSON.stringify(scope + " button")})].find(b=>b.textContent.trim()===${JSON.stringify(text)})`; await waitFor(`(${target})&&!(${target}).disabled`, `enabled ${text} button`); await evaluate(`(${target}).click()`); }
async function search(q, subject = "") { await setInput('[aria-label="Search courses and interests"]', q); await setInput('[aria-label="Subject filter"]', subject); await evaluate("document.querySelector('.api-search').requestSubmit()"); await waitFor("!document.querySelector('.api-catalog-panel').textContent.includes('Loading courses') && (document.querySelector('.api-catalog-panel').textContent.includes('courses found') || document.querySelector('.api-catalog-panel .api-error'))", "catalog search"); }
async function generate() { await evaluate("document.querySelector('[aria-label=\"Close course search results\"]').click()"); await clickText("Generate schedules"); await waitFor("!document.querySelector('.api-plan-panel [role=status]') && (document.querySelector('.api-results') || document.querySelector('.api-error'))", "generation response"); }
try {
  const port = await availablePort(), chromePort = await availablePort(), app = `http://127.0.0.1:${port}`;
  children.push(spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "--hostname", "127.0.0.1", "--port", String(port)], { cwd: process.cwd(), env: { ...process.env, FRONTEND_MODE: "api", BACKEND_URL: backend }, windowsHide: true, stdio: "ignore" }));
  for (let i = 0; i < 120; i++) { try { if ((await fetch(app)).ok) break; } catch {} await pause(100); }
  children.push(spawn(process.env.CHROME_PATH ?? "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", ["--headless=new", "--disable-gpu", "--no-first-run", `--remote-debugging-port=${chromePort}`, `--user-data-dir=${path.join(output, "profile")}`, "about:blank"], { windowsHide: true, stdio: "ignore" }));
  let debuggerUrl;
  for (let i = 0; i < 120; i++) { try { debuggerUrl = (await fetch(`http://127.0.0.1:${chromePort}/json/list`).then(r => r.json())).find(p => p.type === "page")?.webSocketDebuggerUrl; } catch {} if (debuggerUrl) break; await pause(50); }
  assert.ok(debuggerUrl, "Chrome did not start"); socket = new WebSocket(debuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, { once: true }); socket.addEventListener("error", reject, { once: true }); });
  socket.addEventListener("message", event => { const message = JSON.parse(event.data); if (message.id) { const job = pending.get(message.id); pending.delete(message.id); if (message.error) job.reject(new Error(message.error.message)); else job.resolve(message.result); } else if (message.method === "Runtime.exceptionThrown") errors.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text); else if (message.method === "Runtime.consoleAPICalled" && message.params.type === "error") errors.push("Browser console error"); });
  await send("Runtime.enable"); await send("Page.enable"); await send("Network.enable");
  await send("Page.addScriptToEvaluateOnNewDocument", { source: `window.__apiRequests=[];window.__apiResponses=[];const originalFetch=window.fetch;window.fetch=async function(url,options){if(String(url).includes('/api/backend/')){window.__apiRequests.push({url:String(url),body:options?.body?JSON.parse(options.body):null});await new Promise(resolve=>setTimeout(resolve,150));}const response=await originalFetch.apply(this,arguments);if(String(url).includes('/schedules/')){const copy=await response.clone().json();window.__apiResponses.push({url:String(url),status:response.status,body:copy});}return response;}` });
  await send("Page.navigate", { url: app }); await waitFor("document.documentElement.dataset.sylloRuntime==='loaded' && document.querySelector('[data-api-planner]')", "API planner mount");
  await evaluate("document.querySelector('[data-action=login]').click()");
  check(await evaluate("document.querySelector('.api-planner').textContent.includes('Loading catalog terms')"), "Loading catalog terms is visible during the real request");
  await waitFor("document.querySelector('[data-api-course=\"MATH-021\"]')", "real catalog courses");
  check(await evaluate("!document.querySelector('script[src=\"/reference/semester.js\"]')"), "Normal mode does not load the synthetic schedule generator");
  check(await evaluate("document.querySelector('[aria-label=\"Catalog term\"]').value==='2027-SPRING'"), "Terms come from Spring Boot");
  await search("Synthetic", "MATH"); await waitFor("document.querySelectorAll('[data-api-course]').length===1", "subject filtering");
  check(await evaluate("document.querySelector('[data-api-course]').textContent.includes('4 credits · 2 sections')"), "Catalog preserves database credits and section counts");
  await clickText("Details & sections", "[data-api-course]"); await waitFor("document.querySelector('.api-course-dialog .catalog-course-details')", "course detail from database");
  check(await evaluate("document.querySelector('.api-course-dialog').textContent.includes('40100')&&document.querySelector('.api-course-dialog').textContent.includes('40101')"), "Both real fixture sections appear");
  check(await evaluate("document.querySelector('.api-course-dialog').textContent.includes('Eligibility has not been verified')"), "Conditions remain explicitly unverified");
  await clickText("Close course details", ".api-course-dialog");
  await search("MATH-021");
  check(await evaluate("[...document.querySelectorAll('[data-api-course]')].every(card=>card.querySelector('[data-add-next-semester]'))"), "Every semester search result has an Add to next semester button");
  await clickText("Add to next semester", '[data-api-course="MATH-021"]');
  check(await evaluate("document.querySelector('[data-add-next-semester=\"MATH-021\"]').disabled"), "Already-added courses cannot be duplicated");
  await evaluate("document.querySelector('[data-route=requirements]').click()");
  await waitFor("document.querySelector('[data-api-degree-course=\"MATH-021\"]')", "newly selected course in degree calendar");
  check(await evaluate("document.querySelector('.api-degree-term').textContent.includes('Choose sections')&&!document.querySelector('.api-degree-term [data-degree-course]')"), "Degree next semester shows the real planned course instead of sample courses without inventing meetings");
  await evaluate("document.querySelector('.api-degree-term [data-action=degree-to-calendar]').click()");
  check(await evaluate("document.body.dataset.activeScreen==='planner'"), "Degree calendar opens the matching weekly plan");
  await search("ENGL-001"); await clickText("Add to next semester", '[data-api-course="ENGL-001"]');
  await setInput('[name="minCredits"]', "7"); await setInput('[name="maxCredits"]', "7"); await setInput('[name="maxResults"]', "2");
  await evaluate("document.querySelector('[aria-label=\"Close Intelligent Planning\"]').click()");
  await generate(); await waitFor("document.querySelectorAll('[data-schedule-rank]').length===2", "two real solver alternatives");
  const result = await evaluate("window.__apiResponses.find(r=>r.url.includes('/generate')&&r.status===200).body");
  check(result.schedules.length === 2 && result.schedules.every(s => s.totalCredits === 7 && s.overload === false), "Real solver returns two 7-credit alternatives");
  check(await evaluate(`document.querySelector('.api-schedule-facts').textContent.includes('Solver score: ${result.schedules[0].score} / 100')`), "Frontend displays the exact server score");
  check(await evaluate("document.querySelectorAll('.api-week > .api-calendar-scroll [data-event-crn]').length===1&&!document.querySelector('[data-event-crn=\"40200\"]')"), "TBA sections create no fake calendar events");
  check(await evaluate("document.querySelector('.api-section-list').textContent.includes('40200')&&document.querySelector('.api-section-list').textContent.includes('TBA')"), "TBA section is still listed");
  await evaluate("document.querySelector('[data-route=requirements]').click()");
  check(await evaluate("document.querySelectorAll('.api-degree-term [data-api-degree-course]').length===2&&document.querySelector('.api-degree-term .degree-term-heading').textContent.includes('7 credits')"), "All actual calendar courses and credits automatically prefill next semester in the degree calendar");
  check(await evaluate("document.querySelector('[data-api-degree-course=\"ENGL-001\"]').textContent.includes('TBA / arranged')"), "Degree calendar keeps actual TBA courses without fake meeting times");
  for (const [width, height] of [[1440,900], [800,700], [390,844]]) {
    await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: width < 600 }); await pause(100);
    check(await evaluate("(()=>{const r=document.querySelector('.degree-calendar-scroll').getBoundingClientRect();return r.left>=-1&&r.right<=innerWidth+1&&r.bottom<=innerHeight+1})()"), `${width}px: populated live degree calendar stays visible`);
    check(await evaluate("document.querySelector('.api-degree-term .degree-term-courses').getBoundingClientRect().height>=28"), `${width}px: next-semester controls leave space for visible course cards`);
    const shot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false }); await writeFile(path.join(output, `degree-next-semester-${width}.png`), Buffer.from(shot.data, "base64"));
  }
  await send("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await evaluate("document.querySelector('[data-api-degree-course=\"MATH-021\"] button').click()");
  await waitFor("document.querySelector('.api-course-dialog[open] .catalog-course-details')", "live course details from degree card");
  check(await evaluate("document.body.dataset.activeScreen==='planner'&&document.querySelector('.api-course-dialog').textContent.includes('40100')"), "Degree course cards open actual API section details");
  await clickText("Close course details", ".api-course-dialog");
  await evaluate("document.querySelectorAll('[data-schedule-rank]')[1].click()");
  check(await evaluate(`document.querySelector('.api-section-list').textContent.includes('${result.schedules[1].sections.find(s=>s.courseId==='MATH-021').crn}')`), "Choosing another alternative updates the actual CRN");
  for (const [width, height] of [[1440,900], [800,700], [390,844]]) {
    await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: width < 600 }); await pause(100);
    check(await evaluate("document.documentElement.scrollWidth<=innerWidth+1"), `${width}px: page fits viewport`);
    check(await evaluate("[...document.querySelectorAll('.product-nav-tabs [data-route]')].every(b=>getComputedStyle(b).display!=='none'&&b.getBoundingClientRect().width>0)"), `${width}px: all four navigation tabs remain visible`);
    await evaluate("document.querySelector('.api-week').scrollIntoView()");
    const shot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false }); await writeFile(path.join(output, `live-calendar-${width}.png`), Buffer.from(shot.data, "base64"));
  }
  await send("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await evaluate("window.prompt=()=> 'Synthetic saved plan'"); await clickText("Save schedule");
  check(await evaluate("JSON.parse(localStorage.getItem('smartcourse-api-plans-v1'))[0].crns.length===2&&!localStorage.getItem('smartcourse-api-plans-v1').includes('meetings')"), "Saved plans contain identifiers and preferences, not catalog snapshots");
  await search("OPT-100"); await clickText("Add to next semester", '[data-api-course="OPT-100"]');
  await waitFor("document.querySelector('.api-planner').textContent.includes('your existing meetings are kept')", "adding a course rechecks the existing schedule");
  check(await evaluate("document.querySelectorAll('.api-section-list > li').length===2&&document.querySelector('[data-api-degree-course=\"OPT-100\"]').dataset.sectionState==='planned'"), "Adding another course preserves current sections and marks the new course as awaiting sections");
  await evaluate("document.querySelector('[data-route=saved]').click()"); await clickText("Open plan", ".api-saved");
  await waitFor("document.querySelector('[aria-label=\"Manual section validation\"]')?.textContent.includes('Section selection is complete')", "saved plan refreshed through real checker"); checks++;
  await evaluate("document.querySelector('[aria-label=\"Remove CRN 40200\"]').click()");
  await waitFor("document.querySelector('.api-schedule-facts')?.textContent.includes('4 credits')", "manual section removal");
  check(await evaluate("document.querySelector('[data-api-degree-course=\"ENGL-001\"]').dataset.sectionState==='planned'"), "Removing sections updates the degree card to planned rather than retaining stale meetings");
  check(await evaluate("document.querySelector('[aria-label=\"Manual section validation\"]').textContent.includes('INCOMPLETE')"), "Manual checker identifies missing required sections");
  await clickText("Undo"); await waitFor("document.querySelector('.api-schedule-facts')?.textContent.includes('7 credits')", "undo rechecks real sections"); checks++;
  await clickText("Redo"); await waitFor("document.querySelector('.api-schedule-facts')?.textContent.includes('4 credits')", "redo rechecks real sections"); checks++;
  await clickText("Undo"); await waitFor("document.querySelector('.api-schedule-facts')?.textContent.includes('7 credits')", "restore complete selection");
  await clickText("Explain selected schedule"); await waitFor("document.querySelector('.api-error')", "AI unavailable response");
  check(await evaluate("window.__apiResponses.some(r=>r.url.includes('/explain')&&r.status===503)"), "Unconfigured explanation reports actual 503 rather than fake AI output");
  const beforeInvalid = await evaluate("window.__apiRequests.filter(r=>r.url.includes('/generate')).length");
  await setInput('[name="maxCredits"]', "22"); await generate();
  check(await evaluate("document.querySelector('.api-error').textContent.includes('between 1 and 21')"), "Client validation is useful and field specific");
  check(await evaluate(`window.__apiRequests.filter(r=>r.url.includes('/generate')).length===${beforeInvalid}`), "Invalid local input is not submitted");
  await setInput('[name="maxCredits"]', "7");
  await evaluate("const previousFetch=window.fetch;window.fetch=function(url,options){if(String(url).includes('/schedules/generate')){window.fetch=previousFetch;const body=JSON.parse(options.body);body.maxResults=0;options={...options,body:JSON.stringify(body)};}return previousFetch.call(this,url,options);}");
  await generate();
  check(await evaluate("document.querySelector('.api-error').textContent.includes('maxResults')"), "Real backend 400 validation appears in the UI");
  check(await evaluate("window.__apiResponses.some(r=>r.status===400&&r.body.errors.some(e=>e.field==='maxResults'))"), "Spring Boot supplied the field validation response");
  await setInput('[name="minCredits"]', "21"); await setInput('[name="maxCredits"]', "21"); await generate();
  check(await evaluate("document.querySelector('.api-results').textContent.includes('No feasible schedules were returned')"), "No-feasible result has a correction action");
  check(await evaluate("!document.querySelector('[data-schedule-rank]')&&!document.querySelector('.api-week [data-event-crn]')"), "No fabricated schedules or stale calendar events");
  await search("does-not-exist"); check(await evaluate("document.querySelector('.api-catalog-panel').textContent.includes('No courses match')"), "Empty search is handled");
  await send("Network.setBlockedURLs", { urls: ["*api/backend/*"] }); await search("MATH").catch(() => {});
  await waitFor("document.querySelector('.api-catalog-panel .api-error')", "network error notice");
  check(await evaluate("document.querySelector('.api-catalog-panel .api-error').textContent.includes('Cannot reach')"), "Backend network failure is actionable");
  check(await evaluate("!document.querySelector('[data-api-course]')"), "No demo catalog fallback when the backend is unavailable");
  await send("Network.setBlockedURLs", { urls: [] }); await clickText("Retry", ".api-catalog-panel"); await waitFor("document.querySelector('[data-api-course=\"MATH-021\"]')", "network retry"); checks++;
  await evaluate("document.querySelector('[data-route=requirements]').click()"); check(await evaluate("document.body.dataset.activeScreen==='requirements'&&document.querySelector('.api-prototype-label')"), "Degree planner remains available and honestly labeled demo");
  for (const [width, height] of [[1440,900], [800,700], [390,844]]) {
    await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: width < 600 }); await pause(100);
    check(await evaluate("(()=>{const r=document.querySelector('.degree-calendar-scroll').getBoundingClientRect();return r.left>=-1&&r.right<=innerWidth+1&&r.bottom<=innerHeight+1})()"), `${width}px: normal-mode degree calendar stays completely visible`);
  }
  await evaluate("document.querySelector('[data-route=planner]').click()"); check(await evaluate("document.body.dataset.activeScreen==='planner'"), "Navigation remains usable after failed requests");
  const preflight = await fetch(`${backend}/api/schedules/generate`, { method: "OPTIONS", headers: { Origin: "http://localhost:3000", "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type" } });
  check(preflight.ok && preflight.headers.get("access-control-allow-origin") === "http://localhost:3000", "Real Spring CORS preflight permits configured local origin");
  const denied = await fetch(`${backend}/api/courses?term=2027-SPRING`, { headers: { Origin: "https://unapproved.invalid" } }); check(denied.status === 403, "Unapproved cross-origin access stays blocked");
  requests.push(...await evaluate("window.__apiRequests")); responses.push(...await evaluate("window.__apiResponses"));
  const generatedRequest = requests.find(r => r.url.includes("/generate"));
  check(generatedRequest.body.maxResults === 2 && generatedRequest.body.includeClosedSections === false && generatedRequest.body.preferences.hardConstraints.requiredCourseIds.includes("MATH-021"), "Browser submitted exact documented request values");
  check(errors.length === 0, `No browser exceptions or console errors: ${errors.join("; ")}`);
  const unavailablePort = await availablePort(), unavailableBackendPort = await availablePort(), unavailableApp = `http://127.0.0.1:${unavailablePort}`;
  children.push(spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "--hostname", "127.0.0.1", "--port", String(unavailablePort)], { cwd: process.cwd(), env: { ...process.env, FRONTEND_MODE: "api", BACKEND_URL: `http://127.0.0.1:${unavailableBackendPort}` }, windowsHide: true, stdio: "ignore" }));
  for (let i = 0; i < 120; i++) { try { if ((await fetch(unavailableApp)).ok) break; } catch {} await pause(100); }
  await send("Page.navigate", { url: unavailableApp }); await waitFor("document.documentElement.dataset.sylloRuntime==='loaded' && document.querySelector('.api-error')", "actual unreachable Spring backend");
  await evaluate("document.querySelector('[data-action=login]').click()");
  check(await evaluate("document.querySelector('.api-error').textContent.includes('Cannot reach the backend')&&!document.querySelector('[data-api-course]')"), "Actual proxy connection refusal produces a useful error without demo courses");
  check((await fetch(unavailableApp + "/api/backend/terms")).status === 503, "Unreachable backend returns HTTP 503 through the real proxy");
  await writeFile(path.join(output, "evidence.json"), JSON.stringify({ checks, backend, requests, responses, errors }, null, 2));
  console.log(`PASS: ${checks} real frontend/Spring Boot/PostgreSQL/native-solver acceptance checks`); console.log(`Artifacts: ${output}`);
} catch (error) {
  if (socket?.readyState === WebSocket.OPEN) {
    await writeFile(path.join(output, "failure.json"), JSON.stringify({ error: String(error), browserErrors: errors, body: await evaluate("document.querySelector('.api-planner')?.innerText"), requests: await evaluate("window.__apiRequests"), responses: await evaluate("window.__apiResponses") }, null, 2));
  }
  console.error(`Failure artifacts: ${output}`); throw error;
} finally { socket?.close(); for (const child of children.reverse()) child.kill(); }
