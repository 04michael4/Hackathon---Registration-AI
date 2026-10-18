import { spawn } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

const referenceUrl = process.env.SYLLO_REFERENCE_URL ?? "http://127.0.0.1:4173";
const implementationUrl = process.env.SYLLO_IMPLEMENTATION_URL ?? "http://127.0.0.1:3000";
const outputRoot = process.env.SYLLO_PARITY_OUTPUT ?? path.join(os.tmpdir(), "syllo-parity");
const chromePath =
  process.env.CHROME_PATH ?? "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";

class CdpClient {
  constructor(url) {
    this.socket = new WebSocket(url);
    this.nextId = 1;
    this.pending = new Map();
    this.events = [];
  }

  async connect() {
    await new Promise((resolve, reject) => {
      this.socket.addEventListener("open", resolve, { once: true });
      this.socket.addEventListener("error", reject, { once: true });
    });
    this.socket.addEventListener("message", (event) => {
      const message = JSON.parse(event.data);
      if (message.id) {
        const pending = this.pending.get(message.id);
        if (!pending) return;
        this.pending.delete(message.id);
        if (message.error) pending.reject(new Error(message.error.message));
        else pending.resolve(message.result);
        return;
      }
      this.events.push(message);
    });
  }

  send(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.socket.send(JSON.stringify({ id, method, params }));
    });
  }

  close() {
    this.socket.close();
  }
}

async function waitForJson(port) {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const pages = await fetch(`http://127.0.0.1:${port}/json/list`).then((response) =>
        response.json(),
      );
      const page = pages.find((item) => item.type === "page");
      if (page?.webSocketDebuggerUrl) return page.webSocketDebuggerUrl;
    } catch {
      // Chrome is still starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`Chrome DevTools did not start on port ${port}.`);
}

async function startBrowser(port, label) {
  const profile = await mkdtemp(path.join(os.tmpdir(), `syllo-${label}-`));
  const process = spawn(
    chromePath,
    [
      "--headless=new",
      "--disable-gpu",
      "--hide-scrollbars",
      "--no-first-run",
      "--no-default-browser-check",
      `--remote-debugging-port=${port}`,
      `--user-data-dir=${profile}`,
      "about:blank",
    ],
    { stdio: "ignore" },
  );
  const client = new CdpClient(await waitForJson(port));
  await client.connect();
  await Promise.all([
    client.send("Page.enable"),
    client.send("Runtime.enable"),
    client.send("Log.enable"),
  ]);
  return { client, process, profile };
}

async function evaluate(browser, expression, awaitPromise = false) {
  const result = await browser.client.send("Runtime.evaluate", {
    expression,
    awaitPromise,
    returnByValue: true,
  });
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description ?? "Browser evaluation failed.");
  }
  return result.result.value;
}

async function waitFor(browser, expression, label) {
  for (let attempt = 0; attempt < 160; attempt += 1) {
    if (await evaluate(browser, expression)) return;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`Timed out waiting for ${label}.`);
}

async function navigate(browser, url) {
  await browser.client.send("Page.navigate", { url });
  await waitFor(
    browser,
    "Boolean(window.SylloPolish && document.querySelector('[data-screen=\"login\"]'))",
    `${url} runtime`,
  );
  await evaluate(
    browser,
    `(() => {
      const style=document.createElement('style');
      style.dataset.parityStability='true';
      style.textContent='*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important}';
      document.head.append(style);
    })()`,
  );
  await evaluate(browser, "document.fonts.ready.then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))", true);
}

async function setViewport(browser, width, height) {
  await browser.client.send("Emulation.setDeviceMetricsOverride", {
    width,
    height,
    deviceScaleFactor: 1,
    mobile: width <= 600,
  });
}

async function settle(browser) {
  await evaluate(
    browser,
    "new Promise(resolve => { document.getElementById('toast')?.classList.remove('show'); window.scrollTo(0, 0); setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(resolve)), 700); })",
    true,
  );
}

async function screenshot(browser, file) {
  await settle(browser);
  const result = await browser.client.send("Page.captureScreenshot", {
    format: "png",
    fromSurface: true,
    captureBeyondViewport: false,
  });
  const image = Buffer.from(result.data, "base64");
  await writeFile(file, image);
  return image;
}

async function both(reference, implementation, expression) {
  return Promise.all([
    evaluate(reference, expression, true),
    evaluate(implementation, expression, true),
  ]);
}

async function workflowProbe(reference, implementation, name, expression, report, validate) {
  const [referenceValue, implementationValue] = await both(
    reference,
    implementation,
    expression,
  );
  const identical = JSON.stringify(referenceValue) === JSON.stringify(implementationValue);
  const valid = validate(referenceValue);
  report.workflows.push({ name, identical, valid, value: referenceValue });
  if (!identical) report.failures.push(`${name}: workflow results differ`);
  if (!valid) report.failures.push(`${name}: reference workflow did not reach the expected state`);
}

async function capturePair(reference, implementation, name, width, height, report) {
  await Promise.all([
    setViewport(reference, width, height),
    setViewport(implementation, width, height),
  ]);
  const [referenceImage, implementationImage] = await Promise.all([
    screenshot(reference, path.join(outputRoot, `${name}-reference.png`)),
    screenshot(implementation, path.join(outputRoot, `${name}-implementation.png`)),
  ]);
  const identical = referenceImage.equals(implementationImage);
  report.screenshots.push({ name, width, height, identical, bytes: referenceImage.length });
  if (!identical) report.failures.push(`${name}: screenshots differ`);
}

function browserErrors(browser) {
  return browser.client.events
    .filter(
      (event) =>
        event.method === "Runtime.exceptionThrown" ||
        (event.method === "Log.entryAdded" && event.params.entry.level === "error"),
    )
    .map((event) => event.params.exceptionDetails?.text ?? event.params.entry?.text ?? event.method);
}

const report = { screenshots: [], workflows: [], failures: [], errors: {} };
await mkdir(outputRoot, { recursive: true });
const reference = await startBrowser(9322, "reference");
const implementation = await startBrowser(9323, "implementation");

try {
  await Promise.all([
    navigate(reference, referenceUrl),
    navigate(implementation, implementationUrl),
  ]);
  await capturePair(reference, implementation, "login-desktop", 1440, 900, report);
  await capturePair(reference, implementation, "login-mobile", 390, 844, report);

  await both(reference, implementation, "document.querySelector('[data-action=\"login\"]').click()");
  await Promise.all([
    waitFor(reference, "document.querySelector('[data-screen=\"features\"]').classList.contains('active')", "reference features"),
    waitFor(implementation, "document.querySelector('[data-screen=\"features\"]').classList.contains('active')", "implementation features"),
  ]);
  await capturePair(reference, implementation, "features-laptop", 1280, 800, report);

  await both(reference, implementation, "window.Syllo.navigate('planner')");
  await both(reference, implementation, "document.querySelector('[data-sem-action=\"toggle-ai\"]').click()");
  await capturePair(reference, implementation, "planner-ai-tablet", 1024, 768, report);
  await workflowProbe(
    reference,
    implementation,
    "separate course and interest search",
    `(() => {
      const courseInput=document.getElementById('course-search');
      courseInput.value='CSE 109';
      courseInput.dispatchEvent(new Event('input',{bubbles:true}));
      const courseMatches=[...document.querySelectorAll('#course-search-results .search-result-main span')].map(item=>item.textContent.trim());
      const interestInput=document.getElementById('interest-planner');
      interestInput.value='machine learning';
      interestInput.dispatchEvent(new Event('input',{bubbles:true}));
      const interestMatches=[...document.querySelectorAll('#interest-results-planner article strong')].map(item=>item.textContent.trim());
      return {courseMatches,interestMatches};
    })()`,
    report,
    (value) => value.courseMatches.includes("CSE 109") && value.interestMatches.length > 0,
  );
  await workflowProbe(
    reference,
    implementation,
    "assistant preview and retained conversation",
    `(() => {
      const input=document.getElementById('planner-chat-input');
      input.value='Keep Fridays free and 12 to 16 credits';
      input.dispatchEvent(new Event('input',{bubbles:true}));
      document.getElementById('planner-chat-form').requestSubmit();
      const preview=window.SylloPlanner.getPreview();
      const previewCourses=preview?.ids||[];
      document.querySelector('[data-sem-action="apply-preview"]')?.click();
      const result={previewCourses,applied:JSON.stringify(previewCourses)===JSON.stringify(window.Syllo.state.planner),dayOff:window.Syllo.state.preferences.dayOff,lastMessage:window.Syllo.state.chat.at(-1)?.text||''};
      return result;
    })()`,
    report,
    (value) => value.previewCourses.length > 0 && value.applied && value.dayOff === "Fri" && value.lastMessage.length > 0,
  );
  await workflowProbe(
    reference,
    implementation,
    "course add, remove, and keyboard undo",
    `(() => {
      const before=[...window.Syllo.state.planner];
      window.SylloPlanner.add('art003');
      const added=window.Syllo.state.planner.includes('art003');
      window.SylloPlanner.remove('art003');
      const removed=!window.Syllo.state.planner.includes('art003');
      document.body.dispatchEvent(new KeyboardEvent('keydown',{key:'z',ctrlKey:true,bubbles:true}));
      const restoredAdded=window.Syllo.state.planner.includes('art003');
      document.body.dispatchEvent(new KeyboardEvent('keydown',{key:'z',ctrlKey:true,bubbles:true}));
      return {added,removed,restoredAdded,restoredInitial:JSON.stringify(before)===JSON.stringify(window.Syllo.state.planner)};
    })()`,
    report,
    (value) => Object.values(value).every(Boolean),
  );
  await workflowProbe(
    reference,
    implementation,
    "semester recommendation drag and drop",
    `(() => {
      const card=document.querySelector('[data-sem-drag="cse109"]');
      const calendar=document.getElementById('planner-week-calendar');
      const transfer=new DataTransfer();
      card.dispatchEvent(new DragEvent('dragstart',{bubbles:true,dataTransfer:transfer}));
      calendar.dispatchEvent(new DragEvent('dragover',{bubbles:true,cancelable:true,dataTransfer:transfer}));
      calendar.dispatchEvent(new DragEvent('drop',{bubbles:true,cancelable:true,dataTransfer:transfer}));
      card.dispatchEvent(new DragEvent('dragend',{bubbles:true,dataTransfer:transfer}));
      const added=window.Syllo.state.planner.includes('cse109');
      window.SylloPlanner.remove('cse109');
      return {added,removed:!window.Syllo.state.planner.includes('cse109')};
    })()`,
    report,
    (value) => value.added && value.removed,
  );

  await Promise.all([
    setViewport(reference, 1440, 900),
    setViewport(implementation, 1440, 900),
  ]);
  await both(reference, implementation, "document.activeElement?.blur(); window.scrollTo(0,0); window.SylloPlanner.add('ds210'); window.SylloPlanner.add('eco001')");
  await Promise.all([
    waitFor(reference, "document.getElementById('sem-dialog').open", "reference conflict dialog"),
    waitFor(implementation, "document.getElementById('sem-dialog').open", "implementation conflict dialog"),
  ]);
  await capturePair(reference, implementation, "planner-conflict-desktop", 1440, 900, report);
  await both(reference, implementation, "document.querySelector('[data-sem-action=\"close-dialog\"]').click(); document.querySelector('[data-sem-action=\"expand-calendar\"]').click()");
  await capturePair(reference, implementation, "planner-expanded-laptop", 1280, 800, report);
  await both(reference, implementation, "document.getElementById('sem-full-calendar').close(); window.Syllo.saveSchedule('Reference parity plan'); window.Syllo.navigate('saved')");
  await capturePair(reference, implementation, "saved-mobile", 390, 844, report);

  await both(reference, implementation, "window.Syllo.navigate('requirements'); document.querySelector('[data-degree-course=\"cse241\"] .degree-course-info').click()");
  await Promise.all([
    waitFor(reference, "document.body.classList.contains('degree-details-open')", "reference degree details"),
    waitFor(implementation, "document.body.classList.contains('degree-details-open')", "implementation degree details"),
  ]);
  await capturePair(reference, implementation, "degree-details-tablet", 768, 1024, report);
  await capturePair(reference, implementation, "degree-details-mobile", 390, 844, report);
  await workflowProbe(
    reference,
    implementation,
    "degree move validation and completed-course lock",
    `(() => {
      const prerequisiteRejected=window.Syllo.moveDegree('cse202','fall-2027','spring-2027');
      const completedRejected=window.Syllo.moveDegree('cse007','fall-2024','spring-2027');
      const moved=window.Syllo.moveDegree('cse252','fall-2027','spring-2028');
      const restored=window.Syllo.moveDegree('cse252','spring-2028','fall-2027');
      return {prerequisiteRejected:!prerequisiteRejected.ok,completedRejected:!completedRejected.ok,moved:moved.ok,restored:restored.ok};
    })()`,
    report,
    (value) => Object.values(value).every(Boolean),
  );
  await workflowProbe(
    reference,
    implementation,
    "degree course drag and drop",
    `(() => {
      function drag(id,from,to){
        const card=document.querySelector('[data-degree-course="'+id+'"][data-degree-from="'+from+'"]');
        const target=document.querySelector('[data-degree-term="'+to+'"]');
        const transfer=new DataTransfer();
        card.dispatchEvent(new DragEvent('dragstart',{bubbles:true,dataTransfer:transfer}));
        target.dispatchEvent(new DragEvent('dragover',{bubbles:true,cancelable:true,dataTransfer:transfer}));
        target.dispatchEvent(new DragEvent('drop',{bubbles:true,cancelable:true,dataTransfer:transfer}));
        card.dispatchEvent(new DragEvent('dragend',{bubbles:true,dataTransfer:transfer}));
      }
      drag('cse252','fall-2027','spring-2028');
      const moved=window.Syllo.state.degree['spring-2028'].includes('cse252');
      drag('cse252','spring-2028','fall-2027');
      return {moved,restored:window.Syllo.state.degree['fall-2027'].includes('cse252')};
    })()`,
    report,
    (value) => value.moved && value.restored,
  );
  await workflowProbe(
    reference,
    implementation,
    "degree workspace tabs",
    `(() => {
      document.querySelector('[data-polish="degree-tab"][data-tab="ai"]').click();
      const aiVisible=!document.getElementById('degree-panel-ai').hidden;
      document.querySelector('[data-polish="degree-tab"][data-tab="plan"]').click();
      return {aiVisible,planVisible:!document.getElementById('degree-panel-plan').hidden};
    })()`,
    report,
    (value) => value.aiVisible && value.planVisible,
  );

  await both(
    reference,
    implementation,
    "document.querySelector('[data-action=\"close-drawer\"]').click(); document.querySelector('[data-degree-action=\"choose-elective\"][data-slot=\"elective-a\"]').click(); document.querySelector('[data-degree-action=\"select-elective\"][data-course=\"eco001\"]').click(); document.querySelector('[data-degree-action=\"choose-elective\"][data-slot=\"elective-b\"]').click(); document.querySelector('[data-degree-action=\"select-elective\"][data-course=\"art003\"]').click(); window.Syllo.navigate('requirements'); document.querySelector('[data-degree-action=\"submit-review\"]').click(); document.getElementById('role-switch').click()",
  );
  await Promise.all([
    waitFor(reference, "window.Syllo.state.role === 'advisor' && document.getElementById('advisor-feedback-form')", "reference advisor"),
    waitFor(implementation, "window.Syllo.state.role === 'advisor' && document.getElementById('advisor-feedback-form')", "implementation advisor"),
  ]);
  await both(
    reference,
    implementation,
    "const input=document.getElementById('advisor-feedback-input'); input.value='Keep the capstone sequence as planned.'; input.dispatchEvent(new Event('input',{bubbles:true})); document.getElementById('advisor-feedback-form').requestSubmit(); document.querySelector('[data-degree-action=\"approve\"]').click(); document.querySelector('[data-advisor-filter=\"Review\"]').click(); document.querySelector('[data-advisor-filter=\"Planned\"]').click()",
  );
  await capturePair(reference, implementation, "advisor-approved-desktop", 1440, 900, report);
  await capturePair(reference, implementation, "advisor-approved-mobile", 390, 844, report);

  const behaviorExpression = `JSON.stringify({
    planner: window.Syllo.state.planner,
    saved: window.Syllo.state.saved.map(item => ({name:item.name,courses:item.courses})),
    audit: window.Syllo.degreeAudit(),
    review: {status:window.Syllo.state.review.status,comments:window.Syllo.state.review.comments.map(item=>({text:item.text,type:item.type})),notifications:window.Syllo.state.review.notifications.map(item=>item.text)},
    interest: window.SylloPolish.searchInterest('machine learning').map(item=>item.id),
    filters: [...document.querySelectorAll('[data-advisor-filter][aria-pressed=\"true\"]')].map(item=>item.dataset.advisorFilter),
    persisted: Boolean(localStorage.getItem('syllo-planning-v3'))
  })`;
  const [referenceState, implementationState] = await both(
    reference,
    implementation,
    behaviorExpression,
  );
  const stateIdentical = referenceState === implementationState;
  report.workflows.push({ name: "planner-degree-advisor-local-state", identical: stateIdentical });
  if (!stateIdentical) report.failures.push("workflow state differs");
  report.errors.reference = browserErrors(reference);
  report.errors.implementation = browserErrors(implementation);
  if (JSON.stringify(report.errors.reference) !== JSON.stringify(report.errors.implementation)) {
    report.failures.push("reference and implementation emitted different browser errors");
  }
} finally {
  reference.client.close();
  implementation.client.close();
  reference.process.kill();
  implementation.process.kill();
  await Promise.allSettled([
    rm(reference.profile, { recursive: true, force: true }),
    rm(implementation.profile, { recursive: true, force: true }),
  ]);
}

await writeFile(path.join(outputRoot, "report.json"), JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
if (report.failures.length) process.exitCode = 1;
