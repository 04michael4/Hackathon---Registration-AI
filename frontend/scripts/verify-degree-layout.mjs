// Visual/interaction regression checks against a running app. Uses only the browser-local demo.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

const url = process.env.SYLLO_LAYOUT_URL ?? "http://localhost:3000";
const captureOnly = process.env.SYLLO_LAYOUT_CAPTURE_ONLY === "true";
const output = await mkdtemp(path.join(tmpdir(), "syllo-degree-layout-"));
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const portServer = createServer();
await new Promise(resolve => portServer.listen(0, "127.0.0.1", resolve));
const port = portServer.address().port;
await new Promise(resolve => portServer.close(resolve));
const chrome = spawn(process.env.CHROME_PATH ?? "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", [
  "--headless=new", "--disable-gpu", "--no-first-run", `--remote-debugging-port=${port}`,
  `--user-data-dir=${path.join(output, "profile")}`, "about:blank",
], { windowsHide: true, stdio: "ignore" });
let socket, id = 0, checks = 0;
const pending = new Map(), errors = [];
function send(method, params = {}) {
  return new Promise((resolve, reject) => { const requestId = ++id; pending.set(requestId, { resolve, reject }); socket.send(JSON.stringify({ id: requestId, method, params })); });
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
const check = (value, label) => { assert.ok(value, label); checks++; };
async function screenshot(name) {
  await pause(100);
  const result = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
  await writeFile(path.join(output, `${name}.png`), Buffer.from(result.data, "base64"));
}
async function searchSemester(query) {
  await evaluate(`(()=>{const input=document.querySelector('#course-search');input.focus();input.value=${JSON.stringify(query)};input.dispatchEvent(new Event('input',{bubbles:true}))})()`);
}
async function verifySemesterSearch() {
  await evaluate("window.Syllo.navigate('planner')");
  check(await evaluate("document.querySelectorAll('#course-search-area input[type=search]').length===1&&!document.querySelector('#course-search-area .interest-search')"), "Semester has one course and interest search box");
  check(await evaluate("document.querySelector('label.course-search').textContent.includes('Search courses & interests')"), "Unified search label describes both kinds of search");
  for (const [width, height] of [[1440,900], [800,700], [390,844]]) {
    await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: width < 600 });
    await searchSemester("finance");
    check(await evaluate("document.querySelectorAll('#course-search-results .search-result').length===3&&document.querySelector('#course-search-results .search-result').textContent.includes('ECO 001')"), `${width}: interest aliases use the course result list`);
    check(await evaluate("(()=>{const r=document.querySelector('#course-search-results').getBoundingClientRect();return r.left>=-1&&r.right<=innerWidth+1})()"), `${width}: combined results fit the screen width`);
    await screenshot(`semester-unified-search-${width}`);
  }
  await send("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await searchSemester("cse17");
  check(await evaluate("document.querySelectorAll('#course-search-results .search-result').length===1&&document.querySelector('#course-search-results .search-result').textContent.includes('CSE 017')"), "Unified field retains forgiving course code search");
  await searchSemester("machine learning");
  check(await evaluate("document.querySelector('#course-search-results').textContent.includes('DS 210')&&document.querySelector('#course-search-results').textContent.includes('MATH 231')"), "Unified field retains machine learning recommendations");
  await searchSemester("security");
  check(await evaluate("document.querySelector('#course-search-results').textContent.includes('CSE 109')&&document.querySelector('#course-search-results').textContent.includes('CSE 252')"), "Unified field retains security recommendations");
  await searchSemester("finance");
  await evaluate("document.querySelector('#course-search-results [data-sem-action=add][data-id=eco001]').click()");
  check(await evaluate("window.Syllo.state.planner.includes('eco001')&&document.querySelector('#course-search-results [data-sem-action=add][data-id=eco001]').disabled"), "Interest result still adds to the schedule and updates its status");
  await evaluate("document.querySelector('#sem-undo').click()");
  check(await evaluate("!window.Syllo.state.planner.includes('eco001')&&!document.querySelector('#course-search-area .interest-search')"), "Undo restores the plan without recreating separate interest search");
  await searchSemester("ART003");
  check(await evaluate("(()=>{const card=document.querySelector('#course-search-results [data-sem-drag=art003]');const transfer=new DataTransfer();card.dispatchEvent(new DragEvent('dragstart',{bubbles:true,dataTransfer:transfer}));document.querySelector('#planner-week-calendar').dispatchEvent(new DragEvent('drop',{bubbles:true,cancelable:true,dataTransfer:transfer}));return window.Syllo.state.planner.includes('art003')})()"), "Unified search results retain drag and drop to calendar");
  await evaluate("document.querySelector('#sem-undo').click()");
  await searchSemester("DS310");
  check(await evaluate("document.querySelector('#course-search-results [data-sem-action=add][data-id=ds310]').disabled&&document.querySelector('#course-search-results .sem-prerequisite').textContent.includes('DS 210')"), "Unmet prerequisites stay visible and block adding");
  await evaluate("document.querySelector('#course-search-results [data-sem-action=details][data-id=ds310]').click()");
  check(await evaluate("document.querySelector('#detail-drawer').classList.contains('open')&&document.querySelector('#detail-drawer').textContent.includes('DS 310')"), "Unified results retain the course details drawer");
  await evaluate("document.querySelector('[data-action=close-drawer]').click()");
  await searchSemester("");
  await evaluate("(()=>{const filter=document.querySelector('#sem-search-mode');filter.value='electives';filter.dispatchEvent(new Event('change',{bubbles:true}))})()");
  check(await evaluate("[...document.querySelectorAll('#course-search-results [data-sem-action=add]')].every(button=>!window.Syllo.catalog[button.dataset.id].required)"), "Elective-only filter still works");
  await evaluate("(()=>{const filter=document.querySelector('#sem-search-mode');filter.value='all';filter.dispatchEvent(new Event('change',{bubbles:true}))})()");
  await searchSemester("zzzznotacourse");
  check(await evaluate("!!document.querySelector('#course-search-results .empty-state')"), "Unknown query shows an empty state");
  await searchSemester("finance");
  await evaluate("document.querySelector('#course-search').dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}))");
  check(await evaluate("document.activeElement.matches('#course-search-results [data-sem-action=add]:not([disabled])')"), "Enter focuses an available course action");
  await evaluate("document.querySelector('#course-search-results [data-sem-action=close-search]').click()");
  check(await evaluate("document.querySelector('#course-search').getAttribute('aria-expanded')==='false'&&!document.querySelector('#course-search-results').classList.contains('is-open')"), "Close results returns focus without reopening search");
  await searchSemester("finance");
  await evaluate("document.querySelector('#course-search').dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))");
  check(await evaluate("document.querySelector('#course-search').getAttribute('aria-expanded')==='false'"), "Escape closes the unified results");
  await evaluate("window.Syllo.navigate('requirements')");
}
const geometry = `(() => {
  const rect = element => {const r=element.getBoundingClientRect();return {left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height}};
  const calendar=document.querySelector('.degree-calendar-scroll');
  const nav=document.querySelector('#product-nav');
  return {width:innerWidth,height:innerHeight,calendar:rect(calendar),nav:rect(nav),
    pageWidth:document.documentElement.scrollWidth,pageHeight:document.documentElement.scrollHeight,
    terms:[...document.querySelectorAll('.degree-term-heading')].map(rect),
    tabs:[...nav.querySelectorAll('[data-route]')].map(element=>({...rect(element),visible:element.checkVisibility({checkOpacity:true,checkVisibilityCSS:true})})),
    inspector:document.querySelector('#dp-inspector')?.hidden===false?rect(document.querySelector('#dp-inspector')):null,
    bucket:rect(document.querySelector('.degree-bucket')),
    bucketInInspector:document.querySelector('#dp-inspector').contains(document.querySelector('.degree-bucket')),
    placeholderRow:!!document.querySelector('.dp-placeholder-row')};
})()`;
function inspect(g, label) {
  check(g.tabs.length === 4 && g.tabs.every(tab => tab.visible && tab.left >= -1 && tab.right <= g.width + 1 && tab.bottom <= g.nav.bottom + 1), `${label}: all four tabs stay visible`);
  check(g.calendar.left >= -1 && g.calendar.right <= g.width + 1 && g.calendar.top >= g.nav.bottom - 1 && g.calendar.bottom <= g.height + 1, `${label}: complete calendar stays inside viewport`);
  check(g.terms.length === 8 && g.terms.every(term => term.left >= g.calendar.left - 1 && term.right <= g.calendar.right + 1 && term.top >= g.calendar.top - 1 && term.bottom <= g.calendar.bottom + 1), `${label}: all semester headings stay visible`);
  check(g.pageWidth <= g.width + 1 && g.pageHeight <= g.height + 1, `${label}: no page overflow`);
  check(g.calendar.height >= g.height * (g.height < 560 ? 0.32 : 0.4), `${label}: calendar keeps substantial screen height`);
  check(!g.placeholderRow && g.bucketInInspector, `${label}: requirements stay in the side panel with no placeholder row`);
  if (g.inspector) check(g.calendar.right <= g.inspector.left + 1 || g.calendar.bottom <= g.inspector.top + 1, `${label}: plan tools never overlap calendar`);
}

try {
  let endpoint;
  for (let attempt = 0; attempt < 100; attempt++) {
    try { endpoint = (await fetch(`http://127.0.0.1:${port}/json/list`).then(result => result.json())).find(page => page.type === "page")?.webSocketDebuggerUrl; } catch { /* Starting Chrome. */ }
    if (endpoint) break; await pause(50);
  }
  assert.ok(endpoint, "Chrome did not start");
  socket = new WebSocket(endpoint);
  await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, { once: true }); socket.addEventListener("error", reject, { once: true }); });
  socket.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    if (message.id) {
      const job = pending.get(message.id); pending.delete(message.id);
      if (message.error) job.reject(new Error(message.error.message)); else job.resolve(message.result);
    }
    else if (message.method === "Runtime.exceptionThrown") errors.push(message.params.exceptionDetails.text);
    else if (message.method === "Runtime.consoleAPICalled" && message.params.type === "error") errors.push("Browser console error");
  });
  await send("Page.enable"); await send("Runtime.enable");
  await send("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await send("Page.navigate", { url });
  await waitFor("document.documentElement.dataset.sylloRuntime==='loaded'", "app runtime");
  await evaluate("document.querySelector('[data-action=login]').click();window.Syllo.navigate('requirements')");
  await waitFor("document.querySelectorAll('.degree-term').length===8", "degree calendar");
  if (captureOnly) {
    const baseline = await evaluate(geometry);
    await screenshot("before-desktop");
    console.log(JSON.stringify({ baseline, screenshots: output }));
  } else {
    await verifySemesterSearch();
    for (const [width, height] of [[1440,900], [1366,768], [1280,720], [1440,600], [1024,768], [900,650], [800,700], [390,844], [1280,500], [844,390], [640,480], [390,500]]) {
      await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: width < 600 });
      await pause(100);
      inspect(await evaluate(geometry), `${width}x${height}`);
      await screenshot(`degree-${width}x${height}`);
      await evaluate("document.querySelector('[data-degree-action=toggle-inspector]').click()");
      inspect(await evaluate(geometry), `${width}x${height} with requirements`);
      await screenshot(`degree-tools-${width}x${height}`);
      await evaluate("document.querySelector('[data-degree-action=close-inspector]').click()");
    }
    await send("Emulation.setDeviceMetricsOverride", { width: 1280, height: 720, deviceScaleFactor: 1, mobile: false });
    await evaluate("(()=>{const input=document.querySelector('#degree-course-query');input.value='Economics';input.dispatchEvent(new Event('input',{bubbles:true}))})()");
    await waitFor("document.querySelector('[data-degree-action=add-program]')", "minor search");
    inspect(await evaluate(geometry), "course search");
    check(await evaluate("document.querySelector('#dp-inspector').contains(document.querySelector('#degree-course-results'))"), "Search results are in the side panel");
    const addProgram = await evaluate("(()=>{const button=document.querySelector('[data-degree-action=add-program]');button.scrollIntoView({block:'nearest'});const r=button.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2}})()");
    await send("Input.dispatchMouseEvent", { type: "mousePressed", ...addProgram, button: "left", clickCount: 1 });
    await send("Input.dispatchMouseEvent", { type: "mouseReleased", ...addProgram, button: "left", clickCount: 1 });
    await waitFor("document.querySelectorAll('[data-degree-bucket] [data-degree-course]').length>0", "staged minor courses");
    inspect(await evaluate(geometry), "staged minor");
    check(await evaluate("document.querySelector('.dp-inspector .dp-trackers').textContent.includes('Economics minor')"), "Declared minor tracker stays in the requirements panel");
    await evaluate("window.Syllo.stageCourses(['art003','soc001','psy001','phil120','mus001']);window.SylloDegree.render()");
    inspect(await evaluate(geometry), "many staged courses");
    check(await evaluate("document.querySelector('[data-degree-bucket]').checkVisibility()&&document.querySelectorAll('[data-degree-bucket] [data-degree-course]').length>=5"), "Staged courses remain available in the requirements panel");
    await evaluate("document.querySelector('[data-degree-bucket]').scrollIntoView({block:'nearest',inline:'end'});document.querySelector('[data-degree-bucket] [data-degree-course=mus001]').scrollIntoView({block:'nearest',inline:'end'})");
    await screenshot("degree-many-staged");
    check(await evaluate("(()=>{const card=document.querySelector('[data-degree-bucket] [data-degree-course=mus001]');const target=document.querySelector('[data-degree-term=fall-2027]');const transfer=new DataTransfer();card.dispatchEvent(new DragEvent('dragstart',{bubbles:true,dataTransfer:transfer}));target.dispatchEvent(new DragEvent('drop',{bubbles:true,cancelable:true,dataTransfer:transfer}));document.dispatchEvent(new Event('dragend'));return window.Syllo.state.degree['fall-2027'].includes('mus001')&&!window.Syllo.state.bucket.includes('mus001')})()"), "Staging-to-calendar drag and drop still works");
    await evaluate("document.body.dispatchEvent(new KeyboardEvent('keydown',{key:'z',ctrlKey:true,bubbles:true}))");
    check(await evaluate("window.Syllo.state.bucket.includes('mus001')"), "Undo restores staged course");
    check(await evaluate("(()=>{const card=document.querySelector('[data-degree-bucket] [data-degree-course=mus001]');const target=document.querySelector('[data-degree-term=fall-2027]');let transfer=new DataTransfer();card.dispatchEvent(new DragEvent('dragstart',{bubbles:true,dataTransfer:transfer}));target.dispatchEvent(new DragEvent('drop',{bubbles:true,cancelable:true,dataTransfer:transfer}));document.dispatchEvent(new Event('dragend'));const planned=document.querySelector('[data-degree-term=fall-2027] [data-degree-course=mus001]');transfer=new DataTransfer();planned.dispatchEvent(new DragEvent('dragstart',{bubbles:true,dataTransfer:transfer}));document.querySelector('[data-degree-bucket]').dispatchEvent(new DragEvent('drop',{bubbles:true,cancelable:true,dataTransfer:transfer}));document.dispatchEvent(new Event('dragend'));return window.Syllo.state.bucket.includes('mus001')&&!window.Syllo.state.degree['fall-2027'].includes('mus001')})()"), "Calendar-to-staging drag and drop still works");
    await evaluate("document.querySelector('[data-degree-bucket] [data-degree-course=mus001] [data-degree-action=unstage]').click()");
    check(await evaluate("!window.Syllo.state.bucket.includes('mus001')"), "Requirements panel retains Remove from staging");
    await evaluate("window.Syllo.undo()");
    check(await evaluate("window.Syllo.state.bucket.includes('mus001')"), "Undo restores a removed staged course");
    await evaluate("document.querySelector('[data-degree-action=close-inspector]').click();document.querySelector('.degree-term.is-future .degree-course-info').dispatchEvent(new MouseEvent('click',{shiftKey:true,bubbles:true}))");
    check(await evaluate("(()=>{const bar=document.querySelector('#dp-selection-bar').getBoundingClientRect(),calendar=document.querySelector('.degree-calendar-scroll').getBoundingClientRect();return bar.top>=calendar.bottom-1})()"), "Bulk selection toolbar does not cover calendar");
    await evaluate("document.body.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));document.querySelector('#product-nav').dispatchEvent(new PointerEvent('pointerleave',{pointerType:'mouse'}))");
    await pause(500);
    inspect(await evaluate(geometry), "navigation after pointer leave and Escape");
    for (const action of ["open-schedules", "open-advisor", "open-saved", "open-degree"]) {
      await evaluate(`document.querySelector('#product-nav [data-action=${action}]').click()`);
      check(await evaluate("[...document.querySelectorAll('#product-nav [data-route]')].every(tab=>tab.checkVisibility({checkOpacity:true,checkVisibilityCSS:true}))"), `${action}: ribbon tabs remain visible`);
    }
    check(await evaluate("!document.querySelector('#product-nav-toggle')"), "Navigation has no dropdown toggle");
    await evaluate("document.querySelectorAll('[data-degree-action=toggle-year]')[2].click();document.querySelector('[data-degree-action=toggle-inspector]').click();document.querySelector('#degree-restore-term').value='fall-2026';document.querySelector('[data-degree-action=restore-term]').click()");
    check(await evaluate("!!document.querySelector('[data-degree-term=fall-2026]')&&!document.querySelector('[data-degree-term=spring-2027]')"), "Individual semester can still be restored");
    await evaluate("document.querySelector('#degree-restore-term').value='spring-2027';document.querySelector('[data-degree-action=restore-term]').click();document.querySelector('[data-degree-action=close-inspector]').click()");
    inspect(await evaluate(geometry), "individual semester restoration");
    check(await evaluate("!document.querySelector('#ai-widget-launcher').checkVisibility()"), "Floating launcher does not cover calendar or review controls");
    await evaluate("document.querySelector('[data-degree-action=open-degree-ai]').click()");
    check(await evaluate("!document.querySelector('#ai-widget-window').hidden"), "Toolbar still opens Syllo AI");
    await evaluate("document.querySelector('[data-ai-widget=close]').click()");
    check(errors.length === 0, "No browser exceptions or console errors");
    console.log(`PASS: ${checks} degree layout and navigation checks; screenshots: ${output}`);
  }
} catch (error) {
  if (socket?.readyState === WebSocket.OPEN) {
    await screenshot("failed-layout");
    console.error(JSON.stringify({ geometry: await evaluate(geometry), screenshots: output }));
  }
  throw error;
} finally { socket?.close(); chrome.kill(); }
