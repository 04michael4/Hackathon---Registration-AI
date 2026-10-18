// Defines window.__bannerCollector.popup(crn): opens ONE section's "Class Details" popup from the results grid in
// the CURRENT tab, reads a fixed allowlist of its tabs, closes it, and returns a short summary; the sanitized JSON
// stays in window.__capture. Load this file once per page load, then call
//     await window.__bannerCollector.popup('13507')
//
// UI navigation only: it clicks (1) the section's title link, (2) the popup tabs below, (3) the popup's Close
// button. It makes no network request of its own and never clicks anything else. It never opens the
// Instructor/Meeting Times or Enrollment/Waitlist tabs (names and seat counts live there).
// Pacing: 6 s before it starts, 3 s after each click. It refuses to return anything if a tripwire matches.
window.__bannerCollector = window.__bannerCollector || {};
window.__bannerCollector.popup = async (CRN) => {
	const sleep = ms => new Promise(r => setTimeout(r, ms));
	const clean = s => (s || '').replace(/\s+/g, ' ').trim();
	const TABS = ['classDetails', 'restrictions', 'preReqs', 'coReqs', 'linked']; // the ONLY tabs it will click
	const fail = reason => JSON.stringify({ error: reason, view: 'CLASS_DETAILS_POPUP', crn: CRN });
	if (!/^\d{5}$/.test(CRN || '')) return fail('BAD_CRN');
	await sleep(6000);
	const row = [...document.querySelectorAll('tbody tr')].find(r => {
		const c = r.querySelector('td[data-property="courseReferenceNumber"]');
		return c && clean(c.innerText) === CRN;
	});
	const link = row && row.querySelector('td[data-property="courseTitle"] a');
	if (!link) return fail('ROW_NOT_FOUND');
	link.click();
	await sleep(3000);
	const dialog = [...document.querySelectorAll('[role="dialog"]')].find(e => e.offsetParent !== null);
	if (!dialog) return fail('NO_DIALOG');
	const header = dialog.innerText.match(/Term:\s*(\d{6})\s*\|\s*CRN:\s*(\d{5})/);
	if (!header || header[2] !== CRN) return fail('HEADER_MISMATCH');
	const panel = () => [...dialog.querySelectorAll('[role="tabpanel"]')].find(e => e.offsetParent !== null);
	const label = (t, name) => { const m = t.match(new RegExp('(?:^|\\n)\\s*' + name + ':[ \\t]*([^\\n]*)')); return m ? clean(m[1]) : null; };
	const out = { view: 'CLASS_DETAILS_POPUP', captured_at: new Date().toISOString(), term_code: header[1], crn: CRN };
	for (const id of TABS) {
		if (id !== 'classDetails') {
			const tab = dialog.querySelector('h3#' + id + '[role="tab"]');
			if (!tab) return fail('TAB_MISSING_' + id);
			tab.click();
			await sleep(3000);
		}
		const p = panel();
		if (!p) return fail('PANEL_MISSING_' + id);
		const raw = p.innerText;
		if (id === 'classDetails') {
			out.details = {
				associated_term: label(raw, 'Associated Term'), campus: label(raw, 'Campus'),
				schedule_type: label(raw, 'Schedule Type'), section: label(raw, 'Section Number'),
				subject_description: label(raw, 'Subject'), course_number: label(raw, 'Course Number'),
				title: label(raw, 'Title'), credit_hours: label(raw, 'Credit Hours')
			};
		} else if (id === 'restrictions') {
			out.restrictions_text = clean(raw);
		} else if (id === 'preReqs') {
			out.prerequisites_text = clean(raw);
		} else if (id === 'coReqs') {
			out.corequisites_text = clean(raw);
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
			out.linked = { options, panel_text: clean(raw).slice(0, 600) };
		}
	}
	const close = [...dialog.querySelectorAll('button')].find(b => clean(b.innerText) === 'Close');
	if (close) { close.click(); await sleep(500); }
	const json = JSON.stringify(out);
	if (/seats?\s+remain|\(Primary\)|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|\d{8,}/i.test(json)) {
		return fail('SANITIZATION_TRIPWIRE');
	}
	// Long output is truncated by the browser tool: keep the capture in page memory, read it in 900-character
	// chunks, and let the importer verify the transcription against this SHA-256.
	const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(json));
	const sha256 = [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, '0')).join('');
	window.__capture = { json, sha256 };
	return JSON.stringify({ ok: true, view: 'CLASS_DETAILS_POPUP', crn: CRN, length: json.length, chunks: Math.ceil(json.length / 900), sha256 });
};
'popup extractor loaded'
