// LOCAL-ONLY Banner collector for the CSB research (paste once per page load into the Browse Classes page after choosing the term).
// It defines window.__csb with:
//   grid()                      the current results grid as JSON, INCLUDING the instructor cell (so its output is local-only research data)
//   download(name)              downloads grid() as a JSON file
//   collect(crns, file)         opens the Class Details popup of each CRN visible in the grid, reads an ALLOWLIST of panels, downloads one JSON file
//   runQueue(items)             for each {num, crns, file}: sets the Course Number, presses Search, sets 50 rows per page, calls collect()
//   queueStart / queueWait      start the queue in the background / wait up to 38 s for it (the browser tool times out after 45 s)
//
// Panels read: Class Details, Attributes, Restrictions, Prerequisites, Corequisites, Cross Listed Courses, Linked Sections.
// Never opened: Enrollment/Waitlist (seat counts), Instructor/Meeting Times, Fees, Bookstore, Course Description. The Status cell of the
// grid (seats, waitlist) is never read; the cross-list panel prints enrollment numbers above its table, so only the table rows are kept.
// Read-only: it clicks the section's title link, the allowlisted tabs and the popup's Close button, and sets the Course Number / Search
// on the form. It makes no network request of its own and never clicks Add, Register, Drop, Plan or any other action.
// The files it downloads hold real CRNs and instructor names: they are never committed (see data/csb-research/README.md).
window.__csb = window.__csb || {};
window.__csb.store = window.__csb.store || {};   // crn -> capture, survives errors so a rerun skips finished sections
window.__csb.grid = (from, to) => {
	const clean = s => (s || '').replace(/\s+/g, ' ').trim();
	const cell = (row, p) => row.querySelector('td[data-property="' + p + '"]');
	const text = (row, p) => { const c = cell(row, p); return c ? clean(c.innerText) : null; };
	const rows = [...document.querySelectorAll('tbody tr')].filter(r => cell(r, 'courseReferenceNumber'));
	const instructors = row => {
		const c = cell(row, 'instructor');
		if (!c) return [];
		const out = [];
		for (const a of c.querySelectorAll('a')) {
			const after = a.nextSibling && a.nextSibling.nodeType === 3 ? clean(a.nextSibling.textContent) : '';
			out.push({ name: clean(a.innerText), role: /^\(Primary\)/.test(after) ? 'Primary' : 'Additional' });
		}
		return out;
	};
	const all = rows.map(r => ({
		crn: text(r, 'courseReferenceNumber'), subject: text(r, 'subjectDescription'), number: text(r, 'courseNumber'),
		section: text(r, 'sequenceNumber'), title: text(r, 'courseTitle'), hours: text(r, 'creditHours'),
		type: text(r, 'scheduleType'), attribute: text(r, 'attribute'), linked: text(r, 'linked'),
		meetings: [...(cell(r, 'meetingTime') ? cell(r, 'meetingTime').querySelectorAll('div.meeting') : [])].map(m => clean(m.innerText)),
		instructors: instructors(r)
	}));
	const header = clean((document.querySelector('main') || document.body).innerText).match(/Search Results\s*(?:—|-)?\s*(\d+)\s*Classes/);
	return JSON.stringify({ total_rows_in_dom: all.length, banner_count: header ? header[1] : null, rows: all.slice(from || 0, to || all.length) });
};
window.__csb.save = (name, data) => {
	const blob = new Blob([JSON.stringify(data)], { type: 'application/json' });
	const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name;
	document.body.appendChild(a); a.click(); a.remove();
};
window.__csb.download = (name) => {
	const payload = JSON.parse(window.__csb.grid());
	payload.captured_at = new Date().toISOString();
	const lab = (document.querySelector('main') || document.body).innerText.replace(/\s+/g, ' ').match(/Term:.*?(?=Search Again|CRN)/);
	payload.search_label = lab ? lab[0].trim() : null;
	window.__csb.save(name, payload);
	return 'downloaded ' + name + ' rows=' + payload.rows.length + ' banner_count=' + payload.banner_count;
};
window.__csb.pagesize = async (n) => {
	const s = document.querySelector('select.page-size-select');
	if (!s) return 'no selector';
	s.value = String(n); s.dispatchEvent(new Event('change', { bubbles: true }));
	await new Promise(r => setTimeout(r, 2500));
	return 'rows ' + document.querySelectorAll('td[data-property="courseReferenceNumber"]').length;
};
window.__csb.collect = async (crns, fileName) => {
	const sleep = ms => new Promise(r => setTimeout(r, ms));
	const clean = s => (s || '').replace(/\s+/g, ' ').trim();
	const TABS = ['classDetails', 'attributes', 'restrictions', 'preReqs', 'coReqs', 'xlstSections', 'linked'];   // the ONLY tabs it will click
	if (!Array.isArray(crns) || !crns.length || !crns.every(c => /^\d{5}$/.test(c))) return JSON.stringify({ error: 'Pass an array of 5-digit CRNs.' });
	const progress = window.__csb.progress = { started: new Date().toISOString(), total: crns.length, done: 0, error: null, file: fileName };
	const visibleDialogs = () => [...document.querySelectorAll('[role="dialog"]')].filter(e => e.offsetParent !== null);
	const closeAll = async () => {
		for (const d of visibleDialogs()) { const b = [...d.querySelectorAll('button')].find(x => clean(x.innerText) === 'Close'); if (b) b.click(); }
		for (let i = 0; i < 20; i++) { await sleep(300); if (!visibleDialogs().length) return; }
	};
	await closeAll();
	const open = async CRN => {
		for (let attempt = 0; attempt < 3; attempt++) {
			const row = [...document.querySelectorAll('tbody tr')].find(r => { const c = r.querySelector('td[data-property="courseReferenceNumber"]'); return c && clean(c.innerText) === CRN; });
			const link = row && row.querySelector('td[data-property="courseTitle"] a');
			if (!link) return null;
			link.click();
			for (let i = 0; i < 30; i++) {
				await sleep(400);
				const d = visibleDialogs().find(e => e.innerText.includes('CRN: ' + CRN));
				if (d && d.querySelector('[role="tabpanel"]')) return d;
			}
			await closeAll();
		}
		return null;
	};
	for (const CRN of crns) {
		if (window.__csb.store[CRN]) { progress.done++; continue; }
		await sleep(1500);
		const dialog = await open(CRN);
		if (!dialog) { progress.error = CRN + ': NO_DIALOG'; break; }
		const header = dialog.innerText.match(/Term:\s*(\d{6})\s*\|\s*CRN:\s*(\d{5})/);
		if (!header || header[2] !== CRN) { progress.error = CRN + ': HEADER_MISMATCH'; await closeAll(); break; }
		const panel = () => [...dialog.querySelectorAll('[role="tabpanel"]')].find(e => e.offsetParent !== null);
		const label = (t, name) => { const m = t.match(new RegExp('(?:^|\\n)\\s*' + name + ':[ \\t]*([^\\n]*)')); return m ? clean(m[1]) : null; };
		const out = { view: 'CLASS_DETAILS_POPUP_CSB', captured_at: new Date().toISOString(), term_code: header[1], crn: CRN };
		let bad = null;
		let previous = null;
		for (const id of TABS) {
			if (id !== 'classDetails') {
				const tab = dialog.querySelector('h3#' + id + '[role="tab"]');
				if (!tab) { bad = 'TAB_MISSING_' + id; break; }
				tab.click();
				for (let i = 0; i < 20; i++) { await sleep(300); const p = panel(); if (p && clean(p.innerText) && clean(p.innerText) !== previous) break; }
				await sleep(400);
			}
			const p = panel();
			if (!p) { bad = 'PANEL_MISSING_' + id; break; }
			const raw = p.innerText;
			previous = clean(raw);
			if (id === 'classDetails') {
				out.details = {
					associated_term: label(raw, 'Associated Term'), campus: label(raw, 'Campus'), schedule_type: label(raw, 'Schedule Type'),
					section: label(raw, 'Section Number'), subject_description: label(raw, 'Subject'), course_number: label(raw, 'Course Number'),
					title: label(raw, 'Title'), credit_hours: label(raw, 'Credit Hours'), grade_mode: label(raw, 'Grade Mode'),
					instructional_method: label(raw, 'Instructional Method'), level: label(raw, 'Level')
				};
				out.details_text = previous;
			} else if (id === 'attributes') {
				out.attributes_text = previous;
			} else if (id === 'restrictions') {
				out.restrictions_text = previous;
			} else if (id === 'preReqs') {
				out.prerequisites_text = previous;
			} else if (id === 'coReqs') {
				out.corequisites_text = previous;
			} else if (id === 'xlstSections') {
				const rows = [];
				for (const tr of p.querySelectorAll('tr')) {
					const cells = [...tr.children].map(c => clean(c.innerText));
					if (cells.length >= 5 && /^\d{5}$/.test(cells[0])) rows.push({ crn: cells[0], subject: cells[1], course_number: cells[2], title: cells[3], section: cells[4] });
				}
				out.cross_list = { rows, has_panel: true, none_listed: rows.length === 0 && /no cross list|no cross-list/i.test(previous) };
				if (rows.length === 0) out.cross_list.panel_text = previous.replace(/\d+/g, '#').slice(0, 200);
			} else if (id === 'linked') {
				const options = [];
				let current = null;
				for (const tr of p.querySelectorAll('tr')) {
					const cells = [...tr.children].map(c => clean(c.innerText));
					if (cells.length === 1 && /^Option \d+$/.test(cells[0])) {
						current = { label: cells[0], sections: [] };
						options.push(current);
					} else if (cells.length === 4 && /^\d{5}$/.test(cells[3]) && current) {
						current.sections.push({ subject_description: cells[0], schedule_type: cells[1], section: cells[2], crn: cells[3] });
					}
				}
				out.linked = { options, panel_text: previous.slice(0, 600) };
			}
		}
		await closeAll();
		if (bad) { progress.error = CRN + ': ' + bad; break; }
		const json = JSON.stringify(out);
		if (/seats?\s+remain|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|\d{8,}/i.test(json)) { progress.error = CRN + ': SANITIZATION_TRIPWIRE'; break; }
		window.__csb.store[CRN] = out;
		progress.done++;
	}
	const captured = crns.filter(c => window.__csb.store[c]).map(c => window.__csb.store[c]);
	if (captured.length) window.__csb.save(fileName, captured);
	return JSON.stringify({ captured: captured.length, of: crns.length, error: progress.error, file: fileName });
};
window.__csb.runQueue = async (items) => {
	const sleep = ms => new Promise(r => setTimeout(r, ms));
	const log = window.__csb.queueLog = { items: items.length, finished: [], errors: [], running: null };
	window.__csb.queueDone = false;
	for (const it of items) {
		log.running = it.file;
		const again = document.querySelector('#search-again-button');
		if (again && again.offsetParent !== null) { again.click(); await sleep(1200); }
		const inp = document.querySelector('#txt_courseNumber');
		inp.focus(); inp.value = it.num; inp.dispatchEvent(new Event('input', { bubbles: true })); inp.dispatchEvent(new Event('change', { bubbles: true }));
		document.querySelector('#search-go').click();
		let ok = false;
		for (let i = 0; i < 40; i++) {
			await sleep(500);
			const t = (document.querySelector('#searchTerms') || document.body).innerText.replace(/\s+/g, ' ');
			const rows = document.querySelectorAll('td[data-property="courseReferenceNumber"]').length;
			if (new RegExp('Course Number:\\s*' + it.num + '\\b').test(t) && rows > 0) { ok = true; break; }
		}
		if (!ok) { log.errors.push(it.file + ': SEARCH_NOT_LOADED'); continue; }
		await window.__csb.pagesize(50);
		const have = new Set([...document.querySelectorAll('td[data-property="courseReferenceNumber"]')].map(c => c.innerText.trim()));
		const missing = it.crns.filter(c => !have.has(c));
		if (missing.length) { log.errors.push(it.file + ': CRN_NOT_VISIBLE ' + missing.join(',')); continue; }
		const r = JSON.parse(await window.__csb.collect(it.crns, it.file));
		if (r.error) log.errors.push(it.file + ': ' + r.error); else log.finished.push(it.file + ' ' + r.captured);
	}
	log.running = null;
	window.__csb.queueDone = true;
};
window.__csb.queueStart = (items) => { window.__csb.runQueue(items); return 'queue started: ' + items.length; };
window.__csb.queueWait = async () => {
	for (let i = 0; i < 38 && !window.__csb.queueDone; i++) await new Promise(r => setTimeout(r, 1000));
	return (window.__csb.queueDone ? 'QUEUE_DONE ' : 'queue running ') + JSON.stringify(window.__csb.queueLog) + ' stored=' + Object.keys(window.__csb.store).length;
};
'csb local collector loaded'

// ---- discovery queue and compact export (used when downloads are blocked by the browser) --------------------------------------------------
// runDiscover(nums): for each Course Number of the CURRENT subject, search, record the grid rows (instructors included) and the search result
// (0 classes is a result), then collect() the Class Details of every row. packAll() writes ONE compact JSON string of the grid rows, the search
// log and the Class Details captures not exported yet; read it with chunk(i) (900 characters) and verify its SHA-256 after copying it to a file.
window.__csb.gridStore = window.__csb.gridStore || {};
window.__csb.searchLog = window.__csb.searchLog || [];
window.__csb.runDiscover = async (nums) => {
	const sleep = ms => new Promise(r => setTimeout(r, ms));
	const log = window.__csb.queueLog = { items: nums.length, finished: [], errors: [], running: null };
	window.__csb.queueDone = false;
	for (const num of nums) {
		log.running = num;
		const again = document.querySelector('#search-again-button');
		if (again && again.offsetParent !== null) { again.click(); await sleep(1200); }
		const inp = document.querySelector('#txt_courseNumber');
		inp.focus(); inp.value = num; inp.dispatchEvent(new Event('input', { bubbles: true })); inp.dispatchEvent(new Event('change', { bubbles: true }));
		document.querySelector('#search-go').click();
		let header = null;
		for (let i = 0; i < 40; i++) {
			await sleep(500);
			const t = (document.querySelector('#searchTerms') || document.body).innerText.replace(/\s+/g, ' ');
			const body = (document.querySelector('main') || document.body).innerText.replace(/\s+/g, ' ');
			const m = body.match(/Search Results\s*(?:—|-)?\s*(\d+)\s*Classes/);
			if (new RegExp('Course Number:\\s*' + num + '\\b').test(t) && m) { header = { count: +m[1], subject: (t.match(/Subject:\s*(.*?)\s*Course Number:/) || [])[1] || null }; break; }
		}
		if (!header) { log.errors.push(num + ': SEARCH_NOT_LOADED'); continue; }
		await window.__csb.pagesize(50);
		const rows = JSON.parse(window.__csb.grid()).rows;
		if (rows.length !== header.count) { log.errors.push(num + ': ROWS_' + rows.length + '_OF_' + header.count); continue; }
		window.__csb.searchLog.push({ subject: header.subject, number: num, count: header.count });
		for (const r of rows) window.__csb.gridStore[r.crn] = r;
		if (!rows.length) { log.finished.push(num + ' 0'); continue; }
		const r = JSON.parse(await window.__csb.collect(rows.map(x => x.crn), 'csb-popups-' + num + '.json'));
		if (r.error) log.errors.push(num + ': ' + r.error); else log.finished.push(num + ' ' + r.captured);
	}
	log.running = null;
	window.__csb.queueDone = true;
};
window.__csb.discoverStart = nums => { window.__csb.runDiscover(nums); return 'discover started: ' + nums.length; };
window.__csb.packAll = async () => {
	const strings = [], index = new Map();
	const S = v => { if (v === null || v === undefined) return -1; if (!index.has(v)) { index.set(v, strings.length); strings.push(v); } return index.get(v); };
	const MEET = /^(.*?) S M T W T F S (?:(\d\d:\d\d [AP]M) - (\d\d:\d\d [AP]M)|-) Type: (.+?) Building: (.*?) Room: (.*?) Start Date: (\d\d\/\d\d\/\d{4}) End Date: (\d\d\/\d\d\/\d{4})$/;
	const grid = {};
	for (const [crn, r] of Object.entries(window.__csb.gridStore).sort()) {
		if (window.__csb.exportedGrid && window.__csb.exportedGrid.has(crn)) continue;
		const meetings = r.meetings.map(m => { const x = m.match(MEET); if (!x) throw new Error('meeting shape ' + m); return [x[1], x[2] || null, x[3] || null, x[4], x[5], x[6], x[7], x[8]]; });
		grid[crn] = [S(r.subject), r.number, r.section, S(r.title), r.hours, r.type, S(r.attribute), S(r.linked), meetings, r.instructors.map(i => [i.name, i.role])];
	}
	const secs = {};
	for (const [crn, c] of Object.entries(window.__csb.store).sort()) {
		if (window.__csb.exported.has(crn)) continue;
		const d = c.details || {};
		const xl = c.cross_list.rows.length ? c.cross_list.rows.map(r => [r.crn, r.subject, r.course_number, r.title, r.section]) : S(c.cross_list.panel_text);
		const lk = c.linked.options.length ? c.linked.options.map(o => o.sections.map(s => [s.crn, s.schedule_type, s.section])) : S(c.linked.panel_text);
		secs[crn] = [c.term_code, d.schedule_type, d.section, d.credit_hours, S(d.campus), S(d.grade_mode), S(c.attributes_text), S(c.restrictions_text), S(c.prerequisites_text), S(c.corequisites_text), xl, lk];
	}
	const json = JSON.stringify({ v: 2, strings, grid, secs, searches: window.__csb.searchLog });
	const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(json));
	window.__csb.packed = json;
	return JSON.stringify({ length: json.length, chunks: Math.ceil(json.length / 900), sha256: [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, '0')).join(''), grid: Object.keys(grid).length, sections: Object.keys(secs).length, searches: window.__csb.searchLog.length });
};
'discovery queue loaded'
